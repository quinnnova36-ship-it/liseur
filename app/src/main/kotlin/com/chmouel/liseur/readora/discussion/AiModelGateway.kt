package com.chmouel.liseur.readora.discussion

import com.chmouel.liseur.readora.discussion.DiscussionMessage.Companion.ROLE_ASSISTANT
import com.chmouel.liseur.readora.discussion.DiscussionMessage.Companion.ROLE_USER
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What can go wrong sending one discussion turn, in terms the sheet
 * can show without further translation.
 */
sealed class DiscussionSendException(message: String) : Exception(message) {
    /** No model is configured yet; the reader is sent to settings. */
    class NoModelConfigured : DiscussionSendException("没有可用的模型配置")

    /** The address will not parse as an http(s) URL at all. */
    class BadAddress(val detail: String) : DiscussionSendException("模型服务地址无法解析：$detail")

    /** Plain http without the reader having allowed it for this config. */
    class InsecureAddress : DiscussionSendException("该地址不是 https；未开启明文允许")

    /** Transport-level failure: offline, DNS, timeout. */
    class Network(val detail: String) : DiscussionSendException("网络请求失败：$detail")

    /** The server answered but not with a completion we can use. */
    class Server(val code: Int, val body: String) :
        DiscussionSendException("服务返回 $code：${body.take(200)}")
}

/**
 * Sends discussion turns to an OpenAI-compatible chat endpoint.
 *
 * Readora S3. One request per send, made only from the send action —
 * nothing here is called by opening a sheet or entering a question.
 * The reply arrives whole (no streaming): the product runs its P0 on
 * whole replies, and the gateway is the single seam a later streaming
 * implementation replaces.
 *
 * Transport policy: https only, unless the caller passes the reader's
 * explicit plaintext allowance for this config. Redirects are not
 * followed — a key that travels must travel where it was pointed.
 */
class AiModelGateway(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * @param history turns already in the thread, oldest first; each is
     * a role ("user"/"assistant") and its content as stored.
     * @param key the credential for this config, already decrypted;
     * null sends without an Authorization header.
     */
    suspend fun send(
        config: ModelConfig,
        key: String?,
        question: String,
        composedContext: String,
        history: List<Pair<String, String>>,
        allowPlaintext: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        val base: HttpUrl = normalise(config.baseUrl, allowPlaintext)
            ?: run {
                val insecure = config.baseUrl.trim().startsWith("http://", ignoreCase = true)
                return@withContext Result.failure(
                    if (insecure) {
                        DiscussionSendException.InsecureAddress()
                    } else {
                        DiscussionSendException.BadAddress(config.baseUrl)
                    },
                )
            }

        val messages = JSONArray().apply {
            history.forEach { (role, content) -> put(message(role, content)) }
            put(message(ROLE_USER, composedContext))
        }
        val body = JSONObject()
            .put("model", config.modelId)
            .put("stream", false)
            .put("messages", messages)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(requireNotNull(base.newBuilder().addPathSegments("chat/completions").build()))
            .header("User-Agent", "readora")
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()?.take(MAX_REPLY) ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        DiscussionSendException.Server(response.code, text.take(400)),
                    )
                }
                val content = runCatching {
                    JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }.getOrNull()
                    ?: return@withContext Result.failure(
                        DiscussionSendException.Server(response.code, "回复中没有可用的内容"),
                    )
                Result.success(content)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(DiscussionSendException.Network(e.message ?: "network error"))
        }
    }

    private fun message(role: String, content: String): JSONObject {
        val known = if (role == ROLE_ASSISTANT) ROLE_ASSISTANT else ROLE_USER
        return JSONObject().put("role", known).put("content", content)
    }

    /**
     * The endpoint a base address names. A bare host means https (the
     * product's default is secure) and gains `/v1`; a path already
     * carrying a version root is respected as given.
     */
    internal fun normalise(raw: String, allowPlaintext: Boolean): HttpUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }.trimEnd('/')

        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.scheme == "http" && !allowPlaintext) return null
        return if (url.encodedPath.isEmpty() || url.encodedPath == "/") {
            url.newBuilder().addPathSegments("v1").build()
        } else {
            url
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_REPLY = 256 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            // A redirect could carry the Authorization header elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
