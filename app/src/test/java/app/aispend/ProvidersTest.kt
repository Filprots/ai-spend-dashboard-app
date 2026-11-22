package app.aispend

import app.aispend.data.Format
import app.aispend.providers.AnthropicApi
import app.aispend.providers.ChatGptSubscription
import app.aispend.providers.ClaudeSubscription
import app.aispend.providers.DeepSeek
import app.aispend.providers.FetchResult
import app.aispend.providers.Http
import app.aispend.providers.HttpException
import app.aispend.providers.OpenAiApi
import app.aispend.providers.OpenRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProvidersTest {
    private val claudeBody = """{"five_hour":{"utilization":7,"resets_at":"2025-12-23T16:00:00.000Z"},
        "seven_day":{"utilization":21.5,"resets_at":"2025-12-29T23:00:00+00:00"},
        "seven_day_opus":null,"seven_day_sonnet":{"utilization":3,"resets_at":null},
        "extra_usage":{"is_enabled":true,"monthly_limit":2000,"used_credits":500,"utilization":25,"currency":"USD"}}"""

    @Test
    fun claudeSubscription() {
        val r = ClaudeSubscription.parse(claudeBody)
        assertEquals(listOf("5-hour", "Weekly", "Sonnet wk"), r.meters.map { it.label })
        assertEquals(7.0, r.meters[0].usedPercent, 0.0)
        assertEquals(Instant.parse("2025-12-23T16:00:00Z").toEpochMilli(), r.meters[0].resetsAt)
        assertEquals(Instant.parse("2025-12-29T23:00:00Z").toEpochMilli(), r.meters[1].resetsAt)
        assertNull(r.meters[2].resetsAt)
        assertEquals(5.0, r.money.single().amount, 1e-9)
    }

    @Test
    fun claudeRefreshesOnceOn401AndReturnsRotatedTokens() {
        val posts = mutableListOf<String>()
        val http = object : Http {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                if (headers["Authorization"] == "Bearer old") throw HttpException(401, "")
                assertEquals("Bearer new", headers["Authorization"])
                return claudeBody
            }
            override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
                posts += body
                return """{"access_token":"new","refresh_token":"r2","expires_in":28800}"""
            }
        }
        val r: FetchResult = runBlocking { ClaudeSubscription.fetch(mapOf("token" to "old", "refresh" to "r 1"), http) }
        assertEquals(1, posts.size)
        assertTrue(posts[0].contains("refresh_token=r+1"))
        assertEquals(mapOf("token" to "new", "refresh" to "r2"), r.updatedCredentials)
        assertEquals(3, r.meters.size)
    }

    @Test(expected = HttpException::class)
    fun claudeWithoutRefreshTokenSurfaces401() {
        val http = object : Http {
            override suspend fun get(url: String, headers: Map<String, String>): String = throw HttpException(401, "")
            override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String =
                error("must not refresh")
        }
        runBlocking { ClaudeSubscription.fetch(mapOf("token" to "old"), http) }
    }

    @Test
    fun chatGptSubscription() {
        val r = ChatGptSubscription.parse(
            """{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false,
            "primary_window":{"used_percent":15,"limit_window_seconds":18000,"reset_after_seconds":7200,"reset_at":1735401600},
            "secondary_window":{"used_percent":5,"limit_window_seconds":604800,"reset_after_seconds":518400,"reset_at":1735920000}},
            "credits":{"has_credits":true,"unlimited":false,"balance":"150.0"},"additional_rate_limits":null}"""
        )
        assertEquals("pro", r.plan)
        assertEquals(listOf("5-hour", "Weekly"), r.meters.map { it.label })
        assertEquals(1735401600000L, r.meters[0].resetsAt)
        assertEquals(150.0, r.money.single().amount, 0.0)
        // Everything nullable.
        assertTrue(ChatGptSubscription.parse("""{"plan_type":"free","rate_limit":null,"credits":null}""").meters.isEmpty())
    }

    @Test
    fun anthropicCostsAreCentsAndSplitByDay() {
        val page1 = """{"data":[
            {"starting_at":"2025-08-01T00:00:00Z","ending_at":"2025-08-02T00:00:00Z","results":[{"amount":"123.45","currency":"USD"},{"amount":"76.55","currency":"USD"}]},
            {"starting_at":"2025-08-02T00:00:00Z","ending_at":"2025-08-03T00:00:00Z","results":[]}],
            "has_more":true,"next_page":"p2"}"""
        val page2 = """{"data":[{"starting_at":"2025-08-03T00:00:00Z","ending_at":"2025-08-04T00:00:00Z","results":[{"amount":"50","currency":"USD"}]}],"has_more":false,"next_page":null}"""
        val r = AnthropicApi.parse(listOf(page1, page2), Instant.parse("2025-08-03T00:00:00Z"))
        assertEquals(0.50, r.money[0].amount, 1e-9)
        assertEquals(2.50, r.money[1].amount, 1e-9)
    }

    @Test
    fun openAiCostsAreDollars() {
        val body = """{"object":"page","data":[
            {"object":"bucket","start_time":1730419200,"end_time":1730505600,"results":[{"amount":{"value":0.06,"currency":"usd"},"line_item":null}]},
            {"object":"bucket","start_time":1730505600,"end_time":1730592000,"results":[{"amount":{"value":1.5,"currency":"usd"}}]}],
            "has_more":false,"next_page":null}"""
        val r = OpenAiApi.parse(listOf(body), Instant.ofEpochSecond(1730505600))
        assertEquals(1.5, r.money[0].amount, 1e-9)
        assertEquals(1.56, r.money[1].amount, 1e-9)
        assertEquals("USD", r.money[1].currency)
    }

    @Test
    fun openRouter() {
        val key = """{"data":{"label":"sk-or","limit":50.0,"limit_remaining":40.0,"usage":7.3,"usage_daily":0.4,"usage_monthly":7.3}}"""
        val r = OpenRouter.parse(key, """{"data":{"total_credits":25.0,"total_usage":7.32}}""")
        assertEquals(listOf("Today", "This month", "Balance"), r.money.map { it.label })
        assertEquals(17.68, r.money[2].amount, 1e-9)
        assertEquals(20.0, r.meters.single().usedPercent, 1e-9)
        val noCap = OpenRouter.parse("""{"data":{"limit":null,"limit_remaining":null,"usage":1.0}}""", null)
        assertTrue(noCap.meters.isEmpty())
        assertEquals(listOf("This month"), noCap.money.map { it.label })
    }

    @Test
    fun deepSeekAcceptsStringsAndNumbers() {
        val r = DeepSeek.parse("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00"},{"currency":"USD","total_balance":3.5}]}""")
        assertEquals(listOf(110.0, 3.5), r.money.map { it.amount })
        assertEquals("¥110.00", Format.money(r.money[0].amount, r.money[0].currency))
    }

    @Test
    fun formatting() {
        assertEquals("$1,234.50", Format.money(1234.5, "USD"))
        assertEquals("$0.060", Format.money(0.06, "USD"))
        assertEquals("150.00 credits", Format.money(150.0, "credits"))
        assertEquals("in 2h 14m", Format.countdown(134 * 60_000L, 0))
        assertEquals("in 3d 4h", Format.countdown((3 * 1440 + 4 * 60 + 5) * 60_000L, 0))
        assertEquals("now", Format.countdown(0, 5))
    }
}

class SignInTest {
    private class Scripted(val respond: (url: String, body: String) -> String) : Http {
        val posts = mutableListOf<Pair<String, String>>()
        override suspend fun get(url: String, headers: Map<String, String>): String = error("unexpected GET")
        override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
            posts += url to body
            return respond(url, body)
        }
    }

    @Test
    fun pkceMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            app.aispend.providers.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun claudeSignInExchangesPastedCode() {
        val pending = app.aispend.providers.ClaudeSignIn.start()
        assertTrue(pending.url.contains("code_challenge=" + app.aispend.providers.pkceChallenge(pending.verifier)))
        assertTrue(pending.url.contains("state=" + pending.state))
        val http = Scripted { _, _ -> """{"access_token":"a1","refresh_token":"r1","expires_in":28800}""" }
        val creds = runBlocking {
            app.aispend.providers.ClaudeSignIn.finish(http, " thecode#${pending.state} ", pending.verifier, pending.state)
        }
        assertEquals(mapOf("token" to "a1", "refresh" to "r1"), creds)
        assertTrue(http.posts.single().second.contains("\"code\":\"thecode\""))
        assertTrue(http.posts.single().second.contains(pending.verifier))
    }

    @Test(expected = IllegalStateException::class)
    fun claudeSignInRejectsCodeFromAnotherAttempt() {
        val http = Scripted { _, _ -> error("must not exchange") }
        runBlocking { app.aispend.providers.ClaudeSignIn.finish(http, "code#other", "v", "expected") }
    }

    @Test
    fun chatGptDeviceFlowPollsUntilApproved() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct_9"}}""".toByteArray())
        var polls = 0
        val http = Scripted { url, _ ->
            when {
                url.endsWith("/deviceauth/usercode") -> """{"device_auth_id":"d1","user_code":"ABCD-1234","interval":"5"}"""
                url.endsWith("/deviceauth/token") ->
                    if (++polls < 3) throw HttpException(403, "") else """{"authorization_code":"ac","code_challenge":"cc","code_verifier":"cv"}"""
                else -> """{"id_token":"h.$payload.s","access_token":"a1","refresh_token":"r1"}"""
            }
        }
        val waits = mutableListOf<Long>()
        val creds = runBlocking {
            val device = app.aispend.providers.ChatGptSignIn.start(http)
            assertEquals("ABCD-1234", device.userCode)
            app.aispend.providers.ChatGptSignIn.await(http, device) { waits += it }
        }
        assertEquals(listOf(5000L, 5000L), waits)
        assertEquals(mapOf("token" to "a1", "refresh" to "r1", "account" to "acct_9"), creds)
        assertTrue(http.posts.last().second.contains("code_verifier=cv"))
    }
}

class FableAndBalanceTest {
    @Test
    fun claudeScopedWeeklyLimitBecomesItsOwnMeter() {
        val r = ClaudeSubscription.parse(
            """{"five_hour":{"utilization":11.0,"resets_at":"2026-07-03T00:30:00.282668+00:00"},
            "seven_day":{"utilization":9.0,"resets_at":"2026-07-08T09:00:00.282694+00:00"},
            "seven_day_opus":null,"seven_day_sonnet":{"utilization":3,"resets_at":null},
            "limits":[null,
              {"kind":"session","group":"session","percent":11,"resets_at":"2026-07-03T00:30:00+00:00","scope":null},
              {"kind":"weekly_all","group":"weekly","percent":9,"resets_at":"2026-07-08T09:00:00+00:00","scope":null},
              {"kind":"weekly_scoped","group":"weekly","percent":"5","resets_at":"2026-07-08T09:00:00.283070+00:00",
               "scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false},
              {"kind":"weekly_scoped","group":"weekly","percent":40,"resets_at":null,
               "scope":{"model":{"id":null,"display_name":"Sonnet"}}},
              {"kind":"weekly_scoped","group":"weekly","percent":1,"scope":{"model":{"display_name":"All models"}}}]}"""
        )
        // Sonnet from `limits` replaces the legacy seven_day_sonnet field rather than duplicating it.
        assertEquals(listOf("5-hour", "Weekly", "Fable wk", "Sonnet wk"), r.meters.map { it.label })
        assertEquals(5.0, r.meters[2].usedPercent, 0.0)
        assertEquals(Instant.parse("2026-07-08T09:00:00.283070Z").toEpochMilli(), r.meters[2].resetsAt)
        assertEquals(40.0, r.meters[3].usedPercent, 0.0)
    }

    private val orgs = """[{"uuid":"chat-org","capabilities":["chat"]},
        {"uuid":"org-a","capabilities":["api"]},{"uuid":"org-b","capabilities":["chat","api"]}]"""
    private val credits = """{"amount":123456,"currency":"USD","balance":{"credits":{"exponent":2}},"tranches":[]}"""
    private val cost = """{"data":[],"has_more":false,"next_page":null}"""

    private fun http(routes: (String, Map<String, String>) -> String) = object : Http {
        val seen = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): String { seen += url; return routes(url, headers) }
        override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = error("no POST")
    }

    @Test
    fun adminKeyPlusConsoleShowsSpendAndBalanceForTheKeysOrganization() {
        val h = http { url, headers ->
            when {
                url.contains("/cost_report") -> cost
                url.endsWith("/v1/organizations/me") -> """{"id":"org-b","type":"organization","name":"Work"}"""
                url.endsWith("/api/organizations") -> {
                    assertEquals("sessionKey=abc; other=1", headers["Cookie"])
                    assertEquals("UA", headers["User-Agent"])
                    orgs
                }
                url.endsWith("/org-b/prepaid/credits") -> credits
                else -> error("unexpected $url")
            }
        }
        val r = runBlocking {
            AnthropicApi.fetch(mapOf("token" to "k", "console" to "sessionKey=abc; other=1", "consoleUa" to "UA"), h)
        }
        assertEquals(listOf("Today", "This month", "Balance"), r.money.map { it.label })
        assertEquals(1234.56, r.money[2].amount, 1e-9)
        assertNull(r.warning)
        assertTrue(h.seen.none { it.contains("org-a") })
    }

    @Test
    fun consoleFailureKeepsSpendAndWarns() {
        val h = http { url, _ ->
            when {
                url.contains("/cost_report") -> cost
                url.endsWith("/me") -> throw HttpException(404, "")
                else -> throw HttpException(401, "")
            }
        }
        val r = runBlocking { AnthropicApi.fetch(mapOf("token" to "k", "console" to "abc"), h) }
        assertEquals(listOf("Today", "This month"), r.money.map { it.label })
        assertTrue(r.warning!!.contains("sign in to the Console again"))
    }

    @Test
    fun consoleOnlyUsesConsoleSpendAndSkipsOrgsWithoutBillingAccess() {
        val h = http { url, headers ->
            assertEquals("sessionKey=bare", headers["Cookie"])
            when {
                url.endsWith("/api/organizations") -> orgs
                url.endsWith("/org-a/prepaid/credits") -> throw HttpException(403, "{\"error\":\"forbidden\"}")
                url.endsWith("/org-b/prepaid/credits") -> """{"amount":500,"currency":"EUR"}"""
                url.endsWith("/org-b/current_spend") -> """{"amount":1999}"""
                else -> error("unexpected $url")
            }
        }
        val r = runBlocking { AnthropicApi.fetch(mapOf("console" to "bare"), h) }
        assertEquals(listOf("This month", "Balance"), r.money.map { it.label })
        assertEquals(19.99, r.money[0].amount, 1e-9)
        assertEquals(5.0, r.money[1].amount, 1e-9)
        assertEquals("EUR", r.money[1].currency)
    }

    @Test
    fun nothingConfiguredIsAClearError() {
        val e = runCatching { runBlocking { AnthropicApi.fetch(emptyMap(), http { _, _ -> error("no calls") }) } }
        assertTrue(e.exceptionOrNull()!!.message!!.contains("Admin key"))
    }
}

class ConsoleOrganizationTest {
    private val orgs = """[{"uuid":"chat-org","name":"Chat","capabilities":["chat"]},
        {"uuid":"team","name":"IRRISketch GmbH","capabilities":["api"]},
        {"uuid":"personal","name":"Individual Org","capabilities":["api","chat"]}]"""

    private fun http(routes: (String) -> String) = object : Http {
        override suspend fun get(url: String, headers: Map<String, String>) = routes(url)
        override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = error("no POST")
    }

    @Test
    fun listsOnlyApiOrganizations() {
        val list = runBlocking { AnthropicApi.consoleOrganizations(http { orgs }, "sessionKey=x", null) }
        assertEquals(listOf("IRRISketch GmbH", "Individual Org"), list.map { it.name })
    }

    @Test
    fun pinnedOrganizationIsReadEvenWhenAnotherComesFirst() {
        val h = http { url ->
            when {
                url.endsWith("/api/organizations") -> orgs
                url.endsWith("/personal/prepaid/credits") -> """{"amount":16257,"currency":"USD"}"""
                url.endsWith("/personal/current_spend") -> """{"amount":1200}"""
                else -> error("must not read $url")
            }
        }
        val r = runBlocking { AnthropicApi.fetch(mapOf("console" to "x", "consoleOrg" to "personal"), h) }
        assertEquals("Individual Org", r.plan)
        assertEquals(listOf(12.0, 162.57), r.money.map { it.amount })
    }

    @Test
    fun pinnedOrganizationMissingFromLoginIsAnErrorNotAnotherOrgsBalance() {
        val h = http { url -> if (url.endsWith("/api/organizations")) orgs else error("must not read $url") }
        val e = runCatching { runBlocking { AnthropicApi.fetch(mapOf("console" to "x", "consoleOrg" to "gone"), h) } }
        assertTrue(e.exceptionOrNull()!!.message!!.contains("cannot see the profile's organization"))
    }

    @Test
    fun adminKeyProfileIsNamedAfterItsOrganization() {
        val h = http { url ->
            when {
                url.contains("/cost_report") -> """{"data":[],"has_more":false}"""
                url.endsWith("/v1/organizations/me") -> """{"id":"team","name":"IRRISketch GmbH","type":"organization"}"""
                else -> error("unexpected $url")
            }
        }
        assertEquals("IRRISketch GmbH", runBlocking { AnthropicApi.fetch(mapOf("token" to "k"), h) }.plan)
    }
}
