import { AppRegistry, NativeEventEmitter, Platform } from 'react-native';
import NativeGeofencing, {
  type ConfigSpec,
  type GeofenceEventPayload,
  type GeofenceSpec,
  type Spec,
  type StateSpec,
} from './NativeGeofencing';
import type {
  GeofenceAction,
  Geofence,
  GeofenceEvent,
  GeofencesChange,
  GeofencingConfig,
  GeofencingState,
  HeadlessGeofenceTask,
  RegisteredGeofence,
  Subscription,
} from './type';

export * from './type';

const LINKING_ERROR =
  `The package 'react-native-geofencing' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

/**
 * Resolved once, lazily. `NativeGeofencing` is nullable by design (§15.2) so that a
 * missing native module produces this message rather than an import-time invariant
 * violation from `getEnforcing`.
 */
const Native: Spec =
  NativeGeofencing ??
  new Proxy({} as Spec, {
    get() {
      throw new Error(LINKING_ERROR);
    },
  });

/** Namespaced so they cannot collide with another library's device events (§15.4). */
const EVENT_GEOFENCE = 'RNGeofencing:geofence';
const EVENT_GEOFENCES_CHANGE = 'RNGeofencing:geofencesChange';

/** The name the Android headless service starts (§6.5). */
const HEADLESS_TASK_KEY = 'RNGeofenceHeadlessTask';

// ---------------------------------------------------------------------------
// extras: JSON string on the wire (§8.1), object at the API surface
// ---------------------------------------------------------------------------

function encodeExtras(extras: Record<string, unknown> | undefined) {
  if (extras === undefined || extras === null) {
    return undefined;
  }
  try {
    return JSON.stringify(extras);
  } catch {
    // A host app that hands us a cyclic object should get a clear failure here
    // rather than an opaque native store error later.
    throw new Error(
      '[react-native-geofencing] `extras` must be JSON-serialisable'
    );
  }
}

function decodeExtras(extras: string | undefined | null) {
  if (extras === undefined || extras === null || extras === '') {
    return undefined;
  }
  try {
    return JSON.parse(extras) as Record<string, unknown>;
  } catch {
    // Never throw out of an event path: a corrupt blob must not swallow a crossing.
    return { __unparsed: extras };
  }
}

function toGeofenceSpec(geofence: Geofence): GeofenceSpec {
  const spec: GeofenceSpec = {
    identifier: geofence.identifier,
    latitude: geofence.latitude,
    longitude: geofence.longitude,
    radius: geofence.radius,
  };
  if (geofence.notifyOnEntry !== undefined) {
    spec.notifyOnEntry = geofence.notifyOnEntry;
  }
  if (geofence.notifyOnExit !== undefined) {
    spec.notifyOnExit = geofence.notifyOnExit;
  }
  if (geofence.notifyOnDwell !== undefined) {
    spec.notifyOnDwell = geofence.notifyOnDwell;
  }
  if (geofence.loiteringDelay !== undefined) {
    spec.loiteringDelay = geofence.loiteringDelay;
  }
  const extras = encodeExtras(geofence.extras);
  if (extras !== undefined) {
    spec.extras = extras;
  }
  return spec;
}

function fromGeofenceSpec(spec: GeofenceSpec): RegisteredGeofence {
  const geofence: RegisteredGeofence = {
    identifier: spec.identifier,
    latitude: spec.latitude,
    longitude: spec.longitude,
    radius: spec.radius,
    notifyOnEntry: spec.notifyOnEntry ?? true,
    notifyOnExit: spec.notifyOnExit ?? true,
    notifyOnDwell: spec.notifyOnDwell ?? false,
    loiteringDelay: spec.loiteringDelay ?? 30000,
  };
  const extras = decodeExtras(spec.extras);
  if (extras !== undefined) {
    geofence.extras = extras;
  }
  return geofence;
}

function fromEventPayload(payload: GeofenceEventPayload): GeofenceEvent {
  const event: GeofenceEvent = {
    identifier: payload.identifier,
    action: payload.action as GeofenceAction,
    timestamp: payload.timestamp,
    approximate: payload.approximate === true,
    synthetic: payload.synthetic === true,
  };
  if (payload.latitude !== undefined && payload.latitude !== null) {
    event.latitude = payload.latitude;
  }
  if (payload.longitude !== undefined && payload.longitude !== null) {
    event.longitude = payload.longitude;
  }
  if (payload.accuracy !== undefined && payload.accuracy !== null) {
    event.accuracy = payload.accuracy;
  }
  const extras = decodeExtras(payload.extras);
  if (extras !== undefined) {
    event.extras = extras;
  }
  return event;
}

function fromStateSpec(state: StateSpec): GeofencingState {
  return {
    ...state,
    authorization: state.authorization as GeofencingState['authorization'],
    accuracyAuthorization:
      state.accuracyAuthorization as GeofencingState['accuracyAuthorization'],
  };
}

function toConfigSpec(config: GeofencingConfig): ConfigSpec {
  // Passed through verbatim; every field is optional on both sides and native owns
  // the defaults, because the killed-app paths read the config from the store and
  // never see this object (§9.2).
  return config;
}

// ---------------------------------------------------------------------------
// Event fan-out
//
// The wrapper keeps its own listener sets rather than handing subscriptions straight
// to NativeEventEmitter, for one reason: `ready()` flushes the native queue (§6.5),
// and a host app that calls `ready()` before `onGeofence()` would otherwise drop
// every event that had been waiting. Flushed events with no listener yet are held
// here and delivered to the first subscriber.
// ---------------------------------------------------------------------------

type EventListener = (event: GeofenceEvent) => void;
type ChangeListener = (change: GeofencesChange) => void;

const eventListeners = new Set<EventListener>();
const changeListeners = new Set<ChangeListener>();
let pendingEvents: GeofenceEvent[] = [];

let emitter: NativeEventEmitter | undefined;
let nativeSubscribed = false;

/**
 * The shape `NativeEventEmitter` actually requires of a native module. React Native
 * does not export its `NativeModule` type from the package root, so it is restated
 * here rather than reached for through a deep import that moves between versions.
 */
type EmitterModule = {
  addListener: (eventName: string) => void;
  removeListeners: (count: number) => void;
};

function getEmitter(): NativeEventEmitter {
  if (emitter === undefined) {
    // `Native` satisfies this shape via addListener/removeListeners, which the spec
    // declares for exactly this purpose (§15.4).
    emitter = new NativeEventEmitter(
      Native as EmitterModule as ConstructorParameters<
        typeof NativeEventEmitter
      >[0]
    );
  }
  return emitter;
}

/** Idempotent; the native side is subscribed once and fanned out from here. */
function ensureNativeSubscription() {
  if (nativeSubscribed) {
    return;
  }
  nativeSubscribed = true;
  const source = getEmitter();

  // The payloads arrive untyped: events go through NativeEventEmitter rather than
  // codegen's EventEmitter<T> (§15.4), so the cast here is where the hand-written
  // contract in `type.ts` is reattached (§15.5).
  source.addListener(EVENT_GEOFENCE, (payload: object) => {
    dispatchEvent(fromEventPayload(payload as GeofenceEventPayload));
  });

  source.addListener(EVENT_GEOFENCES_CHANGE, (raw: object) => {
    const payload = raw as { on?: GeofenceSpec[]; off?: string[] };
    const change: GeofencesChange = {
      on: (payload.on ?? []).map(fromGeofenceSpec),
      off: payload.off ?? [],
    };
    changeListeners.forEach((listener) => {
      try {
        listener(change);
      } catch (error) {
        console.error('[react-native-geofencing] onGeofencesChange', error);
      }
    });
  });
}

function dispatchEvent(event: GeofenceEvent) {
  if (eventListeners.size === 0) {
    // Bounded the same way the native queue is (§9.5) — a host that never
    // subscribes must not grow this without limit.
    pendingEvents.push(event);
    if (pendingEvents.length > 200) {
      pendingEvents.shift();
    }
    return;
  }
  eventListeners.forEach((listener) => {
    try {
      listener(event);
    } catch (error) {
      console.error('[react-native-geofencing] onGeofence', error);
    }
  });
}

// ---------------------------------------------------------------------------
// Public API (§8.2)
// ---------------------------------------------------------------------------

/**
 * Idempotent. Persists `config`, re-arms the stored set, and flushes any events
 * buffered while JS was not running.
 *
 * Safe to call before subscribing: flushed events are held and delivered to the
 * first `onGeofence` listener.
 */
async function ready(config: GeofencingConfig = {}): Promise<GeofencingState> {
  ensureNativeSubscription();
  const state = await Native.ready(toConfigSpec(config));
  // Native queues while no listener is observing; drain what accumulated.
  try {
    const queued = await Native.flushQueue();
    queued.forEach((payload) => dispatchEvent(fromEventPayload(payload)));
  } catch (error) {
    console.warn('[react-native-geofencing] flushQueue on ready failed', error);
  }
  return fromStateSpec(state);
}

/** Begins monitoring. Persisted, so a relaunch re-arms without another `start()`. */
function start(): Promise<void> {
  return Native.start();
}

/** Disarms every region at OS level. The store is kept. */
function stop(): Promise<void> {
  return Native.stop();
}

// `async` so that a non-serialisable `extras` rejects rather than throwing
// synchronously out of a Promise-returning function — a caller's `.catch()` would
// never see a synchronous throw.
async function addGeofence(geofence: Geofence): Promise<void> {
  return Native.addGeofence(toGeofenceSpec(geofence));
}

async function addGeofences(geofences: ReadonlyArray<Geofence>): Promise<void> {
  return Native.addGeofences(geofences.map(toGeofenceSpec));
}

function removeGeofence(identifier: string): Promise<void> {
  return Native.removeGeofence(identifier);
}

/** Omit `identifiers` to remove every geofence. */
function removeGeofences(identifiers?: ReadonlyArray<string>): Promise<void> {
  return identifiers === undefined
    ? Native.removeGeofences()
    : Native.removeGeofences(identifiers);
}

/** Everything in our store, boundary region excluded (§4.2). */
async function getGeofences(): Promise<RegisteredGeofence[]> {
  const geofences = await Native.getGeofences();
  return geofences.map(fromGeofenceSpec);
}

/**
 * Identifiers armed at OS level.
 *
 * iOS answers from `CLLocationManager.monitoredRegions` — the OS's own registry. On
 * Android there is no read API, so this is *what we believe is armed* (§4.6). When
 * debugging a missed crossing, that distinction matters.
 */
function getActiveGeofences(): Promise<string[]> {
  return Native.getActiveGeofences();
}

/**
 * Requests location permission with the correct per-version staging (§6.7, §7.1).
 *
 * **A denial resolves**, with the resulting state — it does not reject. Branch on
 * the returned `authorization`, not on a `catch`. Only a structural failure (no
 * foreground activity, a request already in flight) rejects.
 *
 * Show your rationale *before* calling this; Play Store policy does not accept one
 * shown afterwards.
 */
async function requestPermission(): Promise<GeofencingState> {
  return fromStateSpec(await Native.requestPermission());
}

/**
 * Opens the OS app-settings screen. On Android API 30+ this is the only remaining
 * path to background location once the system has stopped showing the dialog (§6.7).
 */
function openSettings(): Promise<void> {
  return Native.openSettings();
}

/** Always reads the live grant; never a cached value (§6.7). */
async function getState(): Promise<GeofencingState> {
  return fromStateSpec(await Native.getState());
}

/**
 * Drains events buffered natively while JS was not running and delivers them to
 * `onGeofence` listeners, and also returns them.
 */
async function flushQueue(): Promise<GeofenceEvent[]> {
  ensureNativeSubscription();
  const queued = await Native.flushQueue();
  const events = queued.map(fromEventPayload);
  events.forEach(dispatchEvent);
  return events;
}

function onGeofence(callback: EventListener): Subscription {
  ensureNativeSubscription();
  eventListeners.add(callback);

  if (pendingEvents.length > 0) {
    const backlog = pendingEvents;
    pendingEvents = [];
    backlog.forEach((event) => {
      try {
        callback(event);
      } catch (error) {
        console.error('[react-native-geofencing] onGeofence', error);
      }
    });
  }

  return {
    remove: () => {
      eventListeners.delete(callback);
    },
  };
}

function onGeofencesChange(callback: ChangeListener): Subscription {
  ensureNativeSubscription();
  changeListeners.add(callback);
  return {
    remove: () => {
      changeListeners.delete(callback);
    },
  };
}

/**
 * Registers the Android headless task (§6.5, §8.2).
 *
 * **Call this at module scope in `index.js`**, outside the React tree — the headless
 * bundle runs before any component mounts, and a task registered inside a component
 * is not there yet.
 *
 * No-op on iOS: there is no headless JS there (§7.5). Requires
 * `enableHeadless: true` in `ready()`.
 */
function registerHeadlessTask(task: HeadlessGeofenceTask): void {
  if (Platform.OS !== 'android') {
    return;
  }

  AppRegistry.registerHeadlessTask(HEADLESS_TASK_KEY, () => async (data) => {
    // Native hands over a JSON array under `events`: one broadcast can carry
    // several triggering geofences, and spawning one service per event would be
    // both slower and easier to lose.
    const raw = (data as { events?: string } | undefined)?.events;
    if (raw === undefined) {
      return;
    }
    let payloads: GeofenceEventPayload[];
    try {
      payloads = JSON.parse(raw) as GeofenceEventPayload[];
    } catch (error) {
      console.error('[react-native-geofencing] headless payload', error);
      return;
    }
    for (const payload of payloads) {
      await task(fromEventPayload(payload));
    }
  });
}

export const Geofencing = {
  ready,
  start,
  stop,
  addGeofence,
  addGeofences,
  removeGeofence,
  removeGeofences,
  getGeofences,
  getActiveGeofences,
  requestPermission,
  openSettings,
  getState,
  flushQueue,
  onGeofence,
  onGeofencesChange,
  registerHeadlessTask,
};

export default Geofencing;
