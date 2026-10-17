package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RemoteMfiAuthenticationClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun resetsLoadsAndCachesCertificateThenSignsWithStableRequestId() {
        val certificate = ByteArray(300) { it.toByte() }
        val signature = ByteArray(64) { (0xa0 + it).toByte() }
        server.enqueue(json("""{"detail":""}"""))
        server.enqueue(json(certificateJson(certificate)))
        val signed = """{"signature":"${base64(signature)}"}"""
        server.enqueue(json(signed).setBodyDelay(1, TimeUnit.SECONDS))
        server.enqueue(json(signed))
        val client = RemoteMfiAuthenticationClient(
            server.url("/").toString(),
            token = "test-token",
            readTimeoutMillis = 250,
        )

        client.reset()
        assertEquals(3, client.protocolMajor())
        assertArrayEquals(certificate, client.readCertificate())
        val challenge = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(signature, client.signChallenge(challenge))

        val reset = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/mfi/reset", reset.path)
        assertEquals("POST", reset.method)
        assertEquals("{}", reset.body.readUtf8())
        assertEquals("/mfi/certificate", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        val first = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val second = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/mfi/sign", first.path)
        assertEquals("/mfi/sign", second.path)
        val firstBody = first.body.readUtf8()
        val secondBody = second.body.readUtf8()
        assertEquals(RemoteMfiJson.string(firstBody, "requestId"), RemoteMfiJson.string(secondBody, "requestId"))
        assertEquals(base64(challenge), RemoteMfiJson.string(firstBody, "challenge"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun rejectsCertificateWhenSha256DoesNotMatch() {
        server.enqueue(json(
            """{"protocolMajor":3,"certificate":"AQID","certificateSha256":"${"00".repeat(32)}"}""",
        ))
        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(server.url("/").toString()).readCertificate()
        }
        assertEquals("Remote certificate SHA-256 does not match certificateSha256", error.message)
    }

    @Test
    fun loadsBaaPackageAndBuildsShowcaseIap2Payload() {
        val leaf = ByteArray(40) { (0x10 + it).toByte() }
        val intermediate = ByteArray(60) { (0x80 + it).toByte() }
        val bytes = ByteBuffer.allocate(8).putInt(leaf.size).putInt(intermediate.size).array() +
            leaf + intermediate
        server.enqueue(json(certificateJson(bytes, "baa")))
        val client = RemoteMfiAuthenticationClient(server.url("/").toString())

        assertEquals(MfiCertificateType.BAA, client.certificateType)
        assertArrayEquals(
            Iap2AuthenticationMessages.baaCertificatePackage(leaf, intermediate).payload,
            client.readCertificate(),
        )
        assertArrayEquals(leaf, client.baaCertificates().leaf)
        assertArrayEquals(intermediate, client.baaCertificates().intermediate)
    }

    @Test
    fun rejectsUnknownCertificateType() {
        server.enqueue(json(certificateJson(byteArrayOf(1, 2, 3), "unknown")))
        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(server.url("/").toString()).readCertificate()
        }
        assertEquals("Unsupported remote MFI certificate type 'unknown'", error.message)
    }

    @Test
    fun resetSurfacesHttp500DetailWithoutRetrying() {
        server.enqueue(json("""{"detail":"chip reset failed"}""").setResponseCode(500))
        val error = assertThrows(RemoteMfiHttpException::class.java) {
            RemoteMfiAuthenticationClient(server.url("/").toString()).reset()
        }
        assertEquals(500, error.statusCode)
        assertEquals("chip reset failed", error.detail)
        assertEquals(1, server.requestCount)
    }

    private fun certificateJson(bytes: ByteArray, type: String = "mfi"): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return """{"type":"$type","protocolMajor":3,"certificate":"${base64(bytes)}","certificateSha256":"$digest"}"""
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
