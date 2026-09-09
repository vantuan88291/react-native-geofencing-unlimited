import type { Geofence } from 'react-native-geofencing';

export type LatLng = { latitude: number; longitude: number };

const METRES_PER_DEGREE_LAT = 111_320;

/** Offsets a coordinate by a metre delta. Good enough away from the poles. */
export function offset(
  from: LatLng,
  metresNorth: number,
  metresEast: number
): LatLng {
  const latitude = from.latitude + metresNorth / METRES_PER_DEGREE_LAT;
  const longitude =
    from.longitude +
    metresEast /
      (METRES_PER_DEGREE_LAT * Math.cos((from.latitude * Math.PI) / 180));
  return { latitude, longitude };
}

/**
 * Seeds `count` geofences on a ring-and-spiral around `center`.
 *
 * **300 is the number that matters**: it forces rotation on both platforms (19 of 300
 * armed on iOS, 99 of 300 on Android), which is the §13 phase-5 done condition. 10 is
 * the no-rotation case, and 2000 is the store cap (§9.5) — the call above it must be
 * rejected with `E_LIMIT_EXCEEDED`.
 */
export function seedGeofences(center: LatLng, count: number): Geofence[] {
  const geofences: Geofence[] = [];

  for (let i = 0; i < count; i++) {
    // A spiral rather than a grid: it gives a wide spread of distances from the
    // centre, so the "active list must be the nearest N" check in the Registry panel
    // is actually discriminating.
    const angle = i * 0.7;
    const distance = 150 + i * 25;
    const at = offset(
      center,
      Math.cos(angle) * distance,
      Math.sin(angle) * distance
    );

    geofences.push({
      identifier: `seed-${i}`,
      latitude: at.latitude,
      longitude: at.longitude,
      // Above the 200 m floor of §10 so these actually trigger on a device.
      radius: 250,
      notifyOnEntry: true,
      notifyOnExit: true,
      notifyOnDwell: i % 5 === 0,
      loiteringDelay: 30_000,
      extras: { seedIndex: i, seededAt: Date.now() },
    });
  }

  return geofences;
}

/**
 * A tight cluster: more geofences inside 500 m than iOS has slots.
 *
 * This is the §4.3 degenerate case. The boundary radius clamps up to its 500 m
 * minimum and therefore overlaps excluded regions, so an ENTER can be missed — the
 * module logs a warning and the Boundary panel shows the clamped radius.
 */
export function seedDenseCluster(center: LatLng): Geofence[] {
  return Array.from({ length: 25 }, (_, i) => {
    const at = offset(center, Math.cos(i) * 120, Math.sin(i) * 120);
    return {
      identifier: `cluster-${i}`,
      latitude: at.latitude,
      longitude: at.longitude,
      radius: 200,
      notifyOnEntry: true,
      notifyOnExit: true,
      extras: { cluster: true },
    };
  });
}

/** Great-circle distance in metres, for the Registry panel's sort. */
export function distanceMetres(a: LatLng, b: LatLng): number {
  const R = 6_371_008.8;
  const lat1 = (a.latitude * Math.PI) / 180;
  const lat2 = (b.latitude * Math.PI) / 180;
  const dLat = lat2 - lat1;
  const dLng = ((b.longitude - a.longitude) * Math.PI) / 180;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}
