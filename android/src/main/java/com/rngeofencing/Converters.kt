package com.rngeofencing

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.rngeofencing.core.Config
import com.rngeofencing.core.GeofenceEvent
import com.rngeofencing.core.GeofenceRecord
import com.rngeofencing.core.State

/**
 * Marshalling between the bridge types and the core model.
 *
 * Deliberately in the React-aware layer, not in `core/`: `ReadableMap` is a React
 * Native type, and `core/` may not see one (§11, invariant 1).
 *
 * Every read is defaulted rather than assumed. `ReadableMap.getDouble` throws on a
 * missing key, and every field of `ConfigSpec` and most of `GeofenceSpec` are
 * optional (§8.1).
 */

private fun ReadableMap.optDouble(key: String, fallback: Double): Double =
  if (hasKey(key) && !isNull(key)) getDouble(key) else fallback

private fun ReadableMap.optInt(key: String, fallback: Int): Int =
  if (hasKey(key) && !isNull(key)) getDouble(key).toInt() else fallback

private fun ReadableMap.optBoolean(key: String, fallback: Boolean): Boolean =
  if (hasKey(key) && !isNull(key)) getBoolean(key) else fallback

private fun ReadableMap.optString(key: String): String? =
  if (hasKey(key) && !isNull(key) && getType(key) == ReadableType.String) getString(key) else null

fun ReadableMap.toConfig(): Config {
  val defaults = Config()
  return Config(
    proximityRadius = optDouble("proximityRadius", defaults.proximityRadius),
    initialTriggerEntry = optBoolean("initialTriggerEntry", defaults.initialTriggerEntry),
    notificationResponsiveness =
      optInt("notificationResponsiveness", defaults.notificationResponsiveness),
    useSignificantLocationChanges =
      optBoolean("useSignificantLocationChanges", defaults.useSignificantLocationChanges),
    enableHeadless = optBoolean("enableHeadless", defaults.enableHeadless),
    minRadius = optDouble("minRadius", defaults.minRadius),
    debug = optBoolean("debug", defaults.debug),
  )
}

/**
 * A geofence as JS described it.
 *
 * The gate state (`state`, `enteredAt`, `dwellEmitted`) and the `active` flag are
 * deliberately absent: they belong to the store, and an upsert must not reset them —
 * re-registering the same circle is not a reason to forget the device is already
 * inside it (§5.1).
 */
fun ReadableMap.toGeofenceRecord(): GeofenceRecord {
  val defaults = GeofenceRecord(id = "", latitude = 0.0, longitude = 0.0, radius = 0.0)
  return GeofenceRecord(
    id = optString("identifier") ?: "",
    latitude = optDouble("latitude", Double.NaN),
    longitude = optDouble("longitude", Double.NaN),
    radius = optDouble("radius", 0.0),
    notifyOnEntry = optBoolean("notifyOnEntry", true),
    notifyOnExit = optBoolean("notifyOnExit", true),
    notifyOnDwell = optBoolean("notifyOnDwell", false),
    loiteringDelay = optInt("loiteringDelay", defaults.loiteringDelay),
    // Already a JSON string on the wire (§8.1); stored verbatim and never parsed
    // natively.
    extras = optString("extras"),
  )
}

fun ReadableArray.toGeofenceRecords(): List<GeofenceRecord> =
  (0 until size()).mapNotNull { index ->
    if (getType(index) == ReadableType.Map) getMap(index)?.toGeofenceRecord() else null
  }

fun ReadableArray.toStringList(): List<String> =
  (0 until size()).mapNotNull { index ->
    if (getType(index) == ReadableType.String) getString(index) else null
  }

fun GeofenceRecord.toWritableMap(): WritableMap =
  Arguments.createMap().apply {
    putString("identifier", id)
    putDouble("latitude", latitude)
    putDouble("longitude", longitude)
    putDouble("radius", radius)
    putBoolean("notifyOnEntry", notifyOnEntry)
    putBoolean("notifyOnExit", notifyOnExit)
    putBoolean("notifyOnDwell", notifyOnDwell)
    putDouble("loiteringDelay", loiteringDelay.toDouble())
    if (extras != null) putString("extras", extras) else putNull("extras")
  }

fun List<GeofenceRecord>.toWritableArray(): WritableArray =
  Arguments.createArray().apply { this@toWritableArray.forEach { pushMap(it.toWritableMap()) } }

fun GeofenceEvent.toWritableMap(): WritableMap =
  Arguments.createMap().apply {
    putString("identifier", id)
    putString("action", action.wire)
    putDouble("timestamp", timestamp.toDouble())
    if (latitude != null) putDouble("latitude", latitude) else putNull("latitude")
    if (longitude != null) putDouble("longitude", longitude) else putNull("longitude")
    if (accuracy != null) putDouble("accuracy", accuracy) else putNull("accuracy")
    putBoolean("approximate", approximate)
    putBoolean("synthetic", synthetic)
    if (extras != null) putString("extras", extras) else putNull("extras")
  }

fun State.toWritableMap(): WritableMap =
  Arguments.createMap().apply {
    putBoolean("enabled", enabled)
    putBoolean("available", available)
    putString("authorization", authorization)
    putString("accuracyAuthorization", accuracyAuthorization)
    putDouble("geofenceCount", geofenceCount.toDouble())
    putDouble("activeCount", activeCount.toDouble())
    putBoolean("batteryOptimized", batteryOptimized)
    putBoolean("locationServicesEnabled", locationServicesEnabled)
    if (rotationCenterLatitude != null) {
      putDouble("rotationCenterLatitude", rotationCenterLatitude)
    } else {
      putNull("rotationCenterLatitude")
    }
    if (rotationCenterLongitude != null) {
      putDouble("rotationCenterLongitude", rotationCenterLongitude)
    } else {
      putNull("rotationCenterLongitude")
    }
    if (rotationCenterAt != null) {
      putDouble("rotationCenterAt", rotationCenterAt.toDouble())
    } else {
      putNull("rotationCenterAt")
    }
    if (boundaryRadius != null) putDouble("boundaryRadius", boundaryRadius) else putNull("boundaryRadius")
    putDouble("droppedEventCount", droppedEventCount.toDouble())
  }
