package app.aispend.providers

import app.aispend.data.Money
import java.net.URLEncoder
import java.time.Instant
import kotlin.math.pow

/**
 * Anthropic API spend and balance.
 *
 * Spend comes from the Admin API cost report (daily UTC buckets, amounts in cents). The prepaid
 * balance has no Admin API endpoint, so it is read the way the Console billing page reads it, with
 * the Console session of a user who signed in inside the app. Either credential alone is enough.
 */
object AnthropicApi : UsageProvider {
    private const val ADMIN = "https://api.anthropic.com/v1/organizations"
    private const val CONSOLE = "https://platform.claude.com/api/organizations"

    override suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult {
        val key = credentials["token"].orEmpty()
        val session = credentials["console"].orEmpty()
        if (key.isEmpty() && session.isEmpty()) {
            throw IllegalStateException("Enter an Admin key or sign in to the Console")
        }
        val period = UtcPeriod()
        val admin = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

        val spend = if (key.isNotEmpty()) parse(costPages(http, admin, period), period.todayStart) else null
        // The admin key's own organization: names the profile and picks the matching Console org.
        val keyOrg = if (key.isNotEmpty()) runCatching { parseObject(http.get("$ADMIN/me", admin)) }.getOrNull() else null
        if (session.isEmpty()) return spend!!.copy(plan = keyOrg?.str("name"))

        // An organization chosen at sign-in wins; individual accounts have no admin key to tell us.
        val pinned = credentials["consoleOrg"]?.takeIf { it.isNotBlank() } ?: keyOrg?.str("id")
        // A balance failure must not hide spend the admin key did fetch.
        val balance = try {
            consoleBalance(http, consoleHeaders(session, credentials["consoleUa"]), pinned, needSpend = spend == null)
        } catch (e: Exception) {
            if (spend == null) throw describeConsoleFailure(e)
            return spend.copy(plan = keyOrg?.str("name"), warning = "Balance unavailable: ${consoleProblem(e)}")
        }
        return FetchResult(plan = balance.orgName, money = (spend?.money ?: balance.spend) + balance.money)
    }

    private suspend fun costPages(http: Http, headers: Map<String, String>, period: UtcPeriod): List<String> {
        val pages = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "$ADMIN/cost_report?starting_at=${period.monthStart}&ending_at=${period.tomorrowStart}&bucket_width=1d&limit=31" +
                (page?.let { "&page=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val body = http.get(url, headers)
            pages += body
            val root = parseObject(body)
            page = root.str("next_page").takeIf { root.bool("has_more") == true }
        } while (page != null && pages.size < 10)
        return pages
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

    // ---- Console session ----

    internal class ConsoleResult(val money: List<Money>, val spend: List<Money>, val orgName: String?)

    data class ConsoleOrg(val id: String, val name: String)

    /** A pasted bare sessionKey value is accepted as well as a full Cookie header. */
    internal fun consoleHeaders(session: String, userAgent: String?): Map<String, String> = buildMap {
        val cookie = session.trim().removePrefix("Cookie:").trim()
        put("Cookie", if ('=' in cookie) cookie else "sessionKey=$cookie")
        put("Accept", "application/json")
        if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
    }

    /** The API organizations this Console login can see; claude.ai chat organizations are left out. */
    suspend fun consoleOrganizations(http: Http, session: String, userAgent: String?): List<ConsoleOrg> =
        consoleOrganizations(http, consoleHeaders(session, userAgent))

    private suspend fun consoleOrganizations(http: Http, headers: Map<String, String>): List<ConsoleOrg> =
        parseArray(http.get(CONSOLE, headers))
            .filter { org -> org.arr("capabilities").any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "api" } }
            .mapNotNull { org ->
                val id = org.str("uuid")?.takeIf { Regex("^[A-Za-z0-9-]{1,64}$").matches(it) } ?: return@mapNotNull null
                ConsoleOrg(id, org.str("name") ?: "Organization")
            }

    /**
     * With [pinnedOrg] only that organization is read, so a profile never silently shows another
     * organization's balance. Without it, the first organization whose billing is readable wins.
     */
    internal suspend fun consoleBalance(
        http: Http,
        headers: Map<String, String>,
        pinnedOrg: String?,
        needSpend: Boolean,
    ): ConsoleResult {
        val all = consoleOrganizations(http, headers)
        if (all.isEmpty()) throw IllegalStateException("this account has no Console API organization")
        val candidates = if (pinnedOrg == null) all else all.filter { it.id == pinnedOrg }
        if (candidates.isEmpty()) {
            throw IllegalStateException("this Console login cannot see the profile's organization — sign in to the Console again")
        }
        var denied: HttpException? = null
        for (org in candidates) {
            val credits = try {
                http.get("$CONSOLE/${org.id}/prepaid/credits", headers)
            } catch (e: HttpException) {
                // No billing access in this organization; try the next one.
                if (e.code == 403 && !isChallenge(e)) { denied = e; continue } else throw e
            }
            val parsed = parseCredits(credits)
            val spend = if (needSpend) {
                runCatching { parseSpend(http.get("$CONSOLE/${org.id}/current_spend", headers), parsed.currency, parsed.exponent) }
                    .getOrNull()
            } else null
            return ConsoleResult(listOf(parsed.balance), listOfNotNull(spend), org.name)
        }
        throw IllegalStateException(
            if (denied != null) "your role cannot see billing in this organization" else "no billing access"
        )
    }

    internal class Credits(val balance: Money, val currency: String, val exponent: Int)

    /** `amount` is in minor units; the exponent (default 2, i.e. cents) says how many. */
    internal fun parseCredits(body: String): Credits {
        val root = parseObject(body)
        val currency = root.str("currency")?.takeIf { Regex("^[A-Z]{3}$").matches(it) } ?: "USD"
        val exponent = root.obj("balance").obj("credits").num("exponent")?.toInt()?.takeIf { it in 0..4 } ?: 2
        val amount = root.num("amount") ?: throw IllegalStateException("the Console returned no balance")
        return Credits(Money("Balance", amount / 10.0.pow(exponent), currency, isBalance = true), currency, exponent)
    }

    internal fun parseSpend(body: String, currency: String, exponent: Int): Money? =
        parseObject(body).num("amount")?.let { Money("This month", it / 10.0.pow(exponent), currency) }

    private fun isChallenge(e: HttpException) =
        e.body.contains("Just a moment", ignoreCase = true) || e.body.contains("challenge-platform")

    private fun consoleProblem(e: Exception): String = when {
        e is HttpException && isChallenge(e) -> "the Console asked for a browser check — sign in to the Console again"
        e is HttpException && (e.code == 401 || e.code == 403) -> "Console session expired — sign in to the Console again"
        e is HttpException -> "the Console returned HTTP ${e.code}"
        e is java.io.IOException -> "no connection"
        else -> e.message ?: "unexpected response from the Console"
    }

    /** Errors when there is no spend to fall back on: reworded so the profile card explains itself. */
    internal fun describeConsoleFailure(e: Exception): Exception =
        IllegalStateException(consoleProblem(e).replaceFirstChar { it.uppercase() })
}
