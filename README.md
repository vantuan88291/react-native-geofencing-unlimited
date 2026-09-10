# react-native-geofencing-unlimited

[![npm version](https://img.shields.io/npm/v/react-native-geofencing-unlimited.svg?style=flat-square)](https://www.npmjs.com/package/react-native-geofencing-unlimited)
[![npm downloads](https://img.shields.io/npm/dm/react-native-geofencing-unlimited.svg?style=flat-square)](https://www.npmjs.com/package/react-native-geofencing-unlimited)
[![license](https://img.shields.io/npm/l/react-native-geofencing-unlimited.svg?style=flat-square)](./LICENSE)
[![platforms](https://img.shields.io/badge/platforms-ios%20%7C%20android-lightgrey.svg?style=flat-square)](#install)

Unlimited circular geofences for React Native, with background and killed-app delivery.

Wakes your app when the user enters or leaves one of your circles — even if the app has been
swiped away or the device has rebooted. Nothing else: no continuous location stream, no motion
recognition, no trip recording, no upload queue, and **no always-on notification**.

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

That is the whole config for the **default** runtime options. `isAndroidBackgroundLocationEnabled`
is already `true` by default and is spelled out above only for clarity. Going to call
`ready({ enableHeadless: true })`? Then you need a third prop —
`isAndroidForegroundServiceEnabled: true` — or your JS will not run at the crossing after all.
The callout under the props table below spells out why.

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

> **Using `enableHeadless: true`? You must also pass `isAndroidForegroundServiceEnabled: true`.**
>
> Not a style preference — that prop is what keeps `FOREGROUND_SERVICE`,
> `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` and `WAKE_LOCK` in your manifest, and
> React Native acquires a wake lock before your headless task runs. Strip them while
> `enableHeadless` is still `true` and the module logs an error and leaves the events queued
> rather than starting a service it knows will fail.
>
> The two are set in different places and neither knows about the other: `enableHeadless` is
> a runtime argument to `ready()`, while these permissions are stripped from the merged
> manifest at *build* time by this plugin, which defaults to removing them. Nothing can
> compare them until the app runs — so `ready()` does it, and logs a `MISCONFIGURED:` line
> the first time you call it with `enableHeadless: true` and no `WAKE_LOCK`. If you have
> `debug: true` on, that line is also in `getDebugLog()`.
>
> The full copy-paste for the headless case:
>
> ```json
> {
>   "expo": {
>     "plugins": [
>       [
>         "react-native-geofencing-unlimited",
>         {
>           "locationAlwaysAndWhenInUsePermission": "Allow $(PRODUCT_NAME) to use your location to notify you when you arrive.",
>           "isAndroidForegroundServiceEnabled": true
>         }
>       ]
>     ]
>   }
> }
> ```
>
> This is deliberately **not** the default above, and the prop is not free: it keeps
> `FOREGROUND_SERVICE_LOCATION`, and Google Play requires every app carrying that permission
> to declare its foreground-service use case in the Play Console. Turn it on because you
> want headless delivery, not as insurance.

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
and AGP merges them into your app. Minification is handled too — the library ships
`consumer-rules.pro`, which R8 applies automatically, so nothing has to be added to your
own `proguard-rules.pro`. Two of its components are addressed by name at runtime, and
renaming either would stop delivery in release builds only.

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

## Lifecycle

**`ready()` on every app start. `start()` once.**

| | When to call it | Why |
|---|---|---|
| `ready()` | **every app start** | applies your config, flushes events buffered while JS was down, and re-arms the stored set |
| `start()` / `stop()` | **when the user turns the feature on or off** | `enabled` is persisted; it survives every relaunch and every reboot |

Treat `start()`/`stop()` as a **user-facing switch**, not a lifecycle call:

```ts
// Every app start.
const state = await Geofencing.ready({ proximityRadius: 2000 });
if (!state.available) return; // no Play services, or region monitoring unsupported

// Only when the user flips the switch.
const onToggle = (on: boolean) =>
  on ? Geofencing.start() : Geofencing.stop();
```

`state.enabled` tells you where that switch currently is. Calling `start()` again is
harmless, just wasteful: it forces an extra rotation, which may cost a fresh location
fix.

### Setup — safe to run on every launch

```ts
Geofencing.onGeofence(handler);                 // 1. subscribe first
const state = await Geofencing.ready({ ... });  // 2. config + flush queue
if (!state.available) return;                   // 3. bail on unsupported devices
if (state.authorization !== 'always') {         // 4. must reach 'always'
  await Geofencing.requestPermission();
}
await Geofencing.addGeofences(places);          // 5. add
if (!state.enabled) await Geofencing.start();   // 6. arm — only if not already armed
```

Step 4 is guarded because an unconditional call is **not** free once the user has
settled on "While Using the App" — the common case. On iOS `requestAlwaysAuthorization`
is a one-shot per install; after it has been spent the call does nothing and fires no
delegate callback, so the promise waits out its 30-second backstop *on every launch* —
stalling everything after it. On Android API 30+ the background rationale dialog is
shown again on every launch. Better still, ask behind a user action or a screen that
explains the benefit: that is what Play policy expects, and it is granted far more often.

Step 6 is guarded because **`enabled` is persisted by the module**. Calling `start()`
unconditionally on every launch costs an extra rotation, and — once you add a settings
toggle — silently switches the feature back on for a user who turned it off.

Do **not** keep your own "have I started yet?" flag in AsyncStorage. It duplicates state
the module already owns, and the two drift apart the first time anything calls `stop()`:
your flag still says started, `enabled` says stopped, and nothing ever arms again.
`ready()` hands you the real answer in its return value.

Steps 5 and 6 are interchangeable, but **adding before starting costs one rotation
instead of two** — starting first rotates over an empty set and wastes a location fix.

### Removing a listener

`onGeofence` and `onGeofencesChange` both return a `Subscription`. Remove it when the
component that owns it goes away:

```ts
useEffect(() => {
  const sub = Geofencing.onGeofence(handleGeofenceEvent);
  return () => sub.remove();
}, []);
```

Skip this and Fast Refresh or a remount stacks a second listener on the same event —
your handler runs twice per crossing, and if it calls an API, so does that.

**Removing a listener does not stop delivery.** The module keeps its native
subscription for the lifetime of the JS context, and events that arrive with no
listener attached are held (bounded at 200) and replayed to the next subscriber. So a
remount loses nothing.

What that buffer will not survive is the process being killed: events held for a
missing listener were never written to the native queue, because native could see a
live JS context and delivered to it. If losing a crossing is not acceptable — you are
reporting arrivals to a backend, say — keep **one** listener alive for the whole
process rather than only inside a screen: register it in a top-level component that
never unmounts, and let that handler persist the event before anything else. Screens
can then read from your own store instead of subscribing.

### Batch your adds

Once the module is started, **every `addGeofences` call triggers a rotation**. One call
with 300 entries rotates once; 300 calls rotate 300 times.

```ts
await Geofencing.addGeofences(places.map(toGeofence));   // ✅ one rotation
for (const p of places) await Geofencing.addGeofence(p); // ❌ one per place
```

### Permission can arrive later

`start()` **resolves** even with no permission. It simply cannot arm anything yet,
because it has no position to compute the active set from — it logs
`no centre, keeping the current set` and self-heals: the next rotation (permission
granted, or the app returning to the foreground) arms everything.

`E_UNAVAILABLE` — no Play services, or region monitoring unsupported — is the only
condition you should expect `start()` to reject on. (`E_STORE` is possible too, but
only if the device cannot write to disk at all.)

### Re-arming without the app being opened

Re-arming does not depend on `ready()`. Device reboot, app update and location being
switched back on each have their own receiver on Android, and **the first rotation in
any process re-adds the whole set unconditionally** rather than trusting what it
believes is already armed — which is what heals a reboot or a force-stop having
silently cleared the OS side. On iOS the core is constructed during launch, including a
background relaunch.

One asymmetry worth knowing: on Android the native module is created lazily, so nothing
re-arms at app launch until JS touches it — which is why calling `ready()` at startup
matters more there. On iOS it happens whether JS calls anything or not.

## Permissions

```ts
// A denial RESOLVES with the resulting state — it does not reject.
// Branch on the state, never on a catch.
const state = await Geofencing.requestPermission();

if (state.authorization !== 'always') {
  Alert.alert(
    'Background location needed',
    'Geofences only fire in the background with "Allow all the time".',
    [
      { text: 'Not now', style: 'cancel' },
      { text: 'Open settings', onPress: () => Geofencing.openSettings() },
    ]
  );
}
```

**The second prompt is raised for you on both platforms.** iOS asks for `always` with
a second *system* dialog. Android has no equivalent, and what it does instead is worse
than nothing: from **API 30, `requestPermissions(ACCESS_BACKGROUND_LOCATION)` shows no
dialog at all** — it drops the user straight onto the app's location settings page,
with no explanation of why they were sent there.

So on API 30+ the module shows its own dialog giving the reason first, and makes that
system call only if the user accepts — which then lands them exactly where they need to
be: the app's **location permission** page, with "Allow all the time" right there. That
navigation is the only public way to reach that screen; the app-details page buries it
three taps deep under Permissions › Location, and is used only as a fallback.

On API 29 — the one version whose system dialog really does offer "Allow all the
time" — the system is still allowed to ask directly.

Customise the copy, or turn it off, from `ready()`:

```ts
await Geofencing.ready({
  androidBackgroundPermissionRationale: {
    title: 'Cần vị trí nền',
    message:
      'Ứng dụng chỉ nhận biết bạn đến và rời khỏi địa điểm khi đã đóng nếu bạn ' +
      'chọn "Luôn cho phép". Android chỉ cho bật mục này trong Cài đặt.',
    positiveButton: 'Mở cài đặt',
    negativeButton: 'Để sau',
  },
});
```

```ts
// Or drive the whole flow yourself with getState() + openSettings().
await Geofencing.ready({ androidBackgroundPermissionRationale: false });
```

The default copy is English, so **supply your own strings if your app is not**. Play
Store policy expects the *reason* to be stated before the user is sent to Settings,
which is what this dialog is for — keep that in whatever wording you choose.

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
| any | `WAKE_LOCK`, only when `enableHeadless: true` — React Native's `HeadlessJsTaskService` acquires a wake lock before your task runs |

`requestPermission()` stages all of that for you. An approximate-only grant is reported as
`authorization: 'denied'` with `accuracyAuthorization: 'reduced'` — geofencing needs precise
location, and reporting it as success would be a lie.

## Killed-app delivery

| | Behaviour |
|---|---|
| **Android**, `enableHeadless: false` (default) | the process is still woken and the crossing is still detected, stored and rotated on — natively. Your JS is what waits: events flush the next time the app opens. No service, no notification, no `FOREGROUND_SERVICE_LOCATION`. |
| **Android**, `enableHeadless: true` | a short-lived foreground service runs your JS task per event. Costs a low-importance notification for a few seconds. |
| **iOS** | the OS relaunches the app in the background. There is **no headless JS**; events are persisted natively and flushed once JS subscribes. |

`enableHeadless: false` is the single biggest simplification available — take it if your app can
tolerate delayed delivery.

### What `enableHeadless: false` does *not* turn off

It does not stop the OS waking your app. The geofence broadcast still starts your process
(with no UI) and the whole native side still runs: the transition is de-duplicated by the
gate, the state and the event are committed to disk, and **the active set is still rotated**.
That last one matters most — rotation is what keeps the armed set correct as the user moves,
and if it stopped while the app was closed, geofencing would break entirely a kilometre later.

The only thing `false` defers is **your JavaScript**. No bridge boot, no
`registerHeadlessTask`. And `event.timestamp` is the moment of the crossing, not the moment
of the flush, so a late event is still an accurate one.

| | `false` (default) | `true` |
|---|---|---|
| OS wakes the process | yes | yes |
| Crossing detected, nothing lost | yes | yes |
| Active set rotated | yes | yes |
| Your JS runs *at* the crossing | no — on next launch | yes |
| Short "Updating location" notification | no | yes, per event |

### `enableHeadless` is Android-only, and that means platforms diverge

There is no headless JS on iOS and no equivalent to add. iOS always behaves like Android with
`enableHeadless: false`: the OS relaunches the app in the background, the event is committed
natively, and JS receives it once it subscribes — which on a background relaunch may be inside
a ~10 s budget that an RN cold start can exceed, so it is never something to rely on.

So an app that reacts at the moment of arrival gets **different behaviour per platform**:
immediate on Android with `enableHeadless: true`, deferred on iOS. That is a platform limit,
not a bug. If you need the same behaviour on both, do the reacting server-side — the event is
on disk natively the instant it happens, whether or not any JS ran, so uploading from
whichever path wakes first and pushing from your backend is the only route to true parity.

**On Expo, `enableHeadless: true` also needs `isAndroidForegroundServiceEnabled: true`**
in the plugin props — see [Expo](#expo). The plugin strips the foreground-service
permissions by default, and without them the service cannot start.

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

Three of the four wake your process **without producing an event**. So *moving and
seeing no events is normal* — check whether the rotation centre and the armed set
changed instead.

And only **armed** geofences fire. One that is in the store but not currently armed is
invisible to the OS; crossing the boundary is what arms it as you approach.

### Reading the log from JS

The module's log is **native**, and the most interesting lines — the launch re-arm, and
rotations driven from a broadcast receiver or a background relaunch — are written
**before JS is running**, so no JS logger can observe them live. They are buffered
natively instead, and `getDebugLog()` pulls them:

```ts
const lines = await Geofencing.getDebugLog();
console.log(lines.join('\n'));
```

Each line is `<epoch ms> <D|W|E> <message>`. Reading does not drain the buffer, so
calling it repeatedly is safe; it holds the last 300 lines. This is what lets the log
land in whatever JS tooling you already use — Reactotron, React Native DevTools —
instead of needing a native log viewer.

The example app dumps it automatically on startup and has a **Dump native log** button.

### Reading the log natively

Android — `logcat` reads the system log, so it keeps working while the app is killed:

```sh
adb logcat -s RNGeofencing
```

```
D/RNGeofencing: boundary EXIT — rotating
D/RNGeofencing: rotate(BOUNDARY_EXIT): 85 of 300 geofence(s) selected
D/RNGeofencing: rotation applied: center=(59.91, 10.75) boundary=1800m on=[seed-12] off=[seed-90] synthetic=0
D/RNGeofencing: headless service started with 1 event(s)
```

iOS, on a simulator:

```sh
xcrun simctl spawn booted log stream --predicate 'eventMessage CONTAINS "RNGeofencing"'
```

On a real device, open **Console.app**, select the device in the sidebar, and filter on
`RNGeofencing`. Xcode cannot attach — the app is not running.

One iOS line is logged **even with `debug: false`**:

```
[RNGeofencing] W relaunched by a location event — constructing the core now
```

Seeing it is proof the OS relaunched a terminated app to deliver a crossing.

### Event flags

| Flag | Means |
|---|---|
| `synthetic` | the module emitted this itself — you left a region while it was rotated out, so the OS never reported the exit |
| `approximate` | the position was synthesised from the region centre rather than a real fix (iOS carries no location on region callbacks) |

### Testing killed-app delivery

**Do not use `adb shell am force-stop`.** Force-stop removes the app's geofences from
the system, so that test cannot pass by construction. **Swipe the app away from Recents
/ the App Switcher** instead, then move.

- **Android, `enableHeadless: true`** — JS runs immediately, and a short
  "Updating location" notification appears while it does.
- **Android with `enableHeadless: false`, and iOS** — the event is written to disk
  natively and delivered when the app is next opened. Compare `event.timestamp` against
  when your JS started to tell a flushed event from a fresh one.

### Symptoms and what they mean

| Symptom | Usually means |
|---|---|
| `authorization` is not `always` | No background delivery at all. By far the most common cause |
| Rotation centre / boundary radius stop changing as you move | Rotation has stalled — look for `boundary region could not be armed` |
| `rotation: freeing N slot(s) before adding` | Normal. The set changed enough that the platform's slots had to be freed first |
| `addRegions failed … left for retry` | The OS rejected a batch; those stay unarmed until the next rotation retries them |
| `dense geofence cluster` warning | More geofences within 500 m than iOS has slots. A missed ENTER is possible — raise radii or thin the cluster |
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
