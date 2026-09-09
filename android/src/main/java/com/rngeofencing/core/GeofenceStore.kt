package com.rngeofencing.core

/**
 * The persistence seam (§9.6).
 *
 * Everything above this interface — the rotation engine, the transition gate, the
 * platform registry — is forbidden to know how the bytes are stored. That is what
 * makes the "no SQLite" decision (§9) reversible: if a host app ever genuinely needs
 * more than [MAX_GEOFENCES] geofences, swap the implementation rather than scaling
 * the prefs blob (§9.5).
 *
 * Implementations are **not** thread-safe on their own. Every read-modify-write is
 * routed through Core's single-threaded executor instead (§9.4), which is the same
 * executor that serialises rotations (§4.5, invariant 2).
 */
interface GeofenceStore {
  /** Parse the blobs once and build the in-memory working copy (§9.3). */
  fun load()

  fun all(): List<GeofenceRecord>

  fun get(id: String): GeofenceRecord?

  fun count(): Int

  fun upsert(records: List<GeofenceRecord>)

  fun delete(ids: List<String>)

  fun updateState(
    id: String,
    state: GeofenceState,
    enteredAt: Long?,
    dwellEmitted: Boolean,
  )

  /**
   * Marks exactly [ids] as armed and everything else as not.
   *
   * On Android this flag is the only registry there is (§4.6), so it must be written
   * only after the Play Services call has resolved successfully (invariant 9).
   */
  fun setActive(ids: Set<String>)

  /** Marks a subset armed or not, leaving the rest alone — used for partial failures. */
  fun setActive(ids: Collection<String>, active: Boolean)

  fun activeIds(): Set<String>

  fun enqueue(event: GeofenceEvent)

  fun enqueue(events: List<GeofenceEvent>)

  fun drainQueue(): List<GeofenceEvent>

  fun queueSize(): Int

  var meta: RotationMeta

  var config: Config

  /**
   * When `true`, every write is committed synchronously rather than handed to the
   * framework's async flush.
   *
   * Set it on the receiver and headless paths, where the process can be frozen
   * within milliseconds of returning (§9.3). `apply()` normally survives process
   * death because the framework flushes it, but "normally" is not the guarantee you
   * want for the one write that records a crossing.
   */
  var durableWrites: Boolean
}
