package com.lite.stremioplayer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface

class MemcGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var surfaceReadyCallback: ((Surface) -> Unit)? = null
    private var isPlaying = false
    private var isMemcActive = false

    private val renderer: MemcRenderer

    init {
        setEGLContextClientVersion(3)
        // Al salir a Stremio se destruye el contexto EGL y se libera el 100% de la VRAM
        preserveEGLContextOnPause = false

        renderer = MemcRenderer(
            onRequestRender = {
                if (renderMode == RENDERMODE_WHEN_DIRTY) {
                    requestRender()
                }
            },
            onSurfaceReady = { surface ->
                post { surfaceReadyCallback?.invoke(surface) }
            }
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setOnSurfaceReadyListener(listener: (Surface) -> Unit) {
        surfaceReadyCallback = listener
        renderer.getVideoSurface()?.let { listener(it) }
    }

    fun getVideoSurface(): Surface? = renderer.getVideoSurface()

    fun setVideoSize(width: Int, height: Int) {
        renderer.setVideoSize(width, height)
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        updateRenderMode()
    }

    fun setMemcEnabled(enabled: Boolean) {
        isMemcActive = enabled
        renderer.memcEnabled = enabled
        if (!enabled) renderer.resetFrames()
        updateRenderMode()
    }

    fun resetFrames() {
        renderer.resetFrames()
    }

    fun releaseResources() {
        queueEvent { renderer.releaseOnGlThread() }
    }

    private fun updateRenderMode() {
        renderMode = if (isPlaying && isMemcActive) {
            RENDERMODE_CONTINUOUSLY
        } else {
            RENDERMODE_WHEN_DIRTY
        }
        if (renderMode == RENDERMODE_WHEN_DIRTY) {
            requestRender()
        }
    }
}
