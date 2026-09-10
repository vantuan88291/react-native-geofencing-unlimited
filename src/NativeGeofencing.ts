import { TurboModuleRegistry, type TurboModule } from 'react-native';

/**
 * Codegen spec (§8.1).
 *
 * This file speaks codegen's dialect, not the public one:
 *
 * - `extras` is a **JSON string**, not an object — codegen has no arbitrary-map type
 *   that round-trips cleanly, and keeping the string means the legacy bridge behaves
 *   identically (§15.5). `src/index.tsx` serialises and parses it so callers see a
 *   real object.
 * - `action` / `authorization` are `string`, not string-literal unions. The narrow
 *   types live in `src/type.ts` and the wrapper casts. String-literal unions in
 *   TurboModule object types are not portable across the RN versions this module
 *   supports.
 * - There are **no `EventEmitter<T>` properties**. Codegen events exist only under
 *   the New Architecture; this module emits through `NativeEventEmitter` on both
 *   architectures instead, so the spec declares the two subscription bookkeeping
 *   methods `NativeEventEmitter` requires (§15.4).
 * - Object-typed **parameters** are declared `Object`, not as the structured aliases
 *   below. This is a deliberate deviation from §8.1's literal text. Codegen turns a
 *   *structured* object parameter into a generated C++ struct on iOS
 *   (`JS::NativeGeofencing::ConfigSpec &`), so implementing the protocol exactly
 *   would mean writing against a generated type whose shape moves between RN
 *   versions — and it would stop one implementation from serving both architectures
 *   (§15.3). `Object` maps to `NSDictionary *`, which is stable. Nothing is lost on
 *   Android, where structured objects map to `ReadableMap` either way.
 *
 *   Return types keep their structured aliases: a `Promise` resolves through an
 *   untyped block on both platforms, so those are documentation, and the real
 *   contract for callers is the hand-written types in `src/type.ts` (§15.5).
 */

export type GeofenceSpec = {
  identifier: string;
  latitude: number;
  longitude: number;
  radius: number;
  notifyOnEntry?: boolean;
  notifyOnExit?: boolean;
  notifyOnDwell?: boolean;
  /** Milliseconds; dwell only. */
  loiteringDelay?: number;
  /** JSON string — see the note above. */
  extras?: string;
};

export type GeofenceEventPayload = {
  identifier: string;
  /** 'ENTER' | 'EXIT' | 'DWELL' */
  action: string;
  /** Epoch milliseconds. */
  timestamp: number;
  latitude?: number;
  longitude?: number;
  accuracy?: number;
  /** Position synthesised from the region centre (§7.3). */
  approximate: boolean;
  /** Emitted by the transition gate, not the OS (§5.2). */
  synthetic: boolean;
  /** JSON string. */
  extras?: string;
};

export type ConfigSpec = {
  /** Metres; default 2000. */
  proximityRadius?: number;
  /** Default true. */
  initialTriggerEntry?: boolean;
  /** Android only; milliseconds; default 0. */
  notificationResponsiveness?: number;
  /** iOS only; default true. */
  useSignificantLocationChanges?: boolean;
  /** Android only; default false. */
  enableHeadless?: boolean;
  /** Metres; default 200. */
  minRadius?: number;
  /** Default false. */
  debug?: boolean;
};

export type StateSpec = {
  enabled: boolean;
  /** Play Services (Android) / region monitoring (iOS) available. */
  available: boolean;
  /** 'always' | 'whenInUse' | 'denied' | 'restricted' | 'notDetermined' */
  authorization: string;
  /** 'full' | 'reduced' */
  accuracyAuthorization: string;
  geofenceCount: number;
  activeCount: number;
  batteryOptimized?: boolean;
  locationServicesEnabled?: boolean;
  rotationCenterLatitude?: number;
  rotationCenterLongitude?: number;
  rotationCenterAt?: number;
  boundaryRadius?: number;
  droppedEventCount?: number;
};

export interface Spec extends TurboModule {
  /**
   * Idempotent. Persists the config, arms the stored set, and resolves the live state.
   *
   * `config` is a [ConfigSpec]; see the note above on why it is typed `Object` here.
   */
  ready(config: Object): Promise<StateSpec>;
  start(): Promise<void>;
  stop(): Promise<void>;

  /** `geofence` is a [GeofenceSpec]. */
  addGeofence(geofence: Object): Promise<void>;
  /** Each entry is a [GeofenceSpec]. */
  addGeofences(geofences: ReadonlyArray<Object>): Promise<void>;
  removeGeofence(identifier: string): Promise<void>;
  /** Omit `identifiers` to remove everything. */
  removeGeofences(identifiers?: ReadonlyArray<string>): Promise<void>;
  getGeofences(): Promise<GeofenceSpec[]>;
  /** Currently armed. See §4.6 for what "armed" means on each platform. */
  getActiveGeofences(): Promise<string[]>;

  /** Resolves with the resulting state; a denial is a result, not an error (§8.4). */
  requestPermission(): Promise<StateSpec>;
  /** Android API 30+ background-location fallback (§6.7). No-op elsewhere. */
  openSettings(): Promise<void>;
  getState(): Promise<StateSpec>;
  /** Drains events buffered while JS was not running (§6.5). */
  flushQueue(): Promise<GeofenceEventPayload[]>;
  /** Recent native log lines, newest last. Only populated with `debug: true` (§14). */
  getDebugLog(): Promise<string[]>;

  // NativeEventEmitter bookkeeping (§15.4). Required since RN 0.65 — their absence
  // produces a runtime warning on every subscription.
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

/**
 * `get`, not `getEnforcing` (§15.2).
 *
 * `TurboModuleRegistry.get` falls back to `NativeModules[name]` when there is no
 * TurboModule proxy, so this single call resolves the module under both
 * architectures. A module that is genuinely not linked comes back `null`, which lets
 * `src/index.tsx` raise a readable linking error instead of an import-time invariant
 * violation. Codegen accepts `get` exactly like `getEnforcing`.
 */
export default TurboModuleRegistry.get<Spec>('RNGeofencing');
