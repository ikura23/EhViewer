package com.ehviewer.core.network

import android.os.Handler
import android.os.Looper
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import io.ktor.http.renderSetCookieHeader
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidCookieManager : CookieManager {
    private val manager = android.webkit.CookieManager.getInstance()
    override fun getCookies(url: Url) = manager.getCookie(url.toString())?.let { parseClientCookiesHeader(it) }
    override fun setCookie(url: Url, cookie: Cookie) = manager.setCookie(url.toString(), renderSetCookieHeader(cookie))
    override fun removeAllCookies() {
        if (Looper.myLooper() != null) {
            manager.removeAllCookies(null)
        } else {
            Handler(Looper.getMainLooper()).post { manager.removeAllCookies(null) }
        }
    }
    override suspend fun clearAllCookies() = suspendCancellableCoroutine { cont ->
        val invoke = {
            manager.removeAllCookies {
                if (cont.isActive) cont.resume(Unit)
            }
        }
        // WebView CookieManager.removeAllCookies 必须在拥有运行中 Looper 的线程中触发，IO 线程需切换至主线程 Looper。
        if (Looper.myLooper() != null) {
            invoke()
        } else {
            Handler(Looper.getMainLooper()).post(invoke)
        }
    }
    override fun flush() = manager.flush()
}

actual fun getCookieManager(): CookieManager = AndroidCookieManager()
