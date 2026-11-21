package app.aispend.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aispend.data.Store
import app.aispend.sync.RefreshWorker
import app.aispend.widget.SpendWidgetReceiver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val minutes by store.refreshMinutes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Profiles")
            profiles.forEach { profile ->
                ListItem(
                    headlineContent = { Text(profile.name) },
                    supportingContent = { Text(profile.provider.displayName) },
                    modifier = Modifier.clickable { onEdit(profile.id) },
                )
            }
            OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Add, null)
                Text("  Add profile")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle("Background refresh")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h").forEach { (value, label) ->
                    FilterChip(
                        selected = minutes == value,
                        onClick = {
                            store.setRefreshMinutes(value)
                            RefreshWorker.schedule(context, value)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Android decides the exact moment and may delay refreshes to save battery. The widget's refresh button and opening the app always fetch right away.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle("Widget")
            val widgets = AppWidgetManager.getInstance(context)
            if (widgets.isRequestPinAppWidgetSupported) {
                OutlinedButton(
                    onClick = { widgets.requestPinAppWidget(ComponentName(context, SpendWidgetReceiver::class.java), null, null) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Add widget to home screen") }
            }
            Text(
                "You can also long-press an empty spot on the home screen, choose Widgets, then AI Spend. It is resizable and scrolls when you have more profiles than fit.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
