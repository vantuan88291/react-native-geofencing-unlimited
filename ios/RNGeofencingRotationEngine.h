#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>
#import "RNGeofencingPlatformRegistry.h"
#import "RNGeofencingStore.h"
#import "RNGeofencingTransitionGate.h"
#import "RNGeofencingTypes.h"

NS_ASSUME_NONNULL_BEGIN

/// What -computeActiveSetAround: decided.
@interface RNGeofenceActiveSet : NSObject
@property(nonatomic, copy) NSArray<RNGeofenceRecord *> *active;
/// nil means every geofence fits, so no boundary region is needed (§4.3).
@property(nonatomic, strong, nullable) NSNumber *boundaryRadius;
@end

/// What actually reached the OS. Drives `onGeofencesChange` (§4.4).
@interface RNGeofenceRotationResult : NSObject
@property(nonatomic, copy) NSArray<RNGeofenceRecord *> *on;
@property(nonatomic, copy) NSArray<NSString *> *off;
@property(nonatomic, copy) NSArray<RNGeofenceEvent *> *syntheticEvents;
@property(nonatomic, strong, nullable) NSNumber *boundaryRadius;
@property(nonatomic, assign) CLLocationCoordinate2D center;
@end

/**
 * The active-subset rotation engine (§4) — iOS half.
 *
 * iOS monitors at most 20 regions but apps need thousands. So: keep every geofence in
 * our own store, arm only the nearest 19 at OS level, and arm one extra **boundary
 * region** centred on the position the set was computed from. When the user leaves
 * the boundary, the OS wakes us and we recompute.
 *
 * That boundary slot is what makes the design free — no polling, no location stream.
 * The OS itself says "you have moved far enough that your cached nearest-N is stale".
 */
@interface RNGeofencingRotationEngine : NSObject

- (instancetype)initWithStore:(RNGeofencingStore *)store
                     registry:(RNGeofencingPlatformRegistry *)registry
                         gate:(RNGeofencingTransitionGate *)gate
                     capacity:(NSInteger)capacity;

/// §4.3. Pure, so the capacity, safe-zone and dense-cluster cases are unit-testable.
- (RNGeofenceActiveSet *)computeActiveSetAround:(CLLocationCoordinate2D)center;

/**
 * Diffs against `monitoredRegions` and applies (§4.4).
 *
 * Two orderings are load-bearing:
 * - **Add before remove** (invariant 3). A process killed between the two leaves a
 *   *superset* — still detects everything — never a hole.
 * - **Boundary last.** It is the trigger for the next rotation, and re-arming it
 *   before the set is in place would race a fast-moving user.
 */
- (RNGeofenceRotationResult *)applyActiveSet:(NSArray<RNGeofenceRecord *> *)next
                              boundaryRadius:(nullable NSNumber *)boundaryRadius
                                      center:(CLLocationCoordinate2D)center;

@end

NS_ASSUME_NONNULL_END
