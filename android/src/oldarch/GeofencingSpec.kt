package com.rngeofencing

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap

/**
 * Legacy-bridge adapter (§15.3).
 *
 * Mirrors §8.1 exactly, including the argument types codegen would have chosen —
 * TypeScript `number` maps to Kotlin `Double`, so [removeListeners] takes a `Double`
 * and not an `Int`. Diverge from that and the two architectures take different
 * argument types for the same JS call, which surfaces as an unmarshalling error on
 * exactly one of them.
 *
 * Nothing below this class differs between architectures: the rotation engine, the
 * transition gate, the store and the platform registry are byte-for-byte identical,
 * because `core/` has no React Native dependency at all (§11, §15.6). Only this
 * adapter layer changes.
 */
abstract class GeofencingSpec internal constructor(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {

  /**
   * Registered identically on both paths (§15.2) — `getName()` here,
   * `RCT_EXPORT_MODULE` on iOS, and the same string in
   * `TurboModuleRegistry.get('RNGeofencing')`. A mismatch means one architecture
   * resolves the module and the other silently falls into the JS linking-error proxy.
   *
   * Under the New Architecture the generated spec supplies this instead.
   */
  override fun getName(): String = "RNGeofencing"

  abstract fun ready(config: ReadableMap, promise: Promise)

  abstract fun start(promise: Promise)

  abstract fun stop(promise: Promise)

  abstract fun addGeofence(geofence: ReadableMap, promise: Promise)

  abstract fun addGeofences(geofences: ReadableArray, promise: Promise)

  abstract fun removeGeofence(identifier: String, promise: Promise)

  abstract fun removeGeofences(identifiers: ReadableArray?, promise: Promise)

  abstract fun getGeofences(promise: Promise)

  abstract fun getActiveGeofences(promise: Promise)

  abstract fun requestPermission(promise: Promise)

  abstract fun openSettings(promise: Promise)

  abstract fun getState(promise: Promise)

  abstract fun flushQueue(promise: Promise)

  abstract fun getDebugLog(promise: Promise)

  // Required by NativeEventEmitter since RN 0.65 — no-ops, but their absence produces
  // a runtime warning on every subscription. Under the New Architecture the generated
  // spec already declares them; here they have to be written by hand (§15.3).
  abstract fun addListener(eventName: String)

  abstract fun removeListeners(count: Double)
}
