package com.rngeofencing.core

/** What [RotationEngine.computeActiveSet] decided. */
data class ActiveSet(
  val active: List<GeofenceRecord>,
  /** `null` means every geofence fits, so no boundary region is needed (§4.3). */
  val boundaryRadius: Double?,
)

/** What actually reached the OS. Drives `onGeofencesChange` (§4.4). */
data class RotationResult(
  val on: List<GeofenceRecord>,
  val off: List<String>,
  val syntheticEvents: List<GeofenceEvent>,
  val boundaryRadius: Double?,
  val center: LatLng,
  val failed: List<String>,
)

/**
 * The active-subset rotation engine (§4).
 *
 * iOS monitors at most 20 regions and Android at most 100, but apps need thousands.
 * So: keep every geofence in our own store, arm only the nearest N at OS level, and
 * arm one extra **boundary region** centred on the position the set was computed
 * from. When the user leaves the boundary, the OS wakes us and we recompute.
 *
 * That boundary slot is what makes the design free — no polling, no location stream.
 * The OS itself says "you have moved far enough that your cached nearest-N is stale".
 *
 * [computeActiveSet] is deliberately pure so the capacity, safe-zone and degenerate
 * cluster cases are unit-testable without a device (§14).
 */
class RotationEngine(
  private val store: GeofenceStore,
  private val registry: RegionRegistry,
  private val gate: TransitionGate,
  private val capacity: Int = ANDROID_ACTIVE_CAPACITY,
) {

  /** §4.3. */
  fun computeActiveSet(center: LatLng): ActiveSet {
    val config = store.config

    // Linear haversine scan, then sort. No spatial index and no bounding-box
    // prefilter: the store is capped at 2000 entries and already fully in memory, so
    // this is well under a millisecond, and a prefilter only introduces a way to
    // wrongly exclude a candidate (§4.3).
    val candidates =
      store
        .all()
        .asSequence()
        .filter { it.id != BOUNDARY_ID }
        .map { Candidate(it, haversine(center, it)) }
        // `+ radius`, not bare proximityRadius: a geofence whose *edge* is reachable
        // must never be excluded.
        .filter { it.distance <= config.proximityRadius + it.record.radius }
        .sortedBy { it.distance }
        .toList()

    // Everything fits — no rotation, and therefore no boundary region to maintain.
    val total = store.all().count { it.id != BOUNDARY_ID }
    if (total <= capacity) {
      return ActiveSet(store.all().filter { it.id != BOUNDARY_ID }, null)
    }

    val active = candidates.take(capacity)

    // The boundary must stay strictly inside the "safe zone": the region within which
    // no UN-ARMED geofence can be reached. That is the distance to the nearest
    // excluded geofence minus its own radius. If the boundary were any larger, the
    // user could walk into an un-armed region and never be detected — the boundary is
    // a *staleness detector*, and it has to trip before the cache can be wrong.
    val firstExcluded = candidates.getOrNull(capacity)
    val safeZone =
      if (firstExcluded != null) {
        firstExcluded.distance - firstExcluded.record.radius
      } else {
        config.proximityRadius
      }

    val boundaryRadius =
      clamp(
        safeZone - BOUNDARY_SAFETY_MARGIN_M,
        MIN_BOUNDARY_RADIUS_M,
        config.proximityRadius,
      )

    if (safeZone - BOUNDARY_SAFETY_MARGIN_M < MIN_BOUNDARY_RADIUS_M) {
      // Dense cluster: the clamp has produced a boundary that overlaps excluded
      // regions, so an ENTER can be missed. Unavoidable with a fixed slot budget —
      // the host app's options are larger radii or a thinner cluster (§4.3, §10).
      Logger.w(
        "dense geofence cluster: safe zone is ${safeZone.toInt()}m but the minimum " +
          "boundary radius is ${MIN_BOUNDARY_RADIUS_M.toInt()}m — the boundary now " +
          "overlaps excluded geofences and an ENTER may be missed. Raise geofence " +
          "radii or thin the cluster."
      )
    }

    return ActiveSet(active.map { it.record }, boundaryRadius)
  }

  /**
   * Diffs [next] against the OS registry and applies it (§4.4).
   *
   * Two orderings here are load-bearing:
   *
   * - **Add before remove** (invariant 3). A process killed between the two calls
   *   then leaves a *superset* — which still detects everything — never a hole.
   * - **Boundary last.** It is the trigger for the next rotation, and re-arming it
   *   before the set is in place would race a fast-moving user.
   *
   * @param forceReAddAll re-arms every region regardless of what the `active` flags
   *   claim. Required on every process start: a reboot, a force-stop or a Play
   *   Services reset clears the OS side while our flags still say `active = true`,
   *   and a blind re-add is a cheap upsert and the only thing that resolves that
   *   divergence (§4.6, invariant 9).
   */
  fun applyActiveSet(
    next: List<GeofenceRecord>,
    boundaryRadius: Double?,
    center: LatLng,
    forceReAddAll: Boolean,
  ): RotationResult {
    val config = store.config

    // On Android our own `active` flag *is* the registry — `GeofencingClient` has no
    // read API (§4.6).
    val currentIds = store.activeIds()
    val nextIds = next.map { it.id }.toSet()

    // A changed definition is already reflected here: `upsert` clears `active` when
    // the OS-visible definition changed, so such a region is absent from currentIds
    // and lands in toAdd (see PrefsGeofenceStore.upsert).
    val toAdd = if (forceReAddAll) next else next.filter { !currentIds.contains(it.id) }
    val toRemove = currentIds.filter { !nextIds.contains(it) }

    val failed = mutableListOf<String>()
    val added = mutableListOf<GeofenceRecord>()

    if (toAdd.isNotEmpty()) {
      when (val result = registry.addRegions(toAdd, config.initialTriggerEntry, config.notificationResponsiveness)) {
        is RegistryResult.Success -> {
          // `active` is written only now, in the commit that follows a *resolved*
          // Play Services call. A flag set optimistically for a call that then
          // failed makes the next diff skip a region that was never armed
          // (invariant 9).
          store.setActive(toAdd.map { it.id }, active = true)
          added.addAll(toAdd)
        }
        is RegistryResult.Failure -> {
          // Mark them not-armed and let the next rotation retry. Do not throw away
          // the whole set (§4.6).
          store.setActive(toAdd.map { it.id }, active = false)
          failed.addAll(toAdd.map { it.id })
          Logger.w("rotation: addRegions failed, ${toAdd.size} region(s) left for retry — ${result.message}")
        }
      }
    }

    if (toRemove.isNotEmpty()) {
      when (val result = registry.removeRegions(toRemove)) {
        is RegistryResult.Success -> store.setActive(toRemove, active = false)
        is RegistryResult.Failure ->
          // Still armed at OS level, so the flag stays true and the region keeps
          // reporting. Harmless: a superset detects everything.
          Logger.w("rotation: removeRegions failed, regions stay armed — ${result.message}")
      }
    }

    // Boundary last, and always torn down first so a stale centre cannot linger.
    registry.removeBoundary()
    if (boundaryRadius != null) {
      when (val result = registry.addBoundary(center, boundaryRadius)) {
        is RegistryResult.Success -> Unit
        is RegistryResult.Failure ->
          // Without a boundary there is no rotation trigger left but app foreground
          // and process start. Loud, because it degrades silently otherwise.
          Logger.e("rotation: boundary region could not be armed — rotation will stall: ${result.message}")
      }
    }

    store.meta =
      store.meta.copy(
        centerLatitude = center.latitude,
        centerLongitude = center.longitude,
        centerAt = System.currentTimeMillis(),
        boundaryRadius = boundaryRadius,
      )

    // Regions rotated out that we still believe we are inside owe the host an EXIT
    // (§5.2), and regions rotated back in may have been left while unarmed.
    val synthetic = mutableListOf<GeofenceEvent>()
    synthetic.addAll(gate.onRotationApplied(toRemove, center))
    added.forEach { record ->
      val inside = haversine(center, record) <= record.radius
      gate.onStateDetermined(record.id, inside, center)?.let { synthetic.add(it) }
    }

    Logger.d(
      "rotation applied: center=(${center.latitude}, ${center.longitude}) " +
        "boundary=${boundaryRadius?.toInt() ?: "none"}m " +
        "on=${added.map { it.id }} off=$toRemove " +
        "failed=$failed synthetic=${synthetic.size}"
    )

    return RotationResult(
      on = added,
      off = toRemove,
      syntheticEvents = synthetic,
      boundaryRadius = boundaryRadius,
      center = center,
      failed = failed,
    )
  }

  private data class Candidate(val record: GeofenceRecord, val distance: Double)
}
