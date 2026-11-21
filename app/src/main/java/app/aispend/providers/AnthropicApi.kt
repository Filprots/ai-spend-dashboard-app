package app.aispend.providers

import app.aispend.data.Money
import java.net.URLEncoder
import java.time.Instant

/** Anthropic API spend from the Admin API cost report (daily UTC buckets, amounts in cents). */
object AnthropicApi : UsageProvider {
    private const val URL = "https://api.anthropic.com/v1/organizations/cost_report"

    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult {
        val period = UtcPeriod()
        val headers = mapOf("x-api-key" to credentials["token"].orEmpty(), "anthropic-version" to "2023-06-01")
        val pages = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "$URL?starting_at=${period.monthStart}&ending_at=${period.tomorrowStart}&bucket_width=1d&limit=31" +
                (page?.let { "&page=" + URLEncoder.encode(it, "UTF-8") } ?: "")
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
                val start = isoToMillis(bucket.str("starting_at"))
                for (result in bucket.arr("results")) {
                    val dollars = (result.num("amount") ?: 0.0) / 100
                    result.str("currency")?.let { currency = it }
                    month += dollars
                    if (start != null && start >= todayStart.toEpochMilli()) today += dollars
                }
            }
        }
        return FetchResult(money = listOf(Money("Today", today, currency), Money("This month", month, currency)))
    }
}
