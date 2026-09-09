package com.rngeofencing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.rngeofencing.core.Core
import com.rngeofencing.core.Logger

/**
 * Re-arms the set after the events that silently destroy OS registrations (§6.6).
 *
 * | Cause                        | Handled by                                  |
 * |------------------------------|---------------------------------------------|
 * | Device reboot                | `BOOT_COMPLETED`                            |
 * | App updated / reinstalled    | `MY_PACKAGE_REPLACED`                       |
 * | Location services re-enabled | `PROVIDERS_CHANGED`                         |
 * | User force-stops the app     | nothing runs — recovered on next manual launch |
 *
 * **No `LOCKED_BOOT_COMPLETED`.** It fires before the first unlock, when the
 * credential-encrypted `SharedPreferences` this module uses cannot be read at all, so
 * a handler there could do nothing useful (§9.7). Geofences therefore stay
 * unregistered between a reboot and the first unlock — which is acceptable, because
 * Play Services geofencing is not reliably available before unlock either. Do not
 * reach for `createDeviceProtectedStorageContext()` to close a window that is minutes
 * long once per reboot; it would mean a second store to keep in sync and a real risk
 * of the two diverging.
 */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    Logger.d("BootReceiver: $action")

    val pending = goAsync()
    val appContext = context.applicationContext

    Core.executor.execute {
      try {
        when (action) {
          Intent.ACTION_BOOT_COMPLETED,
          Intent.ACTION_MY_PACKAGE_REPLACED -> Core.onBootCompleted(appContext)
          LocationManager.PROVIDERS_CHANGED_ACTION -> Core.onProvidersChanged(appContext)
          else -> Logger.d("BootReceiver: ignoring $action")
        }
      } catch (error: Throwable) {
        Logger.e("re-arm after $action failed", error)
      } finally {
        pending.finish()
      }
    }
  }
}
