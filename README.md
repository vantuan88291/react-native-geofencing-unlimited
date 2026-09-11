# react-native-geofencing-unlimited

[![npm version](https://img.shields.io/npm/v/react-native-geofencing-unlimited.svg?style=flat-square)](https://www.npmjs.com/package/react-native-geofencing-unlimited)
[![npm downloads](https://img.shields.io/npm/dm/react-native-geofencing-unlimited.svg?style=flat-square)](https://www.npmjs.com/package/react-native-geofencing-unlimited)
[![license](https://img.shields.io/npm/l/react-native-geofencing-unlimited.svg?style=flat-square)](./LICENSE)
[![platforms](https://img.shields.io/badge/platforms-ios%20%7C%20android-lightgrey.svg?style=flat-square)](#install)

Unlimited circular geofences for React Native, with background and killed-app delivery.

Wakes your app when the user enters or leaves one of your circles — even if the app has been
swiped away or the device has rebooted. Nothing else, by design: no polygon geofences, no
continuous location stream, no motion or activity recognition, no trip recording, no upload queue,
no scheduling, and **no always-on notification**. Need a location stream too? Add a plain location
module alongside — this one stays small on purpose.

Works with both the **React Native CLI** and **Expo** (prebuild / EAS Build), under both the New
Architecture and the legacy bridge.

```ts
import { Geofencing } from 'react-native-geofencing-unlimited';

// Subscribe first: ready() flushes whatever arrived while your JS was down.
Geofencing.onGeofence((event) => {
  console.log(event.action, event.identifier); // 'ENTER' | 'EXIT' | 'DWELL'
});

const state = await Geofencing.ready({ proximityRadius: 2000 });
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

// `enabled` is persisted by the module, so this is a no-op on later launches.
if (!state.enabled) await Geofencing.start();
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

Expo Go **cannot** run this module — it ships native code. Use `expo prebuild` or EAS Build.

```json
{
  "expo": {
    "plugins": [
      [
        "react-native-geofencing-unlimited",
        {
          "locationAlwaysAndWhenInUsePermission": "Allow $(PRODUCT_NAME) to use your location to notify you when you arrive."
        }
      ]
    ]
  }
}
```

The plugin writes the `Info.plist` keys and appends `location` to `UIBackgroundModes`. It never
*adds* Android permissions — the library manifest declares those and AGP merges them into your
app — it only **removes** what you opt out of.

| Prop | Default | Effect |
|---|---|---|
| `locationWhenInUsePermission` | generic string | `NSLocationWhenInUseUsageDescription`; `false` skips the key |
| `locationAlwaysAndWhenInUsePermission` | generic string | `NSLocationAlwaysAndWhenInUseUsageDescription`; `false` skips the key |
| `isAndroidBackgroundLocationEnabled` | `true` | `false` strips `ACCESS_BACKGROUND_LOCATION` |
| `isAndroidForegroundServiceEnabled` | `false` | `true` keeps `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `WAKE_LOCK` |

> **`enableHeadless: true` also needs `isAndroidForegroundServiceEnabled: true`.** One is a
> runtime argument, the other is stripped at *build* time, so only the running app can compare
> them — `ready()` does, and logs `MISCONFIGURED:` on the first mismatch. Without the permissions
> the headless service cannot start and events wait for the next launch.
>
> Not free, either: `FOREGROUND_SERVICE_LOCATION` obliges a foreground-service declaration in the
> Play Console. Turn it on for headless delivery, not as insurance.

Already own your permission strings — in `ios.infoPlist`, or through `expo-location`? Pass
`false` for the matching props here so this plugin skips those keys. Mind the asymmetry: `false`
on **this** plugin means *skip the key*, while `false` on `expo-location` means *delete it*.
Where two plugins both pass an explicit string, the one listed **first** wins.

**`compileSdk >= 34`** goes through
[`expo-build-properties`](https://docs.expo.dev/versions/latest/sdk/build-properties/), never a
gradle edit — Expo pins it per SDK version and a plugin that rewrites it breaks on upgrade.

```json
["expo-build-properties", { "android": { "compileSdkVersion": 34, "targetSdkVersion": 34 } }]
```

The [iOS launch hook](#the-ios-launch-hook) needs nothing from you and survives SDK bumps.

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

Then `cd ios && pod install`. `example/ios/GeofencingExample/Info.plist` is a working copy.

Android needs nothing: the library manifest declares its own permissions, receivers and service and
AGP merges them into your app. Minification is covered too — the shipped `consumer-rules.pro` is
applied by R8 automatically, and it matters because two components are addressed by name at runtime
and renaming either would break delivery in release builds only.

**Do not** paste a `didFinishLaunchingWithOptions` snippet from another library's README. This
module does not need one — see below.

### The iOS launch hook

A monitored-region crossing relaunches a terminated app into the background, and the OS delivers
that pending event **only to a `CLLocationManager` delegate that already exists**. Creating the
manager lazily when JS calls `ready()` is far too late, and the failure is completely silent: no
crash, no log, just no events.

This library installs that hook itself, from `+load` in `RNGeofencingCore.mm` via
`UIApplicationDidFinishLaunchingNotification` — so there is **nothing for a host app to do** on
either workflow. No `AppDelegate` edit for an `expo prebuild` to wipe, no regex anchor for an Expo
SDK bump to silently stop matching, and no `expo-modules-core` dependency landing on React Native
CLI consumers.

### Google Play background-location declaration

Any app whose **merged** manifest requests `ACCESS_BACKGROUND_LOCATION` needs a background-location
declaration and a manual review from Google — even one that never calls the API. Keeping
`isAndroidBackgroundLocationEnabled: true` (the default) means you will need it. An app that only
needs foreground geofencing opts out with that plugin prop, or with `tools:node="remove"` in a bare
app's own manifest.

## Lifecycle

**`ready()` on every app start. `start()` once** — it is a user-facing switch, not a lifecycle call.

| | When to call it | Why |
|---|---|---|
| `ready()` | **every app start** | applies your config, flushes events buffered while JS was down, re-arms the stored set |
| `start()` / `stop()` | **when the user turns the feature on or off** | `enabled` is persisted; it survives every relaunch and every reboot |

### Setup — safe to run on every launch

```ts
const sub = Geofencing.onGeofence(handler);     // 1. subscribe first
const state = await Geofencing.ready({ ... });  // 2. config + flush queue
if (!state.available) return;                   // 3. bail on unsupported devices
if (state.authorization !== 'always') {         // 4. ask only when needed
  await Geofencing.requestPermission();
}
await Geofencing.addGeofences(places);          // 5. add — batched, never in a loop
if (!state.enabled) await Geofencing.start();   // 6. arm — only if not already armed
```

Why the two guards:

| Guard | Without it |
|---|---|
| `authorization !== 'always'` | **iOS**: `requestAlwaysAuthorization` is a one-shot per install; once spent it fires no callback, so the promise waits out its 30-second backstop *on every launch* and stalls everything after it. **Android 30+**: the rationale dialog reappears every launch |
| `!state.enabled` | One wasted rotation per launch — and once you add a settings toggle, the feature silently switches back on for a user who turned it off |

Better still, ask for permission behind a user action that explains the benefit: Play policy
expects the rationale first, and it is granted far more often.

Don't keep your own "have I started yet?" flag — it duplicates `enabled` and drifts the moment
anything calls `stop()`. The toggle reads the same value:

```ts
const onToggle = (on: boolean) => (on ? Geofencing.start() : Geofencing.stop());
```

Steps 5 and 6 are interchangeable, but adding first costs **one rotation instead of two**, and
every `addGeofences` call rotates — so batch them rather than looping.

### Listeners

```ts
useEffect(() => {
  const sub = Geofencing.onGeofence(handler);
  return () => sub.remove(); // skip this and Fast Refresh stacks duplicates: handler fires twice
}, []);
```

`remove()` does **not** stop delivery. Events arriving with no listener are buffered in JS (last
200) and replayed to the next subscriber, so a remount loses nothing — but that buffer dies with
the process, because those events were never written to the native queue. If a crossing must not
be lost, keep **one** listener alive for the whole process, in a component that never unmounts,
and let it persist the event before anything else.

### Things that happen without you

`start()` resolves with no permission — it simply cannot arm yet, logs
`no centre, keeping the current set`, and self-heals on the next rotation. `E_UNAVAILABLE` (no Play
services, or region monitoring unsupported) is the only rejection to expect.

Re-arming does not need `ready()`. Reboot, app update and location switched back on each have their
own Android receiver, and **the first rotation in any process re-adds the whole set
unconditionally** rather than trusting its own flags — which is what heals a reboot or force-stop
that silently cleared the OS side. On iOS the core is built during launch, background relaunches
included; on Android the module is created lazily, so nothing re-arms until JS touches it, which is
why calling `ready()` at startup matters more there.

## Permissions

```ts
// A denial RESOLVES with the resulting state — it never rejects.
// Branch on the state, never on a catch.
const state = await Geofencing.requestPermission();
if (state.authorization !== 'always') {
  // ... offer Geofencing.openSettings()
}
```

**The second prompt is raised for you on both platforms.** iOS asks for `always` with a second
system dialog. Android has none: from API 30 `requestPermissions(ACCESS_BACKGROUND_LOCATION)` shows
**no dialog at all**, it drops the user onto the app's location settings page with no word about
why. So the module gives the reason in its own dialog first and makes that call only on accept —
the only public route to that screen. API 29 is left to ask directly; its system dialog really does
offer "Allow all the time".

```ts
await Geofencing.ready({
  androidBackgroundPermissionRationale: {
    title: 'Cần vị trí nền',
    message: 'Ứng dụng chỉ nhận biết bạn đến và rời khỏi địa điểm khi đã đóng nếu bạn chọn "Luôn cho phép".',
    positiveButton: 'Mở cài đặt',
    negativeButton: 'Để sau',
  },
  // ...or `false` to drive the whole flow yourself with getState() + openSettings().
});
```

The default copy is English — **supply your own if your app is not**, and keep the *reason* in
whatever wording you choose: Play policy expects it stated before the user reaches Settings.

| API level | Needed for background geofencing |
|---|---|
| ≤ 28 | `ACCESS_FINE_LOCATION` — background is implicit |
| 29 | fine **+** `ACCESS_BACKGROUND_LOCATION`, requestable in one dialog |
| 30+ | the same two, but in **separate** requests, foreground first |
| 31+ | coarse must accompany fine; an *approximate*-only grant is **not** sufficient |
| 33+ | `POST_NOTIFICATIONS`, only with `enableHeadless: true` |
| any | `WAKE_LOCK`, only with `enableHeadless: true` — React Native takes one before your task runs |

`requestPermission()` stages all of that. An approximate-only grant reports as
`authorization: 'denied'` with `accuracyAuthorization: 'reduced'` — geofencing needs precise
location, and calling that success would be a lie.

- **Show your rationale before calling `requestPermission()`.** Play policy does not accept one shown afterwards.
- **Re-check on every resume.** Users revoke in Settings, and Android 11+ auto-revokes for unused apps. `getState()` always reads the live grant.

## Killed-app delivery

| | Behaviour |
|---|---|
| **Android**, `enableHeadless: false` (default) | the process is woken and the crossing is detected, stored and rotated on — all natively. Only your JS waits: events flush the next time the app opens. No service, no notification, no `FOREGROUND_SERVICE_LOCATION`. |
| **Android**, `enableHeadless: true` | a short-lived foreground service runs your JS task per event, at the cost of a low-importance notification for a few seconds. |
| **iOS** | the OS relaunches the app in the background. There is **no headless JS** and no equivalent to add; events are persisted natively and flushed once JS subscribes. |

`enableHeadless: false` is the single biggest simplification available — take it if your app can
tolerate delayed delivery. What it defers is **your JavaScript**, nothing else:

| | `false` (default) | `true` |
|---|---|---|
| OS wakes the process | yes | yes |
| Crossing detected, nothing lost | yes | yes |
| Active set rotated | yes | yes |
| Your JS runs *at* the crossing | no — on next launch | yes |
| "Updating location" notification | no | yes, per event |

Rotation is the row that matters most: it keeps the armed set correct as the user moves, and if
it stopped while the app was closed, geofencing would break entirely a kilometre later.

`event.timestamp` is when the OS **reported** the crossing, not when your code received it — so a
flushed event is still accurately stamped. It is not the instant the boundary was physically
crossed; that lag is the platform's, and on Android it can be minutes
([Known limits](#known-limits)).

**`enableHeadless` is Android-only, so the platforms diverge.** iOS always behaves like Android
with `false`. An app that reacts at the moment of arrival is therefore immediate on Android with
`enableHeadless: true` and deferred on iOS — a platform limit, not a bug. For true parity, react
server-side: the event is on disk natively the instant it happens, whether or not JS ran.

For `enableHeadless: true`, register the task at **module scope in `index.js`**, outside the React
tree — the headless bundle runs before any component mounts — and on Expo pass
`isAndroidForegroundServiceEnabled: true` (see [Expo](#expo)).

```js
// index.js
Geofencing.registerHeadlessTask(handleGeofenceEvent); // must not touch your React tree
```

Use the **same handler** for `registerHeadlessTask` and `onGeofence`. An event reaches exactly
one of them, never both, and you cannot tell in advance which — it depends on whether your JS
happened to be alive. Write it for the stricter of the two (headless: no React tree, 30-second
budget) and it is correct in both.

## `event.location` is best-effort

The one place the two platforms genuinely diverge. Android carries the triggering location on the
event; **iOS does not** — `didEnterRegion` gives only the region. The module falls back to the last
known fix, then to the region centre, setting `approximate: true` when it had to synthesise one.
Check that flag before trusting the coordinates, and request your own fix inside the handler if you
need a precise one.

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
| `getDebugLog()` | Recent native log lines, newest last. Needs `debug: true`. |
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
| `initialTriggerEntry` | `true` | fire ENTER when a geofence is armed for the **very first time** and you are already inside. It never suppresses the ENTER for a geofence rotated back in — that one is a real crossing you were not told about while it was off. |
| `minRadius` | `200` | smaller radii are clamped up, with a warning |
| `notificationResponsiveness` | `0` | Android only; Play Services' delivery-latency budget in ms. **Nothing to do with notifications.** |
| `useSignificantLocationChanges` | `true` | iOS only; backstop rotation trigger |
| `enableHeadless` | `false` | Android only; see above |
| `androidBackgroundPermissionRationale` | on, English copy | Android only; the dialog offering Settings when background is refused. `false` disables it |
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

## Debugging

Turn on `debug: true` first. Almost every issue resolves to reading one rotation log.

### What wakes the app, and what you will actually see

| Cause | iOS | Android | New `onGeofence` event? |
|---|---|---|---|
| Enter / exit / dwell in an **armed** geofence | ✓ | ✓ | **Yes** |
| Crossing the **boundary region** | ✓ | ✓ | No — it rotates the active set instead |
| Significant location change (~500 m) | ✓ | — | No — a backstop trigger only |
| Reboot, app update, location switched back on | — | ✓ | No — it re-arms |

Three of the four wake your process **without producing an event**, so *moving and seeing no
events is normal* — check whether the rotation centre and the armed set changed instead. And only
**armed** geofences fire: one sitting in the store unarmed is invisible to the OS until a boundary
crossing arms it as you approach.

### Reading the log

The log is **native**. The most interesting lines — the launch re-arm, and rotations driven from a
broadcast receiver or a background relaunch — are written **before JS is running**, so no JS
logger can see them live. They are buffered natively, and `getDebugLog()` pulls them into whatever
tooling you already use:

```ts
const lines = await Geofencing.getDebugLog(); // last 300; reading does not drain
```

Each line is `<epoch ms> <D|W|E> <message>`. The example app dumps it on startup and has a
**Dump native log** button.

Natively, which keeps working while the app is killed:

```sh
adb logcat -s RNGeofencing                                                               # Android
xcrun simctl spawn booted log stream --predicate 'eventMessage CONTAINS "RNGeofencing"'  # iOS sim
```

On a real iOS device use **Console.app**, select the device in the sidebar and filter on
`RNGeofencing` — Xcode cannot attach, because the app is not running.

```
D/RNGeofencing: boundary EXIT — rotating
D/RNGeofencing: rotate(BOUNDARY_EXIT): 85 of 300 geofence(s) selected
D/RNGeofencing: rotation applied: center=(59.91, 10.75) boundary=1800m on=[seed-12] off=[seed-90] synthetic=0
```

One iOS line is logged **even with `debug: false`**. Seeing it is proof the OS relaunched a
terminated app to deliver a crossing:

```
[RNGeofencing] W relaunched by a location event — constructing the core now
```

### Testing killed-app delivery

**Do not use `adb shell am force-stop`** — it removes the app's geofences from the system, so the
test cannot pass by construction. **Swipe the app away from Recents / the App Switcher** instead,
then move. With `enableHeadless: true` your JS runs immediately behind a short notification;
otherwise the event is written to disk and delivered on the next launch. Compare `event.timestamp`
against when your JS started to tell a flushed event from a fresh one.

### Symptoms and what they mean

| Symptom | Usually means |
|---|---|
| `authorization` is not `always` | No background delivery at all. By far the most common cause |
| `synthetic: true` on an event | The module emitted it, not the OS — you left a region while it was rotated out, so the exit was never reported |
| `approximate: true` on an event | Position synthesised from the region centre rather than a real fix (iOS carries no location on region callbacks) |
| Rotation centre / boundary radius stop changing as you move | Rotation has stalled — look for `boundary region could not be armed` |
| `rotation: freeing N slot(s) before adding` | Normal. The set changed enough that the platform's slots had to be freed first |
| `addRegions failed … left for retry` | The OS rejected a batch; those stay unarmed until the next rotation retries them |
| `dense geofence cluster` warning | More geofences within 500 m than iOS has slots. A missed ENTER is possible — raise radii or thin the cluster |
| `MISCONFIGURED: … WAKE_LOCK` | `enableHeadless: true` without the permissions it needs — see [Expo](#expo) |
| Armed and unarmed geofences interleaved when sorted by distance | A rotation landed between two reads. If it persists once you stop moving, check the log for the two failures above |

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
