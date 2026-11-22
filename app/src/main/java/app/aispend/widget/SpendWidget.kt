package app.aispend.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.aispend.R
import app.aispend.data.Format
import app.aispend.data.Meter
import app.aispend.data.Profile
import app.aispend.data.Snapshot
import app.aispend.data.Store
import app.aispend.sync.RefreshWorker
import app.aispend.ui.MainActivity

class SpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpendWidget()
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}

class SpendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store.get(context)
        provideContent {
            val profiles by store.profiles.collectAsState()
            val snapshots by store.snapshots.collectAsState()
            GlanceTheme { Content(profiles, snapshots) }
        }
    }
}

private val warn = ColorProvider(Color(0xFFE8A33D))
private val danger = ColorProvider(Color(0xFFE5534B))

@Composable
private fun Content(profiles: List<Profile>, snapshots: Map<String, Snapshot>) {
    val muted = GlanceTheme.colors.onSurfaceVariant
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AI Spend",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            val updated = snapshots.values.maxOfOrNull { it.fetchedAt } ?: 0
            if (updated > 0) Text(Format.clock(updated), style = TextStyle(color = muted, fontSize = 11.sp))
            Spacer(GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(muted),
                modifier = GlanceModifier.size(26.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        if (profiles.isEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Text("Tap to add your first profile", style = TextStyle(color = muted, fontSize = 13.sp))
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(profiles, itemId = { it.id.hashCode().toLong() }) { profile ->
                    ProfileRow(profile, snapshots[profile.id])
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, snapshot: Snapshot?) {
    val onSurface = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.name,
                maxLines = 1,
                style = TextStyle(color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight(),
            )
            val money = snapshot?.money.orEmpty()
            // One headline figure per profile: the balance if there is one, else the longest spend period.
            (money.firstOrNull { it.isBalance } ?: money.lastOrNull())?.let {
                Text(
                    "${Format.money(it.amount, it.currency)} ${it.label.lowercase()}",
                    maxLines = 1,
                    style = TextStyle(color = onSurface, fontSize = 12.sp),
                )
            }
            if (snapshot?.error != null) {
                Text("  !", style = TextStyle(color = danger, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
        snapshot?.meters?.take(3)?.forEach { MeterRow(it) }
        if (snapshot == null || (snapshot.fetchedAt == 0L && snapshot.error == null)) {
            Text("Waiting for first refresh", style = TextStyle(color = muted, fontSize = 11.sp))
        } else if (snapshot.fetchedAt == 0L) {
            Text(snapshot.error.orEmpty(), maxLines = 1, style = TextStyle(color = danger, fontSize = 11.sp))
        }
    }
}

@Composable
private fun MeterRow(meter: Meter) {
    val muted = GlanceTheme.colors.onSurfaceVariant
    val color = when {
        meter.usedPercent >= 90 -> danger
        meter.usedPercent >= 70 -> warn
        else -> GlanceTheme.colors.primary
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(meter.label, maxLines = 1, style = TextStyle(color = muted, fontSize = 11.sp), modifier = GlanceModifier.width(58.dp))
        LinearProgressIndicator(
            progress = (meter.usedPercent / 100).toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.defaultWeight().height(5.dp),
            color = color,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
        Text(
            "  ${Format.percent(meter.usedPercent)}",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium),
        )
        meter.resetsAt?.let {
            Text(" · ${Format.clock(it)}", maxLines = 1, style = TextStyle(color = muted, fontSize = 11.sp))
        }
    }
}
