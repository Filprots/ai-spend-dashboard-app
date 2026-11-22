package app.aispend.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Lenient accessors: provider payloads have nullable fields and mix strings with numbers.

internal fun parseObject(text: String): JsonObject =
    Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalStateException("Unexpected response from provider")

internal fun parseArray(text: String): List<JsonElement> =
    Json.parseToJsonElement(text) as? JsonArray ?: throw IllegalStateException("Unexpected response from provider")

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()
internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.num(key: String): Double? = str(key)?.toDoubleOrNull()
internal fun JsonElement?.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

internal fun isoToMillis(text: String?): Long? =
    text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Billing APIs bucket by UTC day, so "today" and "this month" are UTC here too. */
internal class UtcPeriod(now: Instant = Instant.now()) {
    val today: LocalDate = now.atZone(ZoneOffset.UTC).toLocalDate()
    val monthStart: Instant = today.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    val todayStart: Instant = today.atStartOfDay().toInstant(ZoneOffset.UTC)
    val tomorrowStart: Instant = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
}

internal fun windowLabel(seconds: Double?): String? = when (seconds?.toLong()) {
    null -> null
    18_000L -> "5-hour"
    86_400L -> "Daily"
    604_800L -> "Weekly"
    else -> if (seconds >= 86_400) "${(seconds / 86_400).toLong()}-day" else "${(seconds / 3600).toLong()}-hour"
}
