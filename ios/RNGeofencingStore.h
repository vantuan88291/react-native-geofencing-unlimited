#import <Foundation/Foundation.h>
#import "RNGeofencingTypes.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * The persistence seam (§9.6) — iOS half.
 *
 * Everything above this class is forbidden to know how the bytes are stored, which is
 * what makes the "no SQLite" decision reversible: if a host app ever needs more than
 * `RNGeofenceMaxGeofences`, swap the implementation rather than scaling the plist
 * blob (§9.5).
 *
 * Not thread-safe on its own. Every read-modify-write is routed through the Core's
 * serial queue instead (§9.4) — the same queue that serialises rotations (§4.5,
 * invariant 2).
 */
@interface RNGeofencingStore : NSObject

/// Parses the blobs once and builds the in-memory working copy (§9.3).
- (void)load;

- (NSArray<RNGeofenceRecord *> *)all;
- (nullable RNGeofenceRecord *)get:(NSString *)identifier;
- (NSInteger)count;

- (void)upsert:(NSArray<RNGeofenceRecord *> *)records;
- (void)deleteIdentifiers:(NSArray<NSString *> *)identifiers;

- (void)updateState:(NSString *)identifier
              state:(RNGeofenceState)state
          enteredAt:(nullable NSNumber *)enteredAt
       dwellEmitted:(BOOL)dwellEmitted;

/// Marks exactly these identifiers armed, everything else not.
- (void)setActiveIdentifiers:(NSSet<NSString *> *)identifiers;
/// Marks a subset armed or not, leaving the rest alone — used for partial failures.
- (void)setActive:(NSArray<NSString *> *)identifiers active:(BOOL)active;
- (NSSet<NSString *> *)activeIdentifiers;

- (void)enqueue:(NSArray<RNGeofenceEvent *> *)events;
- (NSArray<RNGeofenceEvent *> *)drainQueue;
- (NSInteger)queueSize;

@property(nonatomic, strong) RNGeofenceMeta *meta;
@property(nonatomic, strong) RNGeofenceConfig *config;

/**
 * Forces a synchronous flush.
 *
 * `UserDefaults` coalesces its own writes, which is fine in the app, but a
 * background relaunch can be terminated within milliseconds of handing back control
 * — so the relaunch and event paths call this explicitly (§9.3).
 */
- (void)synchronizeNow;

@end

NS_ASSUME_NONNULL_END
