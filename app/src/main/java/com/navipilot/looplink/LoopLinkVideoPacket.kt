package com.navipilot.looplink

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** WebCodecs and MP4 need length-prefixed access units, not Annex B start codes. */
internal object LoopLinkVideoPacket {
    data class Frame(val message: ByteArray, val key: Boolean, val rasl: Boolean)

    fun frame(codec: VideoCodec, annexB: ByteArray, arrivalUs: Long): Frame {
        val starts = mutableListOf<Pair<Int, Int>>()
        var at = 0
        while (at + 3 <= annexB.size) {
            val size = when {
                annexB[at] != 0.toByte() || annexB[at + 1] != 0.toByte() -> 0
                annexB[at + 2] == 1.toByte() -> 3
                at + 4 <= annexB.size && annexB[at + 2] == 0.toByte() && annexB[at + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (size > 0) { starts.add(at to size); at += size } else at++
        }
        require(starts.isNotEmpty() && starts.first().first == 0) { "Malformed CarPlay access unit" }
        val output = ByteArrayOutputStream(annexB.size + 10)
        output.write(ByteArray(10))
        val types = starts.mapIndexed { index, (offset, prefix) ->
            val start = offset + prefix
            val end = starts.getOrNull(index + 1)?.first ?: annexB.size
            val length = end - start
            require(length > 0 && annexB[start].toInt() and 0x80 == 0) { "Invalid video NAL header" }
            if (codec == VideoCodec.H265) {
                require(length >= 2 && annexB[start + 1].toInt() and 7 != 0) { "Invalid HEVC NAL header" }
            }
            output.write(ByteBuffer.allocate(4).putInt(length).array())
            output.write(annexB, start, length)
            if (codec == VideoCodec.H265) (annexB[start].toInt() ushr 1) and 63
            else annexB[start].toInt() and 31
        }
        val key = types.any { if (codec == VideoCodec.H265) it in 16..21 else it == 5 }
        val message = output.toByteArray()
        message[0] = 2
        message[1] = if (key) 1 else 0
        ByteBuffer.wrap(message, 2, 8).putLong(arrivalUs)
        return Frame(message, key, codec == VideoCodec.H265 && types.any { it == 8 || it == 9 })
    }
}
