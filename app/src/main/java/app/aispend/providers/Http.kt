package app.aispend.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, val body: String) : Exception("HTTP $code")

/** Minimal HTTP surface so providers stay testable without a network. */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String
}

class OkHttpTransport : Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>) =
        execute(Request.Builder().url(url).get(), headers)

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) =
        execute(Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType())), headers)

    private suspend fun execute(builder: Request.Builder, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpException(response.code, body)
                body
            }
        }
}
