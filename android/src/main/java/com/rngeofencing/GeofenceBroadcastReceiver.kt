package com.rngeofencing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.rngeofencing.core.Core
import com.rngeofencing.core.GeofenceAction
import com.rngeofencing.core.Logger
import com.rngeofencing.core.toLatLng

/**
 * Where every Play Services geofence transition arrives (§6.4).
 *
 * This runs **with no React instance at all** when the app is killed, which is the
 * whole reason `Core` may not depend on React Native (§11, §15.6).
 *
 * Never declare `android:process` on this receiver: `SharedPreferences` is not
 * multi-process safe, and a second process would give two in-memory copies of the
 * store that silently overwrite each other (§9.4, invariant 8).
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return

    if (event.hasError()) {
      // Nothing actionable here, but the status string is what makes a field report
      // readable, so it is always logged (§8.4).
      Logger.w("geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
      return
    }

    val action =
      when (event.geofenceTransition) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceAction.ENTER
        Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceAction.EXIT
        Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceAction.DWELL
        else -> {
          Logger.w("geofence broadcast with unknown transition ${event.geofenceTransition}")
          return
        }
      }

    val ids = event.triggeringGeofences?.map { it.requestId }
    if (ids.isNullOrEmpty()) return

    val location = event.triggeringLocation
    // Android hands us the triggering location; iOS does not, which is the one real
    // behavioural difference in the public event (§7.3).
    val center = location?.toLatLng()
    val accuracy = location?.accuracy?.toDouble()

    // `goAsync()` buys roughly 10 seconds — enough for the store write and, usually,
    // a full rotation. A geofence broadcast also places the app on a temporary power
    // allowlist for about that long, which is what makes this and a short-lived
    // foreground service legal even in Doze (§6.4).
    val pending = goAsync()
    val appContext = context.applicationContext

    Core.executor.execute {
      try {
        Core.handleTransition(appContext, ids, action, center, accuracy)
      } catch (error: Throwable) {
        // A throw here would kill the receiver and lose the crossing silently.
        Logger.e("handleTransition failed for $ids/$action", error)
      } finally {
        pending.finish()
      }
    }
  }
}
