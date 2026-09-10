/**
 * Public types for `react-native-geofencing-unlimited`.
 *
 * These are hand-written and are the contract (§15.5): the codegen spec in
 * `NativeGeofencing.ts` speaks a narrower dialect (no string-literal unions, `extras`
 * as a JSON string), and this file is the ergonomic shape the wrapper presents.
 */

/** A transition reported by the OS, or synthesised by the transition gate (§5.2). */
export type GeofenceAction = 'ENTER' | 'EXIT' | 'DWELL';

/**
 * iOS: `always` is the only authorization that delivers region events in the
 * background (§7.1). Android maps `always` to fine + background granted, and
 * `whenInUse` to fine granted without background.
 */
export type Authorization =
  'always' | 'whenInUse' | 'denied' | 'restricted' | 'notDetermined';

/** iOS 14+ reduced accuracy makes region monitoring unreliable (§7.1, §10). */
export type AccuracyAuthorization = 'full' | 'reduced';

/** A circular geofence as the host app describes it. */
export type Geofence = {
  /** Host-owned, unique. Must not be empty and must not be the reserved boundary id (§4.2). */
  identifier: string;
  latitude: number;
  longitude: number;
  /**
   * Metres. Clamped up to `minRadius` (default 200) — below that neither platform
   * triggers reliably (§10).
   */
  radius: number;
  /** Default `true`. */
  notifyOnEntry?: boolean;
  /** Default `true`. */
  notifyOnExit?: boolean;
  /** Default `false`. Native on Android, emulated on iOS (§5.3, §7.4). */
  notifyOnDwell?: boolean;
  /** Milliseconds the device must stay inside before DWELL fires. Default 30000. */
  loiteringDelay?: number;
  /**
   * Arbitrary host data, round-tripped verbatim. Never reaches the OS, and is not
   * part of `definitionChanged` (§4.4).
   */
  extras?: Record<string, unknown>;
};

/** A geofence as it comes back out of the store — defaults resolved. */
export type RegisteredGeofence = Required<
  Omit<Geofence, 'extras' | 'loiteringDelay'>
> & {
  loiteringDelay: number;
  extras?: Record<string, unknown>;
};

/**
 * A transition delivered to JS.
 *
 * `latitude`/`longitude`/`accuracy` are **best-effort** (§7.3): Android carries the
 * triggering location, iOS does not and falls back to the last known fix or the
 * region centre. Check `approximate` before trusting them, and request your own fix
 * in the handler if you need a precise one.
 */
export type GeofenceEvent = {
  identifier: string;
  action: GeofenceAction;
  /** Epoch milliseconds. */
  timestamp: number;
  latitude?: number;
  longitude?: number;
  /** Horizontal accuracy in metres, when known. */
  accuracy?: number;
  /** `true` when the position was synthesised from the region centre (§7.3). */
  approximate: boolean;
  /** `true` when the gate emitted this itself rather than the OS (§5.2). */
  synthetic: boolean;
  extras?: Record<string, unknown>;
};

/**
 * Which regions are armed at OS level after a rotation (§4.4).
 *
 * `on` carries full definitions of newly armed regions; `off` carries only the
 * identifiers of regions that were disarmed. The reserved boundary region never
 * appears in either (§4.2).
 */
export type GeofencesChange = {
  on: RegisteredGeofence[];
  off: string[];
};

/**
 * Copy for the Android background-location dialog.
 *
 * iOS asks for "always" with a second *system* dialog. Android has no equivalent —
 * API 30+ deliberately removed "Allow all the time" from the system dialog, and the
 * only route to it is the Settings page — so the module shows this dialog itself and
 * opens Settings when the user accepts.
 *
 * Every field is optional; anything omitted falls back to the English default. Supply
 * your own strings to localise, and remember Play Store policy expects the *reason*
 * to be stated before the user is sent to Settings.
 */
export type AndroidBackgroundPermissionRationale = {
  title?: string;
  message?: string;
  /** Opens the app's settings page. */
  positiveButton?: string;
  /** Dismisses; `requestPermission()` still resolves with the resulting state. */
  negativeButton?: string;
};

/** Passed to `ready()`; persisted, so it survives into the killed-app paths (§9.2). */
export type GeofencingConfig = {
  /**
   * Metres. Geofences further than this from the rotation centre are not armed.
   * Default 2000.
   */
  proximityRadius?: number;
  /**
   * Whether the very first arming of a geofence the device is already inside fires
   * ENTER. Only affects `state === UNKNOWN` geofences — it never suppresses a
   * genuine ENTER on a region rotated back in (§5.1). Default `true`.
   */
  initialTriggerEntry?: boolean;
  /**
   * Android only. Play Services' geofence delivery latency budget in milliseconds —
   * nothing to do with user-facing notifications (§1, §6.3). Default 0.
   */
  notificationResponsiveness?: number;
  /** iOS only. Backstop rotation trigger if a boundary EXIT is missed (§7.6). Default `true`. */
  useSignificantLocationChanges?: boolean;
  /**
   * Android only. Run JS from a killed app via a short-lived foreground service.
   * Costs a per-event notification and the `FOREGROUND_SERVICE_LOCATION` permission
   * (§6.5). Default `false` — events queue and flush on next launch instead.
   */
  enableHeadless?: boolean;
  /**
   * Android only. Shown by `requestPermission()` when the user granted foreground
   * location but not background, which is the point where iOS would raise its own
   * second system dialog and Android raises nothing at all.
   *
   * On by default, for parity with iOS. Pass `false` to suppress it and drive the
   * flow yourself with `getState()` + `openSettings()`.
   */
  androidBackgroundPermissionRationale?:
    AndroidBackgroundPermissionRationale | false;
  /** Metres. User radii below this are clamped up (§10). Default 200. */
  minRadius?: number;
  /** Log every rotation's centre, boundary radius and on/off diff (§14). Default `false`. */
  debug?: boolean;
};

/** The live device state. Never cached — `getState()` re-reads the grant every call (§6.7). */
export type GeofencingState = {
  /** `start()` has been called and not `stop()`ped. Persisted across process restarts. */
  enabled: boolean;
  /** Play Services present (Android) / region monitoring available (iOS). */
  available: boolean;
  authorization: Authorization;
  accuracyAuthorization: AccuracyAuthorization;
  /** Everything in our store. */
  geofenceCount: number;
  /**
   * Armed at OS level. On iOS this is `monitoredRegions` — the OS's own answer. On
   * Android there is no read API, so it is *what we believe is armed* (§4.6).
   */
  activeCount: number;
  /** Android: app is battery-"Restricted", so delivery may be dropped entirely (§10). */
  batteryOptimized?: boolean;
  /** Location services (any provider) enabled at device level. */
  locationServicesEnabled?: boolean;
  /** Rotation bookkeeping, for debugging a stalled rotation (§16). */
  rotationCenterLatitude?: number;
  rotationCenterLongitude?: number;
  rotationCenterAt?: number;
  boundaryRadius?: number;
  /** Events dropped from the queue because it hit its 200-entry cap (§9.5). */
  droppedEventCount?: number;
};

/** Returned by the two subscribe helpers. */
export type Subscription = {
  remove: () => void;
};

/** Every `reject()` in the module uses one of these codes (§8.4). */
export const GeofencingErrorCode = {
  /** A method was called before `ready()`. */
  NOT_READY: 'E_NOT_READY',
  /** No foreground activity to host a permission dialog (Android). */
  NO_ACTIVITY: 'E_NO_ACTIVITY',
  /** A permission request is already running. */
  REQUEST_IN_FLIGHT: 'E_REQUEST_IN_FLIGHT',
  /** Geofencing is not available on this device. */
  UNAVAILABLE: 'E_UNAVAILABLE',
  /** Empty/reserved identifier, non-finite coordinate, or `radius <= 0`. */
  INVALID_GEOFENCE: 'E_INVALID_GEOFENCE',
  /** The 2000-entry store cap was exceeded. */
  LIMIT_EXCEEDED: 'E_LIMIT_EXCEEDED',
  /** The OS registration call itself failed; the message carries the platform code. */
  PLATFORM: 'E_PLATFORM',
  /** Prefs/UserDefaults read or write, or a JSON parse, failed. */
  STORE: 'E_STORE',
  /** Unclassified; always accompanied by the original message. */
  INTERNAL: 'E_INTERNAL',
} as const;

export type GeofencingErrorCode =
  (typeof GeofencingErrorCode)[keyof typeof GeofencingErrorCode];

/** Handler registered with `registerHeadlessTask` (Android, §6.5). */
export type HeadlessGeofenceTask = (event: GeofenceEvent) => Promise<void>;
