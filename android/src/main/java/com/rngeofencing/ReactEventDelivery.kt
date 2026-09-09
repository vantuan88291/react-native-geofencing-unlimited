package com.rngeofencing

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.rngeofencing.core.Core
import com.rngeofencing.core.GeofenceEvent
import com.rngeofencing.core.GeofenceRecord
import com.rngeofencing.core.JsDelivery
import com.rngeofencing.core.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * The React Native half of event delivery (§15.4).
 *
 * `NativeEventEmitter` is used on **both** architectures rather than codegen's
 * `EventEmitter<T>`, which exists only under the New Architecture. One code path, at
 * the cost of losing codegen type-checking on the payload — recovered by the
 * hand-written cast in the JS wrapper, which is where the `extras` JSON gets parsed
 * anyway.
 *
 * [listenerCount] is not bookkeeping for its own sake: it is what decides whether an
 * event is **emitted or queued**. Emitting with no listeners logs a warning and drops
 * the event, and on this module a dropped event is a lost crossing.
 */
class ReactEventDelivery(private val reactContext: ReactApplicationContext) : JsDelivery {

  private val listenerCount = AtomicInteger(0)

  fun onListenerAdded() {
    val count = listenerCount.incrementAndGet()
    if (count == 1) {
      // First subscriber: anything that accumulated while JS was down is owed to it
      // immediately (§6.5).
      // `applicationContext`, never the ReactApplicationContext: §15.6 is enforced
      // by Core's signatures.
      Core.onJsStartedObserving(reactContext.applicationContext)
    }
  }

  fun onListenersRemoved(count: Int) {
    listenerCount.updateAndGet { current -> (current - count).coerceAtLeast(0) }
  }

  override fun hasReactInstance(): Boolean =
    try {
      reactContext.hasActiveReactInstance()
    } catch (error: Throwable) {
      // The context can be mid-teardown; treat any failure as "not available" so the
      // caller queues instead of throwing inside a broadcast receiver.
      Logger.d("hasActiveReactInstance threw: ${error.message}")
      false
    }

  override fun isObserving(): Boolean = listenerCount.get() > 0 && hasReactInstance()

  override fun emitEvent(event: GeofenceEvent) {
    emit(EVENT_GEOFENCE, event.toWritableMap())
  }

  override fun emitGeofencesChange(on: List<GeofenceRecord>, off: List<String>) {
    val payload =
      Arguments.createMap().apply {
        putArray("on", on.toWritableArray())
        putArray(
          "off",
          Arguments.createArray().apply { off.forEach { pushString(it) } },
        )
      }
    emit(EVENT_GEOFENCES_CHANGE, payload)
  }

  private fun emit(name: String, payload: WritableMap) {
    if (!hasReactInstance()) {
      Logger.d("emit($name) skipped: no active React instance")
      return
    }
    try {
      // Bridgeless-safe, and the reason this module's honest minimum is RN 0.74
      // (§17.3). On older hosts this one call becomes
      // `getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, payload)`.
      reactContext.emitDeviceEvent(name, payload)
    } catch (error: Throwable) {
      Logger.w("emit($name) failed", error)
    }
  }

  companion object {
    /** Namespaced so they cannot collide with another library's device events (§15.4). */
    const val EVENT_GEOFENCE = "RNGeofencing:geofence"
    const val EVENT_GEOFENCES_CHANGE = "RNGeofencing:geofencesChange"
  }
}
