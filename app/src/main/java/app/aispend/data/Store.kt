package app.aispend.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Profiles, their last snapshots and settings. Single instance shared by the app, worker and widget. */
class Store private constructor(context: Context) {
    @Serializable
    private data class Persisted(
        val profiles: List<Profile> = emptyList(),
        val snapshots: Map<String, Snapshot> = emptyMap(),
    )

    private val file = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val secrets = SecretStore(context)

    private val initial = runCatching { json.decodeFromString<Persisted>(file.readText()) }.getOrDefault(Persisted())
    private val _profiles = MutableStateFlow(initial.profiles)
    private val _snapshots = MutableStateFlow(initial.snapshots)
    private val _refreshMinutes = MutableStateFlow(settings.getInt("refreshMinutes", 30))

    val profiles: StateFlow<List<Profile>> = _profiles
    val snapshots: StateFlow<Map<String, Snapshot>> = _snapshots
    val refreshMinutes: StateFlow<Int> = _refreshMinutes

    fun saveProfile(id: String?, name: String, provider: ProviderType, credentials: Map<String, String>): Profile {
        val profile = Profile(id ?: UUID.randomUUID().toString(), name, provider)
        secrets.put(profile.id, credentials)
        _profiles.update { list ->
            if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
        }
        persist()
        return profile
    }

    fun deleteProfile(id: String) {
        secrets.remove(id)
        _profiles.update { list -> list.filterNot { it.id == id } }
        _snapshots.update { it - id }
        persist()
    }

    fun putSnapshot(snapshot: Snapshot) {
        // A profile deleted while its refresh was in flight must not come back as an orphan snapshot.
        if (_profiles.value.none { it.id == snapshot.profileId }) return
        _snapshots.update { it + (snapshot.profileId to snapshot) }
        persist()
    }

    fun setRefreshMinutes(minutes: Int) {
        settings.edit().putInt("refreshMinutes", minutes).apply()
        _refreshMinutes.value = minutes
    }

    @Synchronized
    private fun persist() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Persisted.serializer(), Persisted(_profiles.value, _snapshots.value)))
        tmp.renameTo(file)
    }

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
            }
    }
}
