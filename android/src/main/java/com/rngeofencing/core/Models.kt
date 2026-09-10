package com.rngeofencing.core

/**
 * Core data model.
 *
 * Nothing in `core/` may import React Native (§11, invariant 1). The core is
 * constructed from a [android.content.BroadcastReceiver] and from app launch, where
 * no React context exists — a single `ReactApplicationContext` reference in here
 * makes killed-app operation impossible.
 */

/** Reserved identifier for the boundary region. Never escapes a public API (§4.2, invariant 10). */
const val BOUNDARY_ID = "@__rn_geofence_boundary__@"

/** iOS 20 / Android 100, minus the slot the boundary region occupies (§4.2). */
const val ANDROID_MAX_REGIONS = 100
const val ANDROID_ACTIVE_CAPACITY = ANDROID_MAX_REGIONS - 1

/** The boundary must trip before the cached nearest-N can be wrong (§4.3). */
const val BOUNDARY_SAFETY_MARGIN_M = 200.0

/** Below this the OS is unreliable about reporting the crossing (§4.3). */
const val MIN_BOUNDARY_RADIUS_M = 500.0

/** Whole-file-rewrite store, so both blobs are bounded (§9.5). */
const val MAX_GEOFENCES = 2000
const val MAX_QUEUED_EVENTS = 200

/** Per-geofence transition state (§5). */
enum class GeofenceState {
  UNKNOWN,
  INSIDE,
  OUTSIDE,
  ;

  companion object {
    fun parse(raw: String?): GeofenceState =
      when (raw) {
        "INSIDE" -> INSIDE
        "OUTSIDE" -> OUTSIDE
        else -> UNKNOWN
      }
  }
}

enum class GeofenceAction(val wire: String) {
  ENTER("ENTER"),
  EXIT("EXIT"),
  DWELL("DWELL"),
  ;

  companion object {
    fun parse(raw: String?): GeofenceAction? = entries.firstOrNull { it.wire == raw }
  }
}

data class LatLng(val latitude: Double, val longitude: Double) {
  val isValid: Boolean
    get() =
      latitude.isFinite() &&
        longitude.isFinite() &&
        latitude >= -90.0 &&
        latitude <= 90.0 &&
        longitude >= -180.0 &&
        longitude <= 180.0
}

/**
 * One geofence, plus the transition-gate state that belongs to it.
 *
 * The two live in the same record — and therefore the same JSON blob — because §5
 * updates state on the same object rotation reads, and splitting them would cost the
 * atomicity that makes the prefs store safe (§9.1).
 */
data class GeofenceRecord(
  val id: String,
  val latitude: Double,
  val longitude: Double,
  val radius: Double,
  val notifyOnEntry: Boolean = true,
  val notifyOnExit: Boolean = true,
  val notifyOnDwell: Boolean = false,
  val loiteringDelay: Int = 30_000,
  /** Opaque JSON string. Never reaches the OS, never part of [definitionChanged]. */
  val extras: String? = null,
  val state: GeofenceState = GeofenceState.UNKNOWN,
  val enteredAt: Long? = null,
  val dwellEmitted: Boolean = false,
  /**
   * Armed at OS level.
   *
   * On Android this flag **is** the registry: `GeofencingClient` has no read API
   * (§4.6). It must only ever be written in the commit that follows a *successful*
   * Play Services call (invariant 9).
   */
  val active: Boolean = false,
) {
  val center: LatLng
    get() = LatLng(latitude, longitude)

  /**
   * Whether the OS-visible definition changed and the region must be re-added.
   *
   * Compares position, radius and transition flags only — not `extras`, and not any
   * of the gate state (§4.4).
   */
  fun definitionChanged(other: GeofenceRecord): Boolean =
    latitude != other.latitude ||
      longitude != other.longitude ||
      radius != other.radius ||
      notifyOnEntry != other.notifyOnEntry ||
      notifyOnExit != other.notifyOnExit ||
      notifyOnDwell != other.notifyOnDwell ||
      loiteringDelay != other.loiteringDelay
}

/** A transition on its way to JS, or parked in the queue (§9.2). */
data class GeofenceEvent(
  val id: String,
  val action: GeofenceAction,
  val timestamp: Long,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val accuracy: Double? = null,
  /** Position synthesised from the region centre rather than a fix (§7.3). */
  val approximate: Boolean = false,
  /** Emitted by the gate, not the OS (§5.2). */
  val synthetic: Boolean = false,
  val extras: String? = null,
) {
  /**
   * Identity for queue bookkeeping only — never part of the JS payload.
   *
   * The headless path hands events to JS through the service intent *and* leaves
   * them queued, so that a service which never starts still loses nothing. Once the
   * task is accepted these keys are what removes exactly those events again, instead
   * of them being re-delivered by the next launch's queue flush (§6.5).
   */
  val queueKey: String
    get() = "$id|${action.name}|$timestamp"
}

/** Rotation bookkeeping (§9.2). */
data class RotationMeta(
  val centerLatitude: Double? = null,
  val centerLongitude: Double? = null,
  val centerAt: Long? = null,
  val boundaryRadius: Double? = null,
  /** `start()` called and not `stop()`ped. Survives process restarts. */
  val enabled: Boolean = false,
  /** Events dropped because the queue hit [MAX_QUEUED_EVENTS] (§9.5). */
  val droppedCount: Int = 0,
) {
  val center: LatLng?
    get() {
      val lat = centerLatitude
      val lng = centerLongitude
      return if (lat != null && lng != null) LatLng(lat, lng) else null
    }
}

/** The [ConfigSpec] last passed to `ready()`, persisted (§9.2). */
data class Config(
  val proximityRadius: Double = 2000.0,
  val initialTriggerEntry: Boolean = true,
  val notificationResponsiveness: Int = 0,
  val useSignificantLocationChanges: Boolean = true,
  val enableHeadless: Boolean = false,
  val minRadius: Double = 200.0,
  val debug: Boolean = false,
)

/** What `getState()` reports (§8.1). */
data class State(
  val enabled: Boolean,
  val available: Boolean,
  val authorization: String,
  val accuracyAuthorization: String,
  val geofenceCount: Int,
  val activeCount: Int,
  val batteryOptimized: Boolean,
  val locationServicesEnabled: Boolean,
  val rotationCenterLatitude: Double?,
  val rotationCenterLongitude: Double?,
  val rotationCenterAt: Long?,
  val boundaryRadius: Double?,
  val droppedEventCount: Int,
)

/** Why a rotation ran. Only used for the `debug: true` log (§14). */
enum class RotationTrigger {
  READY,
  START,
  BOUNDARY_EXIT,
  GEOFENCES_CHANGED,
  FOREGROUND,
  BOOT,
  PROVIDERS_CHANGED,
  PROCESS_START,
}

/** Thrown for the §8.4 rejection codes; the adapter maps it onto `promise.reject`. */
class GeofencingException(
  val code: String,
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
