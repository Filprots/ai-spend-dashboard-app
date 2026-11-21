package app.aispend.providers

import app.aispend.data.Meter
import app.aispend.data.Money

/** OpenRouter spend for the key, plus the account credit balance when the key may read it. */
object OpenRouter : UsageProvider {
    private const val BASE = "https://openrouter.ai/api/v1"

    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult {
        val headers = mapOf("Authorization" to "Bearer ${credentials["token"].orEmpty()}")
        val key = http.get("$BASE/key", headers)
        // /credits is refused for some key types; the key-level numbers are still worth showing.
        val credits = try {
            http.get("$BASE/credits", headers)
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) null else throw e
        }
        return parse(key, credits)
    }

    internal fun parse(keyBody: String, creditsBody: String?): FetchResult {
        val key = parseObject(keyBody).obj("data")
        val money = mutableListOf<Money>()
        key.num("usage_daily")?.let { money += Money("Today", it) }
        (key.num("usage_monthly") ?: key.num("usage"))?.let { money += Money("This month", it) }
        creditsBody?.let { parseObject(it).obj("data") }?.let { credits ->
            val total = credits.num("total_credits")
            val used = credits.num("total_usage")
            if (total != null && used != null) money += Money("Balance", total - used, isBalance = true)
        }
        val limit = key.num("limit")
        val remaining = key.num("limit_remaining")
        val meters = if (limit != null && limit > 0 && remaining != null) {
            listOf(Meter("Key limit", (limit - remaining) / limit * 100))
        } else emptyList()
        return FetchResult(meters = meters, money = money)
    }
}
