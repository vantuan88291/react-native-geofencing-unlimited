import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  Geofencing,
  type GeofencesChange,
  type GeofencingState,
  type RegisteredGeofence,
} from 'react-native-geofencing-unlimited';
import { appendEvents, clearLog, readLog, type LogEntry } from './eventLog';
import {
  distanceMetres,
  seedDenseCluster,
  seedGeofences,
  type LatLng,
} from './seed';

/**
 * The §16 harness.
 *
 * This is not a demo. It is the **only practical way to validate** the behaviour §14
 * cannot unit-test: rotation as you move, killed-app delivery, reboot recovery, and
 * the permission staging on each API level. Every panel exists because some bug is
 * invisible without it.
 */
export default function App() {
  const [state, setState] = useState<GeofencingState | null>(null);
  const [geofences, setGeofences] = useState<RegisteredGeofence[]>([]);
  const [activeIds, setActiveIds] = useState<string[]>([]);
  const [log, setLog] = useState<LogEntry[]>([]);
  const [lastChange, setLastChange] = useState<GeofencesChange | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    const [nextState, nextGeofences, nextActive, nextLog] = await Promise.all([
      Geofencing.getState(),
      Geofencing.getGeofences(),
      Geofencing.getActiveGeofences(),
      readLog(),
    ]);
    if (!mounted.current) {
      return;
    }
    setState(nextState);
    setGeofences(nextGeofences);
    setActiveIds(nextActive);
    setLog(nextLog);
  }, []);

  useEffect(() => {
    mounted.current = true;

    // Subscribe BEFORE ready(): ready() flushes whatever the native queue collected
    // while JS was down, and the wrapper holds those for the first listener either
    // way — but subscribing first is the pattern to copy.
    const eventSub = Geofencing.onGeofence(async (event) => {
      await appendEvents([event], 'foreground');
      await refresh();
    });

    const changeSub = Geofencing.onGeofencesChange((change) => {
      // When this stops firing as you move, rotation has stalled (§16).
      setLastChange(change);
      refresh();
    });

    (async () => {
      await Geofencing.ready({
        proximityRadius: 2000,
        initialTriggerEntry: true,
        // Opt-in on Android: costs a short-lived foreground-service notification per
        // event, and is the only way to run JS from a killed app (§6.5).
        enableHeadless: true,
        // Logs every rotation's centre, boundary radius and on/off diff. Read this
        // first in any bug report (§14).
        debug: true,
      });
      await refresh();
    })();

    // The first thing to read in any bug report, so it is kept live (§16).
    const timer = setInterval(refresh, 5000);

    return () => {
      mounted.current = false;
      eventSub.remove();
      changeSub.remove();
      clearInterval(timer);
    };
  }, [refresh]);

  const run = useCallback(
    async (label: string, action: () => Promise<unknown>) => {
      setBusy(label);
      try {
        await action();
        await refresh();
      } catch (error) {
        // Every rejection carries a §8.4 code, which is what to branch on.
        const code = (error as { code?: string }).code ?? 'unknown';
        Alert.alert(`${label} failed`, `${code}\n\n${String(error)}`);
      } finally {
        setBusy(null);
      }
    },
    [refresh]
  );

  /**
   * Seeding needs a position, and the rotation centre already is one — which is why
   * this app needs no location dependency and no map (§16).
   */
  const rotationCenter: LatLng | null =
    state?.rotationCenterLatitude != null &&
    state?.rotationCenterLongitude != null
      ? {
          latitude: state.rotationCenterLatitude,
          longitude: state.rotationCenterLongitude,
        }
      : null;

  const seed = (count: number) =>
    run(`Add ${count}`, async () => {
      if (rotationCenter === null) {
        throw new Error(
          'No rotation centre yet — grant permission and press start first.'
        );
      }
      await Geofencing.addGeofences(seedGeofences(rotationCenter, count));
    });

  const activeSet = new Set(activeIds);
  const sorted = rotationCenter
    ? [...geofences]
        .map((g) => ({ g, distance: distanceMetres(rotationCenter, g) }))
        .sort((a, b) => a.distance - b.distance)
    : geofences.map((g) => ({ g, distance: Number.NaN }));

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Text style={styles.title}>react-native-geofencing-unlimited</Text>
      <Text style={styles.subtitle}>
        {Platform.OS} · {Platform.OS === 'ios' ? '19 of 20' : '99 of 100'} slots
        usable
      </Text>

      {busy !== null && (
        <View style={styles.busy}>
          <ActivityIndicator />
          <Text style={styles.busyText}>{busy}…</Text>
        </View>
      )}

      {/* ------------------------------------------------------------------ */}
      <Panel title="State" hint="The first thing to read in any bug report">
        {state === null ? (
          <Text style={styles.dim}>loading…</Text>
        ) : (
          <>
            <Row label="enabled" value={String(state.enabled)} />
            <Row
              label="available"
              value={String(state.available)}
              warn={!state.available}
            />
            <Row
              label="authorization"
              value={state.authorization}
              warn={state.authorization !== 'always'}
            />
            <Row
              label="accuracy"
              value={state.accuracyAuthorization}
              warn={state.accuracyAuthorization !== 'full'}
            />
            <Row label="geofenceCount" value={String(state.geofenceCount)} />
            <Row label="activeCount" value={String(state.activeCount)} />
            <Row
              label="locationServices"
              value={String(state.locationServicesEnabled)}
              warn={state.locationServicesEnabled === false}
            />
            {Platform.OS === 'android' && (
              <Row
                label="batteryOptimized"
                value={String(state.batteryOptimized)}
                warn={state.batteryOptimized === true}
              />
            )}
            <Row
              label="droppedEvents"
              value={String(state.droppedEventCount ?? 0)}
              warn={(state.droppedEventCount ?? 0) > 0}
            />
          </>
        )}
        {state?.authorization !== 'always' && (
          <Text style={styles.warnNote}>
            Background region events are only delivered with{' '}
            <Text style={styles.mono}>always</Text> authorization. Anything else
            means no crossings while the app is backgrounded.
          </Text>
        )}
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel
        title="Permissions"
        hint="The only way to exercise the §6.7 / §7.1 staging on each API level"
      >
        <View style={styles.buttonRow}>
          <Button
            label="Request permission"
            onPress={() =>
              run('requestPermission', async () => {
                // Resolves with the resulting state even on denial — branch on the
                // state, never on a catch (§8.4).
                const next = await Geofencing.requestPermission();
                if (next.authorization !== 'always') {
                  Alert.alert(
                    'Not enough',
                    `Got "${next.authorization}". Background delivery needs "always" — use Open settings.`
                  );
                }
              })
            }
          />
          <Button
            label="Open settings"
            onPress={() => run('openSettings', Geofencing.openSettings)}
          />
        </View>
        <Text style={styles.dim}>
          Show your rationale before pressing this, not after — Play Store
          policy does not accept one shown afterwards.
        </Text>
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel
        title="Seed"
        hint="300 is the number that forces rotation on both platforms"
      >
        {rotationCenter === null ? (
          <Text style={styles.warnNote}>
            No rotation centre yet. Grant permission, then press{' '}
            <Text style={styles.mono}>start</Text> — geofences are seeded around
            the centre the module resolved, so this app needs no location
            dependency of its own.
          </Text>
        ) : null}
        <View style={styles.buttonRow}>
          <Button
            label="Add 10"
            disabled={rotationCenter === null}
            onPress={() => seed(10)}
          />
          <Button
            label="Add 300"
            disabled={rotationCenter === null}
            onPress={() => seed(300)}
          />
          <Button
            label="Add 2000"
            disabled={rotationCenter === null}
            onPress={() => seed(2000)}
          />
        </View>
        <View style={styles.buttonRow}>
          <Button
            label="Dense cluster (25 in 120 m)"
            disabled={rotationCenter === null}
            onPress={() =>
              run('Dense cluster', async () => {
                if (rotationCenter === null) {
                  return;
                }
                // The §4.3 degenerate case: more regions inside 500 m than iOS has
                // slots, so the boundary clamps up and overlaps excluded regions.
                await Geofencing.addGeofences(seedDenseCluster(rotationCenter));
              })
            }
          />
        </View>
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel
        title="Boundary"
        hint="When this stops updating as you move, rotation has stalled"
      >
        <Row
          label="centre"
          value={
            rotationCenter
              ? `${rotationCenter.latitude.toFixed(5)}, ${rotationCenter.longitude.toFixed(5)}`
              : '—'
          }
        />
        <Row
          label="radius"
          value={
            state?.boundaryRadius != null
              ? `${Math.round(state.boundaryRadius)} m`
              : 'none (whole set fits)'
          }
        />
        <Row
          label="computed"
          value={
            state?.rotationCenterAt != null
              ? new Date(state.rotationCenterAt).toLocaleTimeString()
              : '—'
          }
        />
        {lastChange !== null && (
          <Row
            label="last diff"
            value={`+${lastChange.on.length} / -${lastChange.off.length}`}
          />
        )}
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel
        title={`Registry — ${activeIds.length} armed of ${geofences.length}`}
        hint={
          Platform.OS === 'android'
            ? 'Android has no read API, so "armed" is what we believe (§4.6)'
            : 'iOS answers from monitoredRegions — the OS’s own registry (§4.6)'
        }
      >
        {sorted.length === 0 ? (
          <Text style={styles.dim}>nothing registered</Text>
        ) : (
          <>
            <Text style={styles.dim}>
              Sorted by distance. The armed ones must be the nearest N — a gap
              in that ordering is a rotation bug.
            </Text>
            {sorted.slice(0, 25).map(({ g, distance }) => (
              <View key={g.identifier} style={styles.registryRow}>
                <Text
                  style={[
                    styles.badge,
                    activeSet.has(g.identifier)
                      ? styles.badgeOn
                      : styles.badgeOff,
                  ]}
                >
                  {activeSet.has(g.identifier) ? 'ARMED' : 'off'}
                </Text>
                <Text style={styles.registryId} numberOfLines={1}>
                  {g.identifier}
                </Text>
                <Text style={styles.registryDistance}>
                  {Number.isNaN(distance) ? '—' : `${Math.round(distance)} m`}
                </Text>
              </View>
            ))}
            {sorted.length > 25 && (
              <Text style={styles.dim}>…and {sorted.length - 25} more</Text>
            )}
          </>
        )}
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel title="Queue" hint="Proves killed-app events actually survived">
        <Button
          label="flushQueue()"
          onPress={() =>
            run('flushQueue', async () => {
              const events = await Geofencing.flushQueue();
              await appendEvents(events, 'flush');
              Alert.alert('Flushed', `${events.length} queued event(s)`);
            })
          }
        />
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel
        title={`Event log — ${log.length}`}
        hint="synthetic-vs-real and headless-vs-foreground are invisible anywhere else"
      >
        {log.length === 0 ? (
          <Text style={styles.dim}>
            no events yet — simulate a location crossing
          </Text>
        ) : (
          log.slice(0, 40).map((entry, index) => (
            <View key={`${entry.at}-${index}`} style={styles.logRow}>
              <Text style={styles.logAction}>{entry.action}</Text>
              <Text style={styles.logId} numberOfLines={1}>
                {entry.identifier}
              </Text>
              <Text style={styles.logMeta}>
                {new Date(entry.timestamp).toLocaleTimeString()}
                {entry.synthetic ? ' ·synthetic' : ''}
                {entry.approximate ? ' ·approx' : ''}
                {entry.source === 'headless' ? ' ·[headless]' : ''}
                {entry.source === 'flush' ? ' ·[flushed]' : ''}
              </Text>
            </View>
          ))
        )}
      </Panel>

      {/* ------------------------------------------------------------------ */}
      <Panel title="Controls">
        <View style={styles.buttonRow}>
          <Button
            label="start()"
            onPress={() => run('start', Geofencing.start)}
          />
          <Button label="stop()" onPress={() => run('stop', Geofencing.stop)} />
        </View>
        <View style={styles.buttonRow}>
          <Button
            label="removeGeofences()"
            onPress={() => run('removeGeofences', Geofencing.removeGeofences)}
          />
          <Button
            label="Clear log"
            onPress={() => run('Clear log', clearLog)}
          />
        </View>
      </Panel>

      <Text style={styles.footer}>
        Device checks that no CI job can do: swipe the app away and cross a
        region; reboot and cross a region without opening the app; revoke
        background location in Settings while it runs.
      </Text>
    </ScrollView>
  );
}

function Panel({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.panel}>
      <Text style={styles.panelTitle}>{title}</Text>
      {hint !== undefined && <Text style={styles.panelHint}>{hint}</Text>}
      {children}
    </View>
  );
}

function Row({
  label,
  value,
  warn = false,
}: {
  label: string;
  value: string;
  warn?: boolean;
}) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={[styles.rowValue, warn && styles.rowValueWarn]}>
        {value}
      </Text>
    </View>
  );
}

function Button({
  label,
  onPress,
  disabled = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.button,
        pressed && styles.buttonPressed,
        disabled && styles.buttonDisabled,
      ]}
    >
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#f4f4f5' },
  content: { padding: 16, paddingBottom: 48, gap: 12 },
  title: { fontSize: 20, fontWeight: '700', color: '#18181b' },
  subtitle: { fontSize: 13, color: '#71717a', marginTop: -8 },

  busy: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  busyText: { fontSize: 13, color: '#71717a' },

  panel: {
    backgroundColor: '#ffffff',
    borderRadius: 10,
    padding: 14,
    gap: 8,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#e4e4e7',
  },
  panelTitle: { fontSize: 15, fontWeight: '700', color: '#18181b' },
  panelHint: { fontSize: 11, color: '#a1a1aa', marginTop: -6 },

  row: { flexDirection: 'row', justifyContent: 'space-between' },
  rowLabel: { fontSize: 13, color: '#52525b' },
  rowValue: {
    fontSize: 13,
    color: '#18181b',
    fontVariant: ['tabular-nums'],
    fontWeight: '600',
  },
  rowValueWarn: { color: '#b45309' },
  warnNote: {
    fontSize: 12,
    color: '#b45309',
    backgroundColor: '#fffbeb',
    padding: 8,
    borderRadius: 6,
    lineHeight: 17,
  },
  dim: { fontSize: 12, color: '#a1a1aa', lineHeight: 16 },
  mono: { fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' },

  buttonRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  button: {
    backgroundColor: '#18181b',
    paddingVertical: 9,
    paddingHorizontal: 13,
    borderRadius: 7,
  },
  buttonPressed: { opacity: 0.7 },
  buttonDisabled: { backgroundColor: '#d4d4d8' },
  buttonLabel: { color: '#ffffff', fontSize: 13, fontWeight: '600' },

  registryRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  badge: {
    fontSize: 9,
    fontWeight: '800',
    paddingHorizontal: 5,
    paddingVertical: 2,
    borderRadius: 3,
    overflow: 'hidden',
    width: 46,
    textAlign: 'center',
  },
  badgeOn: { backgroundColor: '#dcfce7', color: '#15803d' },
  badgeOff: { backgroundColor: '#f4f4f5', color: '#a1a1aa' },
  registryId: { flex: 1, fontSize: 12, color: '#3f3f46' },
  registryDistance: {
    fontSize: 12,
    color: '#71717a',
    fontVariant: ['tabular-nums'],
  },

  logRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  logAction: {
    fontSize: 10,
    fontWeight: '800',
    color: '#1d4ed8',
    width: 44,
  },
  logId: { flex: 1, fontSize: 12, color: '#3f3f46' },
  logMeta: { fontSize: 10, color: '#a1a1aa' },

  footer: { fontSize: 11, color: '#a1a1aa', lineHeight: 16, marginTop: 4 },
});
