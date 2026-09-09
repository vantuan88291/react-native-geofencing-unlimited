package com.rngeofencing.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The **read** side of the permission story (§6.7).
 *
 * Requesting is staged from the native module, because the only supported way to run
 * `requestPermissions` from a module is `PermissionAwareActivity`, which is a React
 * Native type and therefore not allowed in here. Reading a grant needs no React at
 * all, and the killed-app paths do need it — so it lives on this side of the seam.
 *
 * Nothing here is ever cached. The user can revoke in Settings while the app runs,
 * and Android 11+ auto-revokes permissions for unused apps, so `getState()` must
 * read the live grant every time (§6.7).
 */
object Permissions {

  /**
   * What geofencing actually needs, per API level (§6.7):
   *
   * | API      | Required for background delivery                       |
   * |----------|--------------------------------------------------------|
   * | ≤ 28     | `ACCESS_FINE_LOCATION` — background is implicit         |
   * | 29       | fine **+** background, requestable in one call          |
   * | 30+      | the same two, but in **separate** calls, foreground first |
   * | 31+      | coarse must be requested alongside fine                 |
   * | 33+      | `POST_NOTIFICATIONS`, only when `enableHeadless: true`  |
   */
  fun authorization(context: Context): String {
    val fine = hasFine(context)
    val coarse = hasCoarse(context)

    if (!fine) {
      // Geofencing requires **precise** location. An approximate-only grant is a
      // real grant the user made, but it is not sufficient, and reporting it as
      // success would be a lie (§6.7). `accuracyAuthorization` is what tells the
      // host which of the two happened.
      return if (coarse) "denied" else if (hasEverRequested(context)) "denied" else "notDetermined"
    }

    return if (hasBackground(context)) "always" else "whenInUse"
  }

  fun accuracyAuthorization(context: Context): String =
    if (hasFine(context)) "full" else "reduced"

  fun hasFine(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

  fun hasCoarse(context: Context): Boolean =
    granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

  /** `ACCESS_BACKGROUND_LOCATION` does not exist below API 29 — background is implicit there. */
  fun hasBackground(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      true
    } else {
      granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

  /** Only relevant when `enableHeadless: true`, and only on API 33+ (§6.7). */
  fun hasPostNotifications(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      true
    } else {
      granted(context, "android.permission.POST_NOTIFICATIONS")
    }

  private fun granted(context: Context, permission: String): Boolean =
    context.applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  /**
   * Android cannot distinguish "never asked" from "denied" without an Activity, so
   * the module records the fact that it has asked.
   *
   * Kept in its own small prefs file rather than in the blob store: it is a single
   * boolean that has no part in the all-or-nothing guarantee the blobs rely on
   * (§9.1).
   */
  fun markRequested(context: Context) {
    prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
  }

  fun hasEverRequested(context: Context): Boolean =
    prefs(context).getBoolean(KEY_REQUESTED, false)

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private const val PREFS_NAME = "rn_geofencing_permissions"
  private const val KEY_REQUESTED = "requested"
}
