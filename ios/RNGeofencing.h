#import <React/RCTEventEmitter.h>

/**
 * The module surface (§15.4).
 *
 * `RCTEventEmitter`, not codegen's `EventEmitter<T>`: codegen events exist only under
 * the New Architecture, and `RCTEventEmitter` works under both. One code path, at the
 * cost of losing codegen type-checking on the payload — which the hand-written JS
 * wrapper recovers, since that is where the `extras` JSON is parsed anyway.
 *
 * The `__has_include` half of this guard is not belt-and-braces. The generated header
 * is produced by the **app's** codegen (§11), into the app's build tree: on a legacy
 * build it is never generated at all, and `#ifdef RCT_NEW_ARCH_ENABLED` alone would
 * fail at the `#import` with a missing-header error instead of compiling the legacy
 * path.
 */
#if defined(RCT_NEW_ARCH_ENABLED) && __has_include(<RNGeofencingSpec/RNGeofencingSpec.h>)
#import <RNGeofencingSpec/RNGeofencingSpec.h>
@interface RNGeofencing : RCTEventEmitter <NativeGeofencingSpec>
#else
#import <React/RCTBridgeModule.h>
@interface RNGeofencing : RCTEventEmitter <RCTBridgeModule>
#endif

@end
