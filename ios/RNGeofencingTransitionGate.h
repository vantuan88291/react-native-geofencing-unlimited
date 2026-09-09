#import <Foundation/Foundation.h>
#import "RNGeofencingStore.h"
#import "RNGeofencingTypes.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * Dedup and synthetic events (§5, invariant 6) — iOS half.
 *
 * Same algorithm as the Android gate, with two iOS-specific differences:
 *
 * - The re-arm artifact arrives as `didDetermineState(.inside)` rather than
 *   `INITIAL_TRIGGER_ENTER`, and [determinedStateFor:isInside:center:] is a real OS
 *   callback here instead of Android's distance check.
 * - There is no native DWELL, so entering a region with `notifyOnDwell` schedules the
 *   emulation described in §7.4. The gate records `enteredAt` and `dwellEmitted`; the
 *   scheduling itself belongs to the Core, which owns the timers and the background
 *   task.
 */
@interface RNGeofencingTransitionGate : NSObject

- (instancetype)initWithStore:(RNGeofencingStore *)store;

/// §5.1 / §5.3. Returns the event that survived, or nil if it was dropped.
- (nullable RNGeofenceEvent *)enter:(NSString *)identifier
                           location:(nullable CLLocation *)location;

- (nullable RNGeofenceEvent *)exit:(NSString *)identifier
                          location:(nullable CLLocation *)location;

/// Emulated on iOS — only ever called by the Core's dwell resolution (§7.4).
- (nullable RNGeofenceEvent *)dwell:(NSString *)identifier
                           location:(nullable CLLocation *)location;

/**
 * `didDetermineState` (§5.2).
 *
 * The authoritative reconciliation on iOS: if our state says `INSIDE` and the OS says
 * we are not, the EXIT happened while the region was rotated out and the host is owed
 * a synthetic one.
 */
- (nullable RNGeofenceEvent *)determinedStateFor:(NSString *)identifier
                                        isInside:(BOOL)isInside
                                          center:(CLLocationCoordinate2D)center;

/**
 * Called with the set that is about to be disarmed (§5.2).
 *
 * A region rotated out while our state says `INSIDE` is the missed-EXIT case.
 * Distance from the rotation centre decides: further than its radius means we have
 * already left. Closer means genuinely still inside but out of slots — leave it
 * `INSIDE` and resolve on re-arm, because a synthetic EXIT there would be a lie.
 */
- (NSArray<RNGeofenceEvent *> *)rotationApplied:(NSArray<NSString *> *)disarmedIdentifiers
                                         center:(CLLocationCoordinate2D)center;

@end

NS_ASSUME_NONNULL_END
