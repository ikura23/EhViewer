package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Upper bound for the decoded animation window.
 *
 * A decoded animated page keeps two full-size bitmaps alive, so honoring very large
 * preload values would exceed the ~256 MB Java heap limit on common devices.
 */
internal const val MAX_ANIMATED_PRELOAD = 5

/**
 * Per-gallery animated playback state.
 *
 * The manual switch only applies to the gallery that is currently open. Leaving the reader
 * clears it, so the next gallery goes back to automatic 50% detection.
 */
object AnimatedPlaybackSession {
    private var activeToken: Any? = null

    var attached by mutableStateOf(false)
        private set

    var autoActive by mutableStateOf(false)

    var allAnimated by mutableStateOf(false)

    var manualOverride by mutableStateOf<Boolean?>(null)

    var playbackStarted by mutableStateOf(false)

    val active: Boolean
        get() = manualOverride ?: autoActive

    /** 绑定当前阅读器会话并返回唯一令牌，避免页面转场重叠时旧页面 onDispose 清空新页面状态。 */
    fun attach(token: Any = Any()): Any {
        activeToken = token
        attached = true
        autoActive = false
        allAnimated = false
        manualOverride = null
        playbackStarted = false
        return token
    }

    fun detach(token: Any? = null) {
        if (token != null && activeToken !== token) return
        activeToken = null
        attached = false
        autoActive = false
        allAnimated = false
        manualOverride = null
        playbackStarted = false
    }
}
