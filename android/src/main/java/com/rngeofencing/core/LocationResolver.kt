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

    lastKnown()?.let {
      Logger.d("center: using last known location")
      return it
    }

    oneShot()?.let {
      Logger.d("center: using a one-shot fix")
      return it
    }

    Logger.w("center: unresolved — keeping the current active set and retrying on the next trigger")
    return null
  }

  private fun lastKnown(): LatLng? =
    try {
      val location =
        Tasks.await(client.lastLocation, LAST_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      when {
        location == null -> null
        // A cached fix can be arbitrarily old. Rotating around one is as damaging as
        // rotating around no centre at all (invariant 4), so it is rejected here and
        // the caller falls through to a one-shot fix.
        !isFixUsable(
          location.toLatLng(),
          ageMs = System.currentTimeMillis() - location.time,
          accuracyMetres = location.accuracy.toDouble(),
        ) -> {
          Logger.d(
            "center: ignoring last known fix — " +
              "${(System.currentTimeMillis() - location.time) / 1000}s old, " +
              "accuracy ${location.accuracy}m"
          )
          null
        }
        else -> location.toLatLng()
      }
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
    const val ONE_SHOT_TIMEOUT_SECONDS = 10L
  }
}

fun Location.toLatLng(): LatLng = LatLng(latitude, longitude)
