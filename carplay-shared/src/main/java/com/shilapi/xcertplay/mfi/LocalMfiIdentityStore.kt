package com.shilapi.xcertplay.mfi

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One validated atomic bundle prevents mixing a new key with an old certificate. */
class LocalMfiIdentityStore(private val file: File) {
    fun load(): LocalMfiAuthenticationClient = DataInputStream(file.inputStream()).use { input ->
        require(input.readInt() == MAGIC) { "Invalid local identity format" }
        val key = readEntry(input)
        try {
            val certificate = readEntry(input)
            require(input.read() == -1) { "Unexpected local identity data" }
            LocalMfiAuthenticationClient.fromEncoded(key, certificate)
        } finally {
            key.fill(0)
        }
    }

    fun replace(key: ByteArray, certificate: ByteArray) {
        LocalMfiAuthenticationClient.fromEncoded(key, certificate)
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create local identity storage" }
        val temporary = File.createTempFile("mfi-import-", ".tmp", parent)
        try {
            temporary.outputStream().use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                output.writeInt(key.size)
                output.write(key)
                output.writeInt(certificate.size)
                output.write(certificate)
                output.flush()
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            check(!temporary.exists() || temporary.delete()) { "Cannot clean local identity import" }
        }
    }

    fun clear() {
        check(!file.exists() || file.delete()) { "Cannot remove local identity" }
    }

    companion object {
        const val FILE_NAME = "local-mfi.identity"
        private const val MAGIC = 0x4d464931
        private const val MAX_BYTES = 16 * 1024

        fun readDocument(input: InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) { "Identity file exceeds 16384 bytes" }
                    output.write(buffer, 0, count)
                }
                require(output.size() > 0) { "Identity file is empty" }
                return output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        }

        private fun readEntry(input: DataInputStream): ByteArray {
            val length = input.readInt()
            require(length in 1..MAX_BYTES) { "Invalid local identity entry size" }
            return ByteArray(length).also(input::readFully)
        }
    }
}
