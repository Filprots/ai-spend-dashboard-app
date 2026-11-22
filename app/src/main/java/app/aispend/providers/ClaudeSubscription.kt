package app.aispend.providers

import app.aispend.data.Meter
import app.aispend.data.Money
import java.net.URLEncoder

/**
 * Claude Pro/Max limits via the endpoint Claude Code uses for /usage. Undocumented, so it can change
 * without notice. Needs a token with the user:profile scope; `claude setup-token` tokens are rejected.
 */
object ClaudeSubscription : UsageProvider {
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    internal const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    internal const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult {
        val token = credentials["token"].orEmpty()
        return try {
            parse(usage(http, token))
        } catch (e: HttpException) {
            val refresh = credentials["refresh"].orEmpty()
            if (e.code != 401 || refresh.isEmpty()) throw e
            // Refresh tokens are single-use: the new pair must be saved or the profile locks itself out.
            val renewed = parseObject(
                http.post(
                    TOKEN_URL,
                    "grant_type=refresh_token&refresh_token=${URLEncoder.encode(refresh, "UTF-8")}&client_id=$CLIENT_ID",
                    "application/x-www-form-urlencoded",
                )
            )
            val newToken = renewed.str("access_token") ?: throw e
            val updated = credentials + ("token" to newToken) + ("refresh" to (renewed.str("refresh_token") ?: refresh))
            parse(usage(http, newToken)).copy(updatedCredentials = updated)
        }
    }

    private suspend fun usage(http: Http, token: String) = http.get(
        USAGE_URL,
        mapOf(
            "Authorization" to "Bearer $token",
            "anthropic-beta" to "oauth-2025-04-20",
            "Accept" to "application/json",
        ),
    )

    internal fun parse(body: String): FetchResult {
        val root = parseObject(body)
        fun window(key: String, label: String): Meter? {
            val window = root.obj(key) ?: return null
            val used = window.num("utilization") ?: return null
            return Meter(label, used, isoToMillis(window.str("resets_at")))
        }
        // Model-only weekly limits (e.g. "Fable") arrive in the newer `limits` list. The model changes
        // over time, so the label comes from the response rather than a hard-coded name.
        val scoped = root.arr("limits").mapNotNull { limit ->
            if (limit.str("kind") != "weekly_scoped") return@mapNotNull null
            val model = limit.obj("scope").obj("model").str("display_name")?.trim()
            if (model.isNullOrEmpty() || model.equals("all models", ignoreCase = true)) return@mapNotNull null
            val used = limit.num("percent") ?: return@mapNotNull null
            Meter("$model wk", used, isoToMillis(limit.str("resets_at")))
        }.distinctBy { it.label }
        // The older per-model fields, unless the same model is already covered by `limits`.
        val legacy = listOfNotNull(window("seven_day_opus", "Opus wk"), window("seven_day_sonnet", "Sonnet wk"))
            .filter { old -> scoped.none { it.label.equals(old.label, ignoreCase = true) } }
        val meters = listOfNotNull(window("five_hour", "5-hour"), window("seven_day", "Weekly")) + scoped + legacy
        val extra = root.obj("extra_usage")
        val money = if (extra.bool("is_enabled") == true) {
            // Reported in cents.
            listOfNotNull(extra.num("used_credits")?.let { Money("Extra usage", it / 100, extra.str("currency") ?: "USD") })
        } else emptyList()
        return FetchResult(meters = meters, money = money)
    }
}
