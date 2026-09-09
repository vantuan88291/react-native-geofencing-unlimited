package com.rngeofencing.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** The result of one OS registration call. Never throws across the boundary. */
sealed class RegistryResult {
  object Success : RegistryResult()

  /** [code] is Play Services' own status string — that is what makes a field report actionable (§8.4). */
  data class Failure(val code: String, val message: String) : RegistryResult()

  val isSuccess: Boolean
    get() = this is Success
}

/**
 * What the rotation engine needs of the OS.
 *
 * An interface for one reason: it is the seam the §14 unit tests drive, which is what
 * makes the add-before-remove ordering of §4.4 (invariant 3) and the "mark not-armed
 * on failure" rule of §4.6 (invariant 9) testable without a device or Play Services.
 */
interface RegionRegistry {
  fun isAvailable(): Boolean

  fun addRegions(
    records: List<GeofenceRecord>,
    initialTriggerEntry: Boolean,
    responsiveness: Int,
  ): RegistryResult

  fun removeRegions(ids: List<String>): RegistryResult

  fun addBoundary(center: LatLng, radius: Double): RegistryResult

  fun removeBoundary(): RegistryResult

  fun removeAll(): RegistryResult
}

/**
 * `GeofencingClient` wrapper (§6.3).
 *
 * The important asymmetry with iOS lives here: **`GeofencingClient` has no read
 * API** (§4.6). Nothing can be asked of the OS about what is currently armed, so the
 * store's `active` flag is the registry, and it may only be written after a call
 * through here has actually resolved (invariant 9).
 *
 * Every call blocks on the returned `Task`. That is safe and deliberate: this runs on
 * Core's single-threaded executor, never the main thread, and the caller needs the
 * outcome before it is allowed to touch the `active` flag.
 */
class PlatformRegistry(context: Context) : RegionRegistry {

  private val appContext: Context = context.applicationContext

  private val client: GeofencingClient by lazy {
    LocationServices.getGeofencingClient(appContext)
  }

  /**
   * One shared instance for the whole module.
   *
   * `FLAG_MUTABLE` is mandatory on API 31+: Play Services fills the transition detail
   * into this intent's extras, and an immutable PendingIntent silently arrives empty.
   */
  private val pendingIntent: PendingIntent by lazy {
    // Addressed by component name rather than by class literal so that `core/` keeps
    // no compile-time edge into the React Native-aware layer above it (§11,
    // invariant 1). The receiver is declared in the library manifest, which AGP
    // merges into the host app's, so it is a component of the *app's* package.
    val intent =
      Intent().setComponent(
        ComponentName(appContext.packageName, RECEIVER_CLASS_NAME)
      )
    val flags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
    PendingIntent.getBroadcast(appContext, PENDING_INTENT_REQUEST_CODE, intent, flags)
  }

  /**
   * Play Services present?
   *
   * Non-GMS devices (Huawei, some AOSP builds) have no `GeofencingClient` at all.
   * That must surface through `getState().available`, not as a crash (§6.1).
   */
  override fun isAvailable(): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
      ConnectionResult.SUCCESS

  fun hasFineLocationPermission(): Boolean =
    appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  /**
   * Arms [records]. An id that already exists is **replaced**, so the §4.4 diff needs
   * no special casing for updates.
   */
  override fun addRegions(
    records: List<GeofenceRecord>,
    initialTriggerEntry: Boolean,
    responsiveness: Int,
  ): RegistryResult {
    if (records.isEmpty()) return RegistryResult.Success

    val geofences = records.mapNotNull { it.toPlatformGeofence(responsiveness) }
    if (geofences.isEmpty()) {
      return RegistryResult.Failure(
        "E_INVALID_GEOFENCE",
        "no geofence in the batch had any transition type enabled",
      )
    }

    val request =
      GeofencingRequest.Builder()
        .setInitialTrigger(
          if (initialTriggerEntry) GeofencingRequest.INITIAL_TRIGGER_ENTER else 0
        )
        .addGeofences(geofences)
        .build()

    return await("addGeofences(${records.size})") { client.addGeofences(request, pendingIntent) }
  }

  override fun removeRegions(ids: List<String>): RegistryResult {
    if (ids.isEmpty()) return RegistryResult.Success
    return await("removeGeofences(${ids.size})") { client.removeGeofences(ids) }
  }

  /**
   * Arms the boundary region (§4.1).
   *
   * This one slot is what makes the whole design free: no polling and no location
   * stream, because the OS itself reports "the user moved far enough that your cached
   * nearest-N is stale". Registered with its own request so that
   * `INITIAL_TRIGGER_ENTER` cannot apply to it — we only ever care about leaving it.
   */
  override fun addBoundary(center: LatLng, radius: Double): RegistryResult {
    val boundary =
      Geofence.Builder()
        .setRequestId(BOUNDARY_ID)
        .setCircularRegion(center.latitude, center.longitude, radius.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
        .build()

    val request =
      GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(listOf(boundary)).build()

    return await("addBoundary(${radius.toInt()}m)") {
      client.addGeofences(request, pendingIntent)
    }
  }

  override fun removeBoundary(): RegistryResult = removeRegions(listOf(BOUNDARY_ID))

  /** Disarms everything this app registered, boundary included. */
  override fun removeAll(): RegistryResult =
    await("removeGeofences(pendingIntent)") { client.removeGeofences(pendingIntent) }

  private inline fun await(label: String, block: () -> Task<Void>): RegistryResult =
    try {
      Tasks.await(block(), CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      Logger.d("registry: $label ok")
      RegistryResult.Success
    } catch (error: Throwable) {
      val statusCode =
        when (val cause = if (error is java.util.concurrent.ExecutionException) error.cause else error) {
          is ApiException -> GeofenceStatusCodes.getStatusCodeString(cause.statusCode)
          else -> null
        }
      val message = statusCode ?: error.message ?: error::class.java.simpleName
      Logger.w("registry: $label failed — $message")
      RegistryResult.Failure("E_PLATFORM", "$label failed: $message")
    }

  private fun GeofenceRecord.toPlatformGeofence(responsiveness: Int): Geofence? {
    var transitions = 0
    if (notifyOnEntry) transitions = transitions or Geofence.GEOFENCE_TRANSITION_ENTER
    if (notifyOnExit) transitions = transitions or Geofence.GEOFENCE_TRANSITION_EXIT
    if (notifyOnDwell) transitions = transitions or Geofence.GEOFENCE_TRANSITION_DWELL

    if (transitions == 0) {
      Logger.w("registry: skipping '$id' — no transition type enabled")
      return null
    }

    return Geofence.Builder()
      .setRequestId(id)
      .setCircularRegion(latitude, longitude, radius.toFloat())
      .setExpirationDuration(Geofence.NEVER_EXPIRE)
      .setTransitionTypes(transitions)
      .apply {
        // Only legal alongside GEOFENCE_TRANSITION_DWELL; Play Services rejects the
        // whole request otherwise.
        if (notifyOnDwell) setLoiteringDelay(loiteringDelay)
      }
      .setNotificationResponsiveness(responsiveness)
      .build()
  }

  private companion object {
    const val PENDING_INTENT_REQUEST_CODE = 0
    const val CALL_TIMEOUT_SECONDS = 10L
    const val RECEIVER_CLASS_NAME = "com.rngeofencing.GeofenceBroadcastReceiver"
  }
}
