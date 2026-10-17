package com.navipilot.amap2tesla.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MjpegEncoder(
    private val width: Int,
    private val height: Int,
    private val frameStore: LatestFrameStore,
    private val fps: Int = 12,
    private val quality: Int = 72,
    private val onFirstFramePublished: ((Int) -> Unit)? = null,
    private val onEncodingError: ((String) -> Unit)? = null,
) : AutoCloseable {
    private val thread = HandlerThread("Amap2Tesla-mjpeg").apply { start() }
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val output = ByteArrayOutputStream(512 * 1024)
    private var paddedBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCanvas: Canvas? = null
    private var lastFrameAt = 0L
    private var lastEncodingErrorAt = 0L
    private val firstFramePublished = AtomicBoolean(false)

    val surface: Surface
        get() = reader.surface

    init {
        val intervalMillis = 1000L / fps.coerceAtLeast(1)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFrameAt < intervalMillis) return@setOnImageAvailableListener
                lastFrameAt = now

                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                if (pixelStride <= 0 || rowStride < width * pixelStride) {
                    return@setOnImageAvailableListener
                }
                val paddedWidth = rowStride / pixelStride
                ensureBitmaps(paddedWidth)

                plane.buffer.rewind()
                paddedBitmap!!.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (paddedWidth == width) {
                    paddedBitmap!!
                } else {
                    cropCanvas!!.drawBitmap(
                        paddedBitmap!!,
                        Rect(0, 0, width, height),
                        Rect(0, 0, width, height),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    )
                    croppedBitmap!!
                }

                output.reset()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)) {
                    val jpeg = output.toByteArray()
                    frameStore.publish(jpeg)
                    if (firstFramePublished.compareAndSet(false, true)) {
                        Log.i(TAG, "First JPEG frame published size=${jpeg.size}")
                        onFirstFramePublished?.invoke(jpeg.size)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to encode decoded CarPlay frame", error)
                val errorAt = android.os.SystemClock.elapsedRealtime()
                if (errorAt - lastEncodingErrorAt >= ERROR_REPORT_INTERVAL_MS) {
                    lastEncodingErrorAt = errorAt
                    onEncodingError?.invoke(
                        "CarPlay 视频转 JPEG 失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            } finally {
                image.close()
            }
        }, Handler(thread.looper))
    }

    private fun ensureBitmaps(paddedWidth: Int) {
        if (paddedBitmap?.width != paddedWidth || paddedBitmap?.height != height) {
            paddedBitmap?.recycle()
            paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        }
        if (paddedWidth != width &&
            (croppedBitmap?.width != width || croppedBitmap?.height != height)
        ) {
            croppedBitmap?.recycle()
            croppedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            cropCanvas = Canvas(croppedBitmap!!)
        }
    }

    override fun close() {
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        paddedBitmap?.recycle()
        croppedBitmap?.recycle()
        paddedBitmap = null
        croppedBitmap = null
        cropCanvas = null
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "LoopLinkMjpeg"
        const val ERROR_REPORT_INTERVAL_MS = 5_000L
    }
}
