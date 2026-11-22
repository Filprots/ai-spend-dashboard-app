package app.aispend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aispend.data.Profile
import app.aispend.data.ProviderType
import app.aispend.data.Store
import app.aispend.providers.AnthropicApi
import app.aispend.providers.ChatGptSignIn
import app.aispend.providers.ClaudeSignIn
import app.aispend.sync.Refresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

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

    var consoleOpen by remember { mutableStateOf(false) }
    var orgChoices by remember { mutableStateOf<List<AnthropicApi.ConsoleOrg>>(emptyList()) }

    fun credentials() = provider.fields.associate { it.key to values[it.key].orEmpty().trim() }.filterValues { it.isNotEmpty() }
    val complete = provider.fields.all { it.optional || values[it.key].orEmpty().isNotBlank() }

    var manual by remember { mutableStateOf(!provider.isSubscription) }
    val context = LocalContext.current
    // Claude sign-in leaves the app for the browser, so its secrets must survive activity recreation.
    var claudeVerifier by rememberSaveable { mutableStateOf("") }
    var claudeState by rememberSaveable { mutableStateOf("") }
    var claudeCode by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<ChatGptSignIn.DeviceCode?>(null) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { failure = "No browser available to open the sign-in page" }
    }

    fun save(tested: Map<String, String>? = null) {
        val saved = store.saveProfile(existing?.id, name.trim().ifEmpty { provider.displayName }, provider, tested ?: credentials())
        scope.launch { Refresher.refresh(store, saved) }
        onDone()
    }

    /** Verifies credentials against the provider, then saves the profile. */
    suspend fun checkAndSave(credentials: Map<String, String>) {
        val (snapshot, latest) = Refresher.test(Profile("test", name, provider), credentials)
        if (snapshot.error == null) save(latest) else failure = snapshot.error
    }

    /** Runs a sign-in step; on success the resulting tokens are checked and saved. */
    fun signIn(step: suspend () -> Map<String, String>?) {
        busy = true
        failure = null
        scope.launch {
            try {
                step()?.let { tokens ->
                    values.clear()
                    values.putAll(tokens)
                    checkAndSave(tokens)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = if (e is IOException) "No connection" else e.message ?: "Sign-in failed"
            } finally {
                busy = false
            }
        }
    }

    /** Stores a Console login, then picks the organization (asking only when there is a real choice). */
    fun useConsoleLogin(cookie: String, userAgent: String?) {
        values["console"] = cookie
        if (userAgent.isNullOrBlank()) values.remove("consoleUa") else values["consoleUa"] = userAgent
        values.remove("consoleOrg")
        signIn {
            // With an admin key the key's own organization is matched automatically.
            val orgs = if (values["token"].isNullOrBlank()) {
                AnthropicApi.consoleOrganizations(Refresher.http, cookie, userAgent)
            } else emptyList()
            if (orgs.size > 1) {
                orgChoices = orgs
            } else {
                orgs.singleOrNull()?.let {
                    values["consoleOrg"] = it.id
                    if (name.isBlank()) name = it.name
                }
                checkAndSave(credentials())
            }
            null
        }
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
                            onClick = {
                                provider = type; values.clear(); failure = null
                                manual = !type.isSubscription; device = null; claudeVerifier = ""
                            },
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
            when (provider) {
                ProviderType.CLAUDE_SUBSCRIPTION -> {
                    Button(enabled = !busy, onClick = {
                        val pending = ClaudeSignIn.start()
                        claudeVerifier = pending.verifier
                        claudeState = pending.state
                        failure = null
                        open(pending.url)
                    }) { Text(if (existing == null) "Sign in with Claude" else "Sign in again") }
                    if (claudeVerifier.isNotEmpty()) {
                        Text(
                            "Approve access in the browser, then copy the code it shows and paste it here.",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                        OutlinedTextField(
                            value = claudeCode,
                            onValueChange = { claudeCode = it; failure = null },
                            label = { Text("Code from the browser") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(enabled = claudeCode.isNotBlank() && !busy, onClick = {
                            signIn { ClaudeSignIn.finish(Refresher.http, claudeCode, claudeVerifier, claudeState) }
                        }) { Text(if (busy) "Checking…" else "Finish sign-in") }
                    }
                }
                ProviderType.CHATGPT_SUBSCRIPTION -> {
                    val current = device
                    if (current == null) {
                        Button(enabled = !busy, onClick = {
                            signIn {
                                val issued = ChatGptSignIn.start(Refresher.http)
                                device = issued
                                try {
                                    ChatGptSignIn.await(Refresher.http, issued)
                                } finally {
                                    device = null
                                }
                            }
                        }) { Text(if (busy) "Starting…" else if (existing == null) "Sign in with ChatGPT" else "Sign in again") }
                    } else {
                        Text("Enter this one-time code on the ChatGPT sign-in page:", style = MaterialTheme.typography.bodyMedium)
                        Text(current.userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("ChatGPT code", current.userCode))
                                open(ChatGptSignIn.VERIFICATION_URL)
                            }) { Text("Copy code and open page") }
                        }
                        Text(
                            "Waiting for approval… The code expires in 15 minutes. If the page says device codes are " +
                                "disabled, turn on device code authorization in ChatGPT → Settings → Security.",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                }
                ProviderType.ANTHROPIC_API -> {
                    val signedIn = values["console"].orEmpty().isNotBlank()
                    if (orgChoices.isNotEmpty()) {
                        Text("Which organization is this profile for?", style = MaterialTheme.typography.titleSmall)
                        orgChoices.forEach { org ->
                            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                orgChoices = emptyList()
                                values["consoleOrg"] = org.id
                                if (name.isBlank()) name = org.name
                                signIn { checkAndSave(credentials()); null }
                            }) { Text(org.name) }
                        }
                    }
                    Button(enabled = !busy, onClick = { failure = null; consoleOpen = true }) {
                        Text(if (signedIn) "Sign in to Console again" else "Sign in to Console for balance")
                    }
                    // Another profile's Console login can see every organization of that account.
                    val donor = remember(existing) {
                        store.profiles.value.firstOrNull { it.provider == ProviderType.ANTHROPIC_API && it.id != existing?.id }
                            ?.let { p -> store.secrets.get(p.id).takeIf { !it["console"].isNullOrBlank() }?.let { p to it } }
                    }
                    if (!signedIn && donor != null) {
                        OutlinedButton(enabled = !busy, onClick = {
                            failure = null
                            useConsoleLogin(donor.second.getValue("console"), donor.second["consoleUa"])
                        }) { Text("Use the Console login from ${donor.first.name}") }
                    }
                    Text(
                        if (signedIn) "Console session saved — the balance is read with it." else
                            "Optional. The session can read and change your Console account, so it is kept encrypted " +
                                "on this device and used only to read the balance.",
                        style = MaterialTheme.typography.bodySmall, color = muted,
                    )
                }
                else -> {}
            }
            if (provider.isSubscription) {
                TextButton(onClick = { manual = !manual }) {
                    Text(if (manual) "Hide manual token entry" else "Enter tokens manually instead")
                }
                if (manual) Text(provider.manualHelp, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (manual) {
                provider.fields.filterNot { it.hidden }.forEach { field ->
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
            }
            Text("Stored encrypted on this device only, and sent only to ${provider.displayName}.", style = MaterialTheme.typography.bodySmall, color = muted)

            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (manual) {
                    Button(
                        enabled = complete && !busy,
                        onClick = { signIn { checkAndSave(credentials()); null } },
                    ) { Text(if (busy) "Checking…" else "Check and save") }
                }
                if (failure != null && complete) TextButton(enabled = !busy, onClick = { save() }) { Text("Save anyway") }
            }
        }
    }

    if (consoleOpen) {
        ConsoleSignInDialog(
            onSignedIn = { cookie, userAgent ->
                consoleOpen = false
                values["console"] = cookie
                values["consoleUa"] = userAgent
                useConsoleLogin(cookie, userAgent)
            },
            onDismiss = { consoleOpen = false },
        )
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
