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
    private var currentMemcMode = MemcRenderer.MODE_OFF

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

    fun setMemcMode(mode: Int) {
        currentMemcMode = mode.coerceIn(MemcRenderer.MODE_OFF, MemcRenderer.MODE_FLUID)
        renderer.memcMode = currentMemcMode
        if (currentMemcMode == MemcRenderer.MODE_OFF) {
            renderer.resetFrames()
        }
        updateRenderMode()
    }

    fun setImageEnhancements(sharpness: Float, contrast: Float, saturation: Float) {
        renderer.sharpness = sharpness
        renderer.contrast = contrast
        renderer.saturation = saturation
        if (renderMode == RENDERMODE_WHEN_DIRTY) {
            requestRender()
        }
    }

    fun resetFrames() {
        renderer.resetFrames()
    }

    fun releaseResources() {
        queueEvent { renderer.releaseOnGlThread() }
    }

    private fun updateRenderMode() {
        renderMode = if (isPlaying && currentMemcMode != MemcRenderer.MODE_OFF) {
            RENDERMODE_CONTINUOUSLY
        } else {
            RENDERMODE_WHEN_DIRTY
        }
        if (renderMode == RENDERMODE_WHEN_DIRTY) {
            requestRender()
        }
    }
}
