package com.rngeofencing.core

/**
 * How [Core] reaches JS — the seam that keeps React Native out of `core/` (§11,
 * invariant 1).
 *
 * The implementation lives one layer up, in the React-aware package, and is attached
 * by the native module when it is constructed and detached when it is invalidated.
 * **Everything here is optional by design**: when a geofence fires with the app
 * killed there is no implementation at all, and the core must still record the
 * crossing, update its state machine and persist the event (§15.6).
 */
interface JsDelivery {
  /**
   * Is a React instance alive at all?
   *
   * `false` means the app is killed or tearing down, which is what decides between
   * queueing and starting the headless task (§6.5).
   */
  fun hasReactInstance(): Boolean

  /**
   * Is JS actually listening?
   *
   * A live React instance with no subscriber must still queue: emitting into nothing
   * logs a warning and drops the event, and on this module a dropped event is a lost
   * crossing (§15.4).
   */
  fun isObserving(): Boolean

  fun emitEvent(event: GeofenceEvent)

  fun emitGeofencesChange(on: List<GeofenceRecord>, off: List<String>)
}
