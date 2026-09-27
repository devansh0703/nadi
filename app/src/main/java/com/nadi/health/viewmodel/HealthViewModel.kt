package com.nadi.health.viewmodel

import android.app.Application
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadi.health.NadiApplication
import com.nadi.health.data.local.LocalStats
import com.nadi.health.data.local.NadiRepository
import com.nadi.health.health.DeviceHeartRate
import com.nadi.health.health.DeviceSession
import com.nadi.health.health.HealthConnectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Health tab.
 *
 * Two jobs: show what Health Connect already knows about the user (heart rate,
 * steps, distance and sessions synced in from watches and other apps), and show
 * what Nadi has stored locally. Syncing pushes Nadi's own measurements out.
 *
 * Nothing here runs at launch — every read happens on [refresh], so a device
 * without Health Connect never pays for it.
 */
class HealthViewModel(application: Application) : AndroidViewModel(application) {

    /** Everything the Health screen renders. */
    data class UiState(
        val availability: HealthConnectManager.Availability =
            HealthConnectManager.Availability.NOT_INSTALLED,
        val grantedCount: Int = 0,
        val totalPermissions: Int = 0,
        val deviceHeartRate: List<DeviceHeartRate> = emptyList(),
        val deviceSessions: List<DeviceSession> = emptyList(),
        val stepsToday: Long? = null,
        val distanceTodayM: Double? = null,
        val local: LocalStats = LocalStats(0, 0, 0f, 0, 0.0),
        val loading: Boolean = false,
        /** One-line result of the last action, or null. */
        val message: String? = null
    ) {
        val hasFullAccess: Boolean get() = totalPermissions > 0 && grantedCount >= totalPermissions
        val hasAnyAccess: Boolean get() = grantedCount > 0
    }

    private val repository: NadiRepository? = (application as? NadiApplication)?.repository
    private val healthConnect: HealthConnectManager? =
        (application as? NadiApplication)?.healthConnect

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Permissions to hand to the permission launcher. */
    val requestedPermissions: Set<String> = healthConnect?.permissions ?: emptySet()

    /**
     * Contract for the Health Connect permission screen, or null when the
     * provider is absent — the UI only builds a launcher when this is non-null.
     */
    val permissionContract: ActivityResultContract<Set<String>, Set<String>>? =
        healthConnect?.permissionContract()

    init {
        _state.value = _state.value.copy(
            availability = healthConnect?.availability()
                ?: HealthConnectManager.Availability.NOT_INSTALLED,
            totalPermissions = requestedPermissions.size
        )
        refresh()
    }

    /** Re-reads Health Connect and the local store. Safe to call repeatedly. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            val hc = healthConnect
            val granted = hc?.grantedPermissions() ?: emptySet()

            // Only query Health Connect when it can actually answer; otherwise
            // every call would throw and burn a cycle for nothing.
            val canRead = hc != null && hc.isAvailable() && granted.isNotEmpty()
            val deviceHr = if (canRead) hc!!.readHeartRate(days = 1) else emptyList()
            val steps = if (canRead) hc!!.readStepsToday() else null
            val distance = if (canRead) hc!!.readDistanceToday() else null
            val sessions = if (canRead) hc!!.readExerciseSessions(days = 7) else emptyList()

            val local = withContext(Dispatchers.IO) {
                runCatching { repository?.stats() }.getOrNull()
            }

            _state.value = _state.value.copy(
                availability = hc?.availability()
                    ?: HealthConnectManager.Availability.NOT_INSTALLED,
                grantedCount = granted.size,
                totalPermissions = requestedPermissions.size,
                deviceHeartRate = deviceHr,
                deviceSessions = sessions,
                stepsToday = steps,
                distanceTodayM = distance,
                local = local ?: _state.value.local,
                loading = false
            )
        }
    }

    /** Called after the permission dialog returns, so the UI reflects the grant. */
    fun onPermissionsResult(granted: Set<String>) {
        Log.d(TAG, "Health Connect permissions granted: ${granted.size}/${requestedPermissions.size}")
        refresh()
    }

    /**
     * Pushes Nadi's own stored measurements into Health Connect.
     * Reports exactly what happened — including "nothing to sync" — rather than
     * claiming success unconditionally.
     */
    fun syncToHealthConnect() {
        val hc = healthConnect
        val repo = repository
        if (hc == null || repo == null) {
            _state.value = _state.value.copy(message = "Local store unavailable")
            return
        }
        if (!hc.isAvailable()) {
            _state.value = _state.value.copy(message = "Health Connect not available")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (hc.grantedPermissions().isEmpty()) {
                        null
                    } else {
                        val readings = repo.heartRateSince(hours = SYNC_WINDOW_HOURS)
                        val sessions = repo.workoutsSince(hours = SYNC_WINDOW_HOURS)
                        val writtenHr = hc.writeHeartRate(readings)
                        val writtenSessions = hc.writeWorkouts(sessions)
                        writtenHr to writtenSessions
                    }
                }
            }.getOrNull()

            _state.value = _state.value.copy(
                loading = false,
                message = when (result) {
                    null -> "Grant Health Connect access first"
                    else -> if (result.first == 0 && result.second == 0) {
                        "Nothing new to sync in the last $SYNC_WINDOW_HOURS h"
                    } else {
                        "Synced ${result.first} heart-rate samples and " +
                            "${result.second} workouts"
                    }
                }
            )
            refresh()
        }
    }

    /** Opens Health Connect so the user can attach/see their data sources. */
    fun settingsIntent() = healthConnect?.settingsIntent()

    /** Play Store fallback when Health Connect is not installed. */
    fun installIntent() = healthConnect?.installIntent()

    /** Wipes the local database. Irreversible, so the UI confirms first. */
    fun clearLocalData() {
        val repo = repository ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            withContext(Dispatchers.IO) {
                runCatching { repo.clearAll() }
                    .onFailure { Log.w(TAG, "Could not clear local data", it) }
            }
            _state.value = _state.value.copy(
                loading = false,
                message = "Local history cleared"
            )
            refresh()
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        private const val TAG = "HealthViewModel"

        /** How far back a manual sync reaches. */
        private const val SYNC_WINDOW_HOURS = 24 * 7
    }
}
