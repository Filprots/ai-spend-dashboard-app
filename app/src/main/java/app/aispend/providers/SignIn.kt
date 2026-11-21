package app.aispend.providers

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * In-app sign-in. Each sign-in is its own OAuth grant with its own refresh token, so renewing it
 * here never invalidates the tokens held by Claude Code or Codex on another machine.
 */

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
private val b64 = Base64.getUrlEncoder().withoutPadding()

private fun randomToken(): String = b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

internal fun pkceChallenge(verifier: String): String =
    b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/**
 * Claude: browser approval that ends on a page showing a one-time code, which the user pastes back.
 * (Claude has no device-code or custom-redirect flow for this client.)
 */
object ClaudeSignIn {
    private const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
    private const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
    private const val SCOPES = "user:profile user:inference"

    /** [verifier] and [state] must be kept until [finish]; they survive the trip to the browser. */
    data class Pending(val url: String, val verifier: String, val state: String)

    fun start(): Pending {
        val verifier = randomToken()
        val state = randomToken()
        val url = AUTHORIZE_URL +
            "?code=true&client_id=${ClaudeSubscription.CLIENT_ID}&response_type=code" +
            "&redirect_uri=${enc(REDIRECT_URI)}&scope=${enc(SCOPES)}" +
            "&code_challenge=${pkceChallenge(verifier)}&code_challenge_method=S256&state=$state"
        return Pending(url, verifier, state)
    }

    /** [pasted] is what the approval page shows: "code#state". */
    suspend fun finish(http: Http, pasted: String, verifier: String, state: String): Map<String, String> {
        val parts = pasted.trim().split("#")
        val code = parts[0]
        if (code.isEmpty()) throw IllegalStateException("Paste the code shown after approving")
        if (parts.size > 1 && parts[1] != state) {
            throw IllegalStateException("That code belongs to a different sign-in attempt — start again")
        }
        val request = buildJsonObject {
            put("grant_type", "authorization_code")
            put("code", code)
            put("state", state)
            put("client_id", ClaudeSubscription.CLIENT_ID)
            put("redirect_uri", REDIRECT_URI)
            put("code_verifier", verifier)
        }
        val tokens = try {
            parseObject(http.post(ClaudeSubscription.TOKEN_URL, request.toString(), "application/json"))
        } catch (e: HttpException) {
            throw IllegalStateException("Claude rejected the code (HTTP ${e.code}) — it may have expired, start again")
        }
        val access = tokens.str("access_token") ?: throw IllegalStateException("Claude returned no token")
        return buildMap {
            put("token", access)
            tokens.str("refresh_token")?.let { put("refresh", it) }
        }
    }
}

/** ChatGPT: device-code flow, the same one `codex login --device-auth` uses. */
object ChatGptSignIn {
    private const val ISSUER = "https://auth.openai.com"
    const val VERIFICATION_URL = "$ISSUER/codex/device"
    private const val TIMEOUT_MS = 15 * 60_000L

    data class DeviceCode(val userCode: String, val deviceAuthId: String, val intervalSeconds: Long)

    suspend fun start(http: Http): DeviceCode {
        val request = buildJsonObject { put("client_id", ChatGptSubscription.CLIENT_ID) }
        val body = try {
            parseObject(http.post("$ISSUER/api/accounts/deviceauth/usercode", request.toString(), "application/json"))
        } catch (e: HttpException) {
            throw IllegalStateException("ChatGPT did not issue a sign-in code (HTTP ${e.code})")
        }
        val code = body.str("user_code") ?: body.str("usercode")
        val id = body.str("device_auth_id")
        if (code == null || id == null) throw IllegalStateException("ChatGPT did not issue a sign-in code")
        return DeviceCode(code, id, (body.num("interval") ?: 5.0).toLong().coerceIn(2, 30))
    }

    /** Suspends until the user approves in the browser (or 15 minutes pass), then returns credentials. */
    suspend fun await(http: Http, device: DeviceCode, wait: suspend (Long) -> Unit = { delay(it) }): Map<String, String> {
        val poll = buildJsonObject {
            put("device_auth_id", device.deviceAuthId)
            put("user_code", device.userCode)
        }.toString()
        val grant = awaitApproval(http, poll, device.intervalSeconds * 1000, wait)
        val code = grant.str("authorization_code") ?: throw IllegalStateException("Sign-in returned no code")
        val verifier = grant.str("code_verifier") ?: throw IllegalStateException("Sign-in returned no verifier")
        val form = "grant_type=authorization_code&code=${enc(code)}" +
            "&redirect_uri=${enc("$ISSUER/deviceauth/callback")}" +
            "&client_id=${ChatGptSubscription.CLIENT_ID}&code_verifier=${enc(verifier)}"
        val tokens = try {
            parseObject(http.post(ChatGptSubscription.TOKEN_URL, form, "application/x-www-form-urlencoded"))
        } catch (e: HttpException) {
            throw IllegalStateException("ChatGPT rejected the sign-in (HTTP ${e.code})")
        }
        val access = tokens.str("access_token") ?: throw IllegalStateException("ChatGPT returned no token")
        return buildMap {
            put("token", access)
            tokens.str("refresh_token")?.let { put("refresh", it) }
            (accountId(tokens.str("id_token")) ?: accountId(access))?.let { put("account", it) }
        }
    }

    private suspend fun awaitApproval(http: Http, poll: String, intervalMs: Long, wait: suspend (Long) -> Unit): JsonObject {
        var waited = 0L
        while (true) {
            try {
                return parseObject(http.post("$ISSUER/api/accounts/deviceauth/token", poll, "application/json"))
            } catch (e: HttpException) {
                // 403/404 mean "not approved yet".
                if (e.code != 403 && e.code != 404) throw IllegalStateException("Sign-in failed (HTTP ${e.code})")
            }
            if (waited >= TIMEOUT_MS) throw IllegalStateException("Sign-in timed out — start again")
            wait(intervalMs)
            waited += intervalMs
        }
    }

    /** The ChatGPT account ID lives in a namespaced claim of the JWT payload. */
    internal fun accountId(jwt: String?): String? = runCatching {
        val payload = Base64.getUrlDecoder().decode(jwt!!.split(".")[1]).decodeToString()
        parseObject(payload).obj("https://api.openai.com/auth").str("chatgpt_account_id")
    }.getOrNull()
}
