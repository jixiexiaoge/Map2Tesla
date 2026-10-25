package com.navipilot.amap2tesla.sponsorship

import android.content.Context
import com.google.gson.JsonParseException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jixiexiaoge.drivingassist.core.ErrorCode
import com.jixiexiaoge.drivingassist.core.Result
import com.jixiexiaoge.drivingassist.core.SecurePrefs
import com.jixiexiaoge.drivingassist.core.runSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

data class SponsorshipOption(
    val code: String,
    val name: String,
    val priceCents: Int,
)

data class SponsorshipCheckout(
    val orderNo: String,
    val checkoutUrl: String,
)

internal val SPONSORSHIP_OPTIONS = listOf(
    SponsorshipOption("sponsor_5", "支持一下", 500),
    SponsorshipOption("sponsor_15", "请开发者喝杯咖啡", 1500),
    SponsorshipOption("sponsor_25", "支持持续开发", 2500),
    SponsorshipOption("sponsor_55", "助力开源维护", 5500),
)

class SponsorshipClient(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = SecurePrefs.get(appContext)

    suspend fun loadOptions(): Result<List<SponsorshipOption>> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            val root = request("/api/v1/sponsorships").also { requireSuccessful(it) }
            parseSponsorshipOptions(root)
        }
    }

    suspend fun createOrder(optionCode: String): Result<SponsorshipCheckout> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            require(SPONSORSHIP_OPTIONS.any { it.code == optionCode }) { "无效的赞助选项" }
            val response = authenticatedRequest(
                path = "/api/v1/sponsorships/orders",
                method = "POST",
                body = JsonObject().apply {
                    addProperty("plan_code", optionCode)
                    addProperty("payment_type", "alipay")
                }.toString(),
            )
            SponsorshipCheckout(
                orderNo = response.requiredString("order_no"),
                checkoutUrl = response.requiredString("checkout_url"),
            )
        }
    }

    suspend fun refreshOrder(orderNo: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runSafely(ErrorCode.NETWORK_CONNECTION_FAILED) {
            val response = authenticatedRequest(
                path = "/api/v1/orders/$orderNo/refresh",
                method = "POST",
            )
            isSponsorshipPaid(response)
        }
    }

    private fun authenticatedRequest(path: String, method: String, body: String? = null): JsonObject {
        var response = request(path, authorizationToken(), method, body)
        if (response.requiredInt("http_status") == 401) {
            preferences.edit().remove(PREF_DEVICE_TOKEN).apply()
            registerDevice()
            response = request(path, authorizationToken(), method, body)
        }
        requireSuccessful(response)
        return response
    }

    private fun authorizationToken(): String {
        return preferences.getString(PREF_DEVICE_TOKEN, null) ?: registerDevice()
    }

    private fun registerDevice(): String {
        val installationId = preferences.getString(PREF_INSTALLATION_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(PREF_INSTALLATION_ID, it).apply()
            }
        val root = request(
            path = "/api/v1/devices/register",
            method = "POST",
            body = """{"installation_id":"$installationId"}""",
        )
        requireSuccessful(root)
        return root.requiredString("device_token").also { token ->
            preferences.edit().putString(PREF_DEVICE_TOKEN, token).apply()
        }
    }

    private fun request(
        path: String,
        bearerToken: String? = null,
        method: String = "GET",
        body: String? = null,
    ): JsonObject {
        try {
            return requestAt(PRIMARY_BASE_URL, path, bearerToken, method, body)
        } catch (error: JsonParseException) {
            throw IOException("赞助服务返回了无效数据", error)
        }
    }

    private fun requestAt(
        baseUrl: String,
        path: String,
        bearerToken: String?,
        method: String,
        body: String?,
    ): JsonObject {
        val requestBuilder = Request.Builder().url("$baseUrl$path")
        bearerToken?.let { requestBuilder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(
                (body ?: "").toRequestBody(JSON_MEDIA_TYPE)
            )
            else -> error("Unsupported HTTP method: $method")
        }
        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("赞助服务未返回响应内容")
            val root = JsonParser.parseString(responseBody).asJsonObject
            root.addProperty("http_status", response.code)
            root
        }
    }

    private fun requireSuccessful(root: JsonObject) {
        val status = root.requiredInt("http_status")
        if (status !in 200..299) {
            throw IOException(root.get("error")?.asString ?: "赞助服务请求失败（$status），不影响免费使用")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.asString?.takeIf { it.isNotBlank() }
            ?: throw IOException("赞助服务未返回 $name")

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.asInt ?: throw IOException("赞助服务未返回 $name")

    private companion object {
        const val PRIMARY_BASE_URL = "https://cp.jixiexiaoge.online"
        const val PREF_INSTALLATION_ID = "projection_membership_installation_id"
        const val PREF_DEVICE_TOKEN = "projection_membership_device_token"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val httpClient = OkHttpClient()
    }

}

internal fun parseSponsorshipOptions(root: JsonObject): List<SponsorshipOption> {
    val options = root.getAsJsonArray("options") ?: throw IOException("赞助服务未返回赞助选项")
    val parsed = options.map { item ->
        val option = item.asJsonObject
        val code = option.get("code")?.asString ?: throw IOException("赞助选项缺少编号")
        val name = option.get("name")?.asString ?: throw IOException("赞助选项缺少名称")
        val cents = option.get("price_cents")?.asInt ?: throw IOException("赞助选项缺少金额")
        if (!code.startsWith("sponsor_") || name.isBlank() || cents <= 0) {
            throw IOException("赞助选项无效")
        }
        SponsorshipOption(code, name, cents)
    }
    if (parsed.size != SPONSORSHIP_OPTIONS.size ||
        parsed.map { it.code }.toSet().size != parsed.size ||
        SPONSORSHIP_OPTIONS.any { expected ->
            parsed.none { it.code == expected.code && it.priceCents == expected.priceCents }
        }
    ) {
        throw IOException("赞助服务尚未更新为 5、15、25、55 元选项，请稍后重试")
    }
    return SPONSORSHIP_OPTIONS.map { expected -> parsed.first { it.code == expected.code } }
}

internal fun isSponsorshipPaid(response: JsonObject): Boolean = when (response.get("status")?.asString) {
    "paid" -> true
    "pending" -> false
    else -> throw IOException("赞助服务返回了未知订单状态")
}
