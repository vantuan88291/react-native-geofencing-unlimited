#import "RNGeofencingRotationEngine.h"
#import "RNGeofencingLogger.h"

@implementation RNGeofenceActiveSet
@end

@implementation RNGeofenceRotationResult
@end

/// One candidate plus its distance from the rotation centre.
@interface RNGeofenceCandidate : NSObject
@property(nonatomic, strong) RNGeofenceRecord *record;
@property(nonatomic, assign) double distance;
@end

@implementation RNGeofenceCandidate
@end

@implementation RNGeofencingRotationEngine {
  RNGeofencingStore *_store;
  RNGeofencingPlatformRegistry *_registry;
  RNGeofencingTransitionGate *_gate;
  NSInteger _capacity;
}

- (instancetype)initWithStore:(RNGeofencingStore *)store
                     registry:(RNGeofencingPlatformRegistry *)registry
                         gate:(RNGeofencingTransitionGate *)gate
                     capacity:(NSInteger)capacity {
  if (self = [super init]) {
    _store = store;
    _registry = registry;
    _gate = gate;
    _capacity = capacity;
  }
  return self;
}

#pragma mark - §4.3

- (RNGeofenceActiveSet *)computeActiveSetAround:(CLLocationCoordinate2D)center {
  RNGeofenceConfig *config = _store.config;
  RNGeofenceActiveSet *result = [RNGeofenceActiveSet new];

  NSArray<RNGeofenceRecord *> *stored = [_store all];

  // Linear haversine scan, then sort. No spatial index and no bounding-box prefilter:
  // the store is capped at 2000 entries and already fully in memory, so this is well
  // under a millisecond, and a prefilter only introduces a way to wrongly exclude a
  // candidate (§4.3).
  NSMutableArray<RNGeofenceCandidate *> *candidates = [NSMutableArray new];
  for (RNGeofenceRecord *record in stored) {
    if ([record.identifier isEqualToString:RNGeofenceBoundaryID]) {
      continue;
    }
    double distance = RNGeofenceHaversine(center, record.center);
    // `+ radius`, not bare proximityRadius: a geofence whose *edge* is reachable must
    // never be excluded.
    if (distance > config.proximityRadius + record.radius) {
      continue;
    }
    RNGeofenceCandidate *candidate = [RNGeofenceCandidate new];
    candidate.record = record;
    candidate.distance = distance;
    [candidates addObject:candidate];
  }

  [candidates sortUsingComparator:^NSComparisonResult(RNGeofenceCandidate *a,
                                                      RNGeofenceCandidate *b) {
    if (a.distance < b.distance) {
      return NSOrderedAscending;
    }
    if (a.distance > b.distance) {
      return NSOrderedDescending;
    }
    return NSOrderedSame;
  }];

  // Everything fits — no rotation, and therefore no boundary region to maintain.
  NSMutableArray<RNGeofenceRecord *> *allReal = [NSMutableArray new];
  for (RNGeofenceRecord *record in stored) {
    if (![record.identifier isEqualToString:RNGeofenceBoundaryID]) {
      [allReal addObject:record];
    }
  }
  if ((NSInteger)allReal.count <= _capacity) {
    result.active = allReal;
    result.boundaryRadius = nil;
    return result;
  }

  NSMutableArray<RNGeofenceRecord *> *active = [NSMutableArray new];
  for (NSInteger i = 0; i < (NSInteger)candidates.count && i < _capacity; i++) {
    [active addObject:candidates[(NSUInteger)i].record];
  }

  // The boundary must stay strictly inside the "safe zone": the region within which no
  // UN-ARMED geofence can be reached. That is the distance to the nearest excluded
  // geofence minus its own radius. If the boundary were any larger, the user could
  // walk into an un-armed region and never be detected — the boundary is a *staleness
  // detector*, and it has to trip before the cache can be wrong.
  double safeZone = config.proximityRadius;
  if ((NSInteger)candidates.count > _capacity) {
    RNGeofenceCandidate *firstExcluded = candidates[(NSUInteger)_capacity];
    safeZone = firstExcluded.distance - firstExcluded.record.radius;
  }

  double boundaryRadius = RNGeofenceClamp(safeZone - RNGeofenceBoundarySafetyMarginM,
                                          RNGeofenceMinBoundaryRadiusM, config.proximityRadius);

  if (safeZone - RNGeofenceBoundarySafetyMarginM < RNGeofenceMinBoundaryRadiusM) {
    // Dense cluster: the clamp has produced a boundary that overlaps excluded regions,
    // so an ENTER can be missed. Unavoidable with 20 slots — the host app's options
    // are larger radii or a thinner cluster (§4.3, §10).
    [RNGeofencingLogger warn:@"dense geofence cluster: safe zone is %dm but the minimum boundary "
                             @"radius is %dm — the boundary now overlaps excluded geofences and an "
                             @"ENTER may be missed. Raise geofence radii or thin the cluster.",
                             (int)safeZone, (int)RNGeofenceMinBoundaryRadiusM];
  }

  result.active = active;
  result.boundaryRadius = @(boundaryRadius);
  return result;
}

#pragma mark - §4.4

- (RNGeofenceRotationResult *)applyActiveSet:(NSArray<RNGeofenceRecord *> *)next
                              boundaryRadius:(NSNumber *)boundaryRadius
                                      center:(CLLocationCoordinate2D)center {
  // The OS answers this, authoritatively, and across process restarts (§4.6). No
  // blind process-start re-add is needed on iOS for that reason — the flag-versus-OS
  // divergence that forces one on Android cannot arise here.
  NSSet<NSString *> *currentIdentifiers = [_registry monitoredIdentifiers];

  // Diagnostic. `monitoredRegions` is restored asynchronously by CoreLocation, so
  // reading it too early in a launch reports an empty set — which makes every region
  // look unmonitored, re-registers all of them, and fires a `requestStateForRegion:`
  // storm whose `didDetermineState` answers look like fresh crossings.
  NSInteger storeActive = 0;
  for (RNGeofenceRecord *record in [_store all]) {
    if (record.active) {
      storeActive++;
    }
  }
  [RNGeofencingLogger debug:@"rotation diff: OS reports %lu monitored, store believes %ld active",
                            (unsigned long)currentIdentifiers.count, (long)storeActive];

  NSMutableSet<NSString *> *nextIdentifiers = [NSMutableSet new];
  for (RNGeofenceRecord *record in next) {
    [nextIdentifiers addObject:record.identifier];
  }

  NSMutableArray<RNGeofenceRecord *> *toAdd = [NSMutableArray new];
  for (RNGeofenceRecord *record in next) {
    RNGeofenceRecord *stored = [_store get:record.identifier];
    BOOL alreadyMonitored = [currentIdentifiers containsObject:record.identifier];
    // A definition change clears `active` at upsert time, which is how a re-register
    // of a moved circle gets picked up here even though the OS still lists the id.
    BOOL definitionStale = stored != nil && !stored.active;
    if (!alreadyMonitored || definitionStale) {
      [toAdd addObject:record];
    }
  }

  NSMutableArray<NSString *> *toRemove = [NSMutableArray new];
  for (NSString *identifier in currentIdentifiers) {
    if (![nextIdentifiers containsObject:identifier]) {
      [toRemove addObject:identifier];
    }
  }

  // Furthest-first, so that when slots have to be freed below it is the least
  // relevant regions that go first.
  [toRemove sortUsingComparator:^NSComparisonResult(NSString *a, NSString *b) {
    RNGeofenceRecord *ra = [self->_store get:a];
    RNGeofenceRecord *rb = [self->_store get:b];
    double da = ra != nil ? RNGeofenceHaversine(center, ra.center) : DBL_MAX;
    double db = rb != nil ? RNGeofenceHaversine(center, rb.center) : DBL_MAX;
    if (da > db) {
      return NSOrderedAscending;
    }
    if (da < db) {
      return NSOrderedDescending;
    }
    return NSOrderedSame;
  }];

  // The boundary comes down first. It is re-armed at the new centre at the end of
  // this method anyway, and while it is registered it holds one of the 20 slots — the
  // very slot the adds below may need.
  [_registry removeBoundary];

  // **iOS caps monitored regions at 20, and add-before-remove cannot exceed it.**
  //
  // Invariant 3 wants the adds first, so that a process killed between the two calls
  // leaves a superset rather than a hole. That holds only while the superset can
  // actually exist — and here it cannot: `startMonitoringForRegion:` past the cap is
  // rejected outright, reported asynchronously through
  // `monitoringDidFailForRegion:` as kCLErrorRegionMonitoringFailure, and the *new*
  // regions are simply never armed. With 19 armed plus the boundary the app is
  // already at the cap, so a rotation whose set changes completely would arm almost
  // nothing until the app was killed and relaunched.
  //
  // So the superset is kept whenever it fits, and when it does not, exactly the
  // shortfall is freed first — and only ever from regions that were being removed
  // anyway. A region we intend to keep is never taken down early.
  NSMutableSet<NSString *> *afterAdd = [currentIdentifiers mutableCopy];
  for (RNGeofenceRecord *record in toAdd) {
    [afterAdd addObject:record.identifier];
  }
  NSInteger overflow = (NSInteger)afterAdd.count - _capacity;

  NSUInteger freeCount = overflow > 0 ? MIN((NSUInteger)overflow, toRemove.count) : 0;
  if (freeCount > 0) {
    NSArray<NSString *> *preRemove =
        [toRemove subarrayWithRange:NSMakeRange(0, freeCount)];
    [RNGeofencingLogger warn:@"rotation: freeing %lu slot(s) before adding — %lu armed + %lu to "
                             @"add would exceed the platform cap of %ld",
                             (unsigned long)freeCount, (unsigned long)currentIdentifiers.count,
                             (unsigned long)toAdd.count, (long)(_capacity + 1)];
    [_registry removeIdentifiers:preRemove];
    [_store setActive:preRemove active:NO];
  }

  if (toAdd.count > 0) {
    [_registry addRegions:toAdd];
    NSMutableArray<NSString *> *addedIdentifiers = [NSMutableArray new];
    for (RNGeofenceRecord *record in toAdd) {
      [addedIdentifiers addObject:record.identifier];
    }
    [_store setActive:addedIdentifiers active:YES];
  }

  // Whatever was not already freed above.
  if (toRemove.count > freeCount) {
    NSArray<NSString *> *deferred =
        [toRemove subarrayWithRange:NSMakeRange(freeCount, toRemove.count - freeCount)];
    [_registry removeIdentifiers:deferred];
    [_store setActive:deferred active:NO];
  }

  // Boundary last: it is the trigger for the next rotation, and re-arming it before
  // the set is in place would race a fast-moving user.
  if (boundaryRadius != nil) {
    [_registry addBoundaryAt:center radius:boundaryRadius.doubleValue];
  }

  RNGeofenceMeta *meta = [_store.meta copy];
  meta.centerLatitude = @(center.latitude);
  meta.centerLongitude = @(center.longitude);
  meta.centerAt = @((long long)([[NSDate date] timeIntervalSince1970] * 1000.0));
  meta.boundaryRadius = boundaryRadius;
  _store.meta = meta;

  // Regions rotated out that we still believe we are inside owe the host an EXIT
  // (§5.2). Regions rotated *in* are reconciled by the OS instead: -addRegions:
  // asked for their state, and didDetermineState resolves each one.
  NSArray<RNGeofenceEvent *> *synthetic = [_gate rotationApplied:toRemove center:center];

  [RNGeofencingLogger debug:@"rotation applied: center=(%.5f, %.5f) boundary=%@ on=%@ off=%@ "
                            @"synthetic=%ld",
                            center.latitude, center.longitude,
                            boundaryRadius ? [NSString stringWithFormat:@"%dm",
                                                                        boundaryRadius.intValue]
                                           : @"none",
                            [toAdd valueForKey:@"identifier"], toRemove,
                            (long)synthetic.count];

  RNGeofenceRotationResult *result = [RNGeofenceRotationResult new];
  result.on = toAdd;
  result.off = toRemove;
  result.syntheticEvents = synthetic;
  result.boundaryRadius = boundaryRadius;
  result.center = center;
  return result;
}

@end
