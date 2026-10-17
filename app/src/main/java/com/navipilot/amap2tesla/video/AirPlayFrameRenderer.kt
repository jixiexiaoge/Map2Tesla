package com.navipilot.amap2tesla.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AirPlayFrameRenderer(
    private val width: Int,
    private val height: Int,
    outputSurface: Surface,
    targetFps: Int,
    private val onFirstFrameRendered: (() -> Unit)? = null,
    private val onRenderError: ((String) -> Unit)? = null,
) : AutoCloseable {
    private val thread = HandlerThread("AirPlay-frame-renderer").apply { start() }
    private val handler = Handler(thread.looper)
    private val frameIntervalMillis = 1_000L / targetFps.coerceAtLeast(1)
    private val transform = FloatArray(16)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var released = false
    private var lastRenderedAt = 0L
    private var sourceWidth = width
    private var sourceHeight = height
    private var firstFrameRendered = false
    private var lastRenderErrorAt = 0L
    private val vertices = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

    val inputSurface: Surface

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                initialize(outputSurface)
            } catch (error: Throwable) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "AirPlay frame renderer timed out" }
        failure?.let { throw IllegalStateException("AirPlay frame renderer initialization failed", it) }
        inputSurface = Surface(requireNotNull(texture))
    }

    fun setSourceSize(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        handler.post {
            this.sourceWidth = sourceWidth
            this.sourceHeight = sourceHeight
        }
    }

    private fun initialize(outputSurface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to open EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize EGL" }
        val config = chooseConfig()
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        surface = EGL14.eglCreateWindowSurface(display, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        makeCurrent()

        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ renderLatestFrameSafely() }, handler)
        }
    }

    private fun renderLatestFrameSafely() {
        try {
            renderLatestFrame()
        } catch (error: Exception) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRenderErrorAt >= ERROR_REPORT_INTERVAL_MS) {
                lastRenderErrorAt = now
                onRenderError?.invoke(
                    "CarPlay 视频 EGL 转换失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                configs.size,
                count,
                0,
            ) && count[0] > 0,
        ) { "Unable to choose EGL config" }
        return requireNotNull(configs[0])
    }

    private fun renderLatestFrame() {
        if (released) return
        makeCurrent()
        texture?.updateTexImage() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRenderedAt < frameIntervalMillis) return
        lastRenderedAt = now

        texture?.getTransformMatrix(transform)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val outputAspect = width.toFloat() / height
        val scaleX = if (sourceAspect > outputAspect) 1f else sourceAspect / outputAspect
        val scaleY = if (sourceAspect > outputAspect) outputAspect / sourceAspect else 1f
        vertices.position(0)
        vertices.put(floatArrayOf(-scaleX, -scaleY, scaleX, -scaleY, -scaleX, scaleY, scaleX, scaleY))
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
        GLES20.glUniformMatrix4fv(transformHandle, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(EGL14.eglSwapBuffers(display, surface)) { "Unable to publish EGL frame" }
        if (!firstFrameRendered) {
            firstFrameRendered = true
            onFirstFrameRendered?.invoke()
        }
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Unable to make EGL context current" }
    }

    override fun close() {
        if (released) return
        released = true
        val closed = CountDownLatch(1)
        handler.post {
            runCatching {
                makeCurrent()
                texture?.setOnFrameAvailableListener(null)
                texture?.release()
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            closed.countDown()
        }
        closed.await(2, TimeUnit.SECONDS)
        inputSurface.release()
        thread.quitSafely()
    }

    private val program: Int by lazy {
        val vertex = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTransform;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTransform * aTexCoord).xy;
                }
            """.trimIndent(),
        )
        val fragment = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """.trimIndent(),
        )
        GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(it) }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private val positionHandle: Int
        get() = GLES20.glGetAttribLocation(program, "aPosition")
    private val texCoordHandle: Int
        get() = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val transformHandle: Int
        get() = GLES20.glGetUniformLocation(program, "uTransform")
    private val textureHandle: Int
        get() = GLES20.glGetUniformLocation(program, "uTexture")

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(it) }
        }

    private companion object {
        const val ERROR_REPORT_INTERVAL_MS = 5_000L
        val textureCoordinates = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
