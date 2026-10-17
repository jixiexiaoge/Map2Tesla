package com.phairplay.diagnostics

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * CapturingInputStream — passes reads straight through while keeping a copy of every byte.
 *
 * Used to record RTSP requests exactly as they arrived, rather than re-serialising the parsed
 * form: a reconstruction would normalise header order and whitespace, and a recording that
 * quietly differs from the wire is worse than none.
 *
 * Only safe to wrap a reader that does not read ahead past the message it is parsing —
 * [com.phairplay.airplay.RtspRequestReader] reads the request line a byte at a time and then
 * exactly Content-Length body bytes, so the capture ends precisely at the message boundary.
 */
class CapturingInputStream(private val delegate: InputStream) : InputStream() {

    private val captured = ByteArrayOutputStream(INITIAL_CAPACITY)

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) captured.write(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) captured.write(b, off, n)
        return n
    }

    override fun available(): Int = delegate.available()

    /** Bytes read so far. Does not reset — one instance captures one message. */
    fun captured(): ByteArray = captured.toByteArray()

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
