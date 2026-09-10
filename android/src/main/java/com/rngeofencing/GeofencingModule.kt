package com.rngeofencing

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.rngeofencing.core.Core
import com.rngeofencing.core.GeofencingException
import com.rngeofencing.core.Logger
import com.rngeofencing.core.Permissions

/**
 * The **only** module implementation — a read-and-forward adapter over [Core]
 * (§15.3, §15.6).
 *
 * `@ReactMethod` on an `override` is harmless under the New Architecture (the
 * annotation is ignored; codegen drives the binding) and mandatory under the legacy
 * bridge. That is what lets one implementation serve both.
 *
 * Every call hops onto [Core.executor] before touching anything, which is what
 * serialises it against a rotation that may already be running from a broadcast
 * receiver (§4.5, invariant 2). Note that `Core` is handed `applicationContext`, never
 * the `ReactApplicationContext` — §15.6 is enforced by the signature.
 */
@ReactModule(name = GeofencingModule.NAME)
class GeofencingModule internal constructor(private val reactContext: ReactApplicationContext) :
  GeofencingSpec(reactContext), LifecycleEventListener {

  private val delivery = ReactEventDelivery(reactContext)

  /** One permission request at a time (§6.7). */
  private var pendingPermission: Promise? = null

  /**
   * Copy for the background-location dialog, or `null` when the host suppressed it.
   *
   * Captured from `ready()` on the calling thread rather than read back from the
   * store, because the dialog is raised from the permission callback on the main
   * thread and must not wait on the core's executor.
   */
  private var backgroundRationale: Rationale? = Rationale()

  /** Guards against the dialog reappearing when the system request it starts returns. */
  private var rationaleShown = false

  private data class Rationale(
    val title: String = "Background location needed",
    val message: String =
      "This app only detects arrivals and departures while it is closed if location " +
        "is set to \"Allow all the time\".",
    val positive: String = "Open settings",
    val negative: String = "Not now",
  )

  init {
    Core.attachDelivery(delivery)
    reactContext.addLifecycleEventListener(this)
  }

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  override fun onHostResume() {
    // A cheap correction: on foreground the last known location is fresh, and the
    // user may have granted a permission or re-enabled location while we were away
    // (§4.5).
    Core.executor.execute {
      runCatching { Core.onAppForeground(reactContext.applicationContext) }
        .onFailure { Logger.w("foreground rotation failed", it) }
    }
  }

  override fun onHostPause() = Unit

  override fun onHostDestroy() = Unit

  override fun invalidate() {
    super.invalidate()
    reactContext.removeLifecycleEventListener(this)
    // Detach the adapter; never shut Core down. It outlives the module (§15.6).
    Core.detachDelivery()
  }

  // -------------------------------------------------------------------------
  // §8.1 surface
  // -------------------------------------------------------------------------

  @ReactMethod
  override fun ready(config: ReadableMap, promise: Promise) {
    // Read on the calling thread: a ReadableMap is not guaranteed to outlive this call.
    backgroundRationale = config.readRationale()
    resolveOnExecutor(promise) { Core.ready(appContext(), config.toConfig()).toWritableMap() }
  }

  private fun ReadableMap.readRationale(): Rationale? {
    val key = "androidBackgroundPermissionRationale"
    if (!hasKey(key) || isNull(key)) return Rationale()
    return when (getType(key)) {
      // `false` suppresses the dialog entirely; the host drives the flow itself.
      ReadableType.Boolean -> if (getBoolean(key)) Rationale() else null
      ReadableType.Map -> {
        val map = getMap(key) ?: return Rationale()
        val defaults = Rationale()
        Rationale(
          title = map.optString("title") ?: defaults.title,
          message = map.optString("message") ?: defaults.message,
          positive = map.optString("positiveButton") ?: defaults.positive,
          negative = map.optString("negativeButton") ?: defaults.negative,
        )
      }
      else -> Rationale()
    }
  }

  private fun ReadableMap.optString(key: String): String? =
    if (hasKey(key) && !isNull(key) && getType(key) == ReadableType.String) {
      getString(key)?.takeIf { it.isNotBlank() }
    } else {
      null
    }

  @ReactMethod
  override fun start(promise: Promise) {
    resolveOnExecutor(promise) {
      Core.start(appContext())
      null
    }
  }

  @ReactMethod
  override fun stop(promise: Promise) {
    resolveOnExecutor(promise) {
      Core.stop(appContext())
      null
    }
  }

  @ReactMethod
  override fun addGeofence(geofence: ReadableMap, promise: Promise) {
    val record = geofence.toGeofenceRecord()
    resolveOnExecutor(promise) {
      Core.addGeofences(appContext(), listOf(record))
      null
    }
  }

  @ReactMethod
  override fun addGeofences(geofences: ReadableArray, promise: Promise) {
    // Read off the bridge on the calling thread: a ReadableArray is not guaranteed
    // to outlive this call once we hand control to another thread.
    val records = geofences.toGeofenceRecords()
    resolveOnExecutor(promise) {
      Core.addGeofences(appContext(), records)
      null
    }
  }

  @ReactMethod
  override fun removeGeofence(identifier: String, promise: Promise) {
    resolveOnExecutor(promise) {
      Core.removeGeofences(appContext(), listOf(identifier))
      null
    }
  }

  @ReactMethod
  override fun removeGeofences(identifiers: ReadableArray?, promise: Promise) {
    // `null` means "remove everything" (§8.1); an empty array means "remove nothing"
    // and must resolve rather than reject (§8.4).
    val ids = identifiers?.toStringList()
    resolveOnExecutor(promise) {
      Core.removeGeofences(appContext(), ids)
      null
    }
  }

  @ReactMethod
  override fun getGeofences(promise: Promise) {
    resolveOnExecutor(promise) { Core.getGeofences(appContext()).toWritableArray() }
  }

  @ReactMethod
  override fun getActiveGeofences(promise: Promise) {
    resolveOnExecutor(promise) {
      val ids = Core.getActiveIds(appContext())
      Arguments.createArray().apply { ids.forEach { pushString(it) } }
    }
  }

  @ReactMethod
  override fun getState(promise: Promise) {
    resolveOnExecutor(promise) { Core.getState(appContext()).toWritableMap() }
  }

  @ReactMethod
  override fun flushQueue(promise: Promise) {
    resolveOnExecutor(promise) {
      val events = Core.flushQueue(appContext())
      Arguments.createArray().apply {
        events.forEach { pushMap(it.toWritableMap()) }
      }
    }
  }

  /**
   * Recent native log lines (§14).
   *
   * The rotation trace this returns is produced during process wakes — a broadcast
   * receiver, or the launch re-arm — which happen *before* JS is running. Buffering
   * natively and letting JS pull the lines is the only way to see them without
   * attaching `logcat`.
   */
  @ReactMethod
  override fun getDebugLog(promise: Promise) {
    resolveOnExecutor(promise) {
      val lines = Logger.snapshot()
      Arguments.createArray().apply { lines.forEach { pushString(it) } }
    }
  }

  @ReactMethod
  override fun openSettings(promise: Promise) {
    // Exposed as its own method rather than navigated automatically: on API 30+ this
    // is the only remaining path to background location once the system has stopped
    // showing the dialog, but the host app owns that UX decision (§6.7).
    try {
      openAppSettings()
      promise.resolve(null)
    } catch (error: Throwable) {
      promise.reject("E_INTERNAL", "could not open app settings: ${error.message}", error)
    }
  }

  /**
   * Sends the user to the screen where background location can actually be enabled.
   *
   * On API 30+ the system's own `requestPermissions` navigation lands directly on the
   * app's **location permission** page, with "Allow all the time" right there — far
   * better than the app-details page, which buries it three taps deep under
   * Permissions › Location. That navigation is the only public way to reach it, so it
   * is used for exactly that, after the user has been told why.
   *
   * Everywhere else, and if that call fails, the app-details page is the fallback.
   */
  private fun openBackgroundPermissionScreen(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity is PermissionAwareActivity) {
      try {
        activity.requestPermissions(
          arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
          RC_BACKGROUND,
          listener,
        )
        // The promise settles in the RC_BACKGROUND callback, once the user returns.
        return
      } catch (error: Throwable) {
        Logger.w("could not open the location permission screen, falling back", error)
      }
    }

    runCatching { openAppSettings() }
      .onFailure { Logger.w("could not open app settings", it) }
    finishPermissionRequest()
  }

  private fun openAppSettings() {
    val intent =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", reactContext.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    reactContext.startActivity(intent)
  }

  @ReactMethod override fun addListener(eventName: String) {
    delivery.onListenerAdded()
  }

  @ReactMethod override fun removeListeners(count: Double) {
    delivery.onListenersRemoved(count.toInt())
  }

  // -------------------------------------------------------------------------
  // Runtime permissions (§6.7)
  //
  // The single most version-dependent part of the module. Get the staging wrong and
  // background delivery is silently absent on a subset of devices.
  // -------------------------------------------------------------------------

  @ReactMethod
  override fun requestPermission(promise: Promise) {
    if (pendingPermission != null) {
      promise.reject("E_REQUEST_IN_FLIGHT", "A permission request is already running")
      return
    }

    val activity = reactContext.currentActivity
    if (activity !is PermissionAwareActivity) {
      // Null whenever the app has no foreground UI — a background relaunch, or the
      // headless path. There is no way to prompt from there, so report it rather
      // than queueing a dialog nobody will see.
      promise.reject("E_NO_ACTIVITY", "requestPermission() needs a foreground activity")
      return
    }

    pendingPermission = promise
    rationaleShown = false
    Permissions.markRequested(reactContext)
    requestForeground(activity)
  }

  private fun requestForeground(activity: PermissionAwareActivity) {
    if (Permissions.hasFine(reactContext)) {
      requestBackgroundIfNeeded(activity)
      return
    }

    val perms =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+: coarse must be requested alongside fine, or the dialog offers no
        // precise option at all. The user can still grant approximate only, which is
        // **not** sufficient for geofencing — `getState()` reports that as
        // insufficient rather than as success.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
      } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }

    // API 29 only: foreground and background may go in a single dialog
    // ("Allow all the time"). From API 30 they must be separate calls.
    val all =
      if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
        perms + Manifest.permission.ACCESS_BACKGROUND_LOCATION
      } else {
        perms
      }

    activity.requestPermissions(all, RC_FOREGROUND, listener)
  }

  /**
   * @return `true` when a second request is now in flight, so the caller knows the
   *   listener must stay registered.
   */
  private fun requestBackgroundIfNeeded(activity: PermissionAwareActivity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Permissions.hasBackground(reactContext)) {
      finishPermissionRequest()
      return false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // **API 30+ has no dialog for this, only navigation.**
      //
      // `requestPermissions(ACCESS_BACKGROUND_LOCATION)` does not prompt here — it
      // sends the user straight to the app's *location permission* page, which is
      // exactly where they need to be, but with no word about why. So the reason is
      // given first, in a dialog, and the system call is made only if they accept —
      // which is also what Play Store policy asks for (§6.7).
      if (!maybeShowBackgroundRationale(activity as Activity)) {
        finishPermissionRequest()
      }
      // The dialog re-registers the listener if the user accepts; nothing is in flight
      // until then.
      return false
    }

    // API 29 is the one version where the system dialog really does offer "Allow all
    // the time", so let it ask.
    activity.requestPermissions(
      arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
      RC_BACKGROUND,
      listener,
    )
    return true
  }

  /**
   * The return value is load-bearing, and not in the obvious direction.
   *
   * `ReactActivityDelegate` clears its stored listener the moment one returns `true`:
   *
   * ```java
   * if (mPermissionListener != null && mPermissionListener.onRequestPermissionsResult(...)) {
   *   mPermissionListener = null;
   * }
   * ```
   *
   * The background request is chained from *inside* this callback, which re-registers
   * this same listener — so returning `true` afterwards wipes the registration that
   * was just made, the background result is delivered to nobody, and the promise from
   * `requestPermission()` never settles. Returning `false` while a request is still in
   * flight is what keeps the chain alive.
   */
  private val listener =
    PermissionListener { requestCode, _, _ ->
      when (requestCode) {
        RC_FOREGROUND -> {
          // Never request background before foreground is granted: on API 30+ the
          // call is wasted, and it is the one background request the system honours.
          val activity = reactContext.currentActivity
          val chained =
            if (Permissions.hasFine(reactContext) && activity is PermissionAwareActivity) {
              requestBackgroundIfNeeded(activity)
            } else {
              // Denied. This resolves with the resulting state — a denial is a result,
              // not an error (§8.4).
              finishPermissionRequest()
              false
            }
          // Keep the listener registered exactly while a request is outstanding.
          !chained
        }
        RC_BACKGROUND -> {
          // The system request has come back. If background is still ungranted, this
          // is the point where iOS would have raised its own second dialog and
          // Android raises nothing — so the module raises one (§6.7, and see
          // `backgroundRationale`).
          val activity = reactContext.currentActivity
          val shown =
            !rationaleShown && activity != null && maybeShowBackgroundRationale(activity)
          if (!shown) {
            finishPermissionRequest()
          }
          true
        }
        else -> false
      }
    }

  /**
   * Offers the Settings page when the system has nothing left to ask.
   *
   * From API 30 the "Allow all the time" option is deliberately absent from the system
   * dialog, so a user who wants background location can only enable it in Settings —
   * and nothing in the platform tells them that. Without this dialog the flow simply
   * stops, which reads as the feature being broken.
   *
   * Play Store policy expects the reason to be stated *before* the user is sent to
   * Settings, which is exactly what this dialog does. Hosts that would rather word it
   * themselves pass `androidBackgroundPermissionRationale: false` and drive the flow
   * with `getState()` + `openSettings()`.
   *
   * @return `true` when a dialog was shown, in which case it owns settling the promise.
   */
  private fun maybeShowBackgroundRationale(activity: Activity): Boolean {
    val rationale = backgroundRationale ?: return false
    if (Permissions.hasBackground(reactContext)) return false
    if (activity.isFinishing || activity.isDestroyed) return false

    rationaleShown = true
    activity.runOnUiThread {
      try {
        AlertDialog.Builder(activity)
          .setTitle(rationale.title)
          .setMessage(rationale.message)
          .setPositiveButton(rationale.positive) { _, _ -> openBackgroundPermissionScreen(activity) }
          .setNegativeButton(rationale.negative) { _, _ -> finishPermissionRequest() }
          // Back button or a tap outside must settle the promise too, or the caller
          // waits forever.
          .setOnCancelListener { finishPermissionRequest() }
          .show()
      } catch (error: Throwable) {
        // A window-token failure during a configuration change must not strand the
        // promise.
        Logger.w("background rationale dialog could not be shown", error)
        finishPermissionRequest()
      }
    }
    return true
  }

  private fun finishPermissionRequest() {
    val promise = pendingPermission ?: return
    pendingPermission = null
    resolveOnExecutor(promise) { Core.getState(appContext()).toWritableMap() }
  }

  // -------------------------------------------------------------------------

  private fun appContext() = reactContext.applicationContext

  /**
   * Runs [block] on the single-threaded core executor and settles [promise] with the
   * result.
   *
   * [GeofencingException] carries the §8.4 code; anything else is `E_INTERNAL` with
   * the original message attached, because an unclassified rejection with no message
   * is what makes a field report useless.
   */
  private fun resolveOnExecutor(promise: Promise, block: () -> Any?) {
    Core.executor.execute {
      try {
        promise.resolve(block())
      } catch (error: GeofencingException) {
        promise.reject(error.code, error.message, error)
      } catch (error: Throwable) {
        Logger.e("unhandled error in a module method", error)
        promise.reject("E_INTERNAL", error.message ?: error::class.java.simpleName, error)
      }
    }
  }

  companion object {
    const val NAME = "RNGeofencing"

    private const val RC_FOREGROUND = 0xF0F0
    private const val RC_BACKGROUND = 0xF0F1
  }
}
