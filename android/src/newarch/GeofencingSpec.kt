package com.rngeofencing

import com.facebook.react.bridge.ReactApplicationContext

/**
 * New Architecture adapter (§15.3).
 *
 * `NativeGeofencingSpec` is emitted by the **consuming app's** codegen from
 * `src/NativeGeofencing.ts` and reaches the compile path through
 * `apply plugin: "com.facebook.react"` plus the `react { }` block in
 * `android/build.gradle` — this package ships no generated code (§11).
 *
 * Only the abstract spec class is split per architecture. There is exactly one
 * `GeofencingModule`, in `src/main`, extending whichever of these two is compiled.
 * Duplicating the module body across `newarch/` and `oldarch/` is the obvious first
 * instinct — because `@ReactMethod` lives on the legacy side — and it means every
 * method of §8.1 gets written twice and then drifts.
 */
abstract class GeofencingSpec internal constructor(context: ReactApplicationContext) :
  NativeGeofencingSpec(context)
