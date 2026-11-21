package app.aispend.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.aispend.data.Profile
import app.aispend.data.Snapshot
import app.aispend.data.Store
import app.aispend.providers.Http
import app.aispend.providers.HttpException
import app.aispend.providers.OkHttpTransport
import app.aispend.providers.Providers
import app.aispend.widget.SpendWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

object Refresher {
    private val http: Http = OkHttpTransport()
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Refreshes every profile in parallel. Returns false if any profile failed. */
    suspend fun refreshAll(context: Context): Boolean = mutex.withLock {
        _running.value = true
        try {
            val store = Store.get(context)
            val results = coroutineScope {
                store.profiles.value.map { profile -> async { refresh(store, profile) } }.awaitAll()
            }
            SpendWidget().updateAll(context)
            results.all { it.error == null }
        } finally {
            _running.value = false
        }
    }

    /** Fetches one profile and stores the outcome. On failure the previous numbers are kept. */
    suspend fun refresh(store: Store, profile: Profile): Snapshot {
        val snapshot = fetch(store, profile, store.secrets.get(profile.id))
            ?: (store.snapshots.value[profile.id] ?: Snapshot(profile.id))
        store.putSnapshot(snapshot)
        return snapshot
    }

    /** Tries credentials that are not saved yet. Returns the snapshot and any rotated credentials. */
    suspend fun test(profile: Profile, credentials: Map<String, String>): Pair<Snapshot, Map<String, String>> {
        var latest = credentials
        val snapshot = fetchWith(profile, credentials, previous = null) { latest = it }
        return snapshot to latest
    }

    private suspend fun fetch(store: Store, profile: Profile, credentials: Map<String, String>): Snapshot? =
        fetchWith(profile, credentials, store.snapshots.value[profile.id]) { store.secrets.put(profile.id, it) }

    private suspend fun fetchWith(
        profile: Profile,
        credentials: Map<String, String>,
        previous: Snapshot?,
        onRotated: (Map<String, String>) -> Unit,
    ): Snapshot = try {
        val result = Providers.of(profile.provider).fetch(credentials, http)
        result.updatedCredentials?.let(onRotated)
        Snapshot(profile.id, System.currentTimeMillis(), result.plan, result.meters, result.money)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (previous ?: Snapshot(profile.id)).copy(error = describe(e))
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpException -> when (e.code) {
            401 -> "Not authorised — the key or token is invalid or expired"
            403 -> "Access denied — this key lacks permission for usage data"
            429 -> "Rate limited by the provider — will retry later"
            else -> "Provider returned HTTP ${e.code}"
        }
        is IOException -> "No connection"
        else -> e.message ?: "Unexpected response from provider"
    }
}
