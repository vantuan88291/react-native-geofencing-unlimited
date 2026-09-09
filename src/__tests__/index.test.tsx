import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import type { Spec } from '../NativeGeofencing';

/**
 * The wrapper layer (§8.2).
 *
 * The rotation engine and the transition gate are native and are tested on the JVM
 * (`android/src/test/...`, §14). What is only testable here is the seam this file
 * owns: `extras` crossing the bridge as a JSON string (§8.1), and the event fan-out
 * that stops `ready()` from dropping a flushed backlog when the host app subscribes
 * afterwards (§6.5).
 */

/**
 * Mocks typed from the spec itself, so `mockResolvedValue` is checked against each
 * method's real return type. A bare `jest.Mock` defaults to an unknown-returning
 * function, which makes every `mockResolvedValue` argument `never` — it compiles
 * under jest but not under `tsc`.
 */
type Mocked<T> = {
  [K in keyof T]: T[K] extends (...args: infer A) => infer R
    ? jest.Mock<(...args: A) => R>
    : T[K];
};

const mockNative = {
  ready: jest.fn(),
  start: jest.fn(),
  stop: jest.fn(),
  addGeofence: jest.fn(),
  addGeofences: jest.fn(),
  removeGeofence: jest.fn(),
  removeGeofences: jest.fn(),
  getGeofences: jest.fn(),
  getActiveGeofences: jest.fn(),
  requestPermission: jest.fn(),
  openSettings: jest.fn(),
  getState: jest.fn(),
  flushQueue: jest.fn(),
  addListener: jest.fn(),
  removeListeners: jest.fn(),
} as unknown as Mocked<Spec>;

jest.mock('../NativeGeofencing', () => ({
  __esModule: true,
  default: mockNative,
}));

const STATE = {
  enabled: true,
  available: true,
  authorization: 'always',
  accuracyAuthorization: 'full',
  geofenceCount: 0,
  activeCount: 0,
};

const { Geofencing } = require('../index') as typeof import('../index');

describe('extras crosses the bridge as a JSON string', () => {
  beforeEach(() => {
    Object.values(mockNative).forEach((mock) =>
      (mock as jest.Mock).mockReset()
    );
    mockNative.addGeofences.mockResolvedValue(undefined);
    mockNative.addGeofence.mockResolvedValue(undefined);
  });

  it('serialises an extras object on the way down', async () => {
    await Geofencing.addGeofences([
      {
        identifier: 'a',
        latitude: 1,
        longitude: 2,
        radius: 250,
        extras: { placeId: '123', nested: { deep: true } },
      },
    ]);

    const sent = mockNative.addGeofences.mock.calls[0]?.[0] as Array<{
      extras?: string;
    }>;
    // Codegen has no arbitrary-map type that round-trips cleanly, so the wire format
    // is a string and the wrapper owns the conversion (§8.1).
    expect(typeof sent[0]?.extras).toBe('string');
    expect(JSON.parse(sent[0]!.extras!)).toEqual({
      placeId: '123',
      nested: { deep: true },
    });
  });

  it('omits extras entirely when the caller did not set it', async () => {
    await Geofencing.addGeofence({
      identifier: 'a',
      latitude: 1,
      longitude: 2,
      radius: 250,
    });

    const sent = mockNative.addGeofence.mock.calls[0]?.[0] as Record<
      string,
      unknown
    >;
    expect('extras' in sent).toBe(false);
  });

  it('rejects extras that cannot be serialised, before it reaches native', async () => {
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;

    await expect(
      Geofencing.addGeofence({
        identifier: 'a',
        latitude: 1,
        longitude: 2,
        radius: 250,
        extras: cyclic,
      })
    ).rejects.toThrow(/JSON-serialisable/);
    expect(mockNative.addGeofence).not.toHaveBeenCalled();
  });

  it('parses extras back into an object, and resolves defaults', async () => {
    mockNative.getGeofences.mockResolvedValue([
      {
        identifier: 'a',
        latitude: 1,
        longitude: 2,
        radius: 250,
        extras: '{"placeId":"123"}',
      },
    ]);

    const [geofence] = await Geofencing.getGeofences();

    expect(geofence?.extras).toEqual({ placeId: '123' });
    // Native omits the flags it defaulted; the wrapper restores them so callers see
    // a fully resolved record.
    expect(geofence?.notifyOnEntry).toBe(true);
    expect(geofence?.notifyOnDwell).toBe(false);
    expect(geofence?.loiteringDelay).toBe(30000);
  });

  it('never throws on a corrupt extras blob', async () => {
    mockNative.getGeofences.mockResolvedValue([
      {
        identifier: 'a',
        latitude: 1,
        longitude: 2,
        radius: 250,
        extras: '{bad',
      },
    ]);

    const [geofence] = await Geofencing.getGeofences();

    // A corrupt blob must not swallow a crossing, so it is surfaced rather than
    // raised.
    expect(geofence?.extras).toEqual({ __unparsed: '{bad' });
  });
});

describe('removeGeofences', () => {
  beforeEach(() => {
    Object.values(mockNative).forEach((mock) =>
      (mock as jest.Mock).mockReset()
    );
    mockNative.removeGeofences.mockResolvedValue(undefined);
  });

  it('passes no argument at all when asked to remove everything', async () => {
    await Geofencing.removeGeofences();
    expect(mockNative.removeGeofences).toHaveBeenCalledWith();
  });

  it('passes an empty array through rather than treating it as "everything"', async () => {
    await Geofencing.removeGeofences([]);
    // The distinction matters: `[]` removes nothing and must resolve (§8.4), while
    // omitting the argument removes the whole store.
    expect(mockNative.removeGeofences).toHaveBeenCalledWith([]);
  });
});

describe('flushed events are not lost when ready() runs before onGeofence()', () => {
  beforeEach(() => {
    Object.values(mockNative).forEach((mock) =>
      (mock as jest.Mock).mockReset()
    );
    mockNative.ready.mockResolvedValue(STATE);
  });

  it('holds a flushed backlog for the first subscriber', async () => {
    mockNative.flushQueue.mockResolvedValue([
      {
        identifier: 'queued-while-killed',
        action: 'ENTER',
        timestamp: 1736412000000,
        approximate: true,
        synthetic: false,
        extras: '{"placeId":"9"}',
      },
    ]);

    // The host app calls ready() first — which flushes — and only then subscribes.
    // Without the wrapper's pending buffer this event would be emitted into nothing.
    await Geofencing.ready({ proximityRadius: 2000 });

    const received: string[] = [];
    const subscription = Geofencing.onGeofence((event) => {
      received.push(`${event.action}:${event.identifier}`);
      expect(event.extras).toEqual({ placeId: '9' });
      expect(event.approximate).toBe(true);
    });

    expect(received).toEqual(['ENTER:queued-while-killed']);
    subscription.remove();
  });

  it('does not re-deliver the backlog to a second subscriber', async () => {
    mockNative.flushQueue.mockResolvedValue([
      {
        identifier: 'a',
        action: 'EXIT',
        timestamp: 1,
        approximate: false,
        synthetic: true,
      },
    ]);

    await Geofencing.ready({});

    const first: string[] = [];
    const second: string[] = [];
    Geofencing.onGeofence((event) => first.push(event.identifier)).remove();
    Geofencing.onGeofence((event) => second.push(event.identifier)).remove();

    expect(first).toEqual(['a']);
    expect(second).toEqual([]);
  });

  it('survives a flushQueue that rejects', async () => {
    mockNative.flushQueue.mockRejectedValue(new Error('E_STORE'));

    // ready() must still resolve with the state: a queue that cannot be drained is
    // not a reason to fail startup.
    await expect(Geofencing.ready({})).resolves.toMatchObject({
      authorization: 'always',
    });
  });
});

describe('state narrowing', () => {
  beforeEach(() => {
    Object.values(mockNative).forEach((mock) =>
      (mock as jest.Mock).mockReset()
    );
  });

  it('passes the native state through with the string fields narrowed', async () => {
    mockNative.getState.mockResolvedValue({
      ...STATE,
      authorization: 'whenInUse',
      accuracyAuthorization: 'reduced',
      geofenceCount: 300,
      activeCount: 19,
      boundaryRadius: 1300,
    });

    const state = await Geofencing.getState();

    expect(state.authorization).toBe('whenInUse');
    expect(state.accuracyAuthorization).toBe('reduced');
    expect(state.activeCount).toBe(19);
    expect(state.boundaryRadius).toBe(1300);
  });

  it('resolves rather than rejects when permission is denied', async () => {
    // A denial is a result, not an error (§8.4) — the wrapper must not turn it into
    // one.
    mockNative.requestPermission.mockResolvedValue({
      ...STATE,
      authorization: 'denied',
      accuracyAuthorization: 'reduced',
    });

    await expect(Geofencing.requestPermission()).resolves.toMatchObject({
      authorization: 'denied',
    });
  });
});
