package app.aispend.data

import kotlinx.serialization.Serializable

/** One input the user fills in to connect a profile. All values are stored encrypted. */
data class CredentialField(
    val key: String,
    val label: String,
    val optional: Boolean = false,
    val hint: String = "",
)

@Serializable
enum class ProviderType(
    val displayName: String,
    val isSubscription: Boolean,
    /** False when the provider has no documented endpoint and we rely on the one its own CLI uses. */
    val official: Boolean,
    val fields: List<CredentialField>,
    val help: String,
) {
    CLAUDE_SUBSCRIPTION(
        "Claude Pro / Max", isSubscription = true, official = false,
        fields = listOf(
            CredentialField("token", "OAuth access token", hint = "sk-ant-oat01-…"),
            CredentialField("refresh", "Refresh token", optional = true, hint = "sk-ant-ort01-…"),
        ),
        help = "Shows your 5-hour session and weekly limits. On a computer where Claude Code is signed in, " +
            "open ~/.claude/.credentials.json (macOS: Keychain item \"Claude Code-credentials\") and copy " +
            "accessToken and refreshToken. Access tokens expire after a few hours; with a refresh token the " +
            "app renews them itself, but that signs out the Claude Code install the token was copied from " +
            "(just run /login there again). Uses the endpoint Claude Code itself uses — not a public API.",
    ),
    ANTHROPIC_API(
        "Anthropic API", isSubscription = false, official = true,
        fields = listOf(CredentialField("token", "Admin API key", hint = "sk-ant-admin01-…")),
        help = "Shows API cost for today and this month. Needs an Admin key (Console → Settings → Admin keys); " +
            "regular API keys cannot read costs, and Admin keys exist only for organization accounts. Days are " +
            "counted in UTC. Anthropic does not expose the prepaid credit balance.",
    ),
    CHATGPT_SUBSCRIPTION(
        "ChatGPT / Codex", isSubscription = true, official = false,
        fields = listOf(
            CredentialField("token", "Access token", hint = "eyJ…"),
            CredentialField("account", "Account ID", optional = true, hint = "tokens.account_id"),
            CredentialField("refresh", "Refresh token", optional = true),
        ),
        help = "Shows your Codex 5-hour and weekly limits. On a computer where Codex CLI is signed in, open " +
            "~/.codex/auth.json and copy tokens.access_token, tokens.account_id and tokens.refresh_token. " +
            "With a refresh token the app renews the access token itself, but that signs out the Codex " +
            "install it was copied from (run codex login there again). " +
            "Uses the endpoint Codex itself uses — not a public API.",
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
