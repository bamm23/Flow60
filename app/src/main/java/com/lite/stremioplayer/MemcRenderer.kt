package com.lite.stremioplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class MemcRenderer(
    private val onRequestRender: () -> Unit,
    private val onSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val lock = Any()

    @Volatile var memcEnabled: Boolean = false
    @Volatile private var hasNewFrame = false
    @Volatile private var pendingReset = false

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var videoWidth = 0
    private var videoHeight = 0
    private var allocatedW = 0
    private var allocatedH = 0
    private var lumaW = 0
    private var lumaH = 0
    private var coarseW = 0
    private var coarseH = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val stMatrix = FloatArray(16)

    // Recursos OpenGL
    private var extTexId = 0
    private val frameTex = IntArray(2)
    private val frameFbo = IntArray(2)
    private val lumaTex = IntArray(2)
    private val lumaFbo = IntArray(2)
    private val coarseLumaTex = IntArray(2)
    private val coarseLumaFbo = IntArray(2)
    private var mvCoarseTex = 0
    private var mvCoarseFbo = 0
    private var mvTexA = 0
    private var mvFboA = 0
    private var mvTexB = 0
    private var mvFboB = 0

    // Ping-pong de fotogramas y temporización
    private var currIdx = 0
    private var prevIdx = 1
    private var hasPrevFrame = false
    private var lastFrameNs = 0L
    private var frameIntervalNs = 41_666_666L // ~24 fps inicial

    // Programas Shader
    private var progPassthrough = 0
    private var progLuma = 0
    private var progDown2 = 0
    private var progMeCoarse = 0
    private var progMeFine = 0
    private var progMvBlurSep = 0
    private var progWarp = 0

    // Uniforms cacheados
    private var uPtMatrix = -1
    private var uLumaTex = -1
    private var uLumaSrcTexel = -1
    private var uDownTex = -1
    private var uDownSrcTexel = -1
    private var uCoarsePrev = -1
    private var uCoarseCurr = -1
    private var uCoarseTexel = -1
    private var uFinePrev = -1
    private var uFineCurr = -1
    private var uFineInitMv = -1
    private var uFineTexel = -1
    private var uBlurMv = -1
    private var uBlurStep = -1
    private var uWarpPrev = -1
    private var uWarpCurr = -1
    private var uWarpMv = -1
    private var uWarpAlpha = -1
    private var uWarpLumaSize = -1

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // X, Y, U, V (GL_TRIANGLE_STRIP)
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                )
            )
            position(0)
        }

    fun getVideoSurface(): Surface? = videoSurface

    fun setVideoSize(width: Int, height: Int) {
        synchronized(lock) {
            if (width > 0 && height > 0) {
                videoWidth = width
                videoHeight = height
            }
        }
    }

    fun resetFrames() {
        pendingReset = true
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        hasNewFrame = true
        onRequestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Textura externa para MediaCodec
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        extTexId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Compilar shaders
        progPassthrough = buildProgram(VS_OES, FS_OES_COPY)
        uPtMatrix = GLES30.glGetUniformLocation(progPassthrough, "uStMatrix")

        progLuma = buildProgram(VS_STD, FS_LUMA)
        uLumaTex = GLES30.glGetUniformLocation(progLuma, "uTex")
        uLumaSrcTexel = GLES30.glGetUniformLocation(progLuma, "uSrcTexel")

        progDown2 = buildProgram(VS_STD, FS_DOWN2_LUMA)
        uDownTex = GLES30.glGetUniformLocation(progDown2, "uTex")
        uDownSrcTexel = GLES30.glGetUniformLocation(progDown2, "uSrcTexel")

        progMeCoarse = buildProgram(VS_STD, FS_ME_COARSE)
        uCoarsePrev = GLES30.glGetUniformLocation(progMeCoarse, "uPrevLuma")
        uCoarseCurr = GLES30.glGetUniformLocation(progMeCoarse, "uCurrLuma")
        uCoarseTexel = GLES30.glGetUniformLocation(progMeCoarse, "uTexelSize")

        progMeFine = buildProgram(VS_STD, FS_ME_FINE)
        uFinePrev = GLES30.glGetUniformLocation(progMeFine, "uPrevLuma")
        uFineCurr = GLES30.glGetUniformLocation(progMeFine, "uCurrLuma")
        uFineInitMv = GLES30.glGetUniformLocation(progMeFine, "uCoarseMv")
        uFineTexel = GLES30.glGetUniformLocation(progMeFine, "uTexelSize")

        progMvBlurSep = buildProgram(VS_STD, FS_MV_BLUR_SEP)
        uBlurMv = GLES30.glGetUniformLocation(progMvBlurSep, "uMvTex")
        uBlurStep = GLES30.glGetUniformLocation(progMvBlurSep, "uStep")

        progWarp = buildProgram(VS_STD, FS_WARP)
        uWarpPrev = GLES30.glGetUniformLocation(progWarp, "uPrevTex")
        uWarpCurr = GLES30.glGetUniformLocation(progWarp, "uCurrTex")
        uWarpMv = GLES30.glGetUniformLocation(progWarp, "uMvTex")
        uWarpAlpha = GLES30.glGetUniformLocation(progWarp, "uAlpha")
        uWarpLumaSize = GLES30.glGetUniformLocation(progWarp, "uLumaSize")

        // Crear SurfaceTexture y Surface para ExoPlayer
        releaseSurfaceOnly()
        surfaceTexture = SurfaceTexture(extTexId).apply {
            setOnFrameAvailableListener(this@MemcRenderer)
        }
        videoSurface = Surface(surfaceTexture)
        videoSurface?.let { onSurfaceReady(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return

        if (pendingReset) {
            hasPrevFrame = false
            lastFrameNs = 0L
            pendingReset = false
        }

        val gotNewFrame = hasNewFrame
        if (gotNewFrame) {
            hasNewFrame = false
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        }

        // Modo Pass-through (MEMC OFF): mínimo consumo y calor
        if (!memcEnabled) {
            hasPrevFrame = false
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glUseProgram(progPassthrough)
            GLES30.glUniformMatrix4fv(uPtMatrix, 1, false, stMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extTexId)
            drawQuad()
            return
        }

        // Asegurar FBOs asignados al tamaño actual del video
        var targetW: Int
        var targetH: Int
        synchronized(lock) {
            targetW = if (videoWidth > 0) videoWidth else surfaceWidth
            targetH = if (videoHeight > 0) videoHeight else surfaceHeight
        }
        if (targetW != allocatedW || targetH != allocatedH) {
            reallocateBuffers(targetW, targetH)
        }

        val nowNs = System.nanoTime()

        // Solo al llegar un fotograma nuevo (24/25/30 Hz) ejecutamos la pirámide de movimiento
        if (gotNewFrame) {
            if (lastFrameNs != 0L) {
                val delta = nowNs - lastFrameNs
                if (delta in 15_000_000L..100_000_000L) {
                    frameIntervalNs = (frameIntervalNs * 0.85 + delta * 0.15).toLong()
                    hasPrevFrame = true
                } else if (delta > 200_000_000L) {
                    hasPrevFrame = false
                }
            }
            lastFrameNs = nowNs

            val tmp = prevIdx
            prevIdx = currIdx
            currIdx = tmp

            // Pase 1: Copiar textura externa a frameFbo[currIdx]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo[currIdx])
            GLES30.glViewport(0, 0, allocatedW, allocatedH)
            GLES30.glUseProgram(progPassthrough)
            GLES30.glUniformMatrix4fv(uPtMatrix, 1, false, stMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extTexId)
            drawQuad()

            // Pase 2A: Luminancia 1/8 (240x135 en 1080p)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lumaFbo[currIdx])
            GLES30.glViewport(0, 0, lumaW, lumaH)
            GLES30.glUseProgram(progLuma)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[currIdx])
            GLES30.glUniform1i(uLumaTex, 0)
            GLES30.glUniform2f(uLumaSrcTexel, 1f / allocatedW, 1f / allocatedH)
            drawQuad()

            // Pase 2B: Luminancia 1/16 (120x68 en 1080p) para pirámide gruesa
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coarseLumaFbo[currIdx])
            GLES30.glViewport(0, 0, coarseW, coarseH)
            GLES30.glUseProgram(progDown2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[currIdx])
            GLES30.glUniform1i(uDownTex, 0)
            GLES30.glUniform2f(uDownSrcTexel, 1f / lumaW, 1f / lumaH)
            drawQuad()

            if (hasPrevFrame) {
                // Pase 3A: Flujo óptico grueso 1/16 (captura paneos rápidos hasta ±64 px en 1080p)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvCoarseFbo)
                GLES30.glViewport(0, 0, coarseW, coarseH)
                GLES30.glUseProgram(progMeCoarse)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coarseLumaTex[prevIdx])
                GLES30.glUniform1i(uCoarsePrev, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coarseLumaTex[currIdx])
                GLES30.glUniform1i(uCoarseCurr, 1)
                GLES30.glUniform2f(uCoarseTexel, 1f / coarseW, 1f / coarseH)
                drawQuad()

                // Pase 3B: Refinamiento fino 1/8 (similar a MRecalculate de AviSynth)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvFboA)
                GLES30.glViewport(0, 0, lumaW, lumaH)
                GLES30.glUseProgram(progMeFine)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[prevIdx])
                GLES30.glUniform1i(uFinePrev, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[currIdx])
                GLES30.glUniform1i(uFineCurr, 1)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvCoarseTex)
                GLES30.glUniform1i(uFineInitMv, 2)
                GLES30.glUniform2f(uFineTexel, 1f / lumaW, 1f / lumaH)
                drawQuad()

                // Pase 4A: Suavizado horizontal de vectores (mvTexA -> mvFboB)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvFboB)
                GLES30.glViewport(0, 0, lumaW, lumaH)
                GLES30.glUseProgram(progMvBlurSep)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexA)
                GLES30.glUniform1i(uBlurMv, 0)
                GLES30.glUniform2f(uBlurStep, 1.25f / lumaW, 0f)
                drawQuad()

                // Pase 4B: Suavizado vertical de vectores (mvTexB -> mvFboA)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvFboA)
                GLES30.glViewport(0, 0, lumaW, lumaH)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexB)
                GLES30.glUniform1i(uBlurMv, 0)
                GLES30.glUniform2f(uBlurStep, 0f, 1.25f / lumaH)
                drawQuad()
            }
        }

        // Pase 5 (60 Hz): Warping bidireccional fluido directo a pantalla
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)

        if (!hasPrevFrame) {
            GLES30.glUseProgram(progPassthrough)
            GLES30.glUniformMatrix4fv(uPtMatrix, 1, false, stMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extTexId)
            drawQuad()
            return
        }

        val elapsed = (nowNs - lastFrameNs).coerceAtLeast(0L)
        val alpha = (elapsed.toFloat() / frameIntervalNs.toFloat()).coerceIn(0f, 1f)

        GLES30.glUseProgram(progWarp)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[prevIdx])
        GLES30.glUniform1i(uWarpPrev, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[currIdx])
        GLES30.glUniform1i(uWarpCurr, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexA)
        GLES30.glUniform1i(uWarpMv, 2)

        GLES30.glUniform1f(uWarpAlpha, alpha)
        GLES30.glUniform2f(uWarpLumaSize, lumaW.toFloat(), lumaH.toFloat())
        drawQuad()
    }

    private fun reallocateBuffers(w: Int, h: Int) {
        releaseFrameBuffers()
        allocatedW = w
        allocatedH = h
        lumaW = max(32, w / 8)
        lumaH = max(18, h / 8)
        coarseW = max(16, lumaW / 2)
        coarseH = max(10, lumaH / 2)
        hasPrevFrame = false

        for (i in 0..1) {
            val f = createFboTexture(allocatedW, allocatedH, GLES30.GL_RGBA8, GLES30.GL_RGBA)
            frameTex[i] = f.first
            frameFbo[i] = f.second

            val l = createFboTexture(lumaW, lumaH, GLES30.GL_R8, GLES30.GL_RED)
            lumaTex[i] = l.first
            lumaFbo[i] = l.second

            val c = createFboTexture(coarseW, coarseH, GLES30.GL_R8, GLES30.GL_RED)
            coarseLumaTex[i] = c.first
            coarseLumaFbo[i] = c.second
        }

        val mC = createFboTexture(coarseW, coarseH, GLES30.GL_RGBA8, GLES30.GL_RGBA)
        mvCoarseTex = mC.first
        mvCoarseFbo = mC.second

        val mA = createFboTexture(lumaW, lumaH, GLES30.GL_RGBA8, GLES30.GL_RGBA)
        mvTexA = mA.first
        mvFboA = mA.second

        val mB = createFboTexture(lumaW, lumaH, GLES30.GL_RGBA8, GLES30.GL_RGBA)
        mvTexB = mB.first
        mvFboB = mB.second
    }

    private fun createFboTexture(w: Int, h: Int, internalFormat: Int, format: Int): Pair<Int, Int> {
        val tex = IntArray(1)
        val fbo = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return Pair(tex[0], fbo[0])
    }

    private fun releaseFrameBuffers() {
        if (allocatedW == 0) return
        GLES30.glDeleteTextures(2, frameTex, 0)
        GLES30.glDeleteFramebuffers(2, frameFbo, 0)
        GLES30.glDeleteTextures(2, lumaTex, 0)
        GLES30.glDeleteFramebuffers(2, lumaFbo, 0)
        GLES30.glDeleteTextures(2, coarseLumaTex, 0)
        GLES30.glDeleteFramebuffers(2, coarseLumaFbo, 0)
        if (mvCoarseTex != 0) GLES30.glDeleteTextures(1, intArrayOf(mvCoarseTex), 0)
        if (mvCoarseFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(mvCoarseFbo), 0)
        if (mvTexA != 0) GLES30.glDeleteTextures(1, intArrayOf(mvTexA), 0)
        if (mvFboA != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(mvFboA), 0)
        if (mvTexB != 0) GLES30.glDeleteTextures(1, intArrayOf(mvTexB), 0)
        if (mvFboB != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(mvFboB), 0)
        frameTex.fill(0); frameFbo.fill(0)
        lumaTex.fill(0); lumaFbo.fill(0)
        coarseLumaTex.fill(0); coarseLumaFbo.fill(0)
        mvCoarseTex = 0; mvCoarseFbo = 0
        mvTexA = 0; mvFboA = 0
        mvTexB = 0; mvFboB = 0
        allocatedW = 0; allocatedH = 0
    }

    private fun releaseSurfaceOnly() {
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    fun releaseOnGlThread() {
        releaseFrameBuffers()
        if (extTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(extTexId), 0)
            extTexId = 0
        }
        listOf(progPassthrough, progLuma, progDown2, progMeCoarse, progMeFine, progMvBlurSep, progWarp).forEach {
            if (it != 0) GLES30.glDeleteProgram(it)
        }
        progPassthrough = 0; progLuma = 0; progDown2 = 0
        progMeCoarse = 0; progMeFine = 0; progMvBlurSep = 0; progWarp = 0
        releaseSurfaceOnly()
    }

    private fun drawQuad() {
        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vsCode: String, fsCode: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsCode)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsCode)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        private val VS_OES = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat4 uStMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FS_OES_COPY = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uTex;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vTexCoord);
            }
        """.trimIndent()

        private val VS_STD = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // Downscale 1/8 con filtro de área (4 muestras bilineales = 16 píxeles)
        private val FS_LUMA = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uSrcTexel;
            out vec4 fragColor;
            void main() {
                vec2 o1 = uSrcTexel * 2.0;
                vec2 o2 = vec2(o1.x, -o1.y);
                vec3 c = texture(uTex, vTexCoord + o1).rgb
                       + texture(uTex, vTexCoord - o1).rgb
                       + texture(uTex, vTexCoord + o2).rgb
                       + texture(uTex, vTexCoord - o2).rgb;
                float y = dot(c * 0.25, vec3(0.2126, 0.7152, 0.0722));
                fragColor = vec4(y, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        // Downscale 1/8 -> 1/16 en canal R8
        private val FS_DOWN2_LUMA = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform vec2 uSrcTexel;
            out vec4 fragColor;
            void main() {
                vec2 o1 = uSrcTexel * 0.5;
                vec2 o2 = vec2(o1.x, -o1.y);
                float y = 0.25 * (
                    texture(uTex, vTexCoord + o1).r +
                    texture(uTex, vTexCoord - o1).r +
                    texture(uTex, vTexCoord + o2).r +
                    texture(uTex, vTexCoord - o2).r
                );
                fragColor = vec4(y, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        // Nivel 1 (1/16): Lucas-Kanade en highp (fp32) para evitar underflow en GPUs Mali/PowerVR
        // MAX_DISP = 4.0 px en 1/16 = ±64 px en 1080p
        private val FS_ME_COARSE = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uPrevLuma;
            uniform sampler2D uCurrLuma;
            uniform vec2 uTexelSize;
            out vec4 fragColor;

            const float MAX_DISP = 4.0;
            const float REG_LAMBDA = 0.0005;

            vec2 lkStep(vec2 uv, vec2 initV) {
                float sxx = REG_LAMBDA;
                float syy = REG_LAMBDA;
                float sxy = 0.0;
                float sxt = 0.0;
                float syt = 0.0;

                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);
                vec2 halfShift = initV * 0.5 * uTexelSize;

                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        vec2 off = vec2(float(i), float(j)) * uTexelSize * 1.25;
                        vec2 uvP = uv + off - halfShift;
                        vec2 uvC = uv + off + halfShift;

                        float p0 = texture(uPrevLuma, uvP).r;
                        float c0 = texture(uCurrLuma, uvC).r;
                        float it = c0 - p0;

                        float ix = 0.25 * (
                            (texture(uPrevLuma, uvP + dx).r - texture(uPrevLuma, uvP - dx).r) +
                            (texture(uCurrLuma, uvC + dx).r - texture(uCurrLuma, uvC - dx).r)
                        );
                        float iy = 0.25 * (
                            (texture(uPrevLuma, uvP + dy).r - texture(uPrevLuma, uvP - dy).r) +
                            (texture(uCurrLuma, uvC + dy).r - texture(uCurrLuma, uvC - dy).r)
                        );

                        sxx += ix * ix;
                        syy += iy * iy;
                        sxy += ix * iy;
                        sxt += ix * it;
                        syt += iy * it;
                    }
                }

                float det = sxx * syy - sxy * sxy;
                if (det < 1e-8) return vec2(0.0);

                vec2 dv = vec2(
                    -(syy * sxt - sxy * syt),
                    -(sxx * syt - sxy * sxt)
                ) / det;

                return clamp(dv, vec2(-2.0), vec2(2.0));
            }

            void main() {
                vec2 v = lkStep(vTexCoord, vec2(0.0));
                v = clamp(v + lkStep(vTexCoord, v), vec2(-MAX_DISP), vec2(MAX_DISP));
                v = clamp(v + lkStep(vTexCoord, v), vec2(-MAX_DISP), vec2(MAX_DISP));

                vec2 encoded = floor((v / MAX_DISP) * 127.0 + 128.5) / 255.0;
                fragColor = vec4(encoded, 1.0, 1.0);
            }
        """.trimIndent()

        // Nivel 2 (1/8): Refinamiento fino en highp partiendo del vector grueso (x2)
        // MAX_DISP = 8.0 px en 1/8 = ±64 px en 1080p
        private val FS_ME_FINE = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uPrevLuma;
            uniform sampler2D uCurrLuma;
            uniform sampler2D uCoarseMv;
            uniform vec2 uTexelSize;
            out vec4 fragColor;

            const float MAX_DISP = 8.0;
            const float REG_LAMBDA = 0.0005;

            vec2 lkStep(vec2 uv, vec2 initV, out float avgErr) {
                float sxx = REG_LAMBDA;
                float syy = REG_LAMBDA;
                float sxy = 0.0;
                float sxt = 0.0;
                float syt = 0.0;
                float errSum = 0.0;

                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);
                vec2 halfShift = initV * 0.5 * uTexelSize;

                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        vec2 off = vec2(float(i), float(j)) * uTexelSize * 1.25;
                        vec2 uvP = uv + off - halfShift;
                        vec2 uvC = uv + off + halfShift;

                        float p0 = texture(uPrevLuma, uvP).r;
                        float c0 = texture(uCurrLuma, uvC).r;
                        float it = c0 - p0;

                        float ix = 0.25 * (
                            (texture(uPrevLuma, uvP + dx).r - texture(uPrevLuma, uvP - dx).r) +
                            (texture(uCurrLuma, uvC + dx).r - texture(uCurrLuma, uvC - dx).r)
                        );
                        float iy = 0.25 * (
                            (texture(uPrevLuma, uvP + dy).r - texture(uPrevLuma, uvP - dy).r) +
                            (texture(uCurrLuma, uvC + dy).r - texture(uCurrLuma, uvC - dy).r)
                        );

                        sxx += ix * ix;
                        syy += iy * iy;
                        sxy += ix * iy;
                        sxt += ix * it;
                        syt += iy * it;
                        errSum += abs(it);
                    }
                }

                avgErr = errSum / 9.0;
                float det = sxx * syy - sxy * sxy;
                if (det < 1e-8) return vec2(0.0);

                vec2 dv = vec2(
                    -(syy * sxt - sxy * syt),
                    -(sxx * syt - sxy * sxt)
                ) / det;

                return clamp(dv, vec2(-2.0), vec2(2.0));
            }

            void main() {
                vec2 cRaw = texture(uCoarseMv, vTexCoord).rg;
                vec2 v = ((cRaw * 255.0) - 128.0) / 127.0 * MAX_DISP;

                float err1 = 0.0;
                v = clamp(v + lkStep(vTexCoord, v, err1), vec2(-MAX_DISP), vec2(MAX_DISP));

                float finalErr = err1;
                v = clamp(v + lkStep(vTexCoord, v, finalErr), vec2(-MAX_DISP), vec2(MAX_DISP));

                // Confianza amplia: solo cae a 0 en cortes bruscos de escena
                float conf = 1.0 - smoothstep(0.16, 0.36, finalErr);

                vec2 encoded = floor((v / MAX_DISP) * 127.0 + 128.5) / 255.0;
                fragColor = vec4(encoded, conf, 1.0);
            }
        """.trimIndent()

        // Suavizado separable de 5 taps (cohesión fluida tipo TV MEMC, sin cuadritos)
        private val FS_MV_BLUR_SEP = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uMvTex;
            uniform vec2 uStep;
            out vec4 fragColor;

            const float MAX_DISP = 8.0;

            void main() {
                float weights[5];
                weights[0] = 0.12;
                weights[1] = 0.22;
                weights[2] = 0.32;
                weights[3] = 0.22;
                weights[4] = 0.12;

                vec2 sumV = vec2(0.0);
                float sumC = 0.0;

                for (int k = -2; k <= 2; k++) {
                    vec3 s = texture(uMvTex, vTexCoord + uStep * float(k)).rgb;
                    vec2 v = ((s.rg * 255.0) - 128.0) / 127.0 * MAX_DISP;
                    float w = weights[k + 2];
                    sumV += v * (s.b * w);
                    sumC += s.b * w;
                }

                vec2 avgV = (sumC > 0.001) ? (sumV / sumC) : vec2(0.0);
                if (length(avgV) < 0.04) {
                    avgV = vec2(0.0);
                }

                vec2 encoded = floor((avgV / MAX_DISP) * 127.0 + 128.5) / 255.0;
                fragColor = vec4(encoded, sumC, 1.0);
            }
        """.trimIndent()

        // Warping bidireccional fluido a 60 Hz
        private val FS_WARP = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uPrevTex;
            uniform sampler2D uCurrTex;
            uniform sampler2D uMvTex;
            uniform float uAlpha;
            uniform vec2 uLumaSize;
            out vec4 fragColor;

            const float MAX_DISP = 8.0;

            void main() {
                vec3 mvData = texture(uMvTex, vTexCoord).rgb;
                vec2 flowPx = ((mvData.rg * 255.0) - 128.0) / 127.0 * MAX_DISP;
                float conf = mvData.b;

                vec2 flowUv = flowPx / uLumaSize;

                vec2 uvPrev = clamp(vTexCoord - flowUv * uAlpha, 0.0, 1.0);
                vec2 uvCurr = clamp(vTexCoord + flowUv * (1.0 - uAlpha), 0.0, 1.0);

                vec3 prevCol = texture(uPrevTex, uvPrev).rgb;
                vec3 currCol = texture(uCurrTex, uvCurr).rgb;
                vec3 warped = mix(prevCol, currCol, uAlpha);

                // Protección relajada: mantiene fluidez en movimiento y evita halos solo en oclusiones fuertes
                float lumaDiff = abs(dot(prevCol - currCol, vec3(0.299, 0.587, 0.114)));
                float matchConf = 1.0 - smoothstep(0.24, 0.48, lumaDiff);
                float finalConf = conf * matchConf;

                vec3 fallback = (uAlpha < 0.5)
                    ? texture(uPrevTex, vTexCoord).rgb
                    : texture(uCurrTex, vTexCoord).rgb;

                fragColor = vec4(mix(fallback, warped, finalConf), 1.0);
            }
        """.trimIndent()
    }
}
