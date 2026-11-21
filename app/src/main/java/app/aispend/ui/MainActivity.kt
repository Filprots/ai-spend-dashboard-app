package app.aispend.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import app.aispend.data.Store
import app.aispend.sync.Refresher
import kotlinx.coroutines.launch

/** Screens are few and shallow, so navigation is a string route rather than a nav graph. */
private const val DASHBOARD = "dashboard"
private const val SETTINGS = "settings"
private const val EDIT = "edit:" // followed by a profile id, or empty for a new profile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store.get(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                android.os.Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                var route by rememberSaveable { mutableStateOf(DASHBOARD) }
                BackHandler(enabled = route != DASHBOARD) {
                    route = if (route.startsWith(EDIT)) SETTINGS else DASHBOARD
                }
                when {
                    route == SETTINGS -> SettingsScreen(
                        store = store,
                        onBack = { route = DASHBOARD },
                        onEdit = { id -> route = EDIT + id.orEmpty() },
                    )
                    route.startsWith(EDIT) -> ProfileEditorScreen(
                        store = store,
                        profileId = route.removePrefix(EDIT).ifEmpty { null },
                        onDone = { route = SETTINGS },
                    )
                    else -> DashboardScreen(
                        store = store,
                        onRefresh = ::refresh,
                        onSettings = { route = SETTINGS },
                        onAdd = { route = EDIT },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is a request for fresh numbers, but not more often than once a minute.
        val newest = Store.get(this).snapshots.value.values.maxOfOrNull { it.fetchedAt } ?: 0
        if (System.currentTimeMillis() - newest > 60_000) refresh()
    }

    private fun refresh() {
        val context = applicationContext
        lifecycleScope.launch { Refresher.refreshAll(context) }
    }
}
