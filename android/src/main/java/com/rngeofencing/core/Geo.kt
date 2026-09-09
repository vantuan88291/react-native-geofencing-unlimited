package com.rngeofencing.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius in metres. */
private const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Great-circle distance in metres.
 *
 * The haversine form (rather than the algebraically equivalent law-of-cosines form)
 * because it stays accurate at the small distances this module actually deals in,
 * where `acos` of a value very close to 1 loses most of its precision.
 */
fun haversine(from: LatLng, to: LatLng): Double {
  val lat1 = Math.toRadians(from.latitude)
  val lat2 = Math.toRadians(to.latitude)
  val dLat = lat2 - lat1
  val dLng = Math.toRadians(to.longitude - from.longitude)

  val sinHalfDLat = sin(dLat / 2.0)
  val sinHalfDLng = sin(dLng / 2.0)
  val a = sinHalfDLat * sinHalfDLat + cos(lat1) * cos(lat2) * sinHalfDLng * sinHalfDLng

  // asin(sqrt(a)) rather than atan2: `a` is clamped below, so this cannot NaN, and
  // it is the cheaper of the two.
  return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

fun haversine(from: LatLng, to: GeofenceRecord): Double = haversine(from, to.center)

fun clamp(value: Double, low: Double, high: Double): Double =
  when {
    // A degenerate range (low > high) resolves to `high`, which is the conservative
    // choice here: it is the proximity radius, never something larger.
    low > high -> high
    value < low -> low
    value > high -> high
    else -> value
  }
