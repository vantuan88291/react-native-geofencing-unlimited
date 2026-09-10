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

/**
 * The oldest a cached fix may be before it is worth spending a one-shot request to
 * replace it.
 *
 * Ten minutes, not two. This only decides whether to *ask* for something better — and
 * asking is expensive: the request blocks the single-threaded executor that also
 * handles geofence transitions, and a broadcast receiver has roughly ten seconds
 * before the process is frozen. Stalling there can cost a real crossing.
 *
 * Ten minutes is comfortably safe for what the centre is used for: picking the nearest
 * N geofences within a 2 km proximity radius. Someone walking covers under a kilometre
 * in that time, well inside the boundary that would have triggered a fresh rotation
 * anyway — and any rotation caused by an actual crossing arrives with the OS's own
 * location attached.
 *
 * It is deliberately *not* the defence against a centre in the wrong place; that is
 * handled by preferring the freshest of the available fixes.
 */
const val MAX_FIX_AGE_MS = 10 * 60 * 1000L

/**
 * Whether a location may be used as a rotation centre (invariant 4).
 *
 * Rejecting a *stale* fix matters as much as rejecting a missing one, and it is the
 * easier mistake to make. Both platforms hand out cached positions eagerly — iOS
 * delivers a saved fix the instant significant-location-change monitoring starts, and
 * it can be hours old and kilometres away. Rotating on one swaps the whole active set
 * to somewhere the user is not; the next rotation swaps it back, and the host app sees
 * a burst of synthetic EXITs followed by re-ENTERs for regions that were never left.
 *
 * @param ageMs how long ago the fix was taken. Negative (a clock skew) is treated as fresh.
 * @param accuracyMetres horizontal accuracy; negative means the OS considers it invalid.
 */
fun isFixUsable(
  center: LatLng,
  ageMs: Long,
  accuracyMetres: Double?,
  maxAgeMs: Long = MAX_FIX_AGE_MS,
): Boolean {
  if (!center.isValid) return false
  if (ageMs > maxAgeMs) return false
  if (accuracyMetres != null && accuracyMetres < 0.0) return false
  return true
}
