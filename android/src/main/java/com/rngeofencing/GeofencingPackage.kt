package com.rngeofencing

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * `BaseReactPackage`, not `ReactPackage` (§15.3).
 *
 * The lazy form is what lets the module be a real TurboModule under the New
 * Architecture while still resolving through `NativeModules` on the legacy bridge.
 *
 * `needsEagerInit = false` does **not** weaken killed-app delivery. Nothing waits for
 * this module: `GeofenceBroadcastReceiver` and `BootReceiver` construct `Core`
 * directly (§15.6). If eager init ever looks like it would "make geofences work", the
 * core has grown a React dependency — fix that instead.
 */
class GeofencingPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == GeofencingModule.NAME) GeofencingModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      GeofencingModule.NAME to
        ReactModuleInfo(
          name = GeofencingModule.NAME,
          className = GeofencingModule.NAME,
          canOverrideExistingModule = false,
          needsEagerInit = false,
          isCxxModule = false,
          // From `buildConfigField` in android/build.gradle, which reads
          // `rootProject`'s newArchEnabled — the app's, not the library's (§15.3).
          isTurboModule = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
        )
    )
  }
}
