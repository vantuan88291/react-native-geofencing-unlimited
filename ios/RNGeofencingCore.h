#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>
#import "RNGeofencingTypes.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * How the Core reaches JS — the seam that keeps React Native out of the core (§11,
 * invariant 1).
 *
 * Implemented by the `RCTEventEmitter` subclass and attached when it is constructed.
 * **Everything here is optional by design**: a terminated-app relaunch has no
 * emitter at all — and often never gets one, because JS may not finish booting inside
 * the OS's ~10 s window — and the Core must still record the crossing, update its
 * state machine and persist the event (§7.5).
 */
@protocol RNGeofencingEventDelegate <NSObject>
/// Is JS actually listening? A `sendEventWithName:` with no listeners drops the event.
- (BOOL)isObserving;
- (void)emitGeofenceEvent:(NSDictionary *)payload;
- (void)emitGeofencesChange:(NSDictionary *)payload;
@end

/**
 * The owner of everything, iOS side (§15.6).
 *
 * `RNGeofencingCore` owns the geofences, the state machine, the store, the queue and
 * the `CLLocationManager`. The React module is a **read-and-forward adapter** over
 * it, constructed and destroyed freely, and never the owner of anything.
 *
 * **The manager and its delegate are created during launch, not when JS calls
 * `ready()`** (§7.5, invariant 11). A monitored-region crossing relaunches a
 * terminated app into the background, and the OS delivers that pending event only to
 * a delegate that already exists. Constructing it lazily from `ready()` happens far
 * too late, and the failure is completely silent — no crash, no log, just no events.
 *
 * This class installs that hook itself, in `+load`, via
 * `UIApplicationDidFinishLaunchingNotification` — mechanism **C** of §18.3. That
 * choice is what makes the module work identically under Expo prebuild and the bare
 * React Native CLI with **no host cooperation at all**: there is no `AppDelegate`
 * edit to survive a `prebuild`, no regex anchor for an Expo SDK bump to break, and no
 * `expo-modules-core` dependency landing on plain-RN consumers.
 */
@interface RNGeofencingCore : NSObject <CLLocationManagerDelegate>

+ (instancetype)sharedInstance;

@property(nonatomic, weak, nullable) id<RNGeofencingEventDelegate> eventDelegate;

/// Serialises every rotation and every read-modify-write (§4.5, §9.4, invariant 2).
@property(nonatomic, readonly) dispatch_queue_t queue;

#pragma mark - §8.1 surface. Each hops onto `queue` and calls back on it.

- (void)readyWithConfig:(NSDictionary *)config
             completion:(void (^)(NSDictionary *state))completion;

- (void)startWithCompletion:(void (^)(NSError *_Nullable error))completion;
- (void)stopWithCompletion:(void (^)(void))completion;

- (void)addGeofences:(NSArray<NSDictionary *> *)specs
          completion:(void (^)(NSError *_Nullable error))completion;

/// Pass nil to remove everything (§8.1).
- (void)removeGeofences:(nullable NSArray<NSString *> *)identifiers
             completion:(void (^)(void))completion;

- (void)getGeofencesWithCompletion:(void (^)(NSArray<NSDictionary *> *geofences))completion;
- (void)getActiveGeofencesWithCompletion:(void (^)(NSArray<NSString *> *identifiers))completion;

/**
 * Requests authorization with the staging Apple expects (§7.1).
 *
 * `whenInUse` first, then `always` — asking for Always up front is grounds for
 * rejection. Region monitoring in the background **requires `authorizedAlways`**;
 * with `authorizedWhenInUse` the OS simply does not deliver, which is the entire use
 * case gone.
 *
 * A denial resolves with the resulting state; it never fails (§8.4).
 */
- (void)requestPermissionWithCompletion:(void (^)(NSDictionary *state))completion;

- (void)getStateWithCompletion:(void (^)(NSDictionary *state))completion;
- (void)flushQueueWithCompletion:(void (^)(NSArray<NSDictionary *> *events))completion;

/// Recent native log lines (§14). Produced before JS is running, so JS has to pull them.
- (void)getDebugLogWithCompletion:(void (^)(NSArray<NSString *> *lines))completion;

/// Flushes anything queued while JS was down, the moment a listener appears (§15.4).
- (void)jsStartedObserving;

/// Cheap correction on foreground: the last known location is fresh (§4.5).
- (void)applicationDidBecomeActive;

@end

NS_ASSUME_NONNULL_END
