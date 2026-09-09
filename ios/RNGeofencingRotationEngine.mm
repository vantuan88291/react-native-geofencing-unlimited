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

  // ADD BEFORE REMOVE (invariant 3).
  if (toAdd.count > 0) {
    [_registry addRegions:toAdd];
    NSMutableArray<NSString *> *addedIdentifiers = [NSMutableArray new];
    for (RNGeofenceRecord *record in toAdd) {
      [addedIdentifiers addObject:record.identifier];
    }
    [_store setActive:addedIdentifiers active:YES];
  }

  if (toRemove.count > 0) {
    [_registry removeIdentifiers:toRemove];
    [_store setActive:toRemove active:NO];
  }

  // Boundary last, and always torn down first so a stale centre cannot linger.
  [_registry removeBoundary];
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
