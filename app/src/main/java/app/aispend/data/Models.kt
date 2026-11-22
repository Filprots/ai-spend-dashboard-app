package app.aispend.data

import kotlinx.serialization.Serializable

/** A connected account: one subscription or one API key on one provider. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val provider: ProviderType,
)

/** A rate-limit style meter, e.g. "5-hour session: 42% used, resets 16:00". */
@Serializable
data class Meter(
    val label: String,
    /** Percent of the limit already used, 0..100. */
    val usedPercent: Double,
    /** Epoch millis when the window resets, if known. */
    val resetsAt: Long? = null,
)

/** A money figure: either spend over a period or a remaining balance. */
@Serializable
data class Money(
    val label: String,
    val amount: Double,
    val currency: String = "USD",
    val isBalance: Boolean = false,
)

/** The last known state of a profile. Kept when a refresh fails, with [error] set. */
@Serializable
data class Snapshot(
    val profileId: String,
    /** Epoch millis of the last successful fetch; 0 if never fetched. */
    val fetchedAt: Long = 0,
    val plan: String? = null,
    val meters: List<Meter> = emptyList(),
    val money: List<Money> = emptyList(),
    val error: String? = null,
    /** Set when the fetch succeeded but part of the data was missing, e.g. the balance. */
    val warning: String? = null,
)
