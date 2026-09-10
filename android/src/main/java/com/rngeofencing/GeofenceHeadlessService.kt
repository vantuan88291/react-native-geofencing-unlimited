package com.rngeofencing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.rngeofencing.core.Core
import com.rngeofencing.core.Logger

/**
 * Runs JS from a killed app (§6.5) — opt-in via `enableHeadless: true`.
 *
 * This is the **only** notification this module ever shows: a low-importance one,
 * for the few seconds the task runs, per event. There is no always-on service and no
 * permanent notification (§1). With `enableHeadless: false` this service is never
 * started, and the `FOREGROUND_SERVICE_LOCATION` / `POST_NOTIFICATIONS` permissions
 * are never exercised — events simply queue and flush on the next launch, which is
 * the single biggest simplification available to a host app that can tolerate
 * delayed delivery.
 *
 * Never declare `android:process` on it (§9.4, invariant 8).
 */
class GeofenceHeadlessService : HeadlessJsTaskService() {

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must reach `startForeground` within 5 seconds of being started with
    // `startForegroundService`, or the system kills the process with an ANR.
    promoteToForeground()
    return super.onStartCommand(intent, flags, startId)
  }

  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
    val extras = intent?.extras ?: return null

    // Returning a non-null config is the point of no return: React Native starts the
    // task from here, so JS *will* see these events. They were queued before the
    // service was started so that a service which never starts loses nothing; now
    // that it has, they have to leave the queue or the next launch flushes them into
    // JS a second time (§6.5).
    Core.headlessTaskAccepted(
      applicationContext,
      extras.getString(Core.HEADLESS_EXTRA_QUEUE_KEYS),
    )

    return HeadlessJsTaskConfig(
      Core.HEADLESS_TASK_NAME,
      Arguments.fromBundle(extras),
      TASK_TIMEOUT_MS,
      // allowedInForeground: the app may be foregrounded between the broadcast and
      // this service starting, and refusing the task then would drop the event.
      true,
    )
  }

  private fun promoteToForeground() {
    try {
      ensureChannel()
      val notification =
        Notification.Builder(this, CHANNEL_ID)
          .setContentTitle("Updating location")
          .setSmallIcon(applicationInfo.icon)
          .setOngoing(true)
          .apply {
            // Pre-O only: from API 26 the channel's IMPORTANCE_MIN governs, and
            // Notification.Builder.setPriority is deprecated there.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
              @Suppress("DEPRECATION")
              setPriority(Notification.PRIORITY_MIN)
            }
          }
          .build()

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // API 34 requires the type to be declared at both the manifest and the call.
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (error: Throwable) {
      // A missing POST_NOTIFICATIONS grant or a background-start restriction must not
      // take the process down: the events are already persisted in the queue before
      // this service is ever started (§6.5).
      Logger.w("headless service could not enter the foreground; events stay queued", error)
      stopSelf()
    }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN).apply {
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
      }
    )
  }

  private companion object {
    const val CHANNEL_ID = "rn_geofencing"
    const val CHANNEL_NAME = "Location updates"
    const val NOTIFICATION_ID = 0x6E67 // "ng"
    const val TASK_TIMEOUT_MS = 30_000L
  }
}
