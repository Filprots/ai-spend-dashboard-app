package app.aispend.providers

import app.aispend.data.Money
import java.net.URLEncoder
import java.time.Instant

/** OpenAI API spend from the organization Costs API (daily UTC buckets, amounts in dollars). */
object OpenAiApi : UsageProvider {
    private const val URL = "https://api.openai.com/v1/organization/costs"

    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult {
        val period = UtcPeriod()
        val headers = mapOf("Authorization" to "Bearer ${credentials["token"].orEmpty()}")
        val pages = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "$URL?start_time=${period.monthStart.epochSecond}&end_time=${period.tomorrowStart.epochSecond}" +
                "&bucket_width=1d&limit=31" + (page?.let { "&page=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val body = http.get(url, headers)
            pages += body
            val root = parseObject(body)
            page = root.str("next_page").takeIf { root.bool("has_more") == true }
        } while (page != null && pages.size < 10)
        return parse(pages, period.todayStart)
    }

    internal fun parse(pages: List<String>, todayStart: Instant): FetchResult {
        var month = 0.0
        var today = 0.0
        var currency = "USD"
        for (body in pages) {
            for (bucket in parseObject(body).arr("data")) {
                val start = bucket.num("start_time")?.toLong()
                for (result in bucket.arr("results")) {
                    val amount = result.obj("amount")
                    val dollars = amount.num("value") ?: 0.0
                    amount.str("currency")?.let { currency = it.uppercase() }
                    month += dollars
                    if (start != null && start >= todayStart.epochSecond) today += dollars
                }
            }
        }
        return FetchResult(money = listOf(Money("Today", today, currency), Money("This month", month, currency)))
    }
}
