#import "RNGeofencingPlatformRegistry.h"
#import "RNGeofencingLogger.h"

/**
 * Every `CLLocationManager` interaction is marshalled onto the main queue.
 *
 * The manager is created on the main thread during launch (§7.5, invariant 11), and
 * `CLLocationManager` is documented as belonging to the thread it was initialised on
 * — but rotation runs on the Core's serial queue (§4.5, invariant 2). So the two are
 * bridged here, synchronously, which keeps the rotation's read-diff-write atomic
 * while still touching the manager only from the main thread.
 *
 * `dispatch_sync` to main is safe from the Core's queue: the main thread never blocks
 * waiting on that queue, so the two cannot deadlock. Do not call into this class
 * *from* a context where main is already blocked on the Core queue.
 */
static void RNGeofenceRunOnMain(dispatch_block_t block) {
  if ([NSThread isMainThread]) {
    block();
  } else {
    dispatch_sync(dispatch_get_main_queue(), block);
  }
}

@implementation RNGeofencingPlatformRegistry {
  CLLocationManager *_manager;
}

- (instancetype)initWithManager:(CLLocationManager *)manager {
  if (self = [super init]) {
    _manager = manager;
  }
  return self;
}

+ (BOOL)isAvailable {
  return [CLLocationManager isMonitoringAvailableForClass:[CLCircularRegion class]];
}

#pragma mark - Reads

- (NSSet<NSString *> *)monitoredIdentifiers {
  NSMutableSet<NSString *> *identifiers = [NSMutableSet new];
  RNGeofenceRunOnMain(^{
    for (CLRegion *region in self->_manager.monitoredRegions) {
      if (![region.identifier isEqualToString:RNGeofenceBoundaryID]) {
        [identifiers addObject:region.identifier];
      }
    }
  });
  return identifiers;
}

- (BOOL)isBoundaryMonitored {
  __block BOOL monitored = NO;
  RNGeofenceRunOnMain(^{
    for (CLRegion *region in self->_manager.monitoredRegions) {
      if ([region.identifier isEqualToString:RNGeofenceBoundaryID]) {
        monitored = YES;
        break;
      }
    }
  });
  return monitored;
}

#pragma mark - Writes

- (void)addRegions:(NSArray<RNGeofenceRecord *> *)records {
  RNGeofenceRunOnMain(^{
    for (RNGeofenceRecord *record in records) {
      CLCircularRegion *region = [self regionFor:record];
      if (region == nil) {
        continue;
      }
      [self->_manager startMonitoringForRegion:region];
      // Drives `initialTriggerEntry` and the §5.2 reconciliation. Asking immediately
      // after arming is what makes a region the device is already inside resolve
      // without waiting for a crossing.
      [self->_manager requestStateForRegion:region];
    }
  });
  if (records.count > 0) {
    [RNGeofencingLogger debug:@"registry: armed %ld region(s)", (long)records.count];
  }
}

/// Must be called on the main queue — see RNGeofenceRunOnMain above.
- (nullable CLCircularRegion *)regionFor:(RNGeofenceRecord *)record {
  if (!record.notifyOnEntry && !record.notifyOnExit && !record.notifyOnDwell) {
    [RNGeofencingLogger warn:@"registry: skipping '%@' — no transition type enabled",
                             record.identifier];
    return nil;
  }

  // The OS silently refuses anything larger, so clamp rather than let the region be
  // dropped without a word.
  double radius = MIN(record.radius, _manager.maximumRegionMonitoringDistance);

  CLCircularRegion *region = [[CLCircularRegion alloc] initWithCenter:record.center
                                                              radius:radius
                                                          identifier:record.identifier];
  // `CLCircularRegion` has no dwell and no loitering delay, so dwell is emulated
  // (§7.4) — and the emulation needs entry to start the clock and exit to cancel it.
  region.notifyOnEntry = record.notifyOnEntry || record.notifyOnDwell;
  region.notifyOnExit = record.notifyOnExit || record.notifyOnDwell;
  return region;
}

- (void)removeIdentifiers:(NSArray<NSString *> *)identifiers {
  if (identifiers.count == 0) {
    return;
  }
  // `stopMonitoring(for:)` needs a region *object*, so the live ones are found in
  // `monitoredRegions` rather than reconstructed — a reconstructed region with the
  // same identifier works, but finding the real one cannot get the geometry wrong.
  [self removeIdentifiersIncludingBoundary:identifiers];
  [RNGeofencingLogger debug:@"registry: disarmed %ld region(s)", (long)identifiers.count];
}

- (void)addBoundaryAt:(CLLocationCoordinate2D)center radius:(double)radius {
  __block double clamped = radius;
  RNGeofenceRunOnMain(^{
    clamped = MIN(radius, self->_manager.maximumRegionMonitoringDistance);
    CLCircularRegion *boundary = [[CLCircularRegion alloc] initWithCenter:center
                                                                  radius:clamped
                                                              identifier:RNGeofenceBoundaryID];
    // Exit only: the boundary is a staleness detector, and entering it means nothing.
    boundary.notifyOnEntry = NO;
    boundary.notifyOnExit = YES;
    [self->_manager startMonitoringForRegion:boundary];
  });
  [RNGeofencingLogger debug:@"registry: boundary armed at %.5f,%.5f r=%dm", center.latitude,
                            center.longitude, (int)clamped];
}

- (void)removeBoundary {
  [self removeIdentifiersIncludingBoundary:@[ RNGeofenceBoundaryID ]];
}

- (void)removeAll {
  RNGeofenceRunOnMain(^{
    for (CLRegion *region in [self->_manager.monitoredRegions copy]) {
      [self->_manager stopMonitoringForRegion:region];
    }
  });
}

- (void)removeIdentifiersIncludingBoundary:(NSArray<NSString *> *)identifiers {
  if (identifiers.count == 0) {
    return;
  }
  NSSet<NSString *> *targets = [NSSet setWithArray:identifiers];
  RNGeofenceRunOnMain(^{
    for (CLRegion *region in [self->_manager.monitoredRegions copy]) {
      if ([targets containsObject:region.identifier]) {
        [self->_manager stopMonitoringForRegion:region];
      }
    }
  });
}

#pragma mark - State queries

- (void)requestStateFor:(NSString *)identifier {
  RNGeofenceRunOnMain(^{
    for (CLRegion *region in [self->_manager.monitoredRegions copy]) {
      if ([region.identifier isEqualToString:identifier]) {
        [self->_manager requestStateForRegion:region];
        return;
      }
    }
  });
}

- (void)requestStateForAllMonitored {
  RNGeofenceRunOnMain(^{
    for (CLRegion *region in [self->_manager.monitoredRegions copy]) {
      if (![region.identifier isEqualToString:RNGeofenceBoundaryID]) {
        [self->_manager requestStateForRegion:region];
      }
    }
  });
}

@end
