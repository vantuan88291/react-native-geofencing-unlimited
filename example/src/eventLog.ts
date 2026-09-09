import AsyncStorage from '@react-native-async-storage/async-storage';
import type { GeofenceEvent } from 'react-native-geofencing-unlimited';

/**
 * The event log, persisted (§16).
 *
 * **It has to survive a process kill.** The one test that matters — swipe the app
 * away, drive a route, reopen — has nothing to show otherwise. So the headless task
 * writes here too, not just the React tree, and each entry records which path
 * delivered it.
 */
export type LogSource = 'foreground' | 'headless' | 'flush';

export type LogEntry = {
  at: number;
  source: LogSource;
  action: string;
  identifier: string;
  timestamp: number;
  approximate: boolean;
  synthetic: boolean;
  latitude?: number;
  longitude?: number;
};

const KEY = 'rn-geofencing-example.event-log';
const MAX_ENTRIES = 500;

export async function appendEvents(
  events: GeofenceEvent[],
  source: LogSource
): Promise<void> {
  if (events.length === 0) {
    return;
  }
  const existing = await readLog();
  const entries: LogEntry[] = events.map((event) => ({
    at: Date.now(),
    source,
    action: event.action,
    identifier: event.identifier,
    timestamp: event.timestamp,
    approximate: event.approximate,
    synthetic: event.synthetic,
    ...(event.latitude !== undefined ? { latitude: event.latitude } : {}),
    ...(event.longitude !== undefined ? { longitude: event.longitude } : {}),
  }));

  // Newest first, bounded — the log is a debugging aid, not an archive.
  const next = [...entries.reverse(), ...existing].slice(0, MAX_ENTRIES);
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
}

export async function readLog(): Promise<LogEntry[]> {
  const raw = await AsyncStorage.getItem(KEY);
  if (raw === null) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as LogEntry[]) : [];
  } catch {
    return [];
  }
}

export async function clearLog(): Promise<void> {
  await AsyncStorage.removeItem(KEY);
}
