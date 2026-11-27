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
    @Volatile var sharpness: Float = 0.0f
    @Volatile var contrast: Float = 1.0f
    @Volatile var saturation: Float = 1.0f

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
    private var sceneCutTex = 0
    private var sceneCutFbo = 0

    // Ping-pong de fotogramas y temporización
    private var currIdx = 0
    private var prevIdx = 1
    private var hasPrevFrame = false
    private var lastFrameNs = 0L
    private var frameIntervalNs = 41_666_666L // ~24 fps inicial

    // Programas Shader
    private var progCopyOes = 0
    private var progDisplayOes = 0
    private var progLuma = 0
    private var progDown2 = 0
    private var progSceneCut = 0
    private var progMeCoarse = 0
    private var progMeFine = 0
    private var progMvBlurBilat = 0
    private var progWarp = 0

    // Uniforms cacheados
    private var uCopyMatrix = -1

    private var uDispMatrix = -1
    private var uDispTexel = -1
    private var uDispSharp = -1
    private var uDispContrast = -1
    private var uDispSat = -1

    private var uLumaTex = -1
    private var uLumaSrcTexel = -1
    private var uDownTex = -1
    private var uDownSrcTexel = -1

    private var uScPrev = -1
    private var uScCurr = -1

    private var uCoarsePrev = -1
    private var uCoarseCurr = -1
    private var uCoarseTexel = -1

    private var uFinePrev = -1
    private var uFineCurr = -1
    private var uFineInitMv = -1
    private var uFineTexel = -1

    private var uBlurMv = -1
    private var uBlurLuma = -1
    private var uBlurStep = -1

    private var uWarpPrev = -1
    private var uWarpCurr = -1
    private var uWarpMv = -1
    private var uWarpScene = -1
    private var uWarpAlpha = -1
    private var uWarpLumaSize = -1
    private var uWarpTexel = -1
    private var uWarpSharp = -1
    private var uWarpContrast = -1
    private var uWarpSat = -1

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
        progCopyOes = buildProgram(VS_OES, FS_OES_RAW_COPY)
        uCopyMatrix = GLES30.glGetUniformLocation(progCopyOes, "uStMatrix")

        progDisplayOes = buildProgram(VS_OES, FS_OES_DISPLAY)
        uDispMatrix = GLES30.glGetUniformLocation(progDisplayOes, "uStMatrix")
        uDispTexel = GLES30.glGetUniformLocation(progDisplayOes, "uTexelSize")
        uDispSharp = GLES30.glGetUniformLocation(progDisplayOes, "uSharpness")
        uDispContrast = GLES30.glGetUniformLocation(progDisplayOes, "uContrast")
        uDispSat = GLES30.glGetUniformLocation(progDisplayOes, "uSaturation")

        progLuma = buildProgram(VS_STD, FS_LUMA)
        uLumaTex = GLES30.glGetUniformLocation(progLuma, "uTex")
        uLumaSrcTexel = GLES30.glGetUniformLocation(progLuma, "uSrcTexel")

        progDown2 = buildProgram(VS_STD, FS_DOWN2_LUMA)
        uDownTex = GLES30.glGetUniformLocation(progDown2, "uTex")
        uDownSrcTexel = GLES30.glGetUniformLocation(progDown2, "uSrcTexel")

        progSceneCut = buildProgram(VS_STD, FS_SCENE_CUT)
        uScPrev = GLES30.glGetUniformLocation(progSceneCut, "uPrevLuma")
        uScCurr = GLES30.glGetUniformLocation(progSceneCut, "uCurrLuma")

        progMeCoarse = buildProgram(VS_STD, FS_ME_COARSE)
        uCoarsePrev = GLES30.glGetUniformLocation(progMeCoarse, "uPrevLuma")
        uCoarseCurr = GLES30.glGetUniformLocation(progMeCoarse, "uCurrLuma")
        uCoarseTexel = GLES30.glGetUniformLocation(progMeCoarse, "uTexelSize")

        progMeFine = buildProgram(VS_STD, FS_ME_FINE)
        uFinePrev = GLES30.glGetUniformLocation(progMeFine, "uPrevLuma")
        uFineCurr = GLES30.glGetUniformLocation(progMeFine, "uCurrLuma")
        uFineInitMv = GLES30.glGetUniformLocation(progMeFine, "uCoarseMv")
        uFineTexel = GLES30.glGetUniformLocation(progMeFine, "uTexelSize")

        progMvBlurBilat = buildProgram(VS_STD, FS_MV_BLUR_BILATERAL)
        uBlurMv = GLES30.glGetUniformLocation(progMvBlurBilat, "uMvTex")
        uBlurLuma = GLES30.glGetUniformLocation(progMvBlurBilat, "uLumaTex")
        uBlurStep = GLES30.glGetUniformLocation(progMvBlurBilat, "uStep")

        progWarp = buildProgram(VS_STD, FS_WARP)
        uWarpPrev = GLES30.glGetUniformLocation(progWarp, "uPrevTex")
        uWarpCurr = GLES30.glGetUniformLocation(progWarp, "uCurrTex")
        uWarpMv = GLES30.glGetUniformLocation(progWarp, "uMvTex")
        uWarpScene = GLES30.glGetUniformLocation(progWarp, "uSceneTex")
        uWarpAlpha = GLES30.glGetUniformLocation(progWarp, "uAlpha")
        uWarpLumaSize = GLES30.glGetUniformLocation(progWarp, "uLumaSize")
        uWarpTexel = GLES30.glGetUniformLocation(progWarp, "uTexelSize")
        uWarpSharp = GLES30.glGetUniformLocation(progWarp, "uSharpness")
        uWarpContrast = GLES30.glGetUniformLocation(progWarp, "uContrast")
        uWarpSat = GLES30.glGetUniformLocation(progWarp, "uSaturation")

        // Crear textura 1x1 para detector global de cortes de escena
        val sc = createFboTexture(1, 1, GLES30.GL_R8, GLES30.GL_RED)
        sceneCutTex = sc.first
        sceneCutFbo = sc.second

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

        val curSharp = sharpness
        val curContrast = contrast
        val curSat = saturation

        // Modo Pass-through (MEMC OFF): dibujado directo con post-procesado opcional
        if (!memcEnabled) {
            hasPrevFrame = false
            drawDirectToScreen(curSharp, curContrast, curSat)
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

            // Pase 1: Copiar textura externa cruda a frameFbo[currIdx]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameFbo[currIdx])
            GLES30.glViewport(0, 0, allocatedW, allocatedH)
            GLES30.glUseProgram(progCopyOes)
            GLES30.glUniformMatrix4fv(uCopyMatrix, 1, false, stMatrix, 0)
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

            // Pase 2B: Luminancia 1/16 (120x68 en 1080p)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coarseLumaFbo[currIdx])
            GLES30.glViewport(0, 0, coarseW, coarseH)
            GLES30.glUseProgram(progDown2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[currIdx])
            GLES30.glUniform1i(uDownTex, 0)
            GLES30.glUniform2f(uDownSrcTexel, 1f / lumaW, 1f / lumaH)
            drawQuad()

            if (hasPrevFrame) {
                // Pase 2C: Detector global de corte de cámara (1x1 px, costo prácticamente cero)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneCutFbo)
                GLES30.glViewport(0, 0, 1, 1)
                GLES30.glUseProgram(progSceneCut)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coarseLumaTex[prevIdx])
                GLES30.glUniform1i(uScPrev, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coarseLumaTex[currIdx])
                GLES30.glUniform1i(uScCurr, 1)
                drawQuad()

                // Pase 3A: Flujo óptico grueso 1/16 (hasta ±64 px en 1080p)
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

                // Pase 3B: Refinamiento fino 1/8
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

                // Pase 4A: Suavizado bilateral horizontal (respeta bordes de objetos)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvFboB)
                GLES30.glViewport(0, 0, lumaW, lumaH)
                GLES30.glUseProgram(progMvBlurBilat)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexA)
                GLES30.glUniform1i(uBlurMv, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[currIdx])
                GLES30.glUniform1i(uBlurLuma, 1)
                GLES30.glUniform2f(uBlurStep, 1.25f / lumaW, 0f)
                drawQuad()

                // Pase 4B: Suavizado bilateral vertical
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvFboA)
                GLES30.glViewport(0, 0, lumaW, lumaH)
                GLES30.glUseProgram(progMvBlurBilat)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTexB)
                GLES30.glUniform1i(uBlurMv, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[currIdx])
                GLES30.glUniform1i(uBlurLuma, 1)
                GLES30.glUniform2f(uBlurStep, 0f, 1.25f / lumaH)
                drawQuad()
            }
        }

        // Pase 5 (60 Hz): Warping bidireccional + Protección de oclusión/cortes + Post-procesado
        if (!hasPrevFrame) {
            drawDirectToScreen(curSharp, curContrast, curSat)
            return
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)

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

        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneCutTex)
        GLES30.glUniform1i(uWarpScene, 3)

        GLES30.glUniform1f(uWarpAlpha, alpha)
        GLES30.glUniform2f(uWarpLumaSize, lumaW.toFloat(), lumaH.toFloat())
        GLES30.glUniform2f(uWarpTexel, 1f / allocatedW, 1f / allocatedH)
        GLES30.glUniform1f(uWarpSharp, curSharp)
        GLES30.glUniform1f(uWarpContrast, curContrast)
        GLES30.glUniform1f(uWarpSat, curSat)
        drawQuad()
    }

    private fun drawDirectToScreen(sharp: Float, cont: Float, sat: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        val w = if (videoWidth > 0) videoWidth else surfaceWidth
        val h = if (videoHeight > 0) videoHeight else surfaceHeight
        GLES30.glUseProgram(progDisplayOes)
        GLES30.glUniformMatrix4fv(uDispMatrix, 1, false, stMatrix, 0)
        GLES30.glUniform2f(uDispTexel, 1f / w, 1f / h)
        GLES30.glUniform1f(uDispSharp, sharp)
        GLES30.glUniform1f(uDispContrast, cont)
        GLES30.glUniform1f(uDispSat, sat)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extTexId)
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
        if (sceneCutTex != 0) GLES30.glDeleteTextures(1, intArrayOf(sceneCutTex), 0)
        if (sceneCutFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(sceneCutFbo), 0)
        sceneCutTex = 0; sceneCutFbo = 0
        if (extTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(extTexId), 0)
            extTexId = 0
        }
        listOf(
            progCopyOes, progDisplayOes, progLuma, progDown2,
            progSceneCut, progMeCoarse, progMeFine, progMvBlurBilat, progWarp
        ).forEach {
            if (it != 0) GLES30.glDeleteProgram(it)
        }
        progCopyOes = 0; progDisplayOes = 0; progLuma = 0; progDown2 = 0
        progSceneCut = 0; progMeCoarse = 0; progMeFine = 0; progMvBlurBilat = 0; progWarp = 0
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

        // Copia cruda sin filtros para alimentar los FBOs internos
        private val FS_OES_RAW_COPY = """
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

        // Dibujado directo (cuando 60 FPS está OFF) con Nitidez CAS, Contraste y Saturación
        private val FS_OES_DISPLAY = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uTex;
            uniform vec2 uTexelSize;
            uniform float uSharpness;
            uniform float uContrast;
            uniform float uSaturation;
            out vec4 fragColor;

            void main() {
                vec3 c = texture(uTex, vTexCoord).rgb;

                if (uSharpness > 0.01) {
                    vec3 n = texture(uTex, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                    vec3 s = texture(uTex, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
                    vec3 e = texture(uTex, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture(uTex, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 minC = min(c, min(min(n, s), min(e, w)));
                    vec3 maxC = max(c, max(max(n, s), max(e, w)));
                    vec3 avg = (n + s + e + w) * 0.25;
                    vec3 detail = c - avg;
                    // Atenuar nitidez en bordes ya extremos para evitar halos blancos
                    vec3 amp = clamp(min(minC, 1.0 - maxC) / max(maxC, vec3(0.001)), 0.0, 1.0);
                    c = clamp(c + detail * (uSharpness * 1.4) * sqrt(amp), 0.0, 1.0);
                }

                // Contraste y Saturación
                c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = clamp(mix(vec3(luma), c, uSaturation), 0.0, 1.0);

                fragColor = vec4(c, 1.0);
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

        // Downscale 1/8 con filtro de área
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

        // Downscale 1/8 -> 1/16
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

        // Mejora 1: Detector global de corte de escena (cuadrícula 5x5 sobre mapa 1/16 -> textura 1x1)
        private val FS_SCENE_CUT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uPrevLuma;
            uniform sampler2D uCurrLuma;
            out vec4 fragColor;

            void main() {
                float diffSum = 0.0;
                float changedCells = 0.0;

                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        vec2 uv = vec2((float(x) + 0.5) / 5.0, (float(y) + 0.5) / 5.0);
                        float d = abs(texture(uPrevLuma, uv).r - texture(uCurrLuma, uv).r);
                        diffSum += d;
                        if (d > 0.14) {
                            changedCells += 1.0;
                        }
                    }
                }

                float avgDiff = diffSum / 25.0;
                float changedRatio = changedCells / 25.0;

                // Si cambió el brillo global O más del 45% de la pantalla saltó de golpe -> corte de cámara
                float cutByAvg = 1.0 - smoothstep(0.11, 0.19, avgDiff);
                float cutByArea = 1.0 - smoothstep(0.40, 0.60, changedRatio);
                float sceneValid = min(cutByAvg, cutByArea);

                fragColor = vec4(sceneValid, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        // Nivel 1 (1/16): Lucas-Kanade en highp
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

        // Nivel 2 (1/8): Refinamiento fino en highp
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

                float conf = 1.0 - smoothstep(0.14, 0.30, finalErr);

                vec2 encoded = floor((v / MAX_DISP) * 127.0 + 128.5) / 255.0;
                fragColor = vec4(encoded, conf, 1.0);
            }
        """.trimIndent()

        // Mejora 2: Suavizado bilateral separable (respeta bordes de siluetas, evita barrido sobre el fondo)
        private val FS_MV_BLUR_BILATERAL = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uMvTex;
            uniform sampler2D uLumaTex;
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

                float centerLuma = texture(uLumaTex, vTexCoord).r;
                vec2 sumV = vec2(0.0);
                float sumC = 0.0;
                float sumW = 0.0;

                for (int k = -2; k <= 2; k++) {
                    vec2 uv = vTexCoord + uStep * float(k);
                    vec3 s = texture(uMvTex, uv).rgb;
                    float sampleLuma = texture(uLumaTex, uv).r;

                    // Peso de borde: si cambia mucho la luminancia, no mezclar vectores a través del borde
                    float lDiff = abs(sampleLuma - centerLuma);
                    float edgeW = 1.0 - smoothstep(0.04, 0.15, lDiff);

                    vec2 v = ((s.rg * 255.0) - 128.0) / 127.0 * MAX_DISP;
                    float w = weights[k + 2] * max(edgeW, 0.05);
                    sumV += v * (s.b * w);
                    sumC += s.b * w;
                    sumW += w;
                }

                vec2 avgV = (sumC > 0.001) ? (sumV / sumC) : vec2(0.0);
                float avgC = (sumW > 0.001) ? (sumC / sumW) : 0.0;

                if (length(avgV) < 0.04) {
                    avgV = vec2(0.0);
                }

                vec2 encoded = floor((avgV / MAX_DISP) * 127.0 + 128.5) / 255.0;
                fragColor = vec4(encoded, avgC, 1.0);
            }
        """.trimIndent()

        // Mejora 3 + Post-procesado: Warping con respaldo inteligente en oclusiones, corte global y Nitidez/Contraste/Color
        private val FS_WARP = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uPrevTex;
            uniform sampler2D uCurrTex;
            uniform sampler2D uMvTex;
            uniform sampler2D uSceneTex;
            uniform float uAlpha;
            uniform vec2 uLumaSize;
            uniform vec2 uTexelSize;
            uniform float uSharpness;
            uniform float uContrast;
            uniform float uSaturation;
            out vec4 fragColor;

            const float MAX_DISP = 8.0;

            void main() {
                float sceneValid = texture(uSceneTex, vec2(0.5)).r;
                vec3 fallback = (uAlpha < 0.5)
                    ? texture(uPrevTex, vTexCoord).rgb
                    : texture(uCurrTex, vTexCoord).rgb;

                vec3 c;
                if (sceneValid < 0.05) {
                    // Corte de cámara limpio sin barrido
                    c = fallback;
                } else {
                    vec3 mvData = texture(uMvTex, vTexCoord).rgb;
                    vec2 flowPx = ((mvData.rg * 255.0) - 128.0) / 127.0 * MAX_DISP;
                    float conf = mvData.b * sceneValid;

                    vec2 flowUv = flowPx / uLumaSize;
                    vec2 uvPrev = clamp(vTexCoord - flowUv * uAlpha, 0.0, 1.0);
                    vec2 uvCurr = clamp(vTexCoord + flowUv * (1.0 - uAlpha), 0.0, 1.0);

                    vec3 prevCol = texture(uPrevTex, uvPrev).rgb;
                    vec3 currCol = texture(uCurrTex, uvCurr).rgb;

                    // Oclusión inteligente: si prev y curr no coinciden, preferir el cuadro más cercano
                    // en lugar de crear una doble imagen (ghosting)
                    float lumaDiff = abs(dot(prevCol - currCol, vec3(0.299, 0.587, 0.114)));
                    float occFactor = smoothstep(0.10, 0.26, lumaDiff);

                    vec3 blendWarped = mix(prevCol, currCol, uAlpha);
                    vec3 nearestWarped = (uAlpha < 0.5) ? prevCol : currCol;
                    vec3 smartWarped = mix(blendWarped, nearestWarped, occFactor * 0.85);

                    float severeMismatch = 1.0 - smoothstep(0.28, 0.45, lumaDiff);
                    c = mix(fallback, smartWarped, conf * severeMismatch);
                }

                // Nitidez adaptativa ligera (CAS) sobre el cuadro resultante
                if (uSharpness > 0.01) {
                    vec3 n = (uAlpha < 0.5)
                        ? texture(uPrevTex, vTexCoord + vec2(0.0, uTexelSize.y)).rgb
                        : texture(uCurrTex, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                    vec3 s = (uAlpha < 0.5)
                        ? texture(uPrevTex, vTexCoord - vec2(0.0, uTexelSize.y)).rgb
                        : texture(uCurrTex, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
                    vec3 e = (uAlpha < 0.5)
                        ? texture(uPrevTex, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb
                        : texture(uCurrTex, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 w = (uAlpha < 0.5)
                        ? texture(uPrevTex, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb
                        : texture(uCurrTex, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 minC = min(c, min(min(n, s), min(e, w)));
                    vec3 maxC = max(c, max(max(n, s), max(e, w)));
                    vec3 detail = c - (n + s + e + w) * 0.25;
                    vec3 amp = clamp(min(minC, 1.0 - maxC) / max(maxC, vec3(0.001)), 0.0, 1.0);
                    c = clamp(c + detail * (uSharpness * 1.35) * sqrt(amp), 0.0, 1.0);
                }

                // Contraste y Saturación editables
                c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = clamp(mix(vec3(luma), c, uSaturation), 0.0, 1.0);

                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()
    }
}
