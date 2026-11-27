use libwebp_sys::{MODE_rgbA, WebPAnimDecoder, WebPAnimDecoderGetInfo, WebPAnimDecoderGetNext, WebPAnimDecoderHasMoreFrames, WebPAnimDecoderNew, WebPAnimDecoderOptions, WebPAnimDecoderReset, WebPAnimInfo, WebPData};
use std::mem::zeroed;
use std::ptr::{copy_nonoverlapping, null_mut};

pub fn create_decoder(data: &[u8]) -> *mut WebPAnimDecoder {
    let webp_data = WebPData { bytes: data.as_ptr(), size: data.len() };
    let dec_options = WebPAnimDecoderOptions { color_mode: MODE_rgbA, use_threads: 1, padding: [0; 7] };
    unsafe { WebPAnimDecoderNew(&webp_data, &dec_options) }
}

pub(crate) unsafe fn get_image_info(dec: *const WebPAnimDecoder) -> WebPAnimInfo {
    let mut info = unsafe { zeroed() };
    let _ = unsafe { WebPAnimDecoderGetInfo(dec, &mut info) };
    info
}

pub fn pack_image_info(info: WebPAnimInfo) -> u64 {
    (info.canvas_width as u64) << 40 | (info.canvas_height as u64) << 16 | (info.loop_count as u64)
}

pub(crate) fn pack_frame_result(timestamp: i32, wrapped: bool) -> i64 {
    let ts_bits = (timestamp as u32) as u64;
    let wrapped_bit = if wrapped { 1u64 << 32 } else { 0u64 };
    (wrapped_bit | ts_bits) as i64
}

/// 解码下一帧；若处于高倍速追帧且未触及片尾回绕，则在最多 `max_skip` 帧内跳过已过期中间帧。
pub(crate) unsafe fn decode_frame_at_or_next(
    dec: *mut WebPAnimDecoder,
    reset: bool,
    target_ms: i32,
    max_skip: i32,
) -> (*const u8, i32, bool) {
    let mut buf = null_mut();
    let mut timestamp = 0;
    let mut wrapped = false;
    unsafe {
        if reset {
            WebPAnimDecoderReset(dec);
        } else if WebPAnimDecoderHasMoreFrames(dec) == 0 {
            WebPAnimDecoderReset(dec);
            wrapped = true;
        }
        if WebPAnimDecoderGetNext(dec, &mut buf, &mut timestamp) == 0 {
            return (null_mut(), 0, wrapped);
        }
        // 高倍速追帧：如果当前尚未跨轮回绕且已存在待追赶目标时间戳，仅在同一周期内跳过已过期帧
        if !wrapped && target_ms >= 0 && max_skip > 0 {
            let mut skipped = 0;
            while skipped < max_skip && timestamp <= target_ms && WebPAnimDecoderHasMoreFrames(dec) != 0 {
                let mut next_buf = null_mut();
                let mut next_ts = timestamp;
                if WebPAnimDecoderGetNext(dec, &mut next_buf, &mut next_ts) == 0 || next_buf.is_null() {
                    break;
                }
                buf = next_buf;
                timestamp = next_ts;
                skipped += 1;
            }
        }
    }
    (buf, timestamp, wrapped)
}

/// 将 RGBA8888 源画布快速拷贝或降采样写入目标缓冲区（零额外堆分配）。
pub(crate) unsafe fn copy_or_downscale_rgba(
    src: *const u8,
    src_w: u32,
    src_h: u32,
    dst: *mut u8,
    dst_w: u32,
    dst_h: u32,
    dst_stride_bytes: usize,
) {
    if src.is_null() || dst.is_null() || src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return;
    }
    let min_stride = (dst_w as usize) * 4;
    if dst_stride_bytes < min_stride {
        return;
    }

    // 1:1 原尺寸走块/按行 memcpy 快速通路
    if src_w == dst_w && src_h == dst_h {
        let row_bytes = (src_w as usize) * 4;
        unsafe {
            if dst_stride_bytes == row_bytes {
                copy_nonoverlapping(src, dst, row_bytes * (src_h as usize));
            } else {
                for y in 0..(src_h as usize) {
                    copy_nonoverlapping(src.add(y * row_bytes), dst.add(y * dst_stride_bytes), row_bytes);
                }
            }
        }
        return;
    }

    // 缩略图降采样路径：缩小 >= 2 倍时取 2x2 盒式均值抑制锯齿，其余取整像素邻近采样
    let src_row_pixels = src_w as usize;
    let src_u32 = src as *const u32;
    let use_box2x2 = src_w >= dst_w.saturating_mul(2) && src_h >= dst_h.saturating_mul(2);
    let max_sx = (src_w - 1) as usize;
    let max_sy = (src_h - 1) as usize;

    for dy in 0..(dst_h as usize) {
        let sy0 = ((dy as u64 * src_h as u64) / dst_h as u64) as usize;
        let dst_row_u32 = unsafe { dst.add(dy * dst_stride_bytes) as *mut u32 };
        let src_row0 = unsafe { src_u32.add(sy0 * src_row_pixels) };

        if use_box2x2 {
            let sy1 = (sy0 + 1).min(max_sy);
            let src_row1 = unsafe { src_u32.add(sy1 * src_row_pixels) };
            for dx in 0..(dst_w as usize) {
                let sx0 = ((dx as u64 * src_w as u64) / dst_w as u64) as usize;
                let sx1 = (sx0 + 1).min(max_sx);
                unsafe {
                    let p00 = *src_row0.add(sx0);
                    let p10 = *src_row0.add(sx1);
                    let p01 = *src_row1.add(sx0);
                    let p11 = *src_row1.add(sx1);
                    let rb = ((p00 & 0x00FF_00FF)
                        + (p10 & 0x00FF_00FF)
                        + (p01 & 0x00FF_00FF)
                        + (p11 & 0x00FF_00FF)
                        + 0x0002_0002)
                        >> 2;
                    let ag = (((p00 >> 8) & 0x00FF_00FF)
                        + ((p10 >> 8) & 0x00FF_00FF)
                        + ((p01 >> 8) & 0x00FF_00FF)
                        + ((p11 >> 8) & 0x00FF_00FF)
                        + 0x0002_0002)
                        >> 2;
                    *dst_row_u32.add(dx) = (rb & 0x00FF_00FF) | ((ag & 0x00FF_00FF) << 8);
                }
            }
        } else {
            for dx in 0..(dst_w as usize) {
                let sx = ((dx as u64 * src_w as u64) / dst_w as u64) as usize;
                unsafe {
                    *dst_row_u32.add(dx) = *src_row0.add(sx);
                }
            }
        }
    }
}
