package app.aispend.data

import kotlinx.serialization.Serializable

/** One input the user fills in to connect a profile. All values are stored encrypted. */
data class CredentialField(
    val key: String,
    val label: String,
    val optional: Boolean = false,
    val hint: String = "",
    /** Filled in by a sign-in flow rather than typed; kept with the credentials but not shown. */
    val hidden: Boolean = false,
)

@Serializable
enum class ProviderType(
    val displayName: String,
    val isSubscription: Boolean,
    /** False when the provider has no documented endpoint and we rely on the one its own CLI uses. */
    val official: Boolean,
    val fields: List<CredentialField>,
    val help: String,
    /** Shown above the token fields when they are an alternative to signing in. */
    val manualHelp: String = "",
) {
    CLAUDE_SUBSCRIPTION(
        "Claude Pro / Max", isSubscription = true, official = false,
        fields = listOf(
            CredentialField("token", "OAuth access token", hint = "sk-ant-oat01-…"),
            CredentialField("refresh", "Refresh token", optional = true, hint = "sk-ant-ort01-…"),
        ),
        help = "Shows your 5-hour session and weekly limits. Sign in below: the app gets its own tokens, so " +
            "Claude Code on your computers stays signed in. Uses the endpoint Claude Code itself uses — " +
            "not a public API.",
        manualHelp = "Copy accessToken and refreshToken from ~/.claude/.credentials.json (macOS: Keychain item " +
            "\"Claude Code-credentials\"). Access tokens expire after a few hours. If you add the refresh " +
            "token the app renews them, but that signs out the Claude Code install it was copied from.",
    ),
    ANTHROPIC_API(
        "Anthropic API", isSubscription = false, official = true,
        fields = listOf(
            CredentialField("token", "Admin API key", optional = true, hint = "sk-ant-admin01-…"),
            CredentialField("console", "Console session cookie", optional = true, hint = "sessionKey=…"),
            CredentialField("consoleUa", "", optional = true, hidden = true),
            CredentialField("consoleOrg", "", optional = true, hidden = true),
        ),
        help = "An Admin key (Console → Settings → Admin keys) shows API cost for today and this month, in UTC. " +
            "Individual Console accounts have no Admin keys, and a key only sees its own organization. " +
            "Signing in to the Console here shows the prepaid balance (and this month's spend without a key), " +
            "read the way the Console billing page does — an unofficial endpoint. Make one profile per " +
            "organization; one Console login can serve all of them.",
    ),
    CHATGPT_SUBSCRIPTION(
        "ChatGPT / Codex", isSubscription = true, official = false,
        fields = listOf(
            CredentialField("token", "Access token", hint = "eyJ…"),
            CredentialField("account", "Account ID", optional = true, hint = "tokens.account_id"),
            CredentialField("refresh", "Refresh token", optional = true),
        ),
        help = "Shows your Codex 5-hour and weekly limits. Sign in below: the app gets its own tokens, so " +
            "Codex on your computers stays signed in. Uses the endpoint Codex itself uses — not a public API.",
        manualHelp = "Copy tokens.access_token, tokens.account_id and tokens.refresh_token from ~/.codex/auth.json. " +
            "If you add the refresh token the app renews the access token, but that signs out the Codex " +
            "install it was copied from.",
    ),
    OPENAI_API(
        "OpenAI API", isSubscription = false, official = true,
        fields = listOf(CredentialField("token", "Admin API key", hint = "sk-admin-…")),
        help = "Shows API cost for today and this month. Needs an Admin key (platform.openai.com → " +
            "Settings → Organization → Admin keys). Days are counted in UTC. OpenAI does not expose the " +
            "prepaid credit balance.",
    ),
    OPENROUTER(
        "OpenRouter", isSubscription = false, official = true,
        fields = listOf(CredentialField("token", "API key", hint = "sk-or-v1-…")),
        help = "Shows spend for today and this month, plus remaining credits when the key is allowed to read them.",
    ),
    DEEPSEEK(
        "DeepSeek", isSubscription = false, official = true,
        fields = listOf(CredentialField("token", "API key", hint = "sk-…")),
        help = "Shows the remaining account balance.",
    ),
}
