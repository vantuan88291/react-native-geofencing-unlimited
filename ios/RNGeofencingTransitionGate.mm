#import "RNGeofencingTransitionGate.h"
#import "RNGeofencingLogger.h"

static long long RNGeofenceNowMs(void) {
  return (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
}

@implementation RNGeofencingTransitionGate {
  RNGeofencingStore *_store;
}

- (instancetype)initWithStore:(RNGeofencingStore *)store {
  if (self = [super init]) {
    _store = store;
  }
  return self;
}

#pragma mark - §5.1

- (nullable RNGeofenceEvent *)enter:(NSString *)identifier location:(CLLocation *)location {
  RNGeofenceRecord *record = [_store get:identifier];
  if (record == nil) {
    return [self unknownIdentifier:identifier];
  }

  if (record.state == RNGeofenceStateInside) {
    // Already inside: this is the re-arm artifact, not a crossing.
    [RNGeofencingLogger debug:@"gate: drop ENTER %@ (already INSIDE)", identifier];
    return nil;
  }

  BOOL firstEverArming = record.state == RNGeofenceStateUnknown;
  [_store updateState:identifier
                state:RNGeofenceStateInside
            enteredAt:@(RNGeofenceNowMs())
         dwellEmitted:NO];

  // `initialTriggerEntry` governs only the *very first* arming of a geofence. It must
  // never suppress a genuine ENTER on a region rotated back in — that region has a
  // known previous state, so it is not affected by this branch (§5.1).
  if (firstEverArming && !_store.config.initialTriggerEntry) {
    [RNGeofencingLogger debug:@"gate: drop ENTER %@ (first arming, initialTriggerEntry=NO)",
                              identifier];
    return nil;
  }

  if (!record.notifyOnEntry) {
    return nil;
  }
  return [self eventFor:record action:RNGeofenceActionEnter location:location synthetic:NO];
}

- (nullable RNGeofenceEvent *)exit:(NSString *)identifier location:(CLLocation *)location {
  RNGeofenceRecord *record = [_store get:identifier];
  if (record == nil) {
    return [self unknownIdentifier:identifier];
  }

  if (record.state == RNGeofenceStateOutside) {
    [RNGeofencingLogger debug:@"gate: drop EXIT %@ (already OUTSIDE)", identifier];
    return nil;
  }

  // Clears the dwell bookkeeping too: a pending dwell is always cancelled on exit
  // (§5.3). The Core cancels the corresponding timer.
  [_store updateState:identifier state:RNGeofenceStateOutside enteredAt:nil dwellEmitted:NO];

  if (!record.notifyOnExit) {
    return nil;
  }
  return [self eventFor:record action:RNGeofenceActionExit location:location synthetic:NO];
}

- (nullable RNGeofenceEvent *)dwell:(NSString *)identifier location:(CLLocation *)location {
  RNGeofenceRecord *record = [_store get:identifier];
  if (record == nil) {
    return [self unknownIdentifier:identifier];
  }

  if (record.dwellEmitted) {
    [RNGeofencingLogger debug:@"gate: drop DWELL %@ (already emitted for this visit)", identifier];
    return nil;
  }
  if (record.state != RNGeofenceStateInside) {
    [RNGeofencingLogger debug:@"gate: drop DWELL %@ (not inside)", identifier];
    return nil;
  }

  [_store updateState:identifier
                state:RNGeofenceStateInside
            enteredAt:record.enteredAt ?: @(RNGeofenceNowMs())
         dwellEmitted:YES];

  if (!record.notifyOnDwell) {
    return nil;
  }
  return [self eventFor:record action:RNGeofenceActionDwell location:location synthetic:NO];
}

#pragma mark - §5.2

- (nullable RNGeofenceEvent *)determinedStateFor:(NSString *)identifier
                                        isInside:(BOOL)isInside
                                          center:(CLLocationCoordinate2D)center {
  RNGeofenceRecord *record = [_store get:identifier];
  if (record == nil) {
    return nil;
  }

  if (record.state == RNGeofenceStateInside && !isInside) {
    [_store updateState:identifier state:RNGeofenceStateOutside enteredAt:nil dwellEmitted:NO];
    [RNGeofencingLogger debug:@"gate: synthetic EXIT %@ (didDetermineState says outside)",
                              identifier];
    if (!record.notifyOnExit) {
      return nil;
    }
    return [self syntheticEventFor:record action:RNGeofenceActionExit center:center];
  }

  // The INSIDE case is deliberately left to -enter:, which the OS drives via
  // didDetermineState(.inside) and which dedups the re-arm artifact (§5.1).
  return nil;
}

- (NSArray<RNGeofenceEvent *> *)rotationApplied:(NSArray<NSString *> *)disarmedIdentifiers
                                         center:(CLLocationCoordinate2D)center {
  NSMutableArray<RNGeofenceEvent *> *events = [NSMutableArray new];

  for (NSString *identifier in disarmedIdentifiers) {
    RNGeofenceRecord *record = [_store get:identifier];
    if (record == nil || record.state != RNGeofenceStateInside) {
      continue;
    }

    double distance = RNGeofenceHaversine(center, record.center);
    if (distance <= record.radius) {
      [RNGeofencingLogger debug:@"gate: rotating out %@ while still inside (%dm <= %dm) — keeping "
                                @"INSIDE",
                                identifier, (int)distance, (int)record.radius];
      continue;
    }

    [_store updateState:identifier state:RNGeofenceStateOutside enteredAt:nil dwellEmitted:NO];
    [RNGeofencingLogger debug:@"gate: synthetic EXIT %@ (rotated out, %dm away)", identifier,
                              (int)distance];

    if (!record.notifyOnExit) {
      continue;
    }
    [events addObject:[self syntheticEventFor:record
                                       action:RNGeofenceActionExit
                                       center:center]];
  }

  return events;
}

#pragma mark - Helpers

- (nullable RNGeofenceEvent *)unknownIdentifier:(NSString *)identifier {
  // The OS named a region we have no record of — a leftover registration from a
  // previous install, or a removal that raced the callback. Dropping it is right;
  // there is nothing to report it against.
  [RNGeofencingLogger warn:@"gate: transition for unknown geofence '%@', dropped", identifier];
  return nil;
}

/**
 * Builds the public event.
 *
 * **iOS carries no location on a region callback** (§7.3): `didEnterRegion` gives
 * only the region. The caller passes `manager.location` when it has one — usually
 * populated and free — and the Core substitutes the region centre when it does not,
 * which is what `approximate` reports.
 */
- (RNGeofenceEvent *)eventFor:(RNGeofenceRecord *)record
                       action:(RNGeofenceAction)action
                     location:(nullable CLLocation *)location
                    synthetic:(BOOL)synthetic {
  RNGeofenceEvent *event = [RNGeofenceEvent new];
  event.identifier = record.identifier;
  event.action = action;
  event.timestamp = RNGeofenceNowMs();
  event.synthetic = synthetic;
  event.extras = record.extras;

  if (location != nil && CLLocationCoordinate2DIsValid(location.coordinate)) {
    event.latitude = @(location.coordinate.latitude);
    event.longitude = @(location.coordinate.longitude);
    event.accuracy = location.horizontalAccuracy >= 0 ? @(location.horizontalAccuracy) : nil;
    event.approximate = NO;
  } else {
    // Option 2 from §7.3: synthesise from the region. Option 3 — a one-shot
    // `requestLocation()` per crossing — is deliberately not the default: it costs a
    // GPS fix on every crossing and can hang for seconds inside a background-relaunch
    // window we may only have ten of.
    event.latitude = @(record.latitude);
    event.longitude = @(record.longitude);
    event.accuracy = @(record.radius);
    event.approximate = YES;
  }

  return event;
}

- (RNGeofenceEvent *)syntheticEventFor:(RNGeofenceRecord *)record
                                action:(RNGeofenceAction)action
                                center:(CLLocationCoordinate2D)center {
  RNGeofenceEvent *event = [RNGeofenceEvent new];
  event.identifier = record.identifier;
  event.action = action;
  event.timestamp = RNGeofenceNowMs();
  event.synthetic = YES;
  event.extras = record.extras;
  event.latitude = @(center.latitude);
  event.longitude = @(center.longitude);
  event.approximate = YES;
  return event;
}

@end
