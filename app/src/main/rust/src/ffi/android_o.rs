#![cfg(feature = "android-26")]

use super::android::use_bitmap_content;
use super::jvm::jni_throwing;
use crate::img::copy_region::CopyRegion;
use crate::img::webp::{copy_or_downscale_rgba, decode_frame_at_or_next, get_image_info, pack_frame_result};
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, jobject};
use jni_fn::jni_fn;
use libwebp_sys::WebPAnimDecoder;
use ndk::hardware_buffer::{HardwareBuffer, HardwareBufferUsage};

#[jni_fn("com.hippo.ehviewer.image.ImageKt")]
pub fn copyBitmapToAHB(mut env: JNIEnv, _: JClass, bm: jobject, ahb: jobject, x: jint, y: jint) {
    let ahb = unsafe { HardwareBuffer::from_jni(env.get_raw(), ahb) };
    jni_throwing(&mut env, |env| {
        let desc = ahb.describe();
        let (w, h, stride) = (desc.width, desc.height, desc.stride);
        let ptr = ahb.lock(HardwareBufferUsage::CPU_WRITE_RARELY, None, None)?;
        let s = CopyRegion { ptr: ptr as *mut !, target_dim: (stride, h), src_rect: (x as u32, y as u32, w, h) };
        let result = use_bitmap_content(env, bm, s);
        ahb.unlock()?;
        result
    })
}

#[jni_fn("com.hippo.ehviewer.coil.AnimatedWebPDrawableKt")]
pub fn nativeDecodeNextFrameZeroCopy(
    mut env: JNIEnv,
    _: JClass,
    decoder: jlong,
    reset: jboolean,
    target_ms: jint,
    max_skip: jint,
    ahb: jobject,
) -> jlong {
    let ahb = unsafe { HardwareBuffer::from_jni(env.get_raw(), ahb) };
    jni_throwing(&mut env, |_env| {
        let dec = decoder as *mut WebPAnimDecoder;
        let (buf, timestamp, wrapped) = unsafe { decode_frame_at_or_next(dec, reset != 0, target_ms, max_skip) };
        if !buf.is_null() {
            let desc = ahb.describe();
            let (dst_w, dst_h, dst_stride) = (desc.width, desc.height, desc.stride);
            let ptr = ahb.lock(HardwareBufferUsage::CPU_WRITE_OFTEN, None, None)? as *mut u8;
            let info = unsafe { get_image_info(dec) };
            unsafe {
                copy_or_downscale_rgba(
                    buf,
                    info.canvas_width,
                    info.canvas_height,
                    ptr,
                    dst_w,
                    dst_h,
                    (dst_stride as usize) * 4,
                );
            }
            ahb.unlock()?;
        }
        Ok(pack_frame_result(timestamp, wrapped))
    })
}
