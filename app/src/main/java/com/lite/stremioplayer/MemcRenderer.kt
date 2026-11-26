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

    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val stMatrix = FloatArray(16)

    // Recursos OpenGL
    private var extTexId = 0
    private val frameTex = IntArray(2)
    private val frameFbo = IntArray(2)
    private val lumaTex = IntArray(2)
    private val lumaFbo = IntArray(2)
    private var mvTex = 0
    private var mvFbo = 0

    // Ping-pong de fotogramas y temporización
    private var currIdx = 0
    private var prevIdx = 1
    private var hasPrevFrame = false
    private var lastFrameNs = 0L
    private var frameIntervalNs = 41_666_666L // ~24 fps inicial

    // Programas Shader
    private var progPassthrough = 0
    private var progLuma = 0
    private var progMe = 0
    private var progWarp = 0

    // Uniforms cacheados
    private var uPtMatrix = -1
    private var uLumaTex = -1
    private var uMePrev = -1
    private var uMeCurr = -1
    private var uMeTexel = -1
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

        progMe = buildProgram(VS_STD, FS_ME_DIAMOND)
        uMePrev = GLES30.glGetUniformLocation(progMe, "uPrevLuma")
        uMeCurr = GLES30.glGetUniformLocation(progMe, "uCurrLuma")
        uMeTexel = GLES30.glGetUniformLocation(progMe, "uTexelSize")

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

        // Si MEMC está desactivado: dibujado directo sin tocar FBOs (mínimo consumo/calor)
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

        // Solo al llegar un nuevo fotograma real (24/25/30 veces/seg) calculamos luma y flujo óptico
        if (gotNewFrame) {
            if (lastFrameNs != 0L) {
                val delta = nowNs - lastFrameNs
                if (delta in 15_000_000L..100_000_000L) {
                    frameIntervalNs = (frameIntervalNs * 0.8 + delta * 0.2).toLong()
                    hasPrevFrame = true
                } else if (delta > 200_000_000L) {
                    // Salto grande (seek o pausa larga): evitar interpolar entre escenas distintas
                    hasPrevFrame = false
                }
            }
            lastFrameNs = nowNs

            // Intercambiar índices ping-pong
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

            // Pase 2: Reducir a luminancia 1/8 (GL_R8)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lumaFbo[currIdx])
            GLES30.glViewport(0, 0, lumaW, lumaH)
            GLES30.glUseProgram(progLuma)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[currIdx])
            GLES30.glUniform1i(uLumaTex, 0)
            drawQuad()

            // Pase 3: Estimación de movimiento (Diamond Search)
            if (hasPrevFrame) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mvFbo)
                GLES30.glViewport(0, 0, lumaW, lumaH)
                GLES30.glUseProgram(progMe)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[prevIdx])
                GLES30.glUniform1i(uMePrev, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lumaTex[currIdx])
                GLES30.glUniform1i(uMeCurr, 1)
                GLES30.glUniform2f(uMeTexel, 1f / lumaW, 1f / lumaH)
                drawQuad()
            }
        }

        // Pase 4 (60 Hz): Warping bidireccional directo a pantalla
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
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mvTex)
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
        hasPrevFrame = false

        for (i in 0..1) {
            val f = createFboTexture(allocatedW, allocatedH, GLES30.GL_RGBA8, GLES30.GL_RGBA)
            frameTex[i] = f.first
            frameFbo[i] = f.second

            val l = createFboTexture(lumaW, lumaH, GLES30.GL_R8, GLES30.GL_RED)
            lumaTex[i] = l.first
            lumaFbo[i] = l.second
        }
        val m = createFboTexture(lumaW, lumaH, GLES30.GL_RGBA8, GLES30.GL_RGBA)
        mvTex = m.first
        mvFbo = m.second
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
        if (mvTex != 0) GLES30.glDeleteTextures(1, intArrayOf(mvTex), 0)
        if (mvFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(mvFbo), 0)
        frameTex.fill(0); frameFbo.fill(0)
        lumaTex.fill(0); lumaFbo.fill(0)
        mvTex = 0; mvFbo = 0
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
        listOf(progPassthrough, progLuma, progMe, progWarp).forEach {
            if (it != 0) GLES30.glDeleteProgram(it)
        }
        progPassthrough = 0; progLuma = 0; progMe = 0; progWarp = 0
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

        private val FS_LUMA = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                vec3 rgb = texture(uTex, vTexCoord).rgb;
                float y = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                fragColor = vec4(y, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        // Búsqueda en diamante simétrica (máximo desplazamiento ±7 px en mapa 1/8 = ±56 px en 1080p)
        private val FS_ME_DIAMOND = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uPrevLuma;
            uniform sampler2D uCurrLuma;
            uniform vec2 uTexelSize;
            out vec4 fragColor;

            const float MAX_DISP = 7.0;

            float patchSad(vec2 uv, vec2 flowPx) {
                vec2 halfV = flowPx * 0.5 * uTexelSize;
                vec2 uvP = uv - halfV;
                vec2 uvC = uv + halfV;
                vec2 dx = vec2(uTexelSize.x, 0.0);
                vec2 dy = vec2(0.0, uTexelSize.y);

                float s = abs(texture(uPrevLuma, uvP).r - texture(uCurrLuma, uvC).r);
                s += abs(texture(uPrevLuma, uvP + dx).r - texture(uCurrLuma, uvC + dx).r);
                s += abs(texture(uPrevLuma, uvP - dx).r - texture(uCurrLuma, uvC - dx).r);
                s += abs(texture(uPrevLuma, uvP + dy).r - texture(uCurrLuma, uvC + dy).r);
                s += abs(texture(uPrevLuma, uvP - dy).r - texture(uCurrLuma, uvC - dy).r);
                return s * 0.2;
            }

            void main() {
                vec2 bestV = vec2(0.0);
                float bestSad = patchSad(vTexCoord, bestV);

                // Zona estática: ahorrar cálculos y evitar vibración en subtítulos/fondos
                if (bestSad > 0.015) {
                    float steps[3];
                    steps[0] = 4.0;
                    steps[1] = 2.0;
                    steps[2] = 1.0;

                    for (int i = 0; i < 3; i++) {
                        float r = steps[i];
                        vec2 base = bestV;
                        vec2 dirs[4];
                        dirs[0] = vec2( r, 0.0);
                        dirs[1] = vec2(-r, 0.0);
                        dirs[2] = vec2(0.0,  r);
                        dirs[3] = vec2(0.0, -r);

                        for (int d = 0; d < 4; d++) {
                            vec2 cand = clamp(base + dirs[d], vec2(-MAX_DISP), vec2(MAX_DISP));
                            float sad = patchSad(vTexCoord, cand);
                            if (sad < bestSad) {
                                bestSad = sad;
                                bestV = cand;
                            }
                        }
                    }
                }

                // Confianza: cae a 0 en cortes bruscos de escena o movimientos extremos
                float confidence = 1.0 - smoothstep(0.08, 0.20, bestSad);
                vec2 encoded = (bestV / MAX_DISP) * 0.5 + 0.5;
                fragColor = vec4(encoded, confidence, 1.0);
            }
        """.trimIndent()

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

            const float MAX_DISP = 7.0;

           void main() {
    vec3 prev = texture(uPrevTex, vTexCoord).rgb;
    vec3 curr = texture(uCurrTex, vTexCoord).rgb;
    fragColor = vec4(mix(prev, curr, uAlpha), 1.0);
}
        """.trimIndent()
    }
}
