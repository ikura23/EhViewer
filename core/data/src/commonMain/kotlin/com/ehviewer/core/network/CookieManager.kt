package com.ehviewer.core.network

import io.ktor.http.Cookie
import io.ktor.http.Url

interface CookieManager {
    fun getCookies(url: Url): Map<String, String>?
    fun setCookie(url: Url, cookie: Cookie)
    fun removeAllCookies()

    /** 清空所有 Cookie 并挂起等待底层存储完成，避免紧随其后的写入被异步清理覆盖。 */
    suspend fun clearAllCookies()

    fun flush()
}

expect fun getCookieManager(): CookieManager
