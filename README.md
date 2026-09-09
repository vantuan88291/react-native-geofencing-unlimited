# react-native-geofencing-unlimited

Unlimited circular geofences for React Native, with background and killed-app delivery.

Wakes your app when the user enters or leaves one of your circles — even if the app has been
swiped away or the device has rebooted. Nothing else: no continuous location stream, no motion
recognition, no trip recording, no upload queue, and **no always-on notification**.

Works with both the **React Native CLI** and **Expo** (prebuild / EAS Build), under both the New
Architecture and the legacy bridge.

```ts
import { Geofencing } from 'react-native-geofencing-unlimited';

await Geofencing.ready({ proximityRadius: 2000 });
await Geofencing.requestPermission();

await Geofencing.addGeofences(
  places.map((p) => ({
    identifier: p.id,
    latitude: p.lat,
    longitude: p.lng,
    radius: Math.max(p.radius, 200),
    notifyOnEntry: true,
    notifyOnExit: true,
    extras: { placeId: p.id },
  }))
);

await Geofencing.start();

Geofencing.onGeofence((event) => {
  console.log(event.action, event.identifier); // 'ENTER' | 'EXIT' | 'DWELL'
});
```

## How it lifts the platform limits

iOS monitors at most 20 regions and Android at most 100. This module keeps **all** your geofences
in its own store and arms only the nearest N at OS level, plus one extra **boundary region**
centred where that selection was computed. When you leave the boundary, the OS wakes the app and
the active set is recomputed.

That boundary region is what makes it free: no polling and no location stream. The OS itself
reports "you have moved far enough that the cached nearest-N is stale".

```
   2000 geofences in the store
        │
        ├── 19 (iOS) / 99 (Android) armed — the nearest ones
        └──  1 boundary region — the staleness detector
```

## Install

```sh
yarn add react-native-geofencing-unlimited
```

Minimum versions:

| | Requires |
|---|---|
| React Native | **0.74+** (`emitDeviceEvent` is what the Android event path uses) |
| Android `compileSdk` | **34+** — see below; a host still pinning 31 will not build |
| Android `minSdk` | 24 |
| iOS | 15.1 |
| Google Play services | required on Android (see [Non-GMS devices](#known-limits)) |

### Expo

Expo Go **cannot** run this module — it contains custom native code. Use `expo prebuild` or EAS
Build.

Add the plugin to `app.json`:

```json
{
  "expo": {
    "plugins": [
      [
        "react-native-geofencing-unlimited",
        {
          "locationAlwaysAndWhenInUsePermission": "Allow $(PRODUCT_NAME) to use your location to notify you when you arrive.",
          "isAndroidBackgroundLocationEnabled": true
        }
      ]
    ]
  }
}
```

The plugin writes the `Info.plist` keys and appends `location` to `UIBackgroundModes` for you. A
host that would rather own those can put them in `app.json` under `ios.infoPlist` and pass `false`
for the matching props — with `prebuild` on every build, `app.json` is the natural source of truth.

Plugin props:

| Prop | Default | Effect |
|---|---|---|
| `locationWhenInUsePermission` | a generic string | `NSLocationWhenInUseUsageDescription`; `false` skips the key |
| `locationAlwaysAndWhenInUsePermission` | a generic string | `NSLocationAlwaysAndWhenInUseUsageDescription`; `false` skips the key |
| `isAndroidBackgroundLocationEnabled` | `true` | `false` strips `ACCESS_BACKGROUND_LOCATION` from the merged manifest |
| `isAndroidForegroundServiceEnabled` | `false` | `true` keeps the foreground-service permissions; only needed with `enableHeadless: true` |

**`compileSdk >= 34`:** set it with
[`expo-build-properties`](https://docs.expo.dev/versions/latest/sdk/build-properties/), not by
editing gradle. The plugin deliberately does not patch `compileSdkVersion` — Expo pins it per SDK
version, and a plugin that rewrites it fights that pin and breaks on upgrade.

```json
["expo-build-properties", { "android": { "compileSdkVersion": 34, "targetSdkVersion": 34 } }]
```

**The iOS launch hook is automatic, and stays automatic across Expo SDK upgrades.** See
[the launch hook](#the-ios-launch-hook) — there is nothing for you to do, and nothing for an SDK
bump to break.

### Bare React Native CLI

Autolinking handles the rest, but iOS needs two `Info.plist` keys and one background mode, which
the Expo plugin would otherwise have written for you:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Shows which places you are near while the app is open.</string>

<!-- Background region monitoring requires "always". With "when in use" the OS does
     not deliver region events in the background, which is the entire use case. -->
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Notifies you when you arrive at or leave a place, even when the app is closed.</string>

<key>UIBackgroundModes</key>
<array><string>location</string></array>
```

Then `cd ios && pod install`. See `example/ios/GeofencingExample/Info.plist` for a working copy.

Android needs nothing: the library's manifest declares its own permissions, receivers and service,
and AGP merges them into your app.

**Do not** add a `didFinishLaunchingWithOptions` snippet from another library's README. This module
does not need one — see below.

### The iOS launch hook

A monitored-region crossing relaunches a terminated app into the background, and the OS delivers
that pending event **only to a `CLLocationManager` delegate that already exists**. Creating the
manager lazily when JS calls `ready()` is far too late, and the failure is completely silent: no
crash, no log, just no events.

This library installs that hook itself, from `+load` in `RNGeofencingCore.mm`, using
`UIApplicationDidFinishLaunchingNotification`.

**So: nothing for a host app to do, on either workflow.** No `AppDelegate` edit that an
`expo prebuild` would wipe, no regex anchor for an Expo SDK bump to silently stop matching, and no
`expo-modules-core` dependency landing on React Native CLI consumers.

### Google Play background-location declaration

Any app whose **merged** manifest requests `ACCESS_BACKGROUND_LOCATION` needs a background-location
declaration and a manual review from Google — even one that never calls the API. Keeping
`isAndroidBackgroundLocationEnabled: true` (the default) means you will need that declaration.

An app that only needs foreground geofencing can opt out with the plugin prop, or in a bare app
with `tools:node="remove"` in its own manifest.

## Permissions

```ts
// A denial RESOLVES with the resulting state — it does not reject.
// Branch on the state, never on a catch.
const state = await Geofencing.requestPermission();

if (state.authorization !== 'always') {
  // Background delivery needs 'always' on iOS, and fine + background on Android.
  await Geofencing.openSettings();
}
```

Two rules that are easy to get wrong:

- **Show your rationale before calling `requestPermission()`.** Play Store policy does not accept
  one shown afterwards.
- **Re-check on every resume.** Users revoke in Settings, and Android 11+ auto-revokes permissions
  for unused apps. `getState()` always reads the live grant, never a cached value.

What is actually required on Android:

| API level | Needed for background geofencing |
|---|---|
| ≤ 28 | `ACCESS_FINE_LOCATION` only — background is implicit |
| 29 | fine **+** `ACCESS_BACKGROUND_LOCATION`, requestable in one dialog |
| 30+ | the same two, but in **separate** requests, foreground first |
| 31+ | coarse must be requested alongside fine; an *approximate*-only grant is **not** sufficient |
| 33+ | `POST_NOTIFICATIONS`, only when `enableHeadless: true` |

`requestPermission()` stages all of that for you. An approximate-only grant is reported as
`authorization: 'denied'` with `accuracyAuthorization: 'reduced'` — geofencing needs precise
location, and reporting it as success would be a lie.

## Killed-app delivery

| | Behaviour |
|---|---|
| **Android**, `enableHeadless: false` (default) | events are persisted and flushed the next time the app opens. No service, no notification, no `FOREGROUND_SERVICE_LOCATION`. |
| **Android**, `enableHeadless: true` | a short-lived foreground service runs your JS task per event. Costs a low-importance notification for a few seconds. |
| **iOS** | the OS relaunches the app in the background. There is **no headless JS**; events are persisted natively and flushed once JS subscribes. |

`enableHeadless: false` is the single biggest simplification available — take it if your app can
tolerate delayed delivery.

For `enableHeadless: true`, register the task at **module scope in `index.js`**, outside the React
tree — the headless bundle runs before any component mounts:

```js
// index.js
import { Geofencing } from 'react-native-geofencing-unlimited';

Geofencing.registerHeadlessTask(async (event) => {
  await recordArrival(event); // must not depend on your React tree
});

AppRegistry.registerComponent(appName, () => App);
```

**On iOS, assume JS will not run.** The OS gives a relaunched app roughly 10 seconds of background
time, which is often less than a React Native cold start. Anything your app depends on must be
updated natively, in the store, before JS is involved — which is exactly what this module does.

## `event.location` is best-effort

This is the one real behavioural difference from `react-native-background-geolocation`.

Android carries the triggering location on the event. **iOS does not** — `didEnterRegion` gives
only the region. The module falls back to the last known fix, and then to the region centre, and
sets `approximate: true` when it had to synthesise one.

```ts
Geofencing.onGeofence((event) => {
  if (event.approximate) {
    // position was synthesised from the region centre; accuracy ≈ the radius
  }
  if (event.synthetic) {
    // the module emitted this itself: you left a region while it was rotated out
  }
});
```

If you need a precise fix at crossing time, request one yourself in the handler.

## API

| Method | Notes |
|---|---|
| `ready(config)` | Idempotent. Persists config, re-arms the stored set, flushes buffered events. |
| `start()` / `stop()` | `stop()` disarms every region; the store is kept. |
| `addGeofence(g)` / `addGeofences(list)` | Re-adding an id replaces it, keeping its inside/outside state. |
| `removeGeofence(id)` / `removeGeofences(ids?)` | Omit `ids` to remove everything. |
| `getGeofences()` | Everything in the store. |
| `getActiveGeofences()` | Armed at OS level — see the note below. |
| `requestPermission()` | Resolves with the resulting state; never rejects on denial. |
| `openSettings()` | The only remaining path to background location on Android 30+. |
| `getState()` | Live, never cached. |
| `flushQueue()` | Drains events buffered while JS was down. |
| `onGeofence(cb)` | `{ identifier, action, timestamp, latitude?, longitude?, accuracy?, approximate, synthetic, extras? }` |
| `onGeofencesChange(cb)` | `{ on, off }` — which regions are actually armed. |
| `registerHeadlessTask(task)` | Android only; module scope in `index.js`. |

**`getActiveGeofences()` means different things per platform.** iOS answers from
`CLLocationManager.monitoredRegions` — the OS's own registry. Android's `GeofencingClient` has no
read API at all, so the Android answer is *what the module believes is armed*. When you are
debugging a missed crossing, that distinction matters.

### Config

| Option | Default | |
|---|---|---|
| `proximityRadius` | `2000` | metres; geofences further than this are not armed |
| `initialTriggerEntry` | `true` | fire ENTER when a geofence is first armed and you are already inside |
| `minRadius` | `200` | smaller radii are clamped up, with a warning |
| `notificationResponsiveness` | `0` | Android only; Play Services' delivery-latency budget in ms. **Nothing to do with notifications.** |
| `useSignificantLocationChanges` | `true` | iOS only; backstop rotation trigger |
| `enableHeadless` | `false` | Android only; see above |
| `debug` | `false` | logs every rotation's centre, boundary radius and on/off diff |

**Turn on `debug` first when something is wrong.** Almost every issue resolves to reading one
rotation log.

### Error codes

Every rejection carries a `code`, so you can branch on it reliably.

| Code | Meaning |
|---|---|
| `E_NOT_READY` | a method was called before `ready()` |
| `E_NO_ACTIVITY` | `requestPermission()` called with no foreground activity (Android) |
| `E_REQUEST_IN_FLIGHT` | a permission request is already running |
| `E_UNAVAILABLE` | geofencing not available on this device |
| `E_INVALID_GEOFENCE` | empty/reserved identifier, non-finite coordinate, `radius <= 0`, or no transition enabled |
| `E_LIMIT_EXCEEDED` | the 2000-geofence store cap was exceeded |
| `E_PLATFORM` | the OS registration call failed; the message carries the platform's own code |
| `E_STORE` | store read/write or JSON parse failed |
| `E_INTERNAL` | unclassified; always carries the original message |

These deliberately **do not** reject: a permission denial, location services being off, a rotation
centre that could not be resolved, an empty geofence list, or `removeGeofences()` on nothing.

## Known limits

Most support questions land here.

| Limit | Value | Consequence |
|---|---|---|
| iOS monitored regions | 20 (19 usable) | rotation is mandatory |
| Android monitored geofences | 100 (99 usable) | rotation is mandatory |
| Minimum reliable radius | ~100 m Android, **~200 m iOS** | smaller circles frequently never trigger; input is clamped up to `minRadius` |
| Detection latency | seconds to **several minutes** | Doze, Low Power Mode and `notificationResponsiveness` all add delay. **Never treat a geofence event as real-time.** |
| Android battery restriction | app "Restricted" → no delivery | surfaced as `getState().batteryOptimized` |
| iOS terminated relaunch budget | ~10 s | often shorter than an RN cold start; critical work happens natively |
| iOS DWELL accuracy | fires on the next wake for delays > ~25 s | compute duration from the ENTER timestamp, not from arrival time |
| Reduced accuracy (iOS 14+) | large position error | region events become unreliable; surfaced as `accuracyAuthorization` |
| Non-GMS Android | no `GeofencingClient` | `available: false`; the module is inert rather than crashing |
| Airplane mode / GPS off | no events | the module re-arms when providers come back |
| Dense clusters (>19 within 500 m on iOS) | boundary overlaps excluded regions | a possible missed ENTER; logs a warning |
| Total registered geofences | **2000** | `addGeofences` rejects beyond it with `E_LIMIT_EXCEEDED` |
| Undelivered event queue | **200** | oldest dropped first; counted in `getState().droppedEventCount` |
| Android before first unlock after reboot | geofences unregistered | the store is credential-encrypted and unreadable in Direct Boot |

**iOS file protection:** if your app raises its data-protection class to
`NSFileProtectionComplete`, this module's `UserDefaults` reads can fail while the device is locked.
The default (`…CompleteUntilFirstUserAuthentication`) is fine.

## Migrating from `react-native-background-geolocation`

For a project using the paid library for geofencing only.

| Before | After |
|---|---|
| `BackgroundGeolocation.ready(config)` | `Geofencing.ready({ proximityRadius, initialTriggerEntry })` |
| `BackgroundGeolocation.startGeofences()` | `Geofencing.start()` |
| `BackgroundGeolocation.stop()` | `Geofencing.stop()` |
| `BackgroundGeolocation.addGeofences(list)` | `Geofencing.addGeofences(list)` — same object shape |
| `BackgroundGeolocation.getGeofences()` | `Geofencing.getGeofences()` |
| `BackgroundGeolocation.removeGeofences()` | `Geofencing.removeGeofences()` |
| `BackgroundGeolocation.onGeofence(cb)` | `Geofencing.onGeofence(cb)` |
| `BackgroundGeolocation.onGeofencesChange(cb)` | `Geofencing.onGeofencesChange(cb)` |
| `config.geofenceProximityRadius` | `config.proximityRadius` |
| `config.geofenceInitialTriggerEntry` | `config.initialTriggerEntry` |
| `BackgroundGeolocation.registerHeadlessTask` | `Geofencing.registerHeadlessTask` (geofence events only) |
| `event.location` (always present) | `event.latitude` / `longitude` / `accuracy`, **may be absent or approximate** |

`event.location` is the only behavioural break. **Audit every geofence handler for code that
assumes a precise fix on the event.**

Not provided, by design: polygon geofences, continuous location streaming, motion/activity
recognition, trip recording, HTTP upload, and scheduling. If you need a location stream, add a
separate plain location module — the whole point of this one is that it stays small.

## Example app

`example/` is the harness for everything that cannot be unit-tested:

```sh
yarn
yarn example android   # or: yarn example ios
```

One screen showing live state, the permission flow, seed buttons (10 / 300 / 2000 geofences and a
dense cluster), the store-vs-armed registry sorted by distance, the current rotation centre and
boundary radius, a persisted event log that marks `[headless]` and `synthetic` events, and the
pending queue. `example/fixtures/` has two GPX routes: one that forces repeated rotation, and one
that traverses the degenerate dense-cluster case.

**300 is the number that matters** — it forces rotation on both platforms.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT
