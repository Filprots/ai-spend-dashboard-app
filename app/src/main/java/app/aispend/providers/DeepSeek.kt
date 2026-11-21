package app.aispend.providers

import app.aispend.data.Money

object DeepSeek : UsageProvider {
    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult = parse(
        http.get(
            "https://api.deepseek.com/user/balance",
            mapOf("Authorization" to "Bearer ${credentials["token"].orEmpty()}", "Accept" to "application/json"),
        )
    )

    internal fun parse(body: String): FetchResult {
        val money = parseObject(body).arr("balance_infos").mapNotNull { info ->
            val total = info.num("total_balance") ?: return@mapNotNull null
            Money("Balance", total, info.str("currency") ?: "USD", isBalance = true)
        }
        if (money.isEmpty()) throw IllegalStateException("Provider returned no balance")
        return FetchResult(money = money)
    }
}
