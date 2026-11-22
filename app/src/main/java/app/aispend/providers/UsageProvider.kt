package app.aispend.providers

import app.aispend.data.Meter
import app.aispend.data.Money
import app.aispend.data.ProviderType

data class FetchResult(
    val plan: String? = null,
    val meters: List<Meter> = emptyList(),
    val money: List<Money> = emptyList(),
    /** Set when the provider rotated tokens during the fetch; the caller must persist them. */
    val updatedCredentials: Map<String, String>? = null,
    /** Fresh numbers were fetched, but part of the data could not be; shown beside them. */
    val warning: String? = null,
)

interface UsageProvider {
    suspend fun fetch(credentials: Map<String, String>, http: Http): FetchResult
}

object Providers {
    fun of(type: ProviderType): UsageProvider = when (type) {
        ProviderType.CLAUDE_SUBSCRIPTION -> ClaudeSubscription
        ProviderType.ANTHROPIC_API -> AnthropicApi
        ProviderType.CHATGPT_SUBSCRIPTION -> ChatGptSubscription
        ProviderType.OPENAI_API -> OpenAiApi
        ProviderType.OPENROUTER -> OpenRouter
        ProviderType.DEEPSEEK -> DeepSeek
    }
}
