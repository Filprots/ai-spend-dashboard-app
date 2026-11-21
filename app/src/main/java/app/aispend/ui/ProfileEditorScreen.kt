package app.aispend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aispend.data.Profile
import app.aispend.data.ProviderType
import app.aispend.data.Store
import app.aispend.sync.Refresher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(store: Store, profileId: String?, onDone: () -> Unit) {
    val existing = remember(profileId) { store.profiles.value.firstOrNull { it.id == profileId } }
    var provider by remember { mutableStateOf(existing?.provider ?: ProviderType.CLAUDE_SUBSCRIPTION) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    val values = remember {
        mutableStateMapOf<String, String>().apply { existing?.let { putAll(store.secrets.get(it.id)) } }
    }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun credentials() = provider.fields.associate { it.key to values[it.key].orEmpty().trim() }.filterValues { it.isNotEmpty() }
    val complete = provider.fields.all { it.optional || values[it.key].orEmpty().isNotBlank() }

    fun save(tested: Map<String, String>? = null) {
        val saved = store.saveProfile(existing?.id, name.trim().ifEmpty { provider.displayName }, provider, tested ?: credentials())
        scope.launch { Refresher.refresh(store, saved) }
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add profile" else "Edit profile") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete profile") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (existing == null) {
                Text("Provider", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderType.entries.forEach { type ->
                        FilterChip(
                            selected = provider == type,
                            onClick = { provider = type; values.clear(); failure = null },
                            label = { Text(type.displayName) },
                        )
                    }
                }
            } else {
                Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
            }
            Text(provider.help, style = MaterialTheme.typography.bodySmall, color = muted)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                placeholder = { Text(if (provider.isSubscription) "e.g. Personal" else "e.g. Work org") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            provider.fields.forEach { field ->
                OutlinedTextField(
                    value = values[field.key].orEmpty(),
                    onValueChange = { values[field.key] = it; failure = null },
                    label = { Text(field.label + if (field.optional) " (optional)" else "") },
                    placeholder = { if (field.hint.isNotEmpty()) Text(field.hint) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Stored encrypted on this device only, and sent only to ${provider.displayName}.", style = MaterialTheme.typography.bodySmall, color = muted)

            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = complete && !busy,
                    onClick = {
                        busy = true
                        failure = null
                        scope.launch {
                            val (snapshot, latest) = Refresher.test(Profile("test", name, provider), credentials())
                            busy = false
                            if (snapshot.error == null) save(latest) else failure = snapshot.error
                        }
                    },
                ) { Text(if (busy) "Checking…" else "Check and save") }
                if (failure != null) TextButton(enabled = complete && !busy, onClick = { save() }) { Text("Save anyway") }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Its saved credentials and numbers are removed from this device.") },
            confirmButton = {
                TextButton(onClick = { store.deleteProfile(existing.id); onDone() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
