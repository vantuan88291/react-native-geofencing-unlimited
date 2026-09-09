#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>
#import "RNGeofencingTypes.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * `CLLocationManager` region-monitoring wrapper (§7.2).
 *
 * The important asymmetry with Android lives here: **`monitoredRegions` is the
 * authoritative OS registry** and it survives process restarts, so the diff in §4.4
 * asks the OS rather than trusting our own `active` flag (§4.6). That is the opposite
 * of the Android side, where `GeofencingClient` has no read API at all.
 *
 * The other difference is failure reporting. `startMonitoring(for:)` returns nothing:
 * a rejected region surfaces asynchronously through
 * `locationManager:monitoringDidFailForRegion:withError:`, which the Core implements.
 * `kCLErrorRegionMonitoringFailure` there usually means the 20-region limit was
 * exceeded — i.e. a rotation bug, not a device problem.
 */
@interface RNGeofencingPlatformRegistry : NSObject

/**
 * The manager is owned by the Core and created during launch, never here (§7.5,
 * invariant 11): a relaunch into the background delivers the pending event only to a
 * delegate that already exists.
 */
- (instancetype)initWithManager:(CLLocationManager *)manager;

/// Region monitoring available on this device at all (§8.4 `E_UNAVAILABLE`).
+ (BOOL)isAvailable;

/**
 * What the OS says is armed, boundary excluded (§4.6).
 *
 * This is *what the OS confirms is armed*, unlike the Android answer, which is only
 * what we believe.
 */
- (NSSet<NSString *> *)monitoredIdentifiers;

- (BOOL)isBoundaryMonitored;

- (void)addRegions:(NSArray<RNGeofenceRecord *> *)records;

- (void)removeIdentifiers:(NSArray<NSString *> *)identifiers;

- (void)addBoundaryAt:(CLLocationCoordinate2D)center radius:(double)radius;

- (void)removeBoundary;

/// Disarms every region this module registered, boundary included.
- (void)removeAll;

/**
 * Asks the OS for the current inside/outside state.
 *
 * Answers arrive at `locationManager:didDetermineState:forRegion:`, which is what
 * drives both `initialTriggerEntry` and the §5.2 reconciliation.
 */
- (void)requestStateFor:(NSString *)identifier;

- (void)requestStateForAllMonitored;

@end

NS_ASSUME_NONNULL_END
