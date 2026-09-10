#import "RNGeofencingCore.h"
#import <UIKit/UIKit.h>
#import "RNGeofencingLogger.h"
#import "RNGeofencingPlatformRegistry.h"
#import "RNGeofencingRotationEngine.h"
#import "RNGeofencingStore.h"
#import "RNGeofencingTransitionGate.h"

/// Layer 1 of the dwell emulation only helps below this; see §7.4.
static const NSTimeInterval RNGeofenceMaxSchedulableDwellMs = 25000.0;

/**
 * The oldest a fix may be before it is unfit to rotate around.
 *
 * Two minutes: the rotation only has to pick the nearest 19 geofences, so a slightly
 * old fix is fine, while a genuinely stale one is not.
 */
static const NSTimeInterval RNGeofenceMaxFixAgeSeconds = 120.0;

/**
 * Whether a location may be used as a rotation centre (invariant 4).
 *
 * Rejecting a **stale** fix matters as much as rejecting a missing one, and it is the
 * easier mistake to make. `startMonitoringSignificantLocationChanges` delivers a saved
 * fix the instant monitoring begins, and it can be hours old and kilometres away.
 * Rotating on one swaps the whole active set to somewhere the user is not; the next
 * rotation swaps it straight back, and the host app sees a burst of synthetic EXITs
 * followed by re-ENTERs for regions that were never left.
 */
static BOOL RNGeofenceIsFixUsable(CLLocation *_Nullable location) {
  if (location == nil || !CLLocationCoordinate2DIsValid(location.coordinate)) {
    return NO;
  }
  // A negative accuracy is CoreLocation's way of saying the fix is invalid.
  if (location.horizontalAccuracy < 0) {
    return NO;
  }
  return -[location.timestamp timeIntervalSinceNow] <= RNGeofenceMaxFixAgeSeconds;
}

static long long RNGeofenceNow(void) {
  return (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
}

@interface RNGeofencingCore ()
@property(nonatomic, strong) CLLocationManager *manager;
@property(nonatomic, strong) RNGeofencingStore *store;
@property(nonatomic, strong) RNGeofencingTransitionGate *gate;
@property(nonatomic, strong) RNGeofencingPlatformRegistry *registry;
@property(nonatomic, strong) RNGeofencingRotationEngine *engine;
- (instancetype)initPrivate;
@end

/**
 * `CLLocationManager.location` belongs to the thread the manager was created on — the
 * main thread, during launch (§7.5, invariant 11) — while rotation runs on the Core's
 * serial queue (§4.5). Reading it is bridged the same way the platform registry
 * bridges its calls.
 */
static CLLocation *_Nullable RNGeofenceManagerLocation(CLLocationManager *manager) {
  if ([NSThread isMainThread]) {
    return manager.location;
  }
  __block CLLocation *location = nil;
  dispatch_sync(dispatch_get_main_queue(), ^{ location = manager.location; });
  return location;
}

@implementation RNGeofencingCore {
  /// Pending `whenInUse` → `always` staging, and the promise waiting on it.
  NSMutableArray<void (^)(NSDictionary *)> *_permissionWaiters;
  BOOL _awaitingAlwaysUpgrade;

  /// Dwell emulation (§7.4): identifier → timer, identifier → background task.
  NSMutableDictionary<NSString *, NSTimer *> *_dwellTimers;
  NSMutableDictionary<NSString *, NSNumber *> *_dwellBackgroundTasks;

  BOOL _significantChangesRunning;

  /// A one-shot `requestLocation` is in flight for a rotation that had no usable fix (§4.7).
  BOOL _awaitingFreshFix;
  RNGeofenceRotationTrigger _pendingRotationTrigger;
}

#pragma mark - Launch hook (mechanism C, §18.3)

/**
 * `+load` cannot live in the same `@implementation` as `RCT_EXPORT_MODULE()` — that
 * macro already defines `+load` (it calls `RCTRegisterModule`), so a second one is a
 * duplicate-method compile error. Hence this hook lives on the Core class and not on
 * `RNGeofencing` (§18.3, trap 1).
 *
 * `UIApplicationDidFinishLaunchingNotification` is an `NSNotificationCenter` event —
 * in-process, no UI, no permission, and nothing to do with user-facing notifications.
 * Its `userInfo` *is* the `launchOptions` dictionary, and UIKit posts it immediately
 * after `didFinishLaunchingWithOptions` returns.
 */
+ (void)load {
  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(handleLaunchNotification:)
                                               name:UIApplicationDidFinishLaunchingNotification
                                             object:nil];
}

+ (void)handleLaunchNotification:(NSNotification *)note {
  if (note.userInfo[UIApplicationLaunchOptionsLocationKey] != nil) {
    // Relaunched *because* of a location event. Constructing the singleton now is the
    // whole point: it creates the CLLocationManager and assigns the delegate before
    // the OS tries to deliver the pending region event (§7.5).
    [RNGeofencingLogger warn:@"relaunched by a location event — constructing the core now"];
    [RNGeofencingCore sharedInstance];
    return;
  }

  // A normal launch. The core is still constructed eagerly, because a region crossing
  // can arrive at any moment and a delegate that does not exist yet receives nothing.
  [RNGeofencingCore sharedInstance];
}

#pragma mark - Lifecycle

+ (instancetype)sharedInstance {
  static RNGeofencingCore *instance = nil;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{ instance = [[RNGeofencingCore alloc] initPrivate]; });
  return instance;
}

- (instancetype)initPrivate {
  if (self = [super init]) {
    // Serial, and the reason two overlapping rotations cannot diff against the same
    // OS registry and leave a partial set (§4.5, invariant 2).
    _queue = dispatch_queue_create("com.rngeofencing.core", DISPATCH_QUEUE_SERIAL);

    _permissionWaiters = [NSMutableArray new];
    _dwellTimers = [NSMutableDictionary new];
    _dwellBackgroundTasks = [NSMutableDictionary new];
    _awaitingAlwaysUpgrade = NO;
    _significantChangesRunning = NO;
    _awaitingFreshFix = NO;

    _store = [RNGeofencingStore new];
    [_store load];
    _gate = [[RNGeofencingTransitionGate alloc] initWithStore:_store];

    [self configureManager];

    _registry = [[RNGeofencingPlatformRegistry alloc] initWithManager:_manager];
    _engine = [[RNGeofencingRotationEngine alloc] initWithStore:_store
                                                      registry:_registry
                                                          gate:_gate
                                                      capacity:RNGeofenceIOSActiveCapacity];

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(applicationDidBecomeActive)
                                                 name:UIApplicationDidBecomeActiveNotification
                                               object:nil];

    // Re-arm on process start, and resolve any dwell whose delay elapsed while we
    // were not running (§7.4, layer 2).
    dispatch_async(_queue, ^{
      if (self.store.meta.enabled) {
        [self rotateWithTrigger:RNGeofenceRotationTriggerLaunch hint:nil];
        [self startSignificantChangesIfEnabled];
      }
      [self resolvePendingDwells];
    });
  }
  return self;
}

/**
 * §7.1.
 *
 * Created and delegated **synchronously here**, during launch — never lazily from
 * `ready()` (invariant 11).
 */
- (void)configureManager {
  _manager = [[CLLocationManager alloc] init];
  _manager.delegate = self;
  _manager.desiredAccuracy = kCLLocationAccuracyHundredMeters;
  _manager.pausesLocationUpdatesAutomatically = NO;

  // Setting this without the `location` background mode in Info.plist raises an
  // exception, so it is guarded rather than assumed. Region monitoring and
  // significant-location-change both work without it; it is set when available
  // because §7.1 asks for it and because it costs nothing when the mode is declared.
  NSArray *modes = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"UIBackgroundModes"];
  if ([modes isKindOfClass:[NSArray class]] && [modes containsObject:@"location"]) {
    _manager.allowsBackgroundLocationUpdates = YES;
  } else {
    [RNGeofencingLogger warn:@"UIBackgroundModes does not include 'location'; background delivery "
                             @"will be limited. Add it via the Expo config plugin or Info.plist."];
  }
}

- (void)dealloc {
  [[NSNotificationCenter defaultCenter] removeObserver:self];
}

#pragma mark - §8.1 surface

- (void)readyWithConfig:(NSDictionary *)config completion:(void (^)(NSDictionary *))completion {
  dispatch_async(self.queue, ^{
    self.store.config = [RNGeofenceConfig fromDictionary:config];
    [RNGeofencingLogger debug:@"ready: %@", [self.store.config toDictionary]];

    if (self.store.meta.enabled) {
      [self rotateWithTrigger:RNGeofenceRotationTriggerReady hint:nil];
      [self startSignificantChangesIfEnabled];
    }
    completion([self buildState]);
  });
}

- (void)startWithCompletion:(void (^)(NSError *_Nullable))completion {
  dispatch_async(self.queue, ^{
    if (![RNGeofencingPlatformRegistry isAvailable]) {
      completion([self errorWithCode:@"E_UNAVAILABLE"
                            message:@"region monitoring is not available on this device"]);
      return;
    }
    RNGeofenceMeta *meta = [self.store.meta copy];
    meta.enabled = YES;
    self.store.meta = meta;

    [self rotateWithTrigger:RNGeofenceRotationTriggerStart hint:nil];
    [self startSignificantChangesIfEnabled];
    completion(nil);
  });
}

- (void)stopWithCompletion:(void (^)(void))completion {
  dispatch_async(self.queue, ^{
    RNGeofenceMeta *meta = [self.store.meta copy];
    meta.enabled = NO;
    meta.boundaryRadius = nil;
    self.store.meta = meta;

    [self.registry removeAll];
    [self.store setActiveIdentifiers:[NSSet set]];
    [self cancelAllDwellTimers];
    if (self->_significantChangesRunning) {
      [self.manager stopMonitoringSignificantLocationChanges];
      self->_significantChangesRunning = NO;
    }
    [self.store synchronizeNow];
    [RNGeofencingLogger debug:@"stop: all regions disarmed, %ld geofence(s) kept in the store",
                              (long)[self.store count]];
    completion();
  });
}

- (void)addGeofences:(NSArray<NSDictionary *> *)specs
          completion:(void (^)(NSError *_Nullable))completion {
  dispatch_async(self.queue, ^{
    if (specs.count == 0) {
      completion(nil);  // must not reject (§8.4)
      return;
    }

    NSMutableArray<RNGeofenceRecord *> *records = [NSMutableArray new];
    for (NSDictionary *spec in specs) {
      RNGeofenceRecord *record = [RNGeofenceRecord fromSpec:spec];
      NSError *invalid = [self validate:record];
      if (invalid != nil) {
        completion(invalid);
        return;
      }
      [records addObject:record];
    }

    NSMutableSet<NSString *> *resulting = [NSMutableSet new];
    for (RNGeofenceRecord *existing in [self.store all]) {
      [resulting addObject:existing.identifier];
    }
    for (RNGeofenceRecord *record in records) {
      [resulting addObject:record.identifier];
    }
    if ((NSInteger)resulting.count > RNGeofenceMaxGeofences) {
      completion([self
          errorWithCode:@"E_LIMIT_EXCEEDED"
                message:[NSString stringWithFormat:
                                      @"adding %ld geofence(s) would bring the store to %ld, "
                                      @"above the cap of %ld (§9.5)",
                                      (long)records.count, (long)resulting.count,
                                      (long)RNGeofenceMaxGeofences]]);
      return;
    }

    [self.store upsert:records];
    if (self.store.meta.enabled) {
      [self rotateWithTrigger:RNGeofenceRotationTriggerGeofencesChanged hint:nil];
    }
    completion(nil);
  });
}

- (void)removeGeofences:(NSArray<NSString *> *)identifiers completion:(void (^)(void))completion {
  dispatch_async(self.queue, ^{
    NSArray<NSString *> *targets = identifiers;
    if (targets == nil) {
      NSMutableArray<NSString *> *all = [NSMutableArray new];
      for (RNGeofenceRecord *record in [self.store all]) {
        [all addObject:record.identifier];
      }
      targets = all;
    }
    if (targets.count == 0) {
      completion();  // must not reject (§8.4)
      return;
    }

    // Disarm before forgetting: a region removed from the store but left monitored
    // would keep firing for an identifier we can no longer resolve.
    [self.registry removeIdentifiers:targets];
    for (NSString *identifier in targets) {
      [self cancelDwellTimerFor:identifier];
    }
    [self.store deleteIdentifiers:targets];

    if (self.store.meta.enabled) {
      [self rotateWithTrigger:RNGeofenceRotationTriggerGeofencesChanged hint:nil];
    }
    completion();
  });
}

- (void)getGeofencesWithCompletion:(void (^)(NSArray<NSDictionary *> *))completion {
  dispatch_async(self.queue, ^{
    NSMutableArray<NSDictionary *> *specs = [NSMutableArray new];
    for (RNGeofenceRecord *record in [self.store all]) {
      // BOUNDARY_ID is an implementation detail and never escapes a public result
      // (§4.2, invariant 10).
      if (![record.identifier isEqualToString:RNGeofenceBoundaryID]) {
        [specs addObject:[record toSpec]];
      }
    }
    completion(specs);
  });
}

- (void)getActiveGeofencesWithCompletion:(void (^)(NSArray<NSString *> *))completion {
  dispatch_async(self.queue, ^{
    // iOS answers from the OS registry, which is authoritative (§4.6) — unlike the
    // Android answer, which is only what we believe is armed.
    NSArray<NSString *> *identifiers = [[[self.registry monitoredIdentifiers] allObjects]
        sortedArrayUsingSelector:@selector(compare:)];
    completion(identifiers);
  });
}

- (void)getStateWithCompletion:(void (^)(NSDictionary *))completion {
  dispatch_async(self.queue, ^{ completion([self buildState]); });
}

- (void)flushQueueWithCompletion:(void (^)(NSArray<NSDictionary *> *))completion {
  dispatch_async(self.queue, ^{
    NSArray<RNGeofenceEvent *> *drained = [self.store drainQueue];
    NSMutableArray<NSDictionary *> *payloads = [NSMutableArray new];
    for (RNGeofenceEvent *event in drained) {
      [payloads addObject:[event toPayload]];
    }
    [self.store synchronizeNow];
    completion(payloads);
  });
}

- (void)getDebugLogWithCompletion:(void (^)(NSArray<NSString *> *))completion {
  completion([RNGeofencingLogger snapshot]);
}

- (void)jsStartedObserving {
  dispatch_async(self.queue, ^{
    id<RNGeofencingEventDelegate> delegate = self.eventDelegate;
    if (delegate == nil || ![delegate isObserving]) {
      return;
    }
    NSArray<RNGeofenceEvent *> *drained = [self.store drainQueue];
    if (drained.count == 0) {
      return;
    }
    [RNGeofencingLogger debug:@"flushing %ld queued event(s) to JS", (long)drained.count];
    for (RNGeofenceEvent *event in drained) {
      [delegate emitGeofenceEvent:[event toPayload]];
    }
  });
}

- (void)applicationDidBecomeActive {
  dispatch_async(self.queue, ^{
    [self resolvePendingDwells];
    if (self.store.meta.enabled) {
      [self rotateWithTrigger:RNGeofenceRotationTriggerForeground hint:nil];
    }
  });
}

#pragma mark - Permissions (§7.1)

- (void)requestPermissionWithCompletion:(void (^)(NSDictionary *))completion {
  dispatch_async(self.queue, ^{
    CLAuthorizationStatus status = [self authorizationStatus];

    if (status == kCLAuthorizationStatusAuthorizedAlways) {
      completion([self buildState]);
      return;
    }

    [self->_permissionWaiters addObject:completion];

    dispatch_async(dispatch_get_main_queue(), ^{
      if (status == kCLAuthorizationStatusNotDetermined) {
        // WhenInUse first. Apple rejects apps that ask for Always up front, so the
        // upgrade happens in -locationManagerDidChangeAuthorization: once the user
        // has seen the value.
        self->_awaitingAlwaysUpgrade = YES;
        [self.manager requestWhenInUseAuthorization];
      } else if (status == kCLAuthorizationStatusAuthorizedWhenInUse) {
        // Region monitoring in the background requires `authorizedAlways` (§7.1).
        [self.manager requestAlwaysAuthorization];
      } else {
        // Denied or restricted: nothing left to ask. A denial is a result, not an
        // error (§8.4).
        dispatch_async(self.queue, ^{ [self settlePermissionWaiters]; });
      }
    });
  });
}

- (void)settlePermissionWaiters {
  if (_permissionWaiters.count == 0) {
    return;
  }
  NSArray<void (^)(NSDictionary *)> *waiters = [_permissionWaiters copy];
  [_permissionWaiters removeAllObjects];
  NSDictionary *state = [self buildState];
  for (void (^waiter)(NSDictionary *) in waiters) {
    waiter(state);
  }
}

/// Instance property, not the deprecated class method: this module's floor is
/// iOS 15.1 (React Native's `min_ios_version_supported`), so the pre-14 class
/// accessor is unreachable dead code.
- (CLAuthorizationStatus)authorizationStatus {
  return self.manager.authorizationStatus;
}

- (NSString *)authorizationString {
  switch ([self authorizationStatus]) {
    case kCLAuthorizationStatusAuthorizedAlways:
      return @"always";
    case kCLAuthorizationStatusAuthorizedWhenInUse:
      return @"whenInUse";
    case kCLAuthorizationStatusDenied:
      return @"denied";
    case kCLAuthorizationStatusRestricted:
      return @"restricted";
    case kCLAuthorizationStatusNotDetermined:
      return @"notDetermined";
  }
  return @"notDetermined";
}

- (NSString *)accuracyAuthorizationString {
  // Region monitoring still works under reduced accuracy, but with much larger error
  // — so it is reported rather than silently tolerated (§7.1, §10).
  return self.manager.accuracyAuthorization == CLAccuracyAuthorizationFullAccuracy ? @"full"
                                                                                  : @"reduced";
}

#pragma mark - Rotation

- (void)rotateWithTrigger:(RNGeofenceRotationTrigger)trigger hint:(nullable CLLocation *)hint {
  if (![RNGeofencingPlatformRegistry isAvailable]) {
    [RNGeofencingLogger warn:@"rotate: region monitoring unavailable, module is inert"];
    return;
  }

  // Take the **freshest** usable fix, never simply the hint.
  //
  // A significant-location-change update can carry a position the device has already
  // left: CoreLocation hands over whatever it last recorded, which just after a move
  // is the place you moved *from*. It passes the staleness check — it is genuinely
  // recent — but it is the wrong place, and rotating on it swaps the whole active set
  // away and back again, which the host app sees as synthetic EXITs followed by
  // re-ENTERs for regions never left. The manager's own last fix is newer in exactly
  // that window, so age is what decides between them.
  CLLocation *managerFix = RNGeofenceManagerLocation(self.manager);
  BOOL hintUsable = RNGeofenceIsFixUsable(hint);
  BOOL managerUsable = RNGeofenceIsFixUsable(managerFix);

  CLLocation *resolved = nil;
  if (hintUsable && managerUsable) {
    BOOL hintIsNewer =
        [hint.timestamp compare:managerFix.timestamp] == NSOrderedDescending;
    resolved = hintIsNewer ? hint : managerFix;
    if (!hintIsNewer) {
      [RNGeofencingLogger debug:@"centre: hint is %.0fs older than the manager's fix, using the "
                                @"manager's",
                                [managerFix.timestamp timeIntervalSinceDate:hint.timestamp]];
    }
  } else if (hintUsable) {
    resolved = hint;
  } else if (managerUsable) {
    resolved = managerFix;
  }

  if (resolved == nil) {
    // Step 3 of §4.7: ask for a fix rather than giving up.
    //
    // This module never calls `startUpdatingLocation`, so `manager.location` only
    // refreshes when a region event or a significant-location-change arrives. Standing
    // still long enough therefore ages out *every* cached fix — and without this, the
    // rotation would simply stop happening, which is a worse failure than the stale
    // centre the usability check exists to prevent.
    [RNGeofencingLogger warn:@"rotate(%ld): no usable cached fix, requesting a fresh one",
                             (long)trigger];
    [self requestFreshFixForTrigger:trigger];
    return;
  }

  CLLocationCoordinate2D center = resolved.coordinate;
  RNGeofenceActiveSet *computed = [self.engine computeActiveSetAround:center];
  // The centre's age and provenance are what distinguish a real move from a rotation
  // driven by a position the device has already left.
  [RNGeofencingLogger debug:@"rotate(%ld): %ld of %ld selected — centre %@ (%.5f, %.5f) "
                            @"%.0fs old, accuracy %.0fm",
                            (long)trigger, (long)computed.active.count,
                            (long)[self.store count],
                            (resolved == hint) ? @"from hint" : @"from manager",
                            center.latitude, center.longitude,
                            -[resolved.timestamp timeIntervalSinceNow],
                            resolved.horizontalAccuracy];

  RNGeofenceRotationResult *result = [self.engine applyActiveSet:computed.active
                                                  boundaryRadius:computed.boundaryRadius
                                                          center:center];

  if (result.syntheticEvents.count > 0) {
    [self deliverEvents:result.syntheticEvents];
  }

  if (result.on.count > 0 || result.off.count > 0) {
    id<RNGeofencingEventDelegate> delegate = self.eventDelegate;
    if (delegate != nil && [delegate isObserving]) {
      NSMutableArray<NSDictionary *> *on = [NSMutableArray new];
      for (RNGeofenceRecord *record in result.on) {
        [on addObject:[record toSpec]];
      }
      [delegate emitGeofencesChange:@{@"on" : on, @"off" : result.off}];
    }
  }

  [self.store synchronizeNow];
}

/**
 * One-shot fix for a rotation that had nothing usable cached (§4.7 step 3).
 *
 * `requestLocation` delivers exactly once, through `didUpdateLocations`, and the
 * pending flag is what tells that callback the update belongs to a rotation rather
 * than to significant-location-change monitoring. Only one is ever in flight, so a
 * rotation that still cannot resolve a centre cannot loop.
 */
- (void)requestFreshFixForTrigger:(RNGeofenceRotationTrigger)trigger {
  if (_awaitingFreshFix) {
    return;
  }
  CLAuthorizationStatus status = [self authorizationStatus];
  if (status != kCLAuthorizationStatusAuthorizedAlways &&
      status != kCLAuthorizationStatusAuthorizedWhenInUse) {
    [RNGeofencingLogger warn:@"rotate: no authorization, keeping the current set"];
    return;
  }

  _awaitingFreshFix = YES;
  _pendingRotationTrigger = trigger;
  dispatch_async(dispatch_get_main_queue(), ^{ [self.manager requestLocation]; });
}

/// §7.6 — a backstop in case a boundary EXIT is missed. ~500 m / 5 min, negligible battery.
- (void)startSignificantChangesIfEnabled {
  if (!self.store.config.useSignificantLocationChanges || _significantChangesRunning) {
    return;
  }
  if (![CLLocationManager significantLocationChangeMonitoringAvailable]) {
    return;
  }
  _significantChangesRunning = YES;
  dispatch_async(dispatch_get_main_queue(),
                 ^{ [self.manager startMonitoringSignificantLocationChanges]; });
}

#pragma mark - CLLocationManagerDelegate

- (void)locationManager:(CLLocationManager *)manager didEnterRegion:(CLRegion *)region {
  CLLocation *location = manager.location;
  dispatch_async(self.queue, ^{ [self handleEnter:region.identifier location:location]; });
}

- (void)locationManager:(CLLocationManager *)manager didExitRegion:(CLRegion *)region {
  CLLocation *location = manager.location;
  dispatch_async(self.queue, ^{ [self handleExit:region.identifier location:location]; });
}

/**
 * Both the answer to `requestStateForRegion:` and the OS's own re-arm report.
 *
 * This is the iOS reconciliation hook of §5.2, and the source of the spurious-ENTER
 * artifact of §5.1 — which is exactly why every one of these passes through the gate
 * (invariant 6).
 */
- (void)locationManager:(CLLocationManager *)manager
      didDetermineState:(CLRegionState)state
              forRegion:(CLRegion *)region {
  if ([region.identifier isEqualToString:RNGeofenceBoundaryID]) {
    return;  // the boundary's state is of no interest; only leaving it matters
  }

  CLLocation *location = manager.location;
  dispatch_async(self.queue, ^{
    if (state == CLRegionStateInside) {
      [self handleEnter:region.identifier location:location];
      // This is `verifyStillInside` resolving (§7.4): the OS has just confirmed we
      // are inside, which is the only trustworthy moment to emit an emulated DWELL.
      [self emitDwellIfElapsed:region.identifier];
      return;
    }
    if (state == CLRegionStateOutside) {
      CLLocationCoordinate2D center =
          location != nil
              ? location.coordinate
              : ([self.store.meta hasCenter] ? [self.store.meta center]
                                             : kCLLocationCoordinate2DInvalid);
      if (!CLLocationCoordinate2DIsValid(center)) {
        return;
      }
      RNGeofenceEvent *event = [self.gate determinedStateFor:region.identifier
                                                   isInside:NO
                                                     center:center];
      if (event != nil) {
        [self deliverEvents:@[ event ]];
      }
    }
  });
}

- (void)locationManager:(CLLocationManager *)manager
    monitoringDidFailForRegion:(nullable CLRegion *)region
                     withError:(NSError *)error {
  // `kCLErrorRegionMonitoringFailure` here usually means the 20-region limit was
  // exceeded, i.e. a rotation bug rather than a device problem — so it is logged
  // loudly with the CLError code, which is what makes a field report actionable
  // (§7.2, §8.4).
  [RNGeofencingLogger error:@"monitoringDidFailForRegion '%@': %@ (CLError %ld)",
                            region.identifier ?: @"(none)", error.localizedDescription,
                            (long)error.code];

  if (region != nil && ![region.identifier isEqualToString:RNGeofenceBoundaryID]) {
    NSString *identifier = region.identifier;
    dispatch_async(self.queue, ^{
      // Mark it not-armed so the next rotation retries it rather than assuming it is
      // live (the Android side does the same on a failed add, §4.6).
      [self.store setActive:@[ identifier ] active:NO];
    });
  }
}

/// Without clearing the pending flag here, one failed `requestLocation` would block
/// every future rotation that needs a fresh fix.
- (void)locationManager:(CLLocationManager *)manager didFailWithError:(NSError *)error {
  [RNGeofencingLogger warn:@"location request failed: %@ (CLError %ld)",
                           error.localizedDescription, (long)error.code];
  dispatch_async(self.queue, ^{ self->_awaitingFreshFix = NO; });
}

- (void)locationManagerDidChangeAuthorization:(CLLocationManager *)manager {
  dispatch_async(self.queue, ^{
    CLAuthorizationStatus status = [self authorizationStatus];
    [RNGeofencingLogger debug:@"authorization changed to %@", [self authorizationString]];

    if (self->_awaitingAlwaysUpgrade && status == kCLAuthorizationStatusAuthorizedWhenInUse) {
      // The user has now seen the value of the feature, which is the moment Apple
      // expects the Always upgrade to be asked for (§7.1).
      self->_awaitingAlwaysUpgrade = NO;
      dispatch_async(dispatch_get_main_queue(), ^{ [self.manager requestAlwaysAuthorization]; });
      return;
    }

    self->_awaitingAlwaysUpgrade = NO;
    [self settlePermissionWaiters];

    if (status == kCLAuthorizationStatusAuthorizedAlways && self.store.meta.enabled) {
      // A grant is the first moment a rotation can actually succeed.
      [self rotateWithTrigger:RNGeofenceRotationTriggerReady hint:nil];
      [self startSignificantChangesIfEnabled];
    }
  });
}

/// The significant-location-change backstop (§7.6).
- (void)locationManager:(CLLocationManager *)manager
     didUpdateLocations:(NSArray<CLLocation *> *)locations {
  CLLocation *latest = locations.lastObject;
  if (latest == nil) {
    return;
  }

  // A fix we asked for explicitly belongs to a rotation that had nothing cached, so it
  // is used regardless of which trigger started it.
  dispatch_async(self.queue, ^{
    if (!self->_awaitingFreshFix) {
      return;
    }
    self->_awaitingFreshFix = NO;
    RNGeofenceRotationTrigger trigger = self->_pendingRotationTrigger;
    [RNGeofencingLogger debug:@"centre: one-shot fix arrived, resuming rotate(%ld)",
                              (long)trigger];
    [self resolvePendingDwells];
    if (self.store.meta.enabled) {
      [self rotateWithTrigger:trigger hint:latest];
    }
  });

  // The first update after `startMonitoringSignificantLocationChanges` is a cached fix
  // that CoreLocation had lying around — routinely hours old and kilometres away.
  // Rotating on it is what produced a burst of synthetic EXITs followed by re-ENTERs
  // on every launch, for a user who had not moved at all.
  if (!RNGeofenceIsFixUsable(latest)) {
    [RNGeofencingLogger debug:@"SLC update ignored: fix is %.0fs old, accuracy %.0fm",
                              -[latest.timestamp timeIntervalSinceNow],
                              latest.horizontalAccuracy];
    return;
  }

  dispatch_async(self.queue, ^{
    [self resolvePendingDwells];
    if (self.store.meta.enabled) {
      [self rotateWithTrigger:RNGeofenceRotationTriggerSignificantLocationChange hint:latest];
    }
  });
}

#pragma mark - Transition handling

- (void)handleEnter:(NSString *)identifier location:(nullable CLLocation *)location {
  if ([identifier isEqualToString:RNGeofenceBoundaryID]) {
    return;  // entering the boundary means nothing
  }

  RNGeofenceEvent *event = [self.gate enter:identifier location:location];
  RNGeofenceRecord *record = [self.store get:identifier];

  if (record != nil && record.notifyOnDwell) {
    [self scheduleDwellFor:record];
  }

  if (event != nil) {
    [self deliverEvents:@[ event ]];
  }
}

- (void)handleExit:(NSString *)identifier location:(nullable CLLocation *)location {
  if ([identifier isEqualToString:RNGeofenceBoundaryID]) {
    // The primary, battery-free rotation trigger (§4.5). Never emitted (invariant 10).
    [RNGeofencingLogger debug:@"boundary EXIT — rotating"];
    [self rotateWithTrigger:RNGeofenceRotationTriggerBoundaryExit hint:location];
    return;
  }

  [self cancelDwellTimerFor:identifier];
  RNGeofenceEvent *event = [self.gate exit:identifier location:location];
  if (event != nil) {
    [self deliverEvents:@[ event ]];
  }
}

#pragma mark - Dwell emulation (§7.4)

/**
 * iOS has no native dwell, and it suspends the app between region events, so a plain
 * `Timer` is not reliable. Two layers:
 *
 * - **Layer 1**, for short delays only: a timer inside a `beginBackgroundTask`, which
 *   buys about 30 s — so anything past ~25 s is not worth scheduling.
 * - **Layer 2**, for everything longer: nothing is scheduled. The dwell is resolved
 *   opportunistically on the next wake — a region event, an SLC update, or the app
 *   coming to the foreground.
 *
 * Be honest about the consequence, and the README is: a DWELL with a delay longer
 * than ~25 s fires *at the next time the app is awake* after the delay elapses, not
 * at the delay. A host app whose logic depends on exact dwell timing must compute the
 * duration from the ENTER timestamp itself rather than trusting when DWELL arrived.
 */
- (void)scheduleDwellFor:(RNGeofenceRecord *)record {
  if ((double)record.loiteringDelay > RNGeofenceMaxSchedulableDwellMs) {
    [RNGeofencingLogger debug:@"dwell %@: %ldms is too long to schedule, will resolve on next wake",
                              record.identifier, (long)record.loiteringDelay];
    return;
  }

  NSString *identifier = record.identifier;
  NSTimeInterval delay = (double)record.loiteringDelay / 1000.0;

  dispatch_async(dispatch_get_main_queue(), ^{
    [self cancelDwellTimerFor:identifier];

    UIBackgroundTaskIdentifier task = [[UIApplication sharedApplication]
        beginBackgroundTaskWithName:@"RNGeofencingDwell"
                  expirationHandler:^{ [self endDwellBackgroundTaskFor:identifier]; }];
    self->_dwellBackgroundTasks[identifier] = @(task);

    NSTimer *timer = [NSTimer
        scheduledTimerWithTimeInterval:delay
                               repeats:NO
                                 block:^(NSTimer *_Nonnull expired) {
                                   [self->_dwellTimers removeObjectForKey:identifier];
                                   [self verifyAndEmitDwellFor:identifier];
                                 }];
    self->_dwellTimers[identifier] = timer;
  });
}

/**
 * Verifies we are still inside before emitting.
 *
 * `requestStateFor:` is the real check — its answer lands at `didDetermineState`,
 * which either resolves the dwell (inside) or emits a synthetic EXIT (outside). The
 * immediate attempt below covers the case where the OS does not answer in time; it is
 * gated on our own persisted state, so it cannot emit a DWELL for a region we already
 * know we left.
 */
- (void)verifyAndEmitDwellFor:(NSString *)identifier {
  [self.registry requestStateFor:identifier];
  dispatch_async(self.queue, ^{
    [self emitDwellIfElapsed:identifier];
    [self endDwellBackgroundTaskFor:identifier];
  });
}

/// Layer 2: resolve every dwell whose delay has elapsed, on any wake.
- (void)resolvePendingDwells {
  for (RNGeofenceRecord *record in [self.store all]) {
    if (record.notifyOnDwell && record.state == RNGeofenceStateInside && !record.dwellEmitted) {
      [self emitDwellIfElapsed:record.identifier];
    }
  }
}

- (void)emitDwellIfElapsed:(NSString *)identifier {
  RNGeofenceRecord *record = [self.store get:identifier];
  if (record == nil || !record.notifyOnDwell || record.dwellEmitted) {
    return;
  }
  if (record.state != RNGeofenceStateInside || record.enteredAt == nil) {
    return;
  }

  long long elapsed = RNGeofenceNow() - record.enteredAt.longLongValue;
  if (elapsed < (long long)record.loiteringDelay) {
    return;
  }

  RNGeofenceEvent *event = [self.gate dwell:identifier
                                   location:RNGeofenceManagerLocation(self.manager)];
  if (event != nil) {
    [RNGeofencingLogger debug:@"dwell %@ resolved after %lldms", identifier, elapsed];
    [self deliverEvents:@[ event ]];
  }
}

- (void)cancelDwellTimerFor:(NSString *)identifier {
  NSTimer *timer = _dwellTimers[identifier];
  if (timer != nil) {
    [timer invalidate];
    [_dwellTimers removeObjectForKey:identifier];
  }
  [self endDwellBackgroundTaskFor:identifier];
}

- (void)cancelAllDwellTimers {
  for (NSString *identifier in [_dwellTimers.allKeys copy]) {
    [self cancelDwellTimerFor:identifier];
  }
}

- (void)endDwellBackgroundTaskFor:(NSString *)identifier {
  NSNumber *task = _dwellBackgroundTasks[identifier];
  if (task == nil) {
    return;
  }
  [_dwellBackgroundTasks removeObjectForKey:identifier];
  [[UIApplication sharedApplication]
      endBackgroundTask:(UIBackgroundTaskIdentifier)task.unsignedLongValue];
}

#pragma mark - Delivery

/**
 * There is **no headless JS on iOS** (§7.5).
 *
 * A relaunched process boots the RN bridge normally, so native must queue the event
 * and flush it once JS registers a listener. The budget is roughly 10 seconds of
 * background runtime, which is often *less* than an RN cold start — so assume JS will
 * not run, and make sure everything the host depends on is already in the store
 * before JS is involved.
 */
- (void)deliverEvents:(NSArray<RNGeofenceEvent *> *)events {
  if (events.count == 0) {
    return;
  }

  id<RNGeofencingEventDelegate> delegate = self.eventDelegate;
  if (delegate != nil && [delegate isObserving]) {
    for (RNGeofenceEvent *event in events) {
      [delegate emitGeofenceEvent:[event toPayload]];
    }
    // The gate has just moved this geofence's INSIDE/OUTSIDE state, and that write is
    // what stops the next launch re-reporting the same crossing. Force it out here
    // too, not only on the queueing path below.
    [self.store synchronizeNow];
    [RNGeofencingLogger debug:@"delivered %ld event(s) to JS", (long)events.count];
    return;
  }

  [self.store enqueue:events];
  // The process may be terminated within milliseconds of returning from a
  // background-relaunch callback, so this write is forced out now (§9.3).
  [self.store synchronizeNow];
  [RNGeofencingLogger debug:@"queued %ld event(s): no JS listener", (long)events.count];
}

#pragma mark - State / validation

- (NSDictionary *)buildState {
  RNGeofenceMeta *meta = self.store.meta;

  NSInteger geofenceCount = 0;
  for (RNGeofenceRecord *record in [self.store all]) {
    if (![record.identifier isEqualToString:RNGeofenceBoundaryID]) {
      geofenceCount++;
    }
  }

  NSMutableDictionary *state = [@{
    @"enabled" : @(meta.enabled),
    @"available" : @([RNGeofencingPlatformRegistry isAvailable]),
    @"authorization" : [self authorizationString],
    @"accuracyAuthorization" : [self accuracyAuthorizationString],
    @"geofenceCount" : @(geofenceCount),
    @"activeCount" : @([self.registry monitoredIdentifiers].count),
    @"locationServicesEnabled" : @([self locationServicesEnabled]),
    @"droppedEventCount" : @(meta.droppedCount),
    // Android-only; reported as NO rather than omitted so the shape is identical.
    @"batteryOptimized" : @(NO),
  } mutableCopy];

  state[@"rotationCenterLatitude"] = meta.centerLatitude ?: [NSNull null];
  state[@"rotationCenterLongitude"] = meta.centerLongitude ?: [NSNull null];
  state[@"rotationCenterAt"] = meta.centerAt ?: [NSNull null];
  state[@"boundaryRadius"] = meta.boundaryRadius ?: [NSNull null];
  return state;
}

/**
 * Deprecated since iOS 14 with no replacement that answers the same question, and
 * Apple's own guidance is to keep calling it off the main thread — which is where
 * this always runs (the Core's serial queue).
 */
- (BOOL)locationServicesEnabled {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
  return [CLLocationManager locationServicesEnabled];
#pragma clang diagnostic pop
}

- (nullable NSError *)validate:(RNGeofenceRecord *)record {
  if (record.identifier.length == 0) {
    return [self errorWithCode:@"E_INVALID_GEOFENCE" message:@"identifier must not be empty"];
  }
  if ([record.identifier isEqualToString:RNGeofenceBoundaryID]) {
    return [self errorWithCode:@"E_INVALID_GEOFENCE"
                       message:[NSString stringWithFormat:@"identifier '%@' is reserved by this "
                                                          @"module",
                                                          RNGeofenceBoundaryID]];
  }
  if (!CLLocationCoordinate2DIsValid(record.center) || !isfinite(record.latitude) ||
      !isfinite(record.longitude)) {
    return [self errorWithCode:@"E_INVALID_GEOFENCE"
                       message:[NSString stringWithFormat:
                                             @"'%@' has a non-finite or out-of-range coordinate "
                                             @"(%f, %f)",
                                             record.identifier, record.latitude,
                                             record.longitude]];
  }
  if (!isfinite(record.radius) || record.radius <= 0.0) {
    return [self errorWithCode:@"E_INVALID_GEOFENCE"
                       message:[NSString stringWithFormat:@"'%@' has radius %f; it must be greater "
                                                          @"than zero",
                                                          record.identifier, record.radius]];
  }
  if (!record.notifyOnEntry && !record.notifyOnExit && !record.notifyOnDwell) {
    return [self errorWithCode:@"E_INVALID_GEOFENCE"
                       message:[NSString stringWithFormat:@"'%@' has no transition enabled, so it "
                                                          @"could never fire",
                                                          record.identifier]];
  }

  // Apple documents ~200 m as the practical floor; smaller circles frequently never
  // trigger. Clamp up and say so rather than registering something that silently does
  // nothing (§10).
  double minRadius = self.store.config.minRadius;
  if (record.radius < minRadius) {
    [RNGeofencingLogger warn:@"geofence '%@' radius %dm is below the reliable minimum; clamping up "
                             @"to %dm",
                             record.identifier, (int)record.radius, (int)minRadius];
    record.radius = minRadius;
  }
  return nil;
}

- (NSError *)errorWithCode:(NSString *)code message:(NSString *)message {
  return [NSError errorWithDomain:@"RNGeofencing"
                             code:0
                         userInfo:@{NSLocalizedDescriptionKey : message, @"code" : code}];
}

@end
