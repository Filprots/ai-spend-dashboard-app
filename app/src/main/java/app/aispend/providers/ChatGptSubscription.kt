package app.aispend.providers

import app.aispend.data.Meter
import app.aispend.data.Money
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** ChatGPT plan limits via the endpoint Codex CLI uses. Undocumented, so it can change without notice. */
object ChatGptSubscription : UsageProvider {
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult {
        val account = credentials["account"].orEmpty()
        return try {
            parse(usage(http, credentials["token"].orEmpty(), account))
        } catch (e: HttpException) {
            val refresh = credentials["refresh"].orEmpty()
            if (e.code != 401 || refresh.isEmpty()) throw e
            // Refresh tokens are single-use: the new pair must be saved or the profile locks itself out.
            val request = buildJsonObject {
                put("client_id", CLIENT_ID)
                put("grant_type", "refresh_token")
                put("refresh_token", refresh)
            }
            val renewed = parseObject(http.post(TOKEN_URL, request.toString(), "application/json"))
            val newToken = renewed.str("access_token") ?: throw e
            val updated = credentials + ("token" to newToken) + ("refresh" to (renewed.str("refresh_token") ?: refresh))
            parse(usage(http, newToken, account)).copy(updatedCredentials = updated)
        }
    }

    private suspend fun usage(http: Http, token: String, account: String) = http.get(
        USAGE_URL,
        buildMap {
            put("Authorization", "Bearer $token")
            put("User-Agent", "codex-cli")
            if (account.isNotEmpty()) put("ChatGPT-Account-Id", account)
        },
    )

    internal fun parse(body: String): FetchResult {
        val root = parseObject(body)
        val limit = root.obj("rate_limit")
        val meters = listOf("primary_window" to "Primary", "secondary_window" to "Secondary").mapNotNull { (key, fallback) ->
            val window = limit.obj(key) ?: return@mapNotNull null
            val used = window.num("used_percent") ?: return@mapNotNull null
            Meter(
                windowLabel(window.num("limit_window_seconds")) ?: fallback,
                used,
                window.num("reset_at")?.let { (it * 1000).toLong() },
            )
        }
        val credits = root.obj("credits")
        val money = if (credits.bool("has_credits") == true && credits.bool("unlimited") != true) {
            listOfNotNull(credits.num("balance")?.let { Money("Credits", it, "credits", isBalance = true) })
        } else emptyList()
        return FetchResult(plan = root.str("plan_type"), meters = meters, money = money)
    }
}
