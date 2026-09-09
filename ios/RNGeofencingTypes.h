#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Core data model, iOS side.
 *
 * The algorithm here is the same one as `android/src/main/java/com/rngeofencing/core`
 * — §3 duplicates the core per platform rather than sharing a C++ layer, so the two
 * must be kept in step deliberately. Where iOS genuinely differs (no event location,
 * no native dwell, an authoritative OS registry) the comment says so.
 */

/// Reserved identifier for the boundary region. Never escapes a public API (§4.2, invariant 10).
extern NSString *const RNGeofenceBoundaryID;

/// iOS monitors at most 20 regions; one slot is the boundary (§4.2).
static const NSInteger RNGeofenceIOSMaxRegions = 20;
static const NSInteger RNGeofenceIOSActiveCapacity = RNGeofenceIOSMaxRegions - 1;

/// The boundary must trip before the cached nearest-N can be wrong (§4.3).
static const double RNGeofenceBoundarySafetyMarginM = 200.0;

/// Below this the OS is unreliable about reporting the crossing (§4.3).
static const double RNGeofenceMinBoundaryRadiusM = 500.0;

/// Whole-file-rewrite store, so both blobs are bounded (§9.5).
static const NSInteger RNGeofenceMaxGeofences = 2000;
static const NSInteger RNGeofenceMaxQueuedEvents = 200;

/// Per-geofence transition state (§5).
typedef NS_ENUM(NSInteger, RNGeofenceState) {
  RNGeofenceStateUnknown = 0,
  RNGeofenceStateInside,
  RNGeofenceStateOutside,
};

typedef NS_ENUM(NSInteger, RNGeofenceAction) {
  RNGeofenceActionEnter = 0,
  RNGeofenceActionExit,
  RNGeofenceActionDwell,
};

/// Why a rotation ran. Only used for the `debug: true` log (§14).
typedef NS_ENUM(NSInteger, RNGeofenceRotationTrigger) {
  RNGeofenceRotationTriggerReady = 0,
  RNGeofenceRotationTriggerStart,
  RNGeofenceRotationTriggerBoundaryExit,
  RNGeofenceRotationTriggerGeofencesChanged,
  RNGeofenceRotationTriggerForeground,
  RNGeofenceRotationTriggerSignificantLocationChange,
  RNGeofenceRotationTriggerLaunch,
};

NSString *RNGeofenceActionToString(RNGeofenceAction action);
BOOL RNGeofenceActionFromString(NSString *_Nullable raw, RNGeofenceAction *out);
NSString *RNGeofenceStateToString(RNGeofenceState state);
RNGeofenceState RNGeofenceStateFromString(NSString *_Nullable raw);

/// Great-circle distance in metres (§4.3).
double RNGeofenceHaversine(CLLocationCoordinate2D from, CLLocationCoordinate2D to);
double RNGeofenceClamp(double value, double low, double high);

#pragma mark - Config

/// The config last passed to `ready()`, persisted (§9.2).
@interface RNGeofenceConfig : NSObject <NSCopying>
@property(nonatomic, assign) double proximityRadius;              // default 2000
@property(nonatomic, assign) BOOL initialTriggerEntry;            // default YES
@property(nonatomic, assign) BOOL useSignificantLocationChanges;  // default YES
@property(nonatomic, assign) double minRadius;                    // default 200
@property(nonatomic, assign) BOOL debug;                          // default NO
/// Android-only knobs, kept so the persisted blob round-trips identically (§9.2).
@property(nonatomic, assign) NSInteger notificationResponsiveness;
@property(nonatomic, assign) BOOL enableHeadless;

+ (instancetype)defaultConfig;
+ (instancetype)fromDictionary:(nullable NSDictionary *)dictionary;
- (NSDictionary *)toDictionary;
@end

#pragma mark - Geofence record

/**
 * One geofence, plus the transition-gate state that belongs to it.
 *
 * The two live in the same object — and therefore the same JSON blob — because §5
 * updates state on the same object rotation reads, and splitting them would cost the
 * atomicity that makes the plist store safe (§9.1).
 */
@interface RNGeofenceRecord : NSObject <NSCopying>
@property(nonatomic, copy) NSString *identifier;
@property(nonatomic, assign) double latitude;
@property(nonatomic, assign) double longitude;
@property(nonatomic, assign) double radius;
@property(nonatomic, assign) BOOL notifyOnEntry;
@property(nonatomic, assign) BOOL notifyOnExit;
@property(nonatomic, assign) BOOL notifyOnDwell;
/// Milliseconds.
@property(nonatomic, assign) NSInteger loiteringDelay;
/// Opaque JSON string. Never reaches the OS, never part of -definitionChangedFrom:.
@property(nonatomic, copy, nullable) NSString *extras;

@property(nonatomic, assign) RNGeofenceState state;
/// Epoch milliseconds; nil when not inside.
@property(nonatomic, strong, nullable) NSNumber *enteredAt;
@property(nonatomic, assign) BOOL dwellEmitted;

/**
 * Unlike Android, this is **not** the registry: `CLLocationManager.monitoredRegions`
 * is, and the OS answers it authoritatively across process restarts (§4.6). The flag
 * is kept only so the persisted blob has the same shape on both platforms.
 */
@property(nonatomic, assign) BOOL active;

@property(nonatomic, readonly) CLLocationCoordinate2D center;

+ (instancetype)fromSpec:(NSDictionary *)spec;
+ (nullable instancetype)fromJSON:(NSDictionary *)json identifier:(NSString *)identifier;
- (NSDictionary *)toJSON;
/// The `GeofenceSpec` shape JS expects back (§8.1).
- (NSDictionary *)toSpec;

/**
 * Whether the OS-visible definition changed and the region must be re-registered.
 * Compares position, radius and transition flags only — not `extras`, and not gate
 * state (§4.4).
 */
- (BOOL)definitionChangedFrom:(RNGeofenceRecord *)other;
@end

#pragma mark - Event

/// A transition on its way to JS, or parked in the queue (§9.2).
@interface RNGeofenceEvent : NSObject
@property(nonatomic, copy) NSString *identifier;
@property(nonatomic, assign) RNGeofenceAction action;
/// Epoch milliseconds.
@property(nonatomic, assign) long long timestamp;
@property(nonatomic, strong, nullable) NSNumber *latitude;
@property(nonatomic, strong, nullable) NSNumber *longitude;
@property(nonatomic, strong, nullable) NSNumber *accuracy;
/// Position synthesised from the region centre rather than a fix (§7.3).
@property(nonatomic, assign) BOOL approximate;
/// Emitted by the gate, not the OS (§5.2).
@property(nonatomic, assign) BOOL synthetic;
@property(nonatomic, copy, nullable) NSString *extras;

+ (nullable instancetype)fromJSON:(NSDictionary *)json;
- (NSDictionary *)toJSON;
/// The `GeofenceEventPayload` shape JS expects (§8.1).
- (NSDictionary *)toPayload;
@end

#pragma mark - Rotation meta

/// Rotation bookkeeping (§9.2).
@interface RNGeofenceMeta : NSObject <NSCopying>
@property(nonatomic, strong, nullable) NSNumber *centerLatitude;
@property(nonatomic, strong, nullable) NSNumber *centerLongitude;
@property(nonatomic, strong, nullable) NSNumber *centerAt;
@property(nonatomic, strong, nullable) NSNumber *boundaryRadius;
/// `start()` called and not `stop()`ped. Survives process restarts.
@property(nonatomic, assign) BOOL enabled;
/// Events dropped because the queue hit its cap (§9.5).
@property(nonatomic, assign) NSInteger droppedCount;

+ (instancetype)fromDictionary:(nullable NSDictionary *)dictionary;
- (NSDictionary *)toDictionary;
- (BOOL)hasCenter;
- (CLLocationCoordinate2D)center;
@end

NS_ASSUME_NONNULL_END
