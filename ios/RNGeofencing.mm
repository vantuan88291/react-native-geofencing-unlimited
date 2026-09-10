#import "RNGeofencing.h"
#import <UIKit/UIKit.h>
#import "RNGeofencingCore.h"
#import "RNGeofencingLogger.h"

/// Namespaced so they cannot collide with another library's device events (§15.4).
static NSString *const RNGeofencingEventGeofence = @"RNGeofencing:geofence";
static NSString *const RNGeofencingEventGeofencesChange = @"RNGeofencing:geofencesChange";

@interface RNGeofencing () <RNGeofencingEventDelegate>
@end

@implementation RNGeofencing {
  BOOL _observing;
}

/**
 * Registers the name `RNGeofencing`, identically to `getName()` on Android and to the
 * string in `TurboModuleRegistry.get('RNGeofencing')` (§15.2). A mismatch means one
 * architecture resolves the module and the other silently falls into the JS
 * linking-error proxy.
 */
RCT_EXPORT_MODULE(RNGeofencing)

/**
 * `NO` deliberately.
 *
 * The Core does not need the main thread to be constructed, and it is already alive
 * long before this module is: it is built during launch, from `+load` (§7.5,
 * mechanism C of §18.3). This module is only ever a read-and-forward adapter over it
 * (§15.6).
 */
+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (instancetype)init {
  if (self = [super init]) {
    _observing = NO;
    // Attaching, not creating: `sharedInstance` was constructed at launch. If this is
    // the call that first constructs it, the app was not relaunched by a location
    // event and nothing has been missed.
    RNGeofencingCore.sharedInstance.eventDelegate = self;
  }
  return self;
}

- (void)invalidate {
  // Detach the adapter; never shut the Core down. It outlives this module (§15.6).
  if (RNGeofencingCore.sharedInstance.eventDelegate == self) {
    RNGeofencingCore.sharedInstance.eventDelegate = nil;
  }
  _observing = NO;
  [super invalidate];
}

#pragma mark - Events

- (NSArray<NSString *> *)supportedEvents {
  return @[ RNGeofencingEventGeofence, RNGeofencingEventGeofencesChange ];
}

/**
 * Not optional bookkeeping — this pair is the hook that decides whether an event is
 * **sent or queued** (§15.4).
 *
 * `sendEventWithName:` with no listeners logs a warning and drops the event, and on
 * this module a dropped event is a lost crossing. So `startObserving` is also what
 * flushes the queue (§6.5).
 */
- (void)startObserving {
  _observing = YES;
  [RNGeofencingCore.sharedInstance jsStartedObserving];
}

- (void)stopObserving {
  _observing = NO;
}

- (BOOL)isObserving {
  return _observing;
}

- (void)emitGeofenceEvent:(NSDictionary *)payload {
  if (!_observing) {
    return;
  }
  [self sendEventWithName:RNGeofencingEventGeofence body:payload];
}

- (void)emitGeofencesChange:(NSDictionary *)payload {
  if (!_observing) {
    return;
  }
  [self sendEventWithName:RNGeofencingEventGeofencesChange body:payload];
}

#pragma mark - §8.1 surface

RCT_EXPORT_METHOD(ready
                  : (NSDictionary *)config resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance readyWithConfig:config
                                        completion:^(NSDictionary *state) { resolve(state); }];
}

RCT_EXPORT_METHOD(start : (RCTPromiseResolveBlock)resolve reject : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance startWithCompletion:^(NSError *error) {
    [self settle:resolve reject:reject error:error value:nil];
  }];
}

RCT_EXPORT_METHOD(stop : (RCTPromiseResolveBlock)resolve reject : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance stopWithCompletion:^{ resolve(nil); }];
}

RCT_EXPORT_METHOD(addGeofence
                  : (NSDictionary *)geofence resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance addGeofences:@[ geofence ]
                                     completion:^(NSError *error) {
                                       [self settle:resolve reject:reject error:error value:nil];
                                     }];
}

RCT_EXPORT_METHOD(addGeofences
                  : (NSArray *)geofences resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance addGeofences:geofences
                                     completion:^(NSError *error) {
                                       [self settle:resolve reject:reject error:error value:nil];
                                     }];
}

RCT_EXPORT_METHOD(removeGeofence
                  : (NSString *)identifier resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance removeGeofences:@[ identifier ]
                                        completion:^{ resolve(nil); }];
}

RCT_EXPORT_METHOD(removeGeofences
                  : (NSArray *)identifiers resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  // `nil` means "remove everything" (§8.1); an empty array means "remove nothing" and
  // must resolve rather than reject (§8.4).
  [RNGeofencingCore.sharedInstance removeGeofences:identifiers completion:^{ resolve(nil); }];
}

RCT_EXPORT_METHOD(getGeofences
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance
      getGeofencesWithCompletion:^(NSArray<NSDictionary *> *geofences) { resolve(geofences); }];
}

RCT_EXPORT_METHOD(getActiveGeofences
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance
      getActiveGeofencesWithCompletion:^(NSArray<NSString *> *identifiers) { resolve(identifiers); }];
}

RCT_EXPORT_METHOD(requestPermission
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  // Resolves with the resulting state even on denial — a denial is a result, not an
  // error (§8.4).
  [RNGeofencingCore.sharedInstance
      requestPermissionWithCompletion:^(NSDictionary *state) { resolve(state); }];
}

RCT_EXPORT_METHOD(openSettings
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  // The Android API 30+ background-location fallback has no iOS counterpart worth
  // special-casing, so this opens the app's own Settings page (§6.7).
  dispatch_async(dispatch_get_main_queue(), ^{
    NSURL *url = [NSURL URLWithString:UIApplicationOpenSettingsURLString];
    if (url == nil || ![UIApplication.sharedApplication canOpenURL:url]) {
      reject(@"E_INTERNAL", @"could not open app settings", nil);
      return;
    }
    [UIApplication.sharedApplication openURL:url options:@{} completionHandler:nil];
    resolve(nil);
  });
}

RCT_EXPORT_METHOD(getState
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance getStateWithCompletion:^(NSDictionary *state) { resolve(state); }];
}

RCT_EXPORT_METHOD(flushQueue
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance
      flushQueueWithCompletion:^(NSArray<NSDictionary *> *events) { resolve(events); }];
}

RCT_EXPORT_METHOD(getDebugLog
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject) {
  [RNGeofencingCore.sharedInstance
      getDebugLogWithCompletion:^(NSArray<NSString *> *lines) { resolve(lines); }];
}

#pragma mark - Helpers

/// Maps an [NSError] carrying a §8.4 code onto the promise.
- (void)settle:(RCTPromiseResolveBlock)resolve
        reject:(RCTPromiseRejectBlock)reject
         error:(nullable NSError *)error
         value:(nullable id)value {
  if (error == nil) {
    resolve(value);
    return;
  }
  NSString *code = error.userInfo[@"code"] ?: @"E_INTERNAL";
  reject(code, error.localizedDescription, error);
}

#pragma mark - New Architecture

#if defined(RCT_NEW_ARCH_ENABLED) && __has_include(<RNGeofencingSpec/RNGeofencingSpec.h>)
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeGeofencingSpecJSI>(params);
}
#endif

@end
