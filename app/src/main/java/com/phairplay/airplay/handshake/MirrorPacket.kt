package com.phairplay.airplay.handshake

/**
 * MirrorPacket — the 128-byte header that prefixes every packet on the AirPlay mirroring
 * data connection, decoded into named fields.
 *
 * WHY: the reader used to pull two magic offsets out of the header and ignore the rest, so
 * three of the five things the sender transmits were discarded unexamined. Naming the fields
 * makes it possible to say what each packet actually is — and to record it faithfully.
 *
 * Wire layout (all integers little-endian):
 *
 * ```
 *  offset  size  field
 *  0       4     payloadSize      bytes following the 128-byte header
 *  4       2     payloadType      see [Type]
 *  6       2     payloadOption    type-specific flags
 *  8       8     ntpTimestamp     sender's NTP time; only meaningful for VIDEO
 *  16      112   type-specific    for CODEC_CONFIG: display geometry (see below)
 * ```
 *
 * Field layout was cross-checked against UxPlay's `raop_rtp_mirror.c`, the most complete
 * open description of this stream.
 */
data class MirrorPacket(
    val payloadSize: Int,
    val payloadType: Int,
    val payloadOption: Int,
    val ntpTimestamp: Long,
    /** The raw 128-byte header, kept verbatim so a recording loses nothing. */
    val header: ByteArray
) {

    val type: Type get() = Type.of(payloadType)

    /**
     * The kinds of packet a sender puts on this connection.
     *
     * Every value here is something an iPhone or Mac actually sends during a normal mirroring
     * session — including the ones that carry no video.
     */
    enum class Type(val code: Int, val label: String) {
        /** AES-CTR encrypted H.264 video: one or more length-prefixed VCL NAL units. */
        VIDEO(0x00, "video"),

        /** Unencrypted SPS + PPS, plus the display geometry the sender chose. */
        CODEC_CONFIG(0x01, "codec-config"),

        /**
         * Once-per-second keepalive with an empty body, from the older mirroring protocol
         * (AirMyPC and similar senders). Nothing to decode — its arrival is the signal.
         */
        HEARTBEAT(0x02, "heartbeat"),

        /**
         * Once-per-second streaming report: a binary plist of the sender's own view of the
         * stream (its frame rate, timing and buffer state). See [ClientStreamingReport].
         *
         * When the sending device's screen is locked, a fixed 25,000-byte trailer of
         * unidentified content is appended after the plist.
         */
        STREAMING_REPORT(0x05, "streaming-report"),

        /** Anything the senders we have seen do not produce. Recorded verbatim, never dropped. */
        UNKNOWN(-1, "unknown");

        companion object {
            fun of(code: Int): Type = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /**
     * Display geometry the sender advertises in a [Type.CODEC_CONFIG] packet.
     * Returns null for every other type.
     */
    fun codecConfigGeometry(): Geometry? {
        if (type != Type.CODEC_CONFIG) return null
        return runCatching {
            Geometry(
                widthPixels = leInt(header, 56),
                heightPixels = leInt(header, 60),
                widthPoints = Float.fromBits(leInt(header, 16)),
                heightPoints = Float.fromBits(leInt(header, 20))
            )
        }.getOrNull()
    }

    /** Sender-declared display size: pixels for the surface, points for the logical size. */
    data class Geometry(
        val widthPixels: Int,
        val heightPixels: Int,
        val widthPoints: Float,
        val heightPoints: Float
    )

    /** One-line description for logs and the session recording index. */
    fun describe(): String = buildString {
        append(type.label)
        append(" size=").append(payloadSize)
        if (payloadOption != 0) append(" opt=0x").append(payloadOption.toString(16))
        if (type == Type.VIDEO) append(" ntp=").append(ntpTimestamp)
        if (type == Type.UNKNOWN) append(" code=0x").append(payloadType.toString(16))
        codecConfigGeometry()?.let {
            append(" ").append(it.widthPixels).append("x").append(it.heightPixels)
            append(" (").append(it.widthPoints).append("x").append(it.heightPoints).append("pt)")
        }
    }

    // data class with a ByteArray member — compare by content so equality is meaningful.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MirrorPacket) return false
        return payloadSize == other.payloadSize &&
            payloadType == other.payloadType &&
            payloadOption == other.payloadOption &&
            ntpTimestamp == other.ntpTimestamp &&
            header.contentEquals(other.header)
    }

    override fun hashCode(): Int {
        var result = payloadSize
        result = 31 * result + payloadType
        result = 31 * result + payloadOption
        result = 31 * result + ntpTimestamp.hashCode()
        result = 31 * result + header.contentHashCode()
        return result
    }

    companion object {
        /** Every packet on this connection is prefixed by a header of exactly this size. */
        const val HEADER_SIZE = 128

        /**
         * Parses [header], which must be [HEADER_SIZE] bytes.
         *
         * The array is copied: the reader reuses one buffer for every packet, and a recording
         * must not end up holding 100,000 references to the same mutating array.
         */
        fun parse(header: ByteArray): MirrorPacket {
            require(header.size >= HEADER_SIZE) { "mirror header must be $HEADER_SIZE bytes" }
            return MirrorPacket(
                payloadSize = leInt(header, 0),
                payloadType = leShort(header, 4) and 0xFF,
                payloadOption = leShort(header, 6),
                ntpTimestamp = leLong(header, 8),
                header = header.copyOf(HEADER_SIZE)
            )
        }

        private fun leInt(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun leShort(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun leLong(b: ByteArray, off: Int): Long =
            (0 until 8).fold(0L) { acc, i -> acc or ((b[off + i].toLong() and 0xFF) shl (8 * i)) }
    }
}
