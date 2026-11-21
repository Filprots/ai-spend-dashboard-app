package app.aispend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aispend.data.Format
import app.aispend.data.Meter
import app.aispend.data.Money
import app.aispend.data.Profile
import app.aispend.data.Snapshot
import app.aispend.data.Store
import app.aispend.sync.Refresher

internal val Warn = Color(0xFFE8A33D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(store: Store, onRefresh: () -> Unit, onSettings: () -> Unit, onAdd: () -> Unit) {
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val snapshots by store.snapshots.collectAsStateWithLifecycle()
    val refreshing by Refresher.running.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Spend") },
                actions = {
                    if (refreshing) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No profiles yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connect a subscription or an API key to see its limits, spend and balance here and on the home-screen widget.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAdd) { Text("Add profile") }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                totals(snapshots.values)?.let { line -> item { TotalsCard(line) } }
                items(profiles, key = { it.id }) { profile -> ProfileCard(profile, snapshots[profile.id]) }
            }
        }
    }
}

/** Sums across profiles, per currency. Only shown when there is something to add up. */
private fun totals(snapshots: Collection<Snapshot>): List<Pair<String, String>>? {
    val money = snapshots.flatMap { it.money }
    fun sum(pick: (Money) -> Boolean) = money.filter(pick).groupBy { it.currency }
        .map { (currency, list) -> Format.money(list.sumOf { it.amount }, currency) }
        .joinToString("\n")
    val rows = listOf(
        "This month" to sum { !it.isBalance && it.label == "This month" },
        "Today" to sum { !it.isBalance && it.label == "Today" },
        "Balances" to sum { it.isBalance && it.currency != "credits" },
    ).filter { it.second.isNotEmpty() }
    return rows.ifEmpty { null }
}

@Composable
private fun TotalsCard(rows: List<Pair<String, String>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile, snapshot: Snapshot?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val plan = snapshot?.plan?.let { " · $it" }.orEmpty()
                    Text(profile.provider.displayName + plan, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (snapshot != null && snapshot.fetchedAt > 0) {
                    Text(Format.ago(snapshot.fetchedAt), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            snapshot?.meters?.forEach { MeterRow(it) }
            if (!snapshot?.money.isNullOrEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    snapshot!!.money.forEach { money ->
                        Column {
                            Text(money.label, style = MaterialTheme.typography.labelMedium, color = muted)
                            Text(
                                Format.money(money.amount, money.currency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            when {
                snapshot?.error != null -> Text(
                    if (snapshot.fetchedAt > 0) "${snapshot.error}. Showing the last numbers received." else snapshot.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                snapshot == null -> Text("Waiting for first refresh", style = MaterialTheme.typography.bodySmall, color = muted)
                snapshot.meters.isEmpty() && snapshot.money.isEmpty() ->
                    Text("The provider reported no usage data for this account", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
internal fun MeterRow(meter: Meter) {
    val color = when {
        meter.usedPercent >= 90 -> MaterialTheme.colorScheme.error
        meter.usedPercent >= 70 -> Warn
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(meter.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            meter.resetsAt?.let {
                Text(
                    "resets ${Format.countdown(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text("${Format.percent(meter.usedPercent)} used", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { (meter.usedPercent / 100).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            strokeCap = StrokeCap.Round,
        )
    }
}
