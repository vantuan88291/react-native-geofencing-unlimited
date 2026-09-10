package com.rngeofencing.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Resolves the centre a rotation is computed around (§4.7).
 *
 * The order matters, and so does the last step: **if no centre can be resolved, do
 * not rotate.** Keep the current set and retry on the next trigger. Rotating around
 * a bogus centre arms the wrong regions everywhere, which is strictly worse than
 * being stale (invariant 4).
 */
class LocationResolver(context: Context) {

  private val appContext: Context = context.applicationContext

  private val client: FusedLocationProviderClient by lazy {
    LocationServices.getFusedLocationProviderClient(appContext)
  }

  /**
   * @param hint the location carried by the triggering geofence event. Android
   *   provides one; iOS does not (§7.3). Always preferred when present — it is both
   *   free and the most relevant fix there is.
   */
  fun resolve(hint: LatLng?): LatLng? {
    if (hint != null && hint.isValid) {
      Logger.d("center: using triggering event location")
      return hint
    }

    if (!hasLocationPermission()) {
      Logger.w("center: no location permission, cannot resolve a rotation centre")
      return null
    }

    val cached = cachedFix()
    val cachedPoint = cached?.toLatLng()?.takeIf { it.isValid }
    val cachedAgeMs = cached?.let { System.currentTimeMillis() - it.time }

    if (cached != null && cachedPoint != null && cachedAgeMs != null &&
      isFixUsable(cachedPoint, cachedAgeMs, cached.accuracy.toDouble())
    ) {
      Logger.d("center: using last known location")
      return cachedPoint
    }

    oneShot()?.let {
      Logger.d("center: using a one-shot fix")
      return it
    }

    // **A stale fix still beats not rotating at all.**
    //
    // Freshness is a preference, not a veto. A device that has not moved has a fix
    // that is old and exactly right, and there is no reason to ask for a new one —
    // which on a stationary device may never arrive, because nothing is producing
    // location updates. Refusing to rotate then strands the whole active set, which is
    // a far worse failure than the slightly-stale centre invariant 4 warns about; that
    // warning is about a centre in the *wrong place*, and age alone does not make one.
    if (cachedPoint != null) {
      Logger.w(
        "center: no fresh fix available, falling back to one ${(cachedAgeMs ?: 0) / 1000}s old " +
          "— stale beats not rotating"
      )
      return cachedPoint
    }

    Logger.w("center: unresolved — keeping the current active set and retrying on the next trigger")
    return null
  }

  /** The provider's cached fix, unfiltered — [resolve] decides what to do with it. */
  private fun cachedFix(): Location? =
    try {
      Tasks.await(client.lastLocation, LAST_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (error: Throwable) {
      Logger.d("center: getLastLocation failed — ${error.message}")
      null
    }

  /**
   * A single fix at `balanced` accuracy with a short deadline.
   *
   * Deliberately not high accuracy: this only needs to be good enough to pick the
   * nearest ~99 geofences out of the store, and a GPS-grade fix would cost seconds we
   * may not have inside a receiver's window.
   */
  private fun oneShot(): LatLng? =
    try {
      val request =
        CurrentLocationRequest.Builder()
          .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
          .setDurationMillis(ONE_SHOT_TIMEOUT_SECONDS * 1000)
          .setMaxUpdateAgeMillis(MAX_FIX_AGE_MS)
          .build()
      Tasks.await(
          client.getCurrentLocation(request, null),
          ONE_SHOT_TIMEOUT_SECONDS,
          TimeUnit.SECONDS,
        )
        ?.toLatLng()
    } catch (error: Throwable) {
      Logger.d("center: getCurrentLocation failed — ${error.message}")
      null
    }

  fun hasLocationPermission(): Boolean =
    appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  /** Airplane mode or a disabled provider means no events at all (§10). */
  fun locationServicesEnabled(): Boolean {
    val manager =
      appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      manager.isLocationEnabled
    } else {
      // `isLocationEnabled` is API 28+; below that, either provider being on is
      // enough for Play Services geofencing.
      manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
  }

  private companion object {
    const val LAST_LOCATION_TIMEOUT_SECONDS = 5L

    /**
     * Deliberately short. This blocks the single-threaded executor that also handles
     * geofence transitions, and a broadcast receiver has only about ten seconds before
     * the process is frozen — so a long wait here can cost a real crossing. If no fix
     * arrives in this window, the cached one is used instead, which is almost always
     * good enough for choosing the nearest N geofences.
     */
    const val ONE_SHOT_TIMEOUT_SECONDS = 4L
  }
}

fun Location.toLatLng(): LatLng = LatLng(latitude, longitude)
