package com.rngeofencing.core

/**
 * Dedup and synthetic events (§5, invariant 6).
 *
 * Rotation introduces two failure modes a fixed set never has:
 *
 * 1. Arming a region the device is already inside makes the OS report entry
 *    (`INITIAL_TRIGGER_ENTER` on Android, `didDetermineState(.inside)` on iOS). That
 *    is a re-arm artifact, not a crossing.
 * 2. Leaving a region while it was rotated out delivers no EXIT at all, so the state
 *    stays `INSIDE` forever — a stuck session in the host app.
 *
 * Every OS callback and every rotation therefore passes through here. The gate owns
 * the persisted per-geofence state and returns the events that survived; delivering
 * them is [Core]'s job.
 *
 * Pure with respect to the platform — no Play Services, no React Native — so the
 * whole `(persistedState, platformEvent)` matrix is unit-testable (§14).
 */
class TransitionGate(
  private val store: GeofenceStore,
  private val now: () -> Long = { System.currentTimeMillis() },
) {

  /**
   * Handles a batch of ids reported by one OS broadcast.
   *
   * One Play Services broadcast can name several triggering geofences, and they all
   * share the same action and location.
   */
  fun onPlatformTransition(
    ids: List<String>,
    action: GeofenceAction,
    location: LatLng?,
    accuracy: Double? = null,
  ): List<GeofenceEvent> =
    ids.mapNotNull { id ->
      when (action) {
        GeofenceAction.ENTER -> onEnter(id, location, accuracy)
        GeofenceAction.EXIT -> onExit(id, location, accuracy)
        GeofenceAction.DWELL -> onDwell(id, location, accuracy)
      }
    }

  /** §5.1. */
  private fun onEnter(id: String, location: LatLng?, accuracy: Double?): GeofenceEvent? {
    val record = store.get(id) ?: return unknownId(id)

    if (record.state == GeofenceState.INSIDE) {
      // Already inside: this is the re-arm artifact, not a crossing.
      Logger.d("gate: drop ENTER $id (already INSIDE)")
      return null
    }

    val firstEverArming = record.state == GeofenceState.UNKNOWN
    store.updateState(id, GeofenceState.INSIDE, now(), dwellEmitted = false)

    // `initialTriggerEntry` governs only the *very first* arming of a geofence. It
    // must never suppress a genuine ENTER on a region rotated back in — that region
    // has a known previous state, so it is not affected by this branch (§5.1).
    if (firstEverArming && !store.config.initialTriggerEntry) {
      Logger.d("gate: drop ENTER $id (first arming, initialTriggerEntry=false)")
      return null
    }

    if (!record.notifyOnEntry) return null

    // No dwell timer is scheduled here: on Android DWELL is native
    // (`GEOFENCE_TRANSITION_DWELL` with `setLoiteringDelay`), and scheduling our own
    // would double-fire (§5.3).
    return event(record, GeofenceAction.ENTER, location, accuracy)
  }

  private fun onExit(id: String, location: LatLng?, accuracy: Double?): GeofenceEvent? {
    val record = store.get(id) ?: return unknownId(id)

    if (record.state == GeofenceState.OUTSIDE) {
      Logger.d("gate: drop EXIT $id (already OUTSIDE)")
      return null
    }

    // Clears the dwell bookkeeping too: a pending dwell is always cancelled on exit
    // (§5.3).
    store.updateState(id, GeofenceState.OUTSIDE, null, dwellEmitted = false)

    if (!record.notifyOnExit) return null
    return event(record, GeofenceAction.EXIT, location, accuracy)
  }

  private fun onDwell(id: String, location: LatLng?, accuracy: Double?): GeofenceEvent? {
    val record = store.get(id) ?: return unknownId(id)

    if (record.dwellEmitted) {
      Logger.d("gate: drop DWELL $id (already emitted for this visit)")
      return null
    }

    // A DWELL implies we are inside, even if we somehow missed the ENTER.
    store.updateState(
      id,
      GeofenceState.INSIDE,
      record.enteredAt ?: now(),
      dwellEmitted = true,
    )

    if (!record.notifyOnDwell) return null
    return event(record, GeofenceAction.DWELL, location, accuracy)
  }

  /**
   * Called with the set that is about to be disarmed (§5.2).
   *
   * A region being rotated out while our state says `INSIDE` is the missed-EXIT case.
   * Distance from the rotation centre is what decides: further than its radius means
   * we have already left and owe the host an EXIT. Closer means we are genuinely
   * still inside but out of slots — leave it `INSIDE` and resolve it on re-arm,
   * because a synthetic EXIT there would be a lie.
   */
  fun onRotationApplied(
    disarmedIds: Collection<String>,
    center: LatLng,
  ): List<GeofenceEvent> =
    disarmedIds.mapNotNull { id ->
      val record = store.get(id) ?: return@mapNotNull null
      if (record.state != GeofenceState.INSIDE) return@mapNotNull null

      val distance = haversine(center, record)
      if (distance <= record.radius) {
        Logger.d(
          "gate: rotating out $id while still inside (${distance.toInt()}m <= " +
            "${record.radius.toInt()}m) — keeping INSIDE"
        )
        return@mapNotNull null
      }

      store.updateState(id, GeofenceState.OUTSIDE, null, dwellEmitted = false)
      Logger.d("gate: synthetic EXIT $id (rotated out, ${distance.toInt()}m away)")

      if (!record.notifyOnExit) return@mapNotNull null
      event(record, GeofenceAction.EXIT, center, accuracy = null, synthetic = true)
    }

  /**
   * Reconciliation on re-arm (§5.2).
   *
   * Android has no `didDetermineState`, so this is driven by a distance check against
   * the rotation centre when a region is armed again. If our state says `INSIDE` and
   * the device is demonstrably outside, the EXIT happened while we were rotated out.
   */
  fun onStateDetermined(id: String, isInside: Boolean, center: LatLng): GeofenceEvent? {
    val record = store.get(id) ?: return null

    if (record.state == GeofenceState.INSIDE && !isInside) {
      store.updateState(id, GeofenceState.OUTSIDE, null, dwellEmitted = false)
      Logger.d("gate: synthetic EXIT $id (state reconciliation on re-arm)")
      if (!record.notifyOnExit) return null
      return event(record, GeofenceAction.EXIT, center, accuracy = null, synthetic = true)
    }

    // The INSIDE-on-re-arm case is deliberately left to the platform: Play Services
    // reports it as INITIAL_TRIGGER_ENTER, which onEnter() then dedups (§5.1).
    return null
  }

  private fun unknownId(id: String): GeofenceEvent? {
    // The OS named a region we have no record of — a leftover registration from a
    // previous install, or a removal that raced the broadcast. Dropping it is right;
    // there is nothing to report it against.
    Logger.w("gate: transition for unknown geofence '$id', dropped")
    return null
  }

  private fun event(
    record: GeofenceRecord,
    action: GeofenceAction,
    location: LatLng?,
    accuracy: Double?,
    synthetic: Boolean = false,
  ): GeofenceEvent =
    GeofenceEvent(
      id = record.id,
      action = action,
      timestamp = now(),
      latitude = location?.latitude,
      longitude = location?.longitude,
      accuracy = accuracy,
      // Android normally carries a real triggering location; when it does not, the
      // caller has substituted the rotation centre, which is approximate (§7.3).
      approximate = location != null && accuracy == null,
      synthetic = synthetic,
      extras = record.extras,
    )
}
