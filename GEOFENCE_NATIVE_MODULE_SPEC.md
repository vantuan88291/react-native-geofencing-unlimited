# Standalone Geofencing Native Module — Implementation Specification

**Audience:** an AI agent (or engineer) implementing this module from scratch in a React Native project.
**Status:** design specification, no code exists yet.
**Target:** React Native **0.76+** (New Architecture / TurboModule), verified against RN **0.85.3**, React 19, Hermes.
**Legacy bridge:** supported — see **§15**, which replaces only the adapter layer. RN **0.60+** for a legacy-only
build. §1–§14 are architecture-independent.
**Expo:** supported through a config plugin — see **§18**.

**Deliverable:** a **standalone npm package** with its own repository — not a package added to an existing
monorepo. §17 therefore assumes release tooling, CI and git hooks are set up from scratch here; nothing is
inherited from a parent workspace. (The only workspace involved is the package's own root + `example/`, §16.)

**Packaging baseline:** `create-react-native-library` **`module-mixed`**, languages **`kotlin-objc`** (no Swift),
codegen left to the consuming app (§11), and an Expo config plugin compiled into a committed `plugin/build/`
(§18). §11, §15, §17 and §18 are written against that baseline; where they differ from what a bare
`create-react-native-library` run emits, the difference is deliberate and the reason is stated inline.

**Version-specific claims are marked as such.** Where this doc names a tool version, an SDK level or an RN
internal, treat it as "true of some released version, verify against yours" — `create-react-native-library`
defaults, `TurboModuleRegistry` internals and the `RCT_NEW_ARCH_ENABLED` default have all moved between releases,
and each place that depends on one says so.

---

## 0. Invariants

Eleven rules. Every one of them, when broken, produces the same user-visible symptom — *geofences silently stop
working* — with no crash and no log. They are the reason the rest of this document is long. If a change conflicts
with one of these, the change is wrong.

| # | Invariant | Why it bites | Where |
|---|---|---|---|
| 1 | `core/` has **zero** React Native dependencies | The core is constructed from a `BroadcastReceiver` and from app launch, where no React context exists | §11, §15.6 |
| 2 | Every rotation runs on **one** serialised executor | Two overlapping rotations diff against the same OS registry and leave a partial set | §4.5, §9.4 |
| 3 | When applying an active set: **add before remove** | A kill between the two calls then leaves a superset (detects everything), never a hole | §4.4 |
| 4 | Never rotate on an unresolved centre — keep the stale set | A rotation around a bogus centre arms the wrong regions everywhere | §4.7 |
| 5 | Boundary radius stays inside the safe zone (nearest excluded geofence − its radius − margin) | Otherwise the user reaches an un-armed region before the staleness detector trips | §4.3 |
| 6 | Every OS callback passes through the TransitionGate | Re-arming inside a region re-fires ENTER; rotating out loses EXIT forever | §5 |
| 7 | One JSON blob per concern, written through on every mutation | Per-geofence keys lose atomicity; write-behind loses the write when the process is frozen | §9.1, §9.3 |
| 8 | Never declare `android:process` on the receiver, service or boot receiver | `SharedPreferences` is not multi-process safe; the two copies diverge and one wins silently | §9.4 |
| 9 | Android: set `active` only **after** the Play Services call resolves — and re-arm the whole set unconditionally on every process start | The flag is the only registry Android has; an optimistic flag makes the next diff skip a region that was never armed | §4.6, §6.6 |
| 10 | `BOUNDARY_ID` never escapes into a public API result or an event | It is an implementation detail the host app would otherwise treat as a real geofence | §4.2 |
| 11 | iOS: create `CLLocationManager` and assign its delegate during launch, not when JS calls `ready()` | A relaunch into the background delivers the pending event only to a delegate that already exists | §7.5, §18.3 |

Two more that are not correctness rules but are the difference between a debuggable module and an opaque one:
a permission denial **resolves** with the resulting state rather than rejecting (§6.7, §8.4), and `debug: true`
logs every rotation's centre, boundary radius and on/off diff (§14).

---

## 1. Why this module exists

The reference implementation (`react-native-background-geolocation` by Transistor Software) solves the geofencing
problem very well, but it is a **paid, commercial license** and ships a large surface we do not need: journey
recording, motion activity recognition, HTTP upload queue, SQLite location store, scheduler, heartbeat, polygon
geofences, background sync, and an Android foreground service with an **always-on** persistent notification.

> On that last one, be precise about what is dropped. This module has no always-on service and no permanent
> notification. It does show a short-lived foreground-service notification — seconds, per event — **but only when
> `enableHeadless: true`** (§6.5), which is the opt-in price of running JS from a killed app. With
> `enableHeadless: false` there is no service, no notification, and no `POST_NOTIFICATIONS` /
> `FOREGROUND_SERVICE_LOCATION` permission at all. Note also that `notificationResponsiveness` (§6.3, §8.1) is
> Play Services' name for geofence delivery latency and has nothing to do with notifications.

A project whose *only* requirement is "wake me when the user enters or leaves one of these circles, even if the app
is killed" pays for all of it. This module reproduces **only the geofencing subsystem**, unlicensed, with the same
two hard problems solved:

1. **Platform region limits** (iOS 20, Android 100) — worked around by rotating an active subset.
2. **Killed-app delivery** — OS relaunches the app on a region crossing; events survive to JS.

### In scope

| Capability | Notes |
|---|---|
| Register/unregister circular geofences | Unlimited count, persisted in a local DB |
| ENTER / EXIT / DWELL events | DWELL is native on Android, emulated on iOS |
| Active-set rotation | Lifts the 20/100 platform ceiling |
| Background + terminated-app delivery | Headless JS (Android), background relaunch (iOS) |
| Event queue + replay | No event lost while JS is not running |
| Permission request flow | Fine + background location |
| `onGeofencesChange` observability | Which regions are actually armed at OS level |

### Explicitly out of scope

Continuous location streaming, motion/activity recognition, journey or trip recording, HTTP upload, geofence
polygons, scheduling, `stopTimeout`/`stationaryRadius` motion tuning, Huawei HMS fallback.

> If the host app later needs a continuous location stream, use a separate, plain location module. Do **not** grow
> this one — the entire point is that it stays small.

---

## 2. Feature parity target

Compared against `react-native-background-geolocation` v5.5.

| Reference API | This module | Note |
|---|---|---|
| `addGeofence` / `addGeofences` | ✅ same shape | |
| `removeGeofence` / `removeGeofences` | ✅ same shape | |
| `getGeofences()` | ✅ | everything in our DB |
| `onGeofence(cb)` | ✅ | `{identifier, action, location, extras, timestamp}` |
| `onGeofencesChange(cb)` | ✅ | `{on: Geofence[], off: string[]}` |
| `geofenceProximityRadius` | ✅ config | our default **2000 m**, no hard minimum |
| `geofenceInitialTriggerEntry` | ✅ config | |
| `notifyOnEntry/Exit/Dwell`, `loiteringDelay` | ✅ per geofence | |
| `startGeofences()` | ✅ `start()` | this module has only one mode |
| `stop()` | ✅ `stop()` | |
| Headless task (Android) | ✅ | |
| Polygon geofences | ❌ | circles only |
| `location` object on the event | ⚠️ reduced | see §7.3 — best-effort, may be `null` |

---

## 3. Architecture

```
                       JS  (TurboModule + codegen events)
  ┌──────────────────────────────────────────────────────────────┐
  │  Geofencing.addGeofences([...])                              │
  │  Geofencing.onGeofence(e => ...)                             │
  └──────────────────────────────┬───────────────────────────────┘
                                 │
  ┌──────────────────────────────▼───────────────────────────────┐
  │  Shared core logic (duplicated per platform, same algorithm) │
  │                                                              │
  │   GeofenceStore     — key/value blob: every geofence (§9)    │
  │   StateStore        — per-geofence INSIDE/OUTSIDE/UNKNOWN    │
  │   EventQueue        — undelivered events                     │
  │   RotationEngine    — picks the active subset (§4)           │
  │   TransitionGate    — dedup + synthetic events (§5)          │
  └───────┬──────────────────────────────────────┬───────────────┘
          │                                      │
  ┌───────▼────────────────┐        ┌────────────▼────────────────┐
  │ Android                │        │ iOS                         │
  │  GeofencingClient      │        │  CLLocationManager          │
  │  (play-services)       │        │  region monitoring          │
  │  ≤100 regions          │        │  ≤20 regions                │
  │  native DWELL          │        │  DWELL emulated             │
  │  BroadcastReceiver     │        │  CLLocationManagerDelegate  │
  │  HeadlessJsTaskService │        │  background relaunch        │
  │  BOOT_COMPLETED re-arm │        │  significant-location-change│
  └────────────────────────┘        └─────────────────────────────┘
```

The **only** part that is genuinely hard is the RotationEngine and the TransitionGate. Everything else is
mechanical platform glue. Implement §4 and §5 first, on paper, before touching native code.

---

## 4. The rotation engine (core algorithm)

### 4.1 Problem

iOS monitors at most 20 regions; Android at most 100. Apps need thousands. The solution used by the reference
library — and reproduced here — is:

- Keep **all** geofences in our own database.
- Arm only the **N nearest** ones at the OS level.
- Arm one extra **boundary region** centred on the position the set was computed from.
- When the user exits the boundary region, recompute and swap the active set.

The boundary region costs one slot and is what makes the whole thing free: no polling, no location stream. The OS
itself tells us "the user moved far enough that your cached nearest-N is stale".

### 4.2 Slot budget

```
IOS_MAX_REGIONS      = 20
ANDROID_MAX_REGIONS  = 100
BOUNDARY_ID          = "@__rn_geofence_boundary__@"   // reserved, never user-visible

activeCapacity = platformMax - 1     // 19 on iOS, 99 on Android
```

`BOUNDARY_ID` must be filtered out of every public API (`getGeofences`, `getActiveGeofences`) and never emitted as
a `GeofenceEvent`.

### 4.3 Selection

```
function computeActiveSet(center: LatLng): { active: Geofence[], boundaryRadius: number | null } {
    // 1. Linear haversine scan over the in-memory set (§9.3), then sort.
    //    No spatial index: the store is capped at 2000 entries and already fully
    //    in memory, so this is well under a millisecond. Do not add a bbox
    //    prefilter — it only introduces a way to wrongly exclude a candidate.
    const candidates = store.all()
        .map(g => ({ g, dist: haversine(center, g) }))
        .filter(c => c.dist <= config.proximityRadius + c.g.radius)
        .sort((a, b) => a.dist - b.dist)

    const active = candidates.slice(0, activeCapacity)

    // 2. All geofences fit → no rotation needed, no boundary region.
    if (store.count() <= activeCapacity) {
        return { active: store.all(), boundaryRadius: null }
    }

    // 3. Boundary must stay strictly inside the "safe zone": the region where no
    //    UN-ARMED geofence can be reached. That is the distance to the nearest
    //    excluded geofence, minus its own radius.
    const firstExcluded = candidates[activeCapacity]           // may be undefined
    const safeZone = firstExcluded
        ? firstExcluded.dist - firstExcluded.g.radius
        : config.proximityRadius

    const boundaryRadius = clamp(
        safeZone - BOUNDARY_SAFETY_MARGIN_M,   // 200 m
        MIN_BOUNDARY_RADIUS_M,                 // 500 m — below this the OS is unreliable
        config.proximityRadius,
    )

    return { active, boundaryRadius }
}
```

**Why `safeZone` and not just `proximityRadius`:** if the boundary were larger than the distance to the nearest
excluded geofence, the user could walk into an un-armed region and never be detected. The boundary is a *staleness
detector*, and it must trip before the cache can be wrong.

**Degenerate case:** in a dense cluster (>19 geofences inside 500 m on iOS) `safeZone - margin` can fall below
`MIN_BOUNDARY_RADIUS_M`. The clamp then produces a boundary that overlaps excluded geofences. This is unavoidable
with 20 slots; accept it, and log a warning so the host app can raise its geofence radii or thin the cluster.

### 4.4 Applying the set (diff, never clear-and-add)

```
function applyActiveSet(next: Geofence[], boundaryRadius: number | null) {
    const currentIds = new Set(osRegistry.ids())      // excluding BOUNDARY_ID; see §4.6
    const nextIds    = new Set(next.map(g => g.id))

    const toAdd    = next.filter(g => !currentIds.has(g.id) || definitionChanged(g))
    const toRemove = [...currentIds].filter(id => !nextIds.has(id))

    // ADD BEFORE REMOVE. If the process is killed between the two calls the result
    // is a superset — still detects everything — never a hole.
    if (toAdd.length)    platform.addRegions(toAdd)
    if (toRemove.length) platform.removeRegions(toRemove)

    // Boundary last: it is the trigger for the next rotation, and re-arming it
    // before the set is in place would race a fast-moving user.
    platform.removeRegion(BOUNDARY_ID)
    if (boundaryRadius !== null) {
        platform.addRegion({ id: BOUNDARY_ID, center, radius: boundaryRadius, notifyOnExit: true, notifyOnEntry: false })
    }

    persist(activeSetIds = nextIds, rotationCenter = center)
    emitGeofencesChange({ on: toAdd, off: toRemove })
}
```

`definitionChanged` compares latitude, longitude, radius and transition flags only — not `extras`, which never
reaches the OS.

### 4.5 Rotation triggers

Run `computeActiveSet` + `applyActiveSet` when **any** of these happen:

| Trigger | Platform | Notes |
|---|---|---|
| Boundary region EXIT | both | the primary, battery-free trigger |
| `start()` / `ready()` | both | cold start and every relaunch |
| `addGeofences` / `removeGeofences` | both | the set changed under us |
| App enters foreground | both | cheap correction; last known location is fresh |
| Significant-location-change update | iOS | backstop if a boundary EXIT is missed |
| `BOOT_COMPLETED` | Android | the OS dropped everything (§6.6) |
| Location services re-enabled | both | provider change |

**Concurrency:** serialise every rotation through a single-threaded executor / actor. Two overlapping rotations
diffing against the same OS registry produce a partial set. This is the single most likely source of a
"geofences silently stopped working" bug.

### 4.6 Where `osRegistry.ids()` comes from — the platforms disagree

| Platform | Source of truth | Notes |
|---|---|---|
| iOS | `manager.monitoredRegions` | the OS answers; authoritative across process restarts |
| Android | **the store's `active` flag** | `GeofencingClient` has **no read API** — nothing can be asked of the OS |

This asymmetry is not cosmetic. On Android our own record *is* the registry, so:

- Write `active` in the **same** commit that follows a successful `addGeofences` / `removeGeofences`, never before
  the Play services call resolves. A flag set optimistically for a call that then failed makes the next diff skip
  a region that was never armed.
- If `addGeofences` fails, mark the affected ids `active = false` and let the next rotation retry them. Do not
  throw away the whole set.
- On every process start, **re-arm the full active set unconditionally** rather than trusting the flags (§6.6).
  A reboot, a force-stop or a Play services reset clears the OS side while our flags still say `active = true`;
  a blind re-add is cheap (it is an upsert) and is the only thing that resolves that divergence.

`getActiveGeofences()` therefore reads `monitoredRegions` on iOS and the store on Android. Say so in its doc
comment — a caller debugging a missed crossing needs to know that the Android answer is *what we believe is
armed*, not *what the OS confirms is armed*.

### 4.7 Resolving the rotation centre

Each rotation needs a `center`. Resolve it in this order:
1. The location carried by the triggering geofence event (Android provides it; iOS does not — see §7.3).
2. `getLastKnownLocation()` (Android: `FusedLocationProviderClient.getLastLocation()`; iOS: `CLLocationManager.location`).
3. A single one-shot fix with a short timeout (10 s) and `balanced`/`hundredMeters` accuracy.
4. If all fail: **do not rotate**. Keep the current set and retry on the next trigger. Rotating around a bogus
   centre is worse than being stale.

---

## 5. Transition gate (dedup + synthetic events)

Rotation introduces two failure modes that do not exist with a fixed set. Both must be handled in a single
component that every OS callback passes through.

Persist a per-geofence state: `UNKNOWN | INSIDE | OUTSIDE`, plus `enteredAt: timestamp?`.

### 5.1 Spurious ENTER on re-arm

Arming a region the device is already inside makes both platforms report entry:
Android via `INITIAL_TRIGGER_ENTER`, iOS via `didDetermineState(.inside)`.

```
onPlatformEnter(id):
    if state[id] == INSIDE:  drop        # already inside, this is a re-arm artifact
    state[id] = INSIDE; enteredAt = now
    if geofence.notifyOnEntry: emit ENTER
    if geofence.notifyOnDwell: scheduleDwell(id, geofence.loiteringDelay)
```

The `config.initialTriggerEntry` flag only controls the **very first** arming of a geofence
(`state == UNKNOWN`); it must never suppress a genuine ENTER on a region rotated back in.

### 5.2 Missed EXIT while rotated out

If the user leaves a region while it was not armed, no EXIT is ever delivered and the state stays `INSIDE`
forever — a stuck session in the host app.

```
onRotationApplied(nextActiveIds):
    for id in previousActiveIds - nextActiveIds:
        if state[id] == INSIDE:
            # We are rotating it out. Distance says whether we already left.
            if haversine(center, geofence) > geofence.radius:
                state[id] = OUTSIDE
                emit EXIT (synthetic, location = rotation center)
            # else: still inside but out of slots — leave INSIDE, resolve on re-arm.

onPlatformStateDetermined(id, isInside):     # iOS didDetermineState / Android re-arm check
    if state[id] == INSIDE and not isInside:
        state[id] = OUTSIDE
        emit EXIT (synthetic)
```

Emit synthetic events with the same shape as real ones; add `extras.__synthetic = true` if the host app needs to
tell them apart.

### 5.3 DWELL

- **Android:** native. `GEOFENCE_TRANSITION_DWELL` with `setLoiteringDelay(ms)`. Nothing to emulate. Do not
  schedule your own timer — you would double-fire.
- **iOS:** no native dwell. See §7.4.

Always clear a pending dwell on EXIT.

---

## 6. Android implementation

### 6.1 Dependencies

`android/build.gradle` keeps the standard scaffold shape: a `buildscript` block reading
`rootProject.ext.kotlinVersion` with a `Geofencing_kotlinVersion` fallback, the `getExtOrDefault` /
`getExtOrIntegerDefault` helpers, `supportsNamespace()`, and the `react { … }` block for codegen.

```gradle
// android/build.gradle of the library
dependencies {
    //noinspection GradleDynamicVersion
    implementation "com.facebook.react:react-native:+"        // replaced by the RN gradle plugin on 0.71+
    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
    implementation "com.google.android.gms:play-services-location:21.3.0"
    // No database dependency: persistence is SharedPreferences (§9).
    // org.json ships with the platform — no JSON library needed either.
}

if (isNewArchitectureEnabled()) {
    react {
        jsRootDir = file("../src/")
        libraryName = "Geofencing"
        codegenJavaPackageName = "com.rngeofencing"
    }
}
```

```properties
# android/gradle.properties — per-library prefixed keys, read via getExtOrDefault
Geofencing_kotlinVersion=1.9.24
Geofencing_minSdkVersion=21
Geofencing_targetSdkVersion=34
Geofencing_compileSdkVersion=34
```

> **Check what your scaffold pinned before trusting it.** `create-react-native-library` has shipped library
> defaults as low as `compileSdkVersion 31` / Kotlin 1.7.x, and both are too low here: this module needs
> **compileSdk ≥ 34** — `play-services-location:21.3.0` is compiled against 34,
> `FOREGROUND_SERVICE_LOCATION` is an API-34 permission, `POST_NOTIFICATIONS` (§6.7) is API 33 — and Kotlin 1.9.x
> for the coroutines and `PermissionAwareActivity` usage. Read the generated `gradle.properties` and raise
> whatever is below those; do not assume the numbers above match the version you scaffolded with. Both are only
> defaults in any case: a host app's `rootProject.ext` overrides them.

Requires Google Play services. **Non-GMS devices (Huawei, some AOSP builds) have no `GeofencingClient`.** Detect
with `GoogleApiAvailability.isGooglePlayServicesAvailable()` and surface it through `getState().available` rather
than crashing. HMS `LocationServices` has a near-identical API if a fallback is ever needed — out of scope here.

### 6.2 Manifest

**Two manifest files, kept in sync.** `android/build.gradle` swaps them based on AGP version, via
`supportsNamespace()`:

```
android/src/main/AndroidManifest.xml      # has package="com.rngeofencing"  — AGP < 7.3
android/src/main/AndroidManifestNew.xml   # no package attribute, namespace — AGP >= 7.3 (supportsNamespace())
```

Every permission, receiver and service below must be written into **both** files, identically apart from the
`package` attribute. Adding a receiver to only `AndroidManifestNew.xml` (the one an IDE opens by default, and the
one actually used on modern AGP) leaves the module inert on any host still on AGP < 7.3. That is not a
hypothetical: scaffolded library `buildscript` blocks have pinned `com.android.tools.build:gradle` in the 7.2.x
range, so check yours — if it is below 7.3, the legacy manifest is the one your own builds use.

Relative names (`.GeofenceBroadcastReceiver`) resolve against the library's package/namespace, not the host app's,
so they need no change per host. The library manifest is merged into the app's — the host declares nothing.

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- only if the headless service runs as a foreground service (§6.5) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<application>
    <receiver
        android:name=".GeofenceBroadcastReceiver"
        android:exported="false" />

    <receiver
        android:name=".BootReceiver"
        android:exported="true"
        android:enabled="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            <!-- No LOCKED_BOOT_COMPLETED: SharedPreferences is unreadable
                 before the first unlock. See §9.7. -->
        </intent-filter>
    </receiver>

    <service
        android:name=".GeofenceHeadlessService"
        android:exported="false"
        android:foregroundServiceType="location" />
</application>
```

> `ACCESS_BACKGROUND_LOCATION` triggers the Google Play background-location declaration and a manual review.
> Without it, geofence transitions are **not delivered** in the background on API 29+.

### 6.3 Registering

```kotlin
val geofence = Geofence.Builder()
    .setRequestId(id)
    .setCircularRegion(lat, lng, radius)
    .setExpirationDuration(Geofence.NEVER_EXPIRE)
    .setTransitionTypes(
        (if (notifyOnEntry) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
        (if (notifyOnExit)  Geofence.GEOFENCE_TRANSITION_EXIT  else 0) or
        (if (notifyOnDwell) Geofence.GEOFENCE_TRANSITION_DWELL else 0)
    )
    .apply { if (notifyOnDwell) setLoiteringDelay(loiteringDelay) }
    .setNotificationResponsiveness(config.notificationResponsiveness)  // default 0
    .build()

val request = GeofencingRequest.Builder()
    .setInitialTrigger(
        if (config.initialTriggerEntry) GeofencingRequest.INITIAL_TRIGGER_ENTER
        else 0
    )
    .addGeofences(list)
    .build()

geofencingClient.addGeofences(request, pendingIntent)
```

**PendingIntent** — one shared instance, `FLAG_MUTABLE` is mandatory on API 31+:

```kotlin
private val pendingIntent: PendingIntent by lazy {
    val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
    PendingIntent.getBroadcast(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}
```

`addGeofences` with an id that already exists **replaces** it, so the diff in §4.4 needs no special casing for
updates. Removal is `geofencingClient.removeGeofences(listOf(id))`.

### 6.4 Receiving

```kotlin
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            log("geofence error ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }

        val action = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT  -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> return
        }
        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        val location = event.triggeringLocation

        // goAsync() buys ~10 s — enough for a DB write and, usually, a rotation.
        val pending = goAsync()
        Core.executor.execute {
            try { Core.handleTransition(context, ids, action, location) }
            finally { pending.finish() }
        }
    }
}
```

`Core.handleTransition` must:
1. If `BOUNDARY_ID` is among the ids and action is EXIT → trigger a rotation, and **stop** (never emit it).
2. Pass every other id through the TransitionGate (§5).
3. Persist surviving events into the queue table.
4. Deliver to JS (§6.5).

A geofence broadcast places the app on a temporary power allowlist for ~10 seconds, which is what makes
`goAsync()` and starting a short-lived service legal even in Doze.

### 6.5 Delivering to JS

Three cases, decided by whether a React context exists:

```
ReactContext alive & JS listener registered  →  emit directly via the TurboModule event emitter
ReactContext alive, no listener              →  queue; flush when JS calls ready()/addListener
No ReactContext (app killed)                 →  headless JS, or queue-only
```

**Headless JS (recommended, opt-in via config `enableHeadless: true`):**

```kotlin
class GeofenceHeadlessService : HeadlessJsTaskService() {
    override fun getTaskConfig(intent: Intent): HeadlessJsTaskConfig? =
        intent.extras?.let {
            HeadlessJsTaskConfig(
                "RNGeofenceHeadlessTask",   // name JS registers with AppRegistry
                Arguments.fromBundle(it),
                30_000,                     // timeout ms
                true,                       // allowedInForeground
            )
        }
}
```

Started from the receiver with `context.startForegroundService(...)` on O+, and the service must call
`startForeground()` within 5 s with a low-importance notification channel, then stop itself when the task
completes. This is the price of running JS from a killed app — the reference library pays it too.

**If the host app can tolerate delayed delivery, skip headless entirely.** Set `enableHeadless: false`, let the
events accumulate in the queue table, and flush them the next time the app is opened. This removes the foreground
service, the notification, and the `FOREGROUND_SERVICE_LOCATION` permission. Decide this per project — it is the
single biggest simplification available.

### 6.6 What silently destroys registrations

| Cause | Recovery |
|---|---|
| Device reboot | `BOOT_COMPLETED` receiver → re-arm from DB |
| App updated / reinstalled | `MY_PACKAGE_REPLACED` receiver → re-arm |
| User force-stops the app | Nothing runs; re-arm on next manual launch |
| Location services turned off | Listen for `PROVIDERS_CHANGED`, re-arm when re-enabled |
| Play services updated | Usually survives; re-arm on next `start()` anyway |
| Battery-optimised / restricted app | Delivery delayed or dropped — see §10 |

Rule: **re-arm from the database on every process start**, unconditionally. It is a cheap diff (§4.4) and it makes
all of the above self-healing.

---

### 6.7 Runtime permission flow

The Android counterpart of §7.1. This is the single most version-dependent part of the module — get the staging
wrong and background delivery is silently absent on a subset of devices.

#### What is actually required

| API level | Needed for geofencing in the background |
|---|---|
| ≤ 28 (Android 9) | `ACCESS_FINE_LOCATION` only — background is implicit, `ACCESS_BACKGROUND_LOCATION` does not exist |
| 29 (Android 10) | `ACCESS_FINE_LOCATION` **+** `ACCESS_BACKGROUND_LOCATION`, requestable **in one call** |
| 30+ (Android 11+) | same two, but they **must be requested in separate calls**, foreground first |
| 31+ (Android 12+) | `ACCESS_COARSE_LOCATION` must be requested alongside `ACCESS_FINE_LOCATION`; the user can grant *approximate* only, which is **not sufficient** — geofencing needs precise |
| 33+ (Android 13+) | `POST_NOTIFICATIONS`, but only if `enableHeadless: true` (the foreground service shows a notification) |

Geofencing requires **precise** location. A grant of `ACCESS_COARSE_LOCATION` without `ACCESS_FINE_LOCATION`
must be reported as insufficient, not as success.

#### Requesting from a native module

React Native's `ReactActivity` implements `PermissionAwareActivity`, which is the only supported way to run
`requestPermissions` from a module and receive the result — a module cannot override
`onRequestPermissionsResult` itself.

```kotlin
private const val RC_FOREGROUND = 0xF0F0
private const val RC_BACKGROUND = 0xF0F1

private var pending: Promise? = null   // one request at a time

@ReactMethod
fun requestPermission(promise: Promise) {
    if (pending != null) { promise.reject("E_REQUEST_IN_FLIGHT", "A permission request is already running"); return }

    val activity = currentActivity
    // Null whenever the app has no foreground UI — a background relaunch, or the
    // headless path. There is no way to prompt from there; report it, do not queue.
    if (activity !is PermissionAwareActivity) {
        promise.reject("E_NO_ACTIVITY", "requestPermission() needs a foreground activity"); return
    }

    pending = promise
    requestForeground(activity)
}

private fun requestForeground(activity: PermissionAwareActivity) {
    if (hasFine()) { requestBackgroundIfNeeded(activity); return }

    val perms = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)   // API 31+: must ask for both
    else
        arrayOf(ACCESS_FINE_LOCATION)

    // API 29 only: foreground + background may go in a single dialog ("Allow all the time").
    val all = if (Build.VERSION.SDK_INT == 29) perms + ACCESS_BACKGROUND_LOCATION else perms

    activity.requestPermissions(all, RC_FOREGROUND, listener)
}

private fun requestBackgroundIfNeeded(activity: PermissionAwareActivity) {
    if (Build.VERSION.SDK_INT < 29 || hasBackground()) { finish(); return }

    // API 30+: a separate request, and only ever ONE. After a denial the system
    // ignores further calls silently — shouldShowRequestPermissionRationale()
    // returning false with the permission ungranted is how you detect that.
    activity.requestPermissions(arrayOf(ACCESS_BACKGROUND_LOCATION), RC_BACKGROUND, listener)
}

private val listener = PermissionListener { requestCode, _, grantResults ->
    when (requestCode) {
        RC_FOREGROUND -> {
            if (hasFine()) requestBackgroundIfNeeded(currentActivity as PermissionAwareActivity)
            else finish()          // denied — resolve with state, do not reject
        }
        RC_BACKGROUND -> finish()
    }
    true   // listener consumed; RN removes it
}

private fun finish() {
    pending?.resolve(Core.getState(reactContext).toWritableMap())
    pending = null
}
```

#### Rules

- **A denial is a result, not an error.** `requestPermission()` resolves with the resulting `StateSpec`
  (`authorization`, `accuracyAuthorization`); only a *structural* failure (no activity, request already running)
  rejects. Callers branch on the state, not on a `catch`.
- **Never request background before foreground is granted.** On API 30+ the call is wasted, and it is the one
  background request the system will honour.
- **Implement the Settings fallback.** On API 30+ the "Allow all the time" option is not reliably offered by the
  system dialog across OEMs and versions — verify on real hardware. When background is still ungranted and
  `shouldShowRequestPermissionRationale(ACCESS_BACKGROUND_LOCATION)` is `false`, the only remaining path is
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`. Expose it as a separate `openSettings()` method rather than
  navigating the user automatically — the host app owns that UX decision.
- **Show your rationale before calling, not after.** Play Store policy expects the app to explain why background
  location is needed before the system prompt; a rationale shown afterwards does not count.
- **Re-check on every resume.** The user can revoke in Settings while the app runs, and Android 11+ auto-revokes
  permissions for unused apps. `getState()` must read the live grant, never a cached value.
- **`start()` must not silently no-op on a missing permission.** `addGeofences` on Play services fails with
  `GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION`; surface that through `getState().authorization` so a host app can
  tell "no permission" from "no crossings yet".

## 7. iOS implementation

### 7.1 Setup

```swift
private let manager = CLLocationManager()

func configure() {
    manager.delegate = self
    manager.allowsBackgroundLocationUpdates = true
    manager.pausesLocationUpdatesAutomatically = false
    manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
}
```

`Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>…</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>…</string>
<key>UIBackgroundModes</key>
<array><string>location</string></array>
```

**Region monitoring requires `authorizedAlways`.** With `authorizedWhenInUse` the OS does not deliver region
events in the background — which is the entire use case. Request `requestWhenInUseAuthorization()` first, then
`requestAlwaysAuthorization()` after the user has seen the value (Apple rejects apps that ask for Always up front).

**iOS 14+ reduced accuracy:** if `manager.accuracyAuthorization == .reducedAccuracy`, region monitoring still
works but with much larger error. Report it in `getState()`; optionally call
`requestTemporaryFullAccuracyAuthorization(withPurposeKey:)`.

### 7.2 Registering

```swift
let region = CLCircularRegion(
    center: CLLocationCoordinate2D(latitude: lat, longitude: lng),
    radius: min(radius, manager.maximumRegionMonitoringDistance),
    identifier: id
)
region.notifyOnEntry = notifyOnEntry || notifyOnDwell   // dwell needs entry
region.notifyOnExit  = notifyOnExit  || notifyOnDwell   // dwell needs exit to cancel
manager.startMonitoring(for: region)

// initial state, drives `initialTriggerEntry` and the §5.2 reconciliation
manager.requestState(for: region)
```

- `manager.monitoredRegions` is the authoritative OS registry — use it for the diff, do not track it in memory.
- `stopMonitoring(for:)` needs a region object; construct one with the same identifier, or find it in
  `monitoredRegions`.
- `CLCircularRegion` has **no dwell and no loitering delay**. Both flags must be emulated.
- Handle `locationManager(_:monitoringDidFailFor:withError:)` — `kCLErrorRegionMonitoringFailure` usually means
  the 20-region limit was exceeded, i.e. a rotation bug.

### 7.3 The event has no location

Android hands you `event.triggeringLocation`. **iOS does not** — `didEnterRegion` gives only the region. Options,
in order of preference:

1. Use `manager.location` (last known fix). Usually populated and free.
2. Synthesise from the region: `location = region.center`, accuracy = `region.radius`. Mark it
   `extras.__approximate = true`.
3. Request a one-shot `requestLocation()` and emit the event only once it returns. **Do not do this by default** —
   it costs a GPS fix on every crossing and can hang for seconds in a background-relaunch window.

Document `GeofenceEvent.location` as **best-effort, possibly approximate, possibly null**. A host app that needs a
precise fix at crossing time should request one itself in the JS handler.

### 7.4 Emulating DWELL

iOS suspends the app between region events, so a plain `Timer` is not reliable. Layered approach:

```
onEnter(region):
    persist enteredAt[id] = now
    if notifyOnDwell:
        # Layer 1 — short delays only. beginBackgroundTask buys ~30 s (do not assume more).
        if loiteringDelay <= 25_000:
            let task = UIApplication.shared.beginBackgroundTask()
            schedule Timer(loiteringDelay) {
                verifyStillInside(id) { inside in if inside { emit DWELL } }
                UIApplication.shared.endBackgroundTask(task)
            }
        # Layer 2 — long delays: nothing to schedule. Resolve opportunistically.

onAnyWake(region event / SLC update / app foreground):
    for id where enteredAt[id] != nil and dwellNotYetEmitted[id]:
        if now - enteredAt[id] >= loiteringDelay[id] and stillInside(id):
            emit DWELL

onExit(region): clear enteredAt[id], cancel timer
```

`verifyStillInside` = `manager.requestState(for:)` and wait for `didDetermineState`.

**Be honest about this in the docs you ship:** on iOS, a DWELL with a delay longer than ~25 s fires *at the next
time the app is awake* after the delay elapses, not exactly at the delay. If the host app's logic depends on exact
dwell timing, it must compute duration from the ENTER timestamp itself rather than trusting the DWELL event's
arrival time.

### 7.5 Terminated-app relaunch

A monitored region crossing relaunches a terminated app into the background. To receive the pending event you must
create the `CLLocationManager` and assign its delegate **synchronously inside**
`application(_:didFinishLaunchingWithOptions:)` — not lazily when JS calls `ready()`, which happens far too late.

```objc
- (BOOL)application:(UIApplication *)application
        didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    if (launchOptions[UIApplicationLaunchOptionsLocationKey]) {
        [RNGeofenceCore sharedInstance];   // constructs CLLocationManager + delegate NOW
    }
    ...
}
```

The snippet above is what has to *happen*; **how it gets there is decided in §18.3**, which compares the three
mechanisms (Expo `appDelegateSubscribers`, a plugin `withAppDelegate` mod, and library-side `+load`). Do not read
this section as "document a manual step in the README" — the target project regenerates `ios/` on every build
(§18), so a manual `AppDelegate` edit does not survive to the next build. Pick a mechanism from §18.3 before
writing any of the phase-8 iOS code.

There is **no headless JS on iOS**. The relaunched process boots the RN bridge normally; native must queue the
event and flush it once JS registers a listener. Budget: the OS gives the relaunched app roughly 10 seconds of
background runtime, which is often *less* than an RN cold start. **Assume JS will not run.** All state that the
host app depends on must be updated natively, in the DB, before JS is involved.

### 7.6 Significant-location-change backstop

```swift
manager.startMonitoringSignificantLocationChanges()
```

~500 m / 5 min granularity, negligible battery, and it also relaunches a terminated app. Use it purely as a
rotation trigger in case a boundary EXIT is missed. Optional; gate behind `config.useSignificantLocationChanges`
(default `true`).

---

## 8. Public API

### 8.1 TurboModule spec — `src/NativeGeofencing.ts`

```ts
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypes';

export type GeofenceSpec = {
    identifier: string;
    latitude: number;
    longitude: number;
    radius: number;
    notifyOnEntry?: boolean;
    notifyOnExit?: boolean;
    notifyOnDwell?: boolean;
    loiteringDelay?: number;   // ms, dwell only
    extras?: string;           // JSON string — codegen has no arbitrary-object type
};

export type GeofenceEventPayload = {
    identifier: string;
    action: 'ENTER' | 'EXIT' | 'DWELL';
    timestamp: number;         // epoch ms
    latitude?: number;
    longitude?: number;
    accuracy?: number;
    approximate: boolean;      // §7.3
    synthetic: boolean;        // §5.2
    extras?: string;
};

export type GeofencesChangePayload = {
    on: ReadonlyArray<GeofenceSpec>;
    off: ReadonlyArray<string>;
};

export type ConfigSpec = {
    proximityRadius?: number;              // default 2000 m
    initialTriggerEntry?: boolean;         // default true
    notificationResponsiveness?: number;   // Android only, ms, default 0
    useSignificantLocationChanges?: boolean; // iOS only, default true
    enableHeadless?: boolean;              // Android only, default false
    debug?: boolean;
};

export type StateSpec = {
    enabled: boolean;
    available: boolean;          // Play services / region monitoring available
    authorization: string;       // 'always' | 'whenInUse' | 'denied' | 'restricted' | 'notDetermined'
    accuracyAuthorization: string; // 'full' | 'reduced'
    geofenceCount: number;
    activeCount: number;
};

export interface Spec extends TurboModule {
    ready(config: ConfigSpec): Promise<StateSpec>;
    start(): Promise<void>;
    stop(): Promise<void>;

    addGeofence(geofence: GeofenceSpec): Promise<void>;
    addGeofences(geofences: ReadonlyArray<GeofenceSpec>): Promise<void>;
    removeGeofence(identifier: string): Promise<void>;
    removeGeofences(identifiers?: ReadonlyArray<string>): Promise<void>;
    getGeofences(): Promise<GeofenceSpec[]>;
    getActiveGeofences(): Promise<string[]>;   // currently armed; see §4.6 for what that means per platform

    requestPermission(): Promise<StateSpec>;   // resolves with the resulting state, never rejects on denial (§8.4)
    openSettings(): Promise<void>;             // Android API 30+ background-location fallback (§6.7)
    getState(): Promise<StateSpec>;
    flushQueue(): Promise<GeofenceEventPayload[]>;  // events buffered while JS was down

    readonly onGeofence: EventEmitter<GeofenceEventPayload>;
    readonly onGeofencesChange: EventEmitter<GeofencesChangePayload>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNGeofencing');
```

Note `extras` is a **JSON string**, not an object: codegen has no arbitrary-map type that round-trips cleanly.
The JS wrapper serialises and deserialises it so callers see a real object.

### 8.2 JS wrapper — `src/index.ts`

Thin ergonomic layer over the spec:

```ts
export const Geofencing = {
    ready,               // idempotent; also flushes the queue into listeners
    start, stop,
    addGeofence, addGeofences, removeGeofence, removeGeofences,
    getGeofences, getActiveGeofences,
    requestPermission, getState,
    onGeofence(cb: (e: GeofenceEvent) => void): Subscription,
    onGeofencesChange(cb: (e: GeofencesChange) => void): Subscription,
    registerHeadlessTask(task: (e: GeofenceEvent) => Promise<void>): void,  // Android
};
```

`registerHeadlessTask` wraps
`AppRegistry.registerHeadlessTask('RNGeofenceHeadlessTask', () => task)` and must be called at module scope in
`index.js`, outside the React tree, so it is registered before the headless bundle runs.

### 8.3 Usage

```ts
import { Geofencing } from '@your-org/react-native-geofencing';

await Geofencing.ready({ proximityRadius: 2000, initialTriggerEntry: true });
await Geofencing.requestPermission();

await Geofencing.addGeofences(
    places.map(p => ({
        identifier: p.id,
        latitude: p.lat,
        longitude: p.lng,
        radius: Math.max(p.radius, 200),   // see §10 on minimum radius
        notifyOnEntry: true,
        notifyOnExit: true,
        notifyOnDwell: true,
        loiteringDelay: 30_000,
        extras: { placeId: p.id },
    })),
);

Geofencing.onGeofence(e => {
    console.log(e.action, e.identifier);
});
```

---

### 8.4 Promise rejection codes

One table for both platforms. Every `reject()` in the module uses a code from here — no ad-hoc strings, so a host
app can branch on `e.code` reliably.

| Code | Meaning | Typical cause |
|---|---|---|
| `E_NOT_READY` | a method was called before `ready()` | host app ordering bug |
| `E_NO_ACTIVITY` | no foreground activity to host a dialog (Android) | `requestPermission()` from the background |
| `E_REQUEST_IN_FLIGHT` | a permission request is already running | double-tap on the permission button |
| `E_UNAVAILABLE` | geofencing not available on this device | no Play services (Android); `isMonitoringAvailable == false` (iOS) |
| `E_INVALID_GEOFENCE` | malformed input | empty/reserved identifier, non-finite lat/lng, `radius <= 0` |
| `E_LIMIT_EXCEEDED` | the store cap of 2000 was exceeded (§9.5) | bulk `addGeofences` |
| `E_PLATFORM` | the OS registration call itself failed | Play services error code, `monitoringDidFailFor` |
| `E_STORE` | prefs read/write or JSON parse failed | corrupt blob, disk full |
| `E_INTERNAL` | anything unclassified | always accompanied by the original message |

**What must *not* reject:**

- A permission denial → `requestPermission()` **resolves** with the resulting `StateSpec`.
- Location services being off, or a rotation centre that could not be resolved → keep the current set, log, resolve.
  Rotating on a bad centre is worse than being stale (§4.7).
- An empty geofence set, or `removeGeofences()` on nothing.

Reserve `E_PLATFORM` for a real OS-level failure and always include the platform's own code in the message
(`GeofenceStatusCodes.getStatusCodeString(...)` on Android, the `CLError` code on iOS). That string is what makes
a field report actionable.

## 9. Persistence — key/value, no database

**Decision: no SQLite.** Use the platform key/value store that already ships with the OS:

| Platform | Store | Backing |
|---|---|---|
| Android | `SharedPreferences` (`context.getSharedPreferences("rn_geofencing", MODE_PRIVATE)`) | XML file |
| iOS | `UserDefaults(suiteName: "rn_geofencing")` | plist file |

This drops the Room dependency on Android and the `sqlite3`/GRDB wrapper on iOS entirely. At the scale this module
is built for it costs nothing: rotation reads the whole set anyway, so an indexed table buys no query speedup over
a linear scan of an in-memory array.

### 9.1 The one rule that makes this safe

> **Store each concern as a single JSON blob under a single key. Never one key per geofence.**

Both stores rewrite the entire file on every commit, and both do so **atomically** (write to temp, then rename).
One key per concern therefore gives the same all-or-nothing guarantee a SQLite transaction would. Spreading
geofences across many keys throws that away: a process killed mid-commit leaves a half-updated set, which is
exactly the "geofences silently stopped working" failure the whole design is trying to avoid.

### 9.2 Keys

Four keys, four JSON blobs.

```jsonc
// "geofences" — the full set, and the transition-gate state, together.
// Kept in one blob: §5 updates state on the same object rotation reads.
{
  "v": 1,
  "items": {
    "place-123": {
      "lat": 59.9139, "lng": 10.7522, "radius": 200,
      "onEntry": true, "onExit": true, "onDwell": true, "loiteringDelay": 30000,
      "extras": "{\"placeId\":\"123\"}",
      "state": "INSIDE",          // UNKNOWN | INSIDE | OUTSIDE   (§5)
      "enteredAt": 1736412000000,
      "dwellEmitted": false,
      "active": true              // currently armed at OS level
    }
  }
}

// "events" — undelivered event queue (§6.5), FIFO, hard-capped
{ "v": 1, "items": [ { "id": "...", "action": "ENTER", "ts": 1736412000000,
                       "lat": 59.9, "lng": 10.7, "accuracy": 65,
                       "approximate": false, "synthetic": false, "extras": null } ] }

// "meta" — rotation bookkeeping
{ "v": 1, "centerLat": 59.9139, "centerLng": 10.7522, "centerAt": 1736412000000,
  "boundaryRadius": 1400, "enabled": true }

// "config" — the ConfigSpec last passed to ready()
{ "v": 1, "proximityRadius": 2000, "initialTriggerEntry": true, "enableHeadless": false }
```

Every blob carries `"v"` so a future format change can migrate instead of throwing away a user's registered set.
On an unknown or unparseable `v`: **keep the raw string, log, and start empty** — never crash the receiver, and
never silently overwrite until a successful migration has run.

### 9.3 In-memory model is the working copy

Both stores load the whole file into memory on first access, so treat that as the design rather than fighting it:

```
process start
    → Store.load()          # parse the 4 blobs once, build in-memory model
    → all reads             # from memory, zero I/O — rotation is free
    → any mutation          # mutate memory, then write-through the affected blob
```

Write-through, not write-behind: a geofence receiver may have only its ~10 s `goAsync()` window before the
process is frozen, so a deferred flush can be lost. Use `apply()` on Android (async but survives process death —
the framework flushes it) and let iOS's `UserDefaults` handle its own coalescing; call `synchronize()` only in
the Android headless / iOS background-relaunch paths where the process may die within milliseconds.

### 9.4 Concurrency

`SharedPreferences` is **not multi-process safe** (`MODE_MULTI_PROCESS` is deprecated and broken), and `UserDefaults`
has the same limitation across app extensions. Two constraints follow:

1. **Never declare `android:process` on the receiver, the headless service, or the boot receiver.** Everything must
   run in the default process. If a second process ever touches these prefs, the in-memory copies diverge and one
   silently overwrites the other.
2. Route every read-modify-write through the single-threaded rotation executor already mandated in §4.5. The store
   itself needs no locking if nothing else can reach it.

### 9.5 Caps

Whole-file rewrite means cost scales with total size, so bound both blobs:

| Blob | Cap | On overflow |
|---|---|---|
| `geofences` | **2000 entries** (~400 KB JSON) | reject the `addGeofences` call with a clear error |
| `events` | **200 entries** | drop oldest first (FIFO), increment a `droppedCount` in `meta` |

2000 is the practical ceiling, not a hard technical one: at that size a full serialise + commit is roughly 5–15 ms
on a mid-range device. That is irrelevant when it happens on rotation (at most every few hundred metres) but would
be if it happened per location update — which is exactly why this module has no location stream.

**If a host app genuinely needs more than ~2000 geofences, do not scale the prefs blob.** Swap the implementation
(§9.6) for an atomically-written JSON file, or reintroduce SQLite at that point.

### 9.6 Keep it swappable

Put the whole thing behind one interface per platform so the decision is reversible without touching the rotation
engine or the transition gate:

```kotlin
interface GeofenceStore {
    fun load()
    fun all(): List<GeofenceRecord>
    fun get(id: String): GeofenceRecord?
    fun upsert(records: List<GeofenceRecord>)
    fun delete(ids: List<String>)
    fun updateState(id: String, state: State, enteredAt: Long?, dwellEmitted: Boolean)
    fun setActive(ids: Set<String>)

    fun enqueue(event: GeofenceEvent)
    fun drainQueue(): List<GeofenceEvent>

    var meta: RotationMeta
    var config: Config
}
```

`PrefsGeofenceStore` / `UserDefaultsGeofenceStore` are the only implementations shipped. Nothing above this
interface may know how the bytes are stored.

### 9.7 Android Direct Boot caveat

`SharedPreferences` created from the normal context live in **credential-encrypted storage**, which is unreadable
until the user unlocks the device after a reboot. Consequences:

- Handle **`BOOT_COMPLETED` only**; drop `LOCKED_BOOT_COMPLETED` from the manifest. It fires before unlock, when
  the store cannot be read, so a handler there can do nothing useful.
- Geofences therefore stay unregistered between a reboot and the first unlock. That is acceptable — Play services
  geofencing is not reliably available before unlock either.
- Do **not** reach for `createDeviceProtectedStorageContext()` to work around this. It would let the boot receiver
  read the set earlier, at the cost of a second store to keep in sync and a real risk of the two diverging. The
  window it closes is minutes long, once per reboot.

The equivalent on iOS is file data protection: if the host app raises the protection class to
`NSFileProtectionComplete`, `UserDefaults` reads can fail while the device is locked. The default
(`…CompleteUntilFirstUserAuthentication`) is fine. Flag it in the README rather than designing around it.

## 10. Known limits and failure modes

Ship this table in the library README. Most support questions land here.

| Limit | Value | Consequence |
|---|---|---|
| iOS monitored regions | 20 (19 usable) | rotation mandatory |
| Android monitored geofences | 100 (99 usable) | rotation mandatory |
| Minimum reliable radius | **~100 m Android, ~200 m iOS** | smaller circles frequently never trigger — Apple documents 200 m as the practical floor. Clamp user input up to a `minRadius` (default 200) and log when you do. |
| Detection latency | seconds to **several minutes** | Doze, Low Power Mode and `notificationResponsiveness` all add delay. Never treat a geofence event as real-time. |
| Android battery restriction | app "Restricted" → no delivery | detect via `PowerManager.isIgnoringBatteryOptimizations`, surface in `getState()` |
| iOS terminated relaunch budget | ~10 s | often shorter than an RN cold start; do critical work natively |
| iOS DWELL accuracy | fires on next wake for delays > ~25 s | compute duration from the ENTER timestamp |
| Reduced accuracy (iOS 14+) | large position error | region events become unreliable; surface it |
| Non-GMS Android | no `GeofencingClient` | `available: false`, module is inert |
| Airplane mode / GPS off | no events | listen for provider change, re-arm |
| Dense clusters (>19 in 500 m on iOS) | boundary overlaps excluded regions | possible missed ENTER; log a warning |
| Total registered geofences | **2000** | prefs-backed store (§9.5); `addGeofences` rejects beyond it |
| Undelivered event queue | **200** | oldest dropped first if JS never flushes |
| Android before first unlock after reboot | geofences unregistered | prefs unreadable in Direct Boot (§9.7) |

---

## 11. Package layout

### 11.0 Bootstrap with the CLI — do not hand-create the package

**Run `create-react-native-library` first.** Everything in §11.1, §15 and §17 is written as a **delta from what
that scaffold emits**, not as a from-scratch file list, and it will not fit anything else. The tree in §11.1 names
only the files that matter to *this* module; the scaffold also produces babel/jest/eslint/tsconfig, `.gitignore`,
the gradle wrapper, the podspec skeleton, the Xcode project bits, workspace wiring, and a complete RN app in
`example/` — none of which is worth hand-writing, and the last of which is not realistically hand-writable at all.

```bash
# Flags move between versions — read this before composing the command below.
npx create-react-native-library@latest --help

npx create-react-native-library@latest react-native-geofencing \
  --type module-mixed \
  --languages kotlin-objc
```

Add the slug / description / author / repo flags `--help` lists. **Whichever you omit, the CLI prompts for** — so
an unattended run hangs on the prompt rather than failing, which is the usual way this step goes wrong. It also
runs `git init` plus a package install, so it needs network, and it creates its own directory: this is a
standalone repository (see the header), not a folder inside a host app.

**Then audit what the scaffold actually pinned, before writing any code.** The scaffold's defaults have shipped
below this module's floor, and every one of these is cheaper to fix now than after ten files depend on it:

| Check | Where | Must be |
|---|---|---|
| `compileSdkVersion`, `targetSdkVersion` | `android/gradle.properties` | **≥ 34** (§6.1) |
| Kotlin version | `android/gradle.properties` | **1.9.x** (§6.1) |
| AGP version | `android/build.gradle` `buildscript` | if **< 7.3**, `AndroidManifest.xml` — not `…New.xml` — is the file your builds use (§6.2) |
| `codegenConfig.type` | `package.json` | `"modules"`, not `"all"` (§11.1) |
| bob targets + `types` path | `package.json` | `esm: true` changes the `types` path (§17.1) |
| `files` | `package.json` | must include `src`, `plugin`, `app.plugin.js` (§17.1) |
| `example/` | — | present and wired as a workspace (§16) |

Only after that does hand-writing start, and it is confined to what actually is this module: `core/`, the two
`GeofencingSpec.kt` files, the platform registries, the receivers and service, `plugin/src/index.ts`, and the
example's screen.

### 11.1 Layout

`module-mixed` records itself in `package.json` as
`"create-react-native-library": { "type": "module-mixed", "languages": "kotlin-objc" }`, and it is what produces
the newarch/oldarch split in §15 without hand-rolling it.

```
react-native-geofencing/
├─ package.json                 # codegenConfig + react-native-builder-bob + files (§17.1)
├─ app.plugin.js                # module.exports = require('./plugin/build/index')   (§18)
├─ react-native.config.js       # optional — only if autolinking misses the package  (below)
├─ RNGeofencing.podspec
├─ tsconfig.json / tsconfig.build.json
├─ turbo.json                   # OPTIONAL task cache — see §17 preamble             (§17.2)
├─ src/
│  ├─ index.tsx                 # public wrapper + module resolution (§15.2) — "source" in package.json
│  ├─ NativeGeofencing.ts       # codegen spec (§8.1)
│  └─ type.ts                   # public types
├─ plugin/                      # Expo config plugin (§18)
│  ├─ src/index.ts
│  ├─ tsconfig.json             # extends expo-module-scripts/tsconfig.plugin
│  └─ build/                    # COMMITTED — app.plugin.js requires it at install time
├─ android/
│  ├─ build.gradle              # §6.1
│  ├─ gradle.properties         # Geofencing_* keys
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml        # with package=…      (AGP < 7.3)   §6.2
│     │  ├─ AndroidManifestNew.xml     # namespace, no package (AGP >= 7.3) §6.2
│     │  └─ java/com/rngeofencing/
│     │     ├─ GeofencingModule.kt          # the ONLY module impl — extends GeofencingSpec
│     │     ├─ GeofencingPackage.kt         # BaseReactPackage (§15.3)
│     │     ├─ core/Core.kt                 # singleton, no React dependency
│     │     ├─ core/RotationEngine.kt
│     │     ├─ core/TransitionGate.kt
│     │     ├─ core/PrefsGeofenceStore.kt   # SharedPreferences, JSON blobs (§9)
│     │     ├─ core/PlatformRegistry.kt     # GeofencingClient wrapper
│     │     ├─ GeofenceBroadcastReceiver.kt
│     │     ├─ BootReceiver.kt
│     │     └─ GeofenceHeadlessService.kt
│     ├─ newarch/GeofencingSpec.kt          # abstract, extends generated NativeGeofencingSpec
│     └─ oldarch/GeofencingSpec.kt          # abstract, extends ReactContextBaseJavaModule
├─ ios/
│  ├─ RNGeofencing.h / .mm              # module surface, ObjC++ — both architectures (§15.4)
│  ├─ RNGeofencingCore.h / .mm          # singleton, CLLocationManagerDelegate
│  ├─ RotationEngine.h / .mm
│  ├─ TransitionGate.h / .mm
│  ├─ UserDefaultsGeofenceStore.h / .mm # UserDefaults, JSON blobs (§9)
│  └─ PlatformRegistry.h / .mm
└─ example/                             # plain RN app, a yarn workspace (§16)
```

**ObjC++ for the whole iOS side, not Swift.** The podspec's `s.source_files = "ios/**/*.{h,m,mm,cpp}"` means a
`.swift` file placed there is silently not compiled. Swift is possible (add `swift` to the glob, keep
`SWIFT_VERSION`/`DEFINES_MODULE`), but mixing it in means a second language in a `kotlin-objc` package and a
bridging story for the ObjC++ TurboModule surface. Write the core in `.mm` and stay with one toolchain.

```json
"codegenConfig": {
    "name": "RNGeofencingSpec",
    "type": "modules",
    "jsSrcsDir": "src",
    "android": { "javaPackageName": "com.rngeofencing" }
}
```

`"type": "modules"`, not `"all"`. This module ships no Fabric component, and `"all"` makes codegen emit a
`ComponentDescriptors` / `Props` / `ShadowNodes` / `EventEmitters` set that nothing ever references — dead build
output that still has to compile on every host app.

**Let the consuming app run codegen. Do not ship generated code.** This is the default and it is the right default
here: the app's build already invokes codegen for every autolinked library, the generated headers land in the
app's own build tree, and the package stays a source-only artifact. Concretely it means the package has **no**
`outputDir`, **no** `includesGeneratedCode`, no `android/generated` or `ios/generated`, no `cmakeListsPath` in
`react-native.config.js`, and no bob `codegen` target. `"src"` in `files` (§17.1) is what makes it work — codegen
reads the spec from source.

> **The alternative, and why it is not the default.** Setting `outputDir` + `includesGeneratedCode: true` ships
> pre-generated code inside the package. It buys marginally faster app builds and costs: a `cmakeListsPath` entry,
> a podspec that must exclude the generated directory on legacy builds (§15.4 — and getting that condition wrong is
> a live bug, see there), a codegen step wired into `prepare` **and** into the example's `Podfile`, and a stale-
> output failure mode whose symptom is an unexplained missing-symbol error rather than "your spec changed". Take it
> only if an app-side codegen step is genuinely a problem for a consuming project; a geofencing module has no
> reason to.

`react-native.config.js` is then needed only if autolinking cannot infer the Android package class from its name
(it normally can, for a class called `GeofencingPackage`):

```js
// optional — add only if autolinking does not find the package
module.exports = {
  dependency: { platforms: { android: { packageInstance: 'new GeofencingPackage()' } } },
};
```

**Critical structural rule:** the `core/` layer must have **zero React Native dependencies**. It is constructed
from a `BroadcastReceiver`, from `didFinishLaunchingWithOptions`, and from the TurboModule — three entry points,
only one of which has a React context. Any `ReactApplicationContext` reference inside the core makes killed-app
operation impossible. The TurboModule is a thin adapter over the core, never the owner of it.

---

## 12. Migration map (from `react-native-background-geolocation`)

For a project currently using the paid library for geofencing only.

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
| `event.location` (always present) | `event.latitude/longitude/accuracy`, **may be absent or approximate** |

The `location` difference is the only behavioural break. Audit every geofence handler for code that assumes a
precise fix on the event.

---

## 13. Implementation order

Do not build platforms in parallel. Android first — it has native DWELL, an event location, and headless JS, so
the core logic is validated with fewer emulated pieces.

Read the sections listed for a phase **before** starting it, not the whole document up front. §0 is the
exception — read it once, first, and re-read it whenever a change feels like it needs an exception.

| Phase | Read first | Deliverable | Done when |
|---|---|---|---|
| 1 | §0, §11.0 **first**, then §11.1, §15.1–15.4, §8.1 | Package skeleton (`module-mixed`, §11), spec + newarch/oldarch abstract split (§15.3), no-op native | JS can call `ready()` on both platforms, with `newArchEnabled` both ways |
| 2 | §9 (all), §8.1 | `GeofenceStore` + blob format, `addGeofences`/`getGeofences` | geofences round-trip through prefs, survive a process restart |
| 3 | §6.1–6.5, §16 | Android `PlatformRegistry` + receiver, **no rotation** (cap at 99), **+ example app skeleton (§16)** | ENTER/EXIT reach JS in foreground and show in the example's event log |
| 4 | §5, §14 (unit list) | `TransitionGate` (§5) | no duplicate ENTER on re-arm; synthetic EXIT works |
| 5 | §4 (all), §10 | `RotationEngine` (§4) + boundary region | 300 geofences, only 99 armed, set swaps as you drive |
| 6 | §6.5–6.6, §9.7 | Android background + headless + boot receiver | events arrive with the app killed and after a reboot |
| 7 | §7.1–7.3, §4.6 | iOS `PlatformRegistry` + delegate + rotation | parity with phase 5 on device |
| 8 | §7.4–7.6, §18.3 | iOS dwell emulation + relaunch hook + SLC | parity with phase 6 |
| 9 | §6.7, §7.1, §8.4 | Permissions (§6.7 Android, §7.1 iOS), `getState`, the §8.4 error codes | every API level in the §6.7 table exercised on a device or emulator; `available: false` paths behave |
| 10 | §17, §18, §12 | Docs (§17.4), migration guide, CI matrix (§17.2), release setup | §10 table verified on real hardware; example app complete per §16 |

Two additions to the order above:

- **Phase 8 also owns the Expo plugin** (§18). The iOS relaunch hook is the plugin's reason to exist, so ship the
  §18.3 mechanism in the same phase as the hook itself. One part of it moves earlier: §18.3's mechanism A needs
  the two questions in phase 1, because the answers decide whether the package contains Swift at all (§11).
- **Phase 1 must build under both architectures before phase 2 starts.** The newarch/oldarch split (§15.3) is
  cheap to set up on an empty module and expensive to bolt on after ten methods exist in one of them.

---

## 14. Testing

Geofencing cannot be validated in unit tests alone. Split it:

**Unit-testable (do this properly — it is where the bugs are):**
- `computeActiveSet`: capacity, boundary radius, safe zone, degenerate clusters, fewer geofences than capacity.
- `TransitionGate`: every `(persistedState, platformEvent)` pair, including re-arm and missed-EXIT.
- Diff logic in `applyActiveSet`: add-before-remove ordering, no-op on unchanged set.
- Haversine, and the `proximityRadius + g.radius` filter in §4.3 (must never exclude a geofence whose edge is reachable).
- Store round-trip: serialise → parse → deep-equal, including an unknown `"v"` (must not crash, must not overwrite).

**Device / simulator:**

| Test | How |
|---|---|
| iOS enter/exit | Xcode → Debug → Simulate Location, or a `.gpx` route file |
| Android enter/exit | `adb emu geo fix <lon> <lat>`, or Extended Controls → Location → Routes |
| Rotation | seed 300 geofences along a route, drive the route, assert `getActiveGeofences()` changes and `onGeofencesChange` fires |
| Killed app (Android) | `adb shell am force-stop <pkg>` is **not** a valid test (force-stop kills geofences too). Swipe the app away from Recents instead, then move location. |
| Killed app (iOS) | swipe up from the app switcher, then simulate a location crossing; app relaunches |
| Reboot (Android) | `adb reboot`, then cross a region without opening the app |
| Doze (Android) | `adb shell dumpsys deviceidle force-idle`, then cross a region; expect multi-minute latency |
| Permission downgrade | revoke background location in Settings while running; expect `getState().authorization` to change |

Ship a debug helper: `Geofencing.getState()` plus a `debug: true` config that logs every rotation with the
computed centre, boundary radius, and the on/off diff. Almost every field report resolves to reading that log.

---

## 15. Building without the New Architecture

Everything above assumes TurboModules. A target project still on the legacy bridge (or one that must support both)
changes **only the outermost adapter layer**. This is the payoff of the §11 rule that `core/` has zero React
Native dependencies: the rotation engine, the transition gate, the store and every platform registry are
byte-for-byte identical. Nothing in §4, §5, §6.3–6.4, §7 or §9 changes.

### 15.1 Pick an option

| | Option A — legacy only | Option B — dual architecture |
|---|---|---|
| When | the target project is on the old bridge and has no migration plan | the module is shared across projects, or the host will migrate later |
| Codegen | none | spec file kept, used only when new arch is on |
| Event mechanism | `NativeEventEmitter` | `NativeEventEmitter` in **both** (see §15.4) |
| Extra files | — | `android/src/newarch/` + `android/src/oldarch/` (one abstract class each), one `#if` (iOS) |
| Min RN | 0.60 (autolinking) | 0.68, realistically 0.71+ |
| Cost | lowest | ~1 extra file per platform |

Option B is worth it only if the module is genuinely shared. For a single legacy app, take Option A and delete
`src/NativeGeofencing.ts` and the `codegenConfig` block.

> **§15.3 and §15.4 below are written for Option B.** They cost one extra file per platform and nothing at
> runtime, so unless the module is known to serve exactly one legacy app forever, Option B is the default: a
> module published as new-arch-only cannot be walked back without a major version and a port.

### 15.2 The JS side barely changes

`TurboModuleRegistry.get()` already falls back to `NativeModules` when there is no TurboModule proxy — RN's own
implementation checks `global.__turboModuleProxy` and returns `NativeModules[name]` when it is absent. **One spec
file therefore resolves the module correctly under both architectures**, as long as the native side registers the
same name (`RNGeofencing`).

**Use `get()`, not `getEnforcing()`, and write no architecture branch at all.** Two files, no `__turboModuleProxy`
check, no lazy `require`:

```ts
// src/NativeGeofencing.ts — codegen input; `get` is accepted by codegen exactly like `getEnforcing`
export default TurboModuleRegistry.get<Spec>('RNGeofencing');   // nullable
```

```ts
// src/index.tsx
import { NativeEventEmitter, Platform } from 'react-native';
import NativeGeofencing from './NativeGeofencing';

const LINKING_ERROR =
    `The package 'react-native-geofencing' doesn't seem to be linked. Make sure: \n\n` +
    Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
    '- You rebuilt the app after installing the package\n' +
    '- You are not using Expo Go\n';

const Native =
    NativeGeofencing ??
    new Proxy({} as typeof NativeGeofencing & {}, {
        get() { throw new Error(LINKING_ERROR); },
    });

export const emitter = new NativeEventEmitter(Native as any);
```

`requireModule` inside `TurboModuleRegistry` falls back to `NativeModules[name]` **unconditionally** when the
TurboModule proxy yields nothing — the fallback is not gated on the architecture — so this one call resolves the
module under both. Verify the shape against the `TurboModuleRegistry.js` of the RN version you target rather than
trusting this paragraph; it is the kind of internal that moves.

Three things to get right:

1. **The `getEnforcing` + lazy-`require` pattern you will see in older libraries solves a different problem than
   it looks like.** `getEnforcing` does *not* throw merely because the legacy bridge is in use — it throws when the
   module is **not linked at all** (or on Expo Go), because its fallback to `NativeModules` also comes back empty.
   Evaluated at import time, that invariant error pre-empts the friendlier `LINKING_ERROR` Proxy, and the lazy
   `require` exists only to defer it. Using `get()` removes the reason for the trick entirely: a missing module is
   `null`, and the Proxy produces the readable message. Do not port the `__turboModuleProxy` gate here — it is a
   second, architecture-shaped code path that has to be kept correct as bridgeless evolves, in exchange for
   nothing.
2. **Native must register the name `RNGeofencing` identically on both paths** — `getName()` on Android,
   `RCT_EXPORT_MODULE()` on iOS — or one architecture resolves and the other silently falls into the Proxy.
3. Drop the codegen `EventEmitter<T>` properties from `interface Spec` and declare
   `addListener(eventName: string)` / `removeListeners(count: number)` instead. See §15.4.

### 15.3 Android adapter

**Split only the abstract spec class, never the module implementation.** This is what `module-mixed` scaffolds:
two tiny `GeofencingSpec.kt` files, one per source set, and **exactly one** `GeofencingModule.kt` in `src/main`
that extends whichever of them is compiled. Duplicating the module body across `newarch/` and `oldarch/` — the
obvious first instinct, because `@ReactMethod` lives on the legacy side — means every method of §8.1 is written
twice and drifts.

```kotlin
// android/src/newarch/GeofencingSpec.kt
package com.rngeofencing

import com.facebook.react.bridge.ReactApplicationContext

abstract class GeofencingSpec internal constructor(context: ReactApplicationContext) :
  NativeGeofencingSpec(context)      // emitted by the app's codegen; on the source path via the react gradle plugin
```

```kotlin
// android/src/oldarch/GeofencingSpec.kt
package com.rngeofencing

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap

abstract class GeofencingSpec internal constructor(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {

  // Mirror §8.1 exactly. Codegen maps TS `number` to Kotlin `Double` — match that,
  // or the two architectures take different argument types for the same JS call.
  abstract fun ready(config: ReadableMap, promise: Promise)
  abstract fun start(promise: Promise)
  abstract fun stop(promise: Promise)
  abstract fun addGeofence(geofence: ReadableMap, promise: Promise)
  abstract fun addGeofences(geofences: ReadableArray, promise: Promise)
  abstract fun removeGeofence(identifier: String, promise: Promise)
  abstract fun removeGeofences(identifiers: ReadableArray?, promise: Promise)
  abstract fun getGeofences(promise: Promise)
  abstract fun getActiveGeofences(promise: Promise)
  abstract fun requestPermission(promise: Promise)
  abstract fun openSettings(promise: Promise)
  abstract fun getState(promise: Promise)
  abstract fun flushQueue(promise: Promise)

  // Required by NativeEventEmitter since RN 0.65 — no-ops, but their absence
  // produces a runtime warning on every subscription. Under the new arch the
  // generated spec already declares them; here they must be written by hand.
  abstract fun addListener(eventName: String)
  abstract fun removeListeners(count: Double)
}
```

```kotlin
// android/src/main/java/com/rngeofencing/GeofencingModule.kt — the only implementation
class GeofencingModule internal constructor(private val reactContext: ReactApplicationContext) :
  GeofencingSpec(reactContext) {

  override fun getName() = NAME

  @ReactMethod
  override fun ready(config: ReadableMap, promise: Promise) {
    Core.executor.execute {
      try { promise.resolve(Core.ready(reactContext.applicationContext, config.toConfig()).toWritableMap()) }
      catch (e: Throwable) { promise.reject("E_INTERNAL", e) }
    }
  }

  // … the rest of §8.1, each one a `@ReactMethod override fun` delegating to Core

  @ReactMethod override fun addListener(eventName: String) {}
  @ReactMethod override fun removeListeners(count: Double) {}

  override fun invalidate() {
    super.invalidate()
    Core.detachReactContext()          // never shut Core down: it outlives the module (§15.6)
  }

  companion object { const val NAME = "RNGeofencing" }
}
```

`@ReactMethod` on an `override` is harmless under the new arch (the annotation is ignored; codegen drives the
binding) and mandatory under the legacy bridge — that is what makes one implementation serve both. Note
`Core.ready` takes `applicationContext`, not the `ReactApplicationContext`: §15.6 is enforced by the signature.

**Package: `BaseReactPackage`, not `ReactPackage`.** The lazy form is what lets the module be a TurboModule
under the new arch:

```kotlin
class GeofencingPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == GeofencingModule.NAME) GeofencingModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      GeofencingModule.NAME to ReactModuleInfo(
        GeofencingModule.NAME,
        GeofencingModule.NAME,
        false,                                        // canOverrideExistingModule
        false,                                        // needsEagerInit
        false,                                        // isCxxModule
        BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,      // isTurboModule
      )
    )
  }
}
```

`BuildConfig.IS_NEW_ARCHITECTURE_ENABLED` comes from `buildConfigField` in `android/build.gradle`, and
`packageInstance: 'new GeofencingPackage()'` in `react-native.config.js` (§11) is what autolinks it.

> **`needsEagerInit = false` does not weaken killed-app delivery.** Nothing waits for the module: the
> `BroadcastReceiver` and `BootReceiver` construct `Core` directly (§15.6). If you ever find yourself wanting
> eager init to "make geofences work", the core has grown a React dependency — fix that instead.

Source sets (note `rootProject`). Only the two hand-written directories are listed — the generated spec reaches
the compile path through `apply plugin: "com.facebook.react"` plus the `react { }` block of §6.1, not through
`srcDirs`; add `generated/java` and `generated/jni` here **only** if you took the `includesGeneratedCode` path
(§11):

```gradle
def isNewArchitectureEnabled() {
    return rootProject.hasProperty("newArchEnabled") && rootProject.getProperty("newArchEnabled") == "true"
}

if (isNewArchitectureEnabled()) {
    apply plugin: "com.facebook.react"
}

android {
    defaultConfig {
        buildConfigField "boolean", "IS_NEW_ARCHITECTURE_ENABLED", isNewArchitectureEnabled().toString()
    }
    buildFeatures { buildConfig true }

    sourceSets {
        main {
            if (isNewArchitectureEnabled()) {
                java.srcDirs += ["src/newarch"]
            } else {
                java.srcDirs += ["src/oldarch"]
            }
        }
    }
}
```

`rootProject.hasProperty`, not `project.hasProperty`: `newArchEnabled` is set in the **app's**
`android/gradle.properties` (the example sets `newArchEnabled=true`), so reading it off the library subproject
finds nothing and silently builds the legacy path under a new-arch host.

### 15.4 Events: use `NativeEventEmitter` on both architectures

Codegen's `EventEmitter<T>` exists only under the new arch. Rather than branch at every call site, **use
`NativeEventEmitter` uniformly** — it is supported in bridgeless mode too. One code path, at the cost of losing
codegen type-checking on the payload (recover it with a hand-written cast in the JS wrapper, which is where the
`extras` JSON is parsed anyway).

Event names, namespaced so they cannot collide with another library's device events:

```
RNGeofencing:geofence
RNGeofencing:geofencesChange
```

Emitting from Android:

```kotlin
fun emit(reactContext: ReactContext, name: String, payload: WritableMap) {
    if (!reactContext.hasActiveReactInstance()) return   // queue instead (§6.5)
    // RN 0.74+ bridgeless-safe:
    reactContext.emitDeviceEvent(name, payload)
    // RN < 0.74:
    // reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
    //     .emit(name, payload)
}
```

Emitting from iOS — subclass `RCTEventEmitter`, which works under both architectures:

```objc
@interface RNGeofencing : RCTEventEmitter <RCTBridgeModule>
@end

@implementation RNGeofencing
RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup { return NO; }

- (NSArray<NSString *> *)supportedEvents {
    return @[@"RNGeofencing:geofence", @"RNGeofencing:geofencesChange"];
}

- (void)startObserving { RNGeofencingCore.shared.jsObserving = YES; [self flushQueue]; }
- (void)stopObserving  { RNGeofencingCore.shared.jsObserving = NO;  }

RCT_EXPORT_METHOD(ready:(NSDictionary *)config
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) { /* … */ }
@end
```

`startObserving` / `stopObserving` are not optional bookkeeping here — they are the hook that decides whether an
event is **sent or queued**. Calling `sendEventWithName:` with no listeners logs a warning and drops the event,
which on this module means a silently lost crossing. Wire them to the queue from §9.2 and flush on
`startObserving`.

For Option B on iOS, guard the header with **`__has_include` in addition to the macro**, not `#ifdef` alone:

```objc
// ios/RNGeofencing.h
#import <React/RCTEventEmitter.h>

#if defined(RCT_NEW_ARCH_ENABLED) && __has_include("RNGeofencingSpec.h")
#import "RNGeofencingSpec.h"
@interface RNGeofencing : RCTEventEmitter <NativeGeofencingSpec>
#else
#import <React/RCTBridgeModule.h>
@interface RNGeofencing : RCTEventEmitter <RCTBridgeModule>
#endif

@end
```

```objc
// ios/RNGeofencing.mm — bottom of @implementation
#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
    return std::make_shared<facebook::react::NativeGeofencingSpecJSI>(params);
}
#endif
```

The `__has_include` half matters because the generated header is produced by the **app's** codegen (§11), into the
app's build tree: on a legacy build it is never generated at all, and `#ifdef RCT_NEW_ARCH_ENABLED` alone would
fail at `#import "RNGeofencingSpec.h"` with a missing-header error instead of compiling the legacy path.

> **If you took the `includesGeneratedCode` path in §11 instead**, the package contains `ios/generated/` and the
> podspec must exclude it on legacy builds — and the obvious condition is wrong:
>
> ```ruby
> if ENV['RCT_NEW_ARCH_ENABLED'] == '0'    # WRONG: misses the variable being unset
> ```
>
> The variable's meaning is inverted by RN version: before 0.76 the new arch is opt-**in** (`'1'`, so *unset means
> legacy*), from 0.76 it is opt-**out** (`'0'`, so *unset means new arch*). `== '0'` therefore compiles the
> generated `.mm`/`.cpp` into a pre-0.76 legacy build — and `__has_include` cannot save that, since it guards a
> header, not the generated translation units. `!= '1'` fails the mirror-image way on 0.76+. Resolve the default
> for the RN versions you actually claim to support (§15.1 puts the legacy floor at 0.60) and write the condition
> explicitly, or avoid the whole question by not shipping generated code.

Podspec:

```ruby
s.platforms           = { :ios => min_ios_version_supported }
s.public_header_files = "ios/RNGeofencing.h"
s.source_files        = "ios/**/*.{h,m,mm,cpp}"       # no `swift` — see §11
s.pod_target_xcconfig = { 'SWIFT_VERSION' => '5.0', 'DEFINES_MODULE' => 'YES' }
s.frameworks          = "CoreLocation"

# RN 0.71+ handles both architectures
if respond_to?(:install_modules_dependencies, true)
  install_modules_dependencies(s)
else
  s.dependency "React-Core"
  if ENV['RCT_NEW_ARCH_ENABLED'] == '1'
    s.compiler_flags = folly_compiler_flags + " -DRCT_NEW_ARCH_ENABLED=1"
    s.pod_target_xcconfig = {
      "HEADER_SEARCH_PATHS"         => "\"$(PODS_ROOT)/boost\"",
      "OTHER_CPLUSPLUSFLAGS"        => "-DFOLLY_NO_CONFIG -DFOLLY_MOBILE=1 -DFOLLY_USE_LIBCPP=1",
      "CLANG_CXX_LANGUAGE_STANDARD" => "c++17",
    }
    s.dependency "React-Codegen"
    s.dependency "RCT-Folly"
    s.dependency "RCTRequired"
    s.dependency "RCTTypeSafety"
    s.dependency "ReactCommon/turbomodule/core"
  end
end
```

`CoreLocation` is the only framework this module adds. Nothing here needs `UIApplication` beyond
`beginBackgroundTask` (§7.4), which is UIKit and already linked.

### 15.5 What genuinely differs

| Concern | New arch | Legacy | Impact here |
|---|---|---|---|
| Method invocation | direct JSI | async bridge | none — every method is already `Promise`-based |
| Sync methods | possible | impossible | none — the module has no sync API |
| `extras` typing | codegen forces a JSON string | `ReadableMap` would work | **keep the JSON string** so both paths behave identically |
| Type safety | codegen-verified | hand-written TS | write the wrapper's types by hand and treat them as the contract |
| Module lifetime | lazy | lazy | identical — and irrelevant, because `Core` is a singleton independent of it |
| Headless JS (Android) | same | same | unaffected |
| iOS background relaunch | same | same | unaffected |

### 15.6 The one thing that must not change

Under **both** architectures, the native module instance may not exist when a geofence fires:

- Android: the broadcast receiver runs with no React instance at all when the app is killed.
- iOS: the app is relaunched into the background and the module is constructed lazily — often never, because JS
  may not finish booting inside the OS's ~10 s window.

So the rule from §11 is not an architectural preference, it is a correctness requirement on both paths:

> `Core` owns the geofences, the state machine, the store and the queue. The React module — TurboModule or legacy —
> is a **read-and-forward adapter** over it, constructed and destroyed freely, and never the owner of anything.

If a legacy port is written by moving logic into `ReactContextBaseJavaModule` because "that's where `@ReactMethod`
lives", killed-app detection stops working and the failure looks like flaky hardware. Port the adapter, never the
core.

### 15.7 Phase changes

Insert into the §13 table:

| Phase | Change for a legacy target |
|---|---|
| 1 | scaffold `module-mixed` (Option B, §15.1) — *Native module* only if you took Option A |
| 3, 7 | emit through `NativeEventEmitter` from the start — do not build on codegen events and retrofit later |
| 8 | the §18.3 launch-hook mechanism ships *with* the relaunch hook, not after it — and §18.3's mechanism A/C need a phase-1 decision (package language set, §11) |
| all others | unchanged |

---

## 16. Example app

The §13 phase-10 deliverable. It is not a demo — it is the **only practical harness** for the behaviour that
cannot be unit-tested (§14), so build it early, around phase 3, and grow it with the module.

`example/` is a plain RN app (the `create-react-native-library` scaffold generates it), wired as follows:

- **A yarn workspace.** Root `package.json` carries `"workspaces": ["example"]` and a
  `"example": "yarn workspace react-native-geofencing-example"` script, so the app runs as
  `yarn example android` / `yarn example ios`.
- **`example/react-native.config.js`** points the dependency at the package root
  (`dependencies: { [pkg.name]: { root: path.join(__dirname, '..') } }`) with
  `project.ios.automaticPodsInstallation = true`.
- **Both architectures from one app.** Flip `newArchEnabled` in `example/android/gradle.properties` and
  `RCT_NEW_ARCH_ENABLED` for `pod install`. Do not fork a second example app per architecture. (If you adopt
  `turbo` — optional, see the §17 preamble — those two are the cache keys its `build:android` / `build:ios`
  tasks must declare, or a cached build is reused across a switch and the matrix silently tests one arch twice.)
- **`example/index.js` is where `registerHeadlessTask` goes** (§8.2) — module scope, next to
  `AppRegistry.registerComponent`, not inside `App.tsx`.

One screen:

| Panel | Contents | Why |
|---|---|---|
| **State** | live `getState()` — `enabled`, `available`, `authorization`, `accuracyAuthorization`, `geofenceCount`, `activeCount`, refreshed on focus and every 5 s | the first thing to read in any bug report |
| **Permissions** | buttons: request, open settings; shows the current grant verbatim | the only way to exercise §6.7 / §7.1 staging on each API level |
| **Seed** | "Add 10 / 300 / 2000 geofences" around the current location, plus "Add from GPX route" | 300 is the number that forces rotation on both platforms |
| **Registry** | two lists side by side: `getGeofences()` vs `getActiveGeofences()`, with distance from current position, sorted | makes a rotation bug visible at a glance — the active list must be the nearest N |
| **Boundary** | the current rotation centre and boundary radius from `meta`, and distance from it | when this stops updating, rotation has stalled |
| **Event log** | append-only, persisted, showing `action`, `identifier`, `ts`, `approximate`, `synthetic` | synthetic-vs-real is invisible anywhere else |
| **Queue** | pending count + a `flushQueue()` button | proves killed-app events actually survived |
| **Controls** | `start`, `stop`, `removeGeofences`, clear log | |

Requirements the app itself must meet:

- **The event log persists across a process kill** (write it in the headless task too, not just in the React tree).
  Otherwise the one test that matters — swipe away, drive, reopen — has nothing to show.
- **A headless task is registered** in `index.js`, appending to that same log with a `[headless]` marker, so you can
  see which path delivered each event.
- **Ship the GPX routes** used for the rotation test in `example/fixtures/` — a route that crosses several seeded
  regions, and one that traverses a dense cluster (the §4.3 degenerate case).
- **No map dependency.** A distance-sorted list is enough and keeps the example installable without API keys.

---

## 17. Packaging, CI and release

Not required to make the module work; required to ship it to another project without hand-copying files.

**Take this section as a menu, not a checklist.** The module exists because the commercial alternative was too
large for the job (§1), and that argument applies to its own tooling too. What is actually load-bearing:

| Required | Why |
|---|---|
| `react-native-builder-bob` + the `files` list (§17.1) | without them the published package is broken or missing `src`, which codegen needs |
| `example/` as a workspace (§16) | it is the only harness for the behaviour §14 cannot unit-test |
| Both-architecture builds in CI (§17.2) | the §15 failure mode is invisible until someone else's app breaks |
| `expo-prebuild-check` (§17.2, §18.4) | the target workflow reruns `prebuild` every build |

Optional, and reasonable to skip for a single-team package: `turbo` (a two-script package does not need a task
cache), `lefthook`, `release-it` + conventional-changelog (`npm version` and a hand-written changelog are fine
until there are outside contributors). Adopt each one when it solves a problem you have, and do not let the
scaffold's defaults decide.

### 17.1 Build

`react-native-builder-bob` — note `esm: true` on all three targets, which changes the `types` path. There is no
`codegen` target: §11 leaves codegen to the consuming app:

```json
"source": "./src/index.tsx",
"main": "./lib/commonjs/index.js",
"module": "./lib/module/index.js",
"types": "./lib/typescript/module/src/index.d.ts",
"files": [
    "src", "lib", "android", "ios", "cpp", "*.podspec",
    "react-native.config.js", "app.plugin.js", "plugin",
    "!ios/build", "!android/build", "!android/gradle", "!android/gradlew",
    "!android/gradlew.bat", "!android/local.properties",
    "!**/__tests__", "!**/__fixtures__", "!**/__mocks__", "!**/.*"
],
"scripts": {
    "typecheck": "tsc",
    "lint": "eslint \"**/*.{js,ts,tsx}\"",
    "test": "jest",
    "prepare": "bob build",
    "build-plugin": "tsc --build plugin"
},
"react-native-builder-bob": {
    "source": "src",
    "output": "lib",
    "targets": [
        ["commonjs", { "esm": true }],
        ["module",   { "esm": true }],
        ["typescript", { "project": "tsconfig.build.json", "esm": true }]
    ]
}
```

Three things a vanilla snippet gets wrong:

- **`types` is `lib/typescript/module/src/index.d.ts`**, not `lib/typescript/src/index.d.ts`. `esm: true` on the
  typescript target splits output into `module/` and `commonjs/`; the shorter path resolves to nothing and every
  consumer silently loses types.
- **`files` must include `src`** — codegen reads the spec from source, not from `lib` — **and `plugin`,
  `app.plugin.js`, `react-native.config.js`** (§11, §18). Omit `plugin` and the Expo integration is missing from
  the published tarball while working perfectly in local development — the worst possible place to find out.
- **`build-plugin` is a separate, manual step.** `prepare` runs `bob build` only, so `plugin/build/` is committed
  rather than generated at publish (§18). Run `yarn build-plugin` and commit the output whenever
  `plugin/src/index.ts` changes.

Release tooling (`release-it` + `@release-it/conventional-changelog`) and git hooks (`lefthook`) come with the
scaffold; §17.3 covers the two rules specific to this module.

### 17.2 CI matrix

The one thing CI must cover that a developer will not: **both architectures**. A module that compiles under the
New Architecture and fails under the legacy bridge (or vice versa) is the standard failure mode of §15.

| Job | What it runs |
|---|---|
| `lint` + `typecheck` + `test` | the §14 unit tests — the rotation and transition-gate logic |
| `build-android-newarch` | assemble `example` with `newArchEnabled=true` |
| `build-android-oldarch` | assemble `example` with `newArchEnabled=false` |
| `build-ios-newarch` | `pod install` with `RCT_NEW_ARCH_ENABLED=1`, then `xcodebuild build` |
| `build-ios-oldarch` | `pod install` without it, then `xcodebuild build` |
| `build-plugin` | `yarn build-plugin`, then fail if `plugin/build/` differs from the committed copy (§17.1) |
| **`expo-prebuild-check`** | `expo prebuild --clean` in a scratch app, then assert the §7.5 hook and the `Info.plist` keys landed — **twice**, to catch a non-idempotent mod (§18.4) |

Drop the two `oldarch` jobs only if the module is new-arch-only (Option A/B in §15.1); under Option B all four
build jobs stay.

**Drive the jobs from the example app's own scripts, not from bespoke gradle/xcodebuild invocations.** Put
`build:android` / `build:ios` in `example/package.json`; each CI job then runs the same command a developer runs,
with `ORG_GRADLE_PROJECT_newArchEnabled` and `RCT_NEW_ARCH_ENABLED` flipped. A CI job that reimplements the build
diverges from what developers run locally, and the divergence is only ever discovered on a red build.

If you adopted `turbo` (optional, see the preamble above), the matrix is `turbo run build:android build:ios` with
those same two env vars flipped — and both **must** be declared as cache keys for those tasks. A task cache that
does not know about them replays the previous architecture's output and the matrix quietly tests one arch twice.

`expo-prebuild-check` is not optional padding. The target project runs `expo prebuild` on **every build**, so a
plugin mod that stops applying — most likely after an Expo SDK bump moves the AppDelegate anchors (§18.3) — breaks
every subsequent build's killed-app delivery, silently, with a green test suite. This is the one failure in the
whole module that CI can catch and a human reliably will not.

No CI job can verify geofencing behaviour — it needs real movement. Keep the device checklist from §14 as a
release gate a human signs off, and do not let green CI stand in for it.

### 17.3 Release

`release-it` with conventional commits (the scaffold's default) or changesets. Two project-specific rules:

- **Treat the blob format as public API.** A change to the §9.2 `"v"` schema is a breaking change for anyone who
  upgrades in place: users have geofences registered under the old format. Bump major, and ship the migration.
- **Note the minimum RN version in the README** and keep it honest — `emitDeviceEvent` needs 0.74+, the codegen
  event types need 0.76+, the legacy path works from 0.60.

### 17.4 What the README must carry

Everything a consuming project cannot discover from the type definitions:

1. The iOS launch hook (§7.5) — **the module is silently broken without it** and nothing warns you. State which
   mechanism from §18.3 the library uses and therefore what, if anything, the host app must do.
2. The `Info.plist` keys and the Play Store background-location declaration requirement.
3. The §10 limits table, verbatim.
4. `event.location` is best-effort and may be absent or approximate (§7.3) — the one real behavioural difference
   from `react-native-background-geolocation`.
5. The §12 migration map.
6. **An Expo section** (§18): the `app.json` `plugins` entry, the plugin props, and the fact that Expo Go cannot
   run this module — a bare/prebuild workflow is required.
7. **A bare-RN section**, only if the library supports plain RN CLI hosts — they get neither the plugin nor Expo
   autolinking, so they need either mechanism C (§18.3, nothing to do) or the manual `Info.plist` +
   `didFinishLaunchingWithOptions` steps. If the library is Expo-only, say that instead of implying both work.
8. The minimum `compileSdk` (34) — a host app still pinning 31 will not build (§6.1).

---

## 18. Expo support (config plugin)

The module ships an Expo config plugin: `app.plugin.js` → `plugin/build/index`, documented in the README under
*Installation → Expo*.

The plugin exists for one reason. §7.5 is the module's only *silent* failure mode — without the
`didFinishLaunchingWithOptions` hook, a terminated-app relaunch delivers nothing and no error is ever logged — and
a `prebuild` that regenerates `ios/` wipes any manual edit. Automating that hook is the plugin's main job;
everything else it does is convenience.

**Expo Go cannot run this module** (custom native code). The `LINKING_ERROR` string in §15.2 already says so;
the README must too. The supported workflows are bare RN, `expo prebuild`, and EAS Build.

**Target workflow: Expo bare, with `expo prebuild` on every build.** This is the assumption the rest of §18 is
written against, and it is the strictest of the three. It means `ios/` is regenerated continuously rather than
committed, so:

- Any instruction of the form "add this line to `AppDelegate`" is worthless — it survives until the next build.
  Every native integration point must come from the plugin or from the library's own code.
- The plugin runs on every build, not once at setup. A mod that is non-idempotent, or that stops matching its
  anchor, degrades **every** subsequent build rather than one prebuild the developer would notice.
- `app.json` is the single source of truth for `Info.plist` and `AndroidManifest.xml`. Anything the plugin does
  not write, and the app author does not put in `app.json`, does not exist in the built app.

### 18.1 Files

```
app.plugin.js            # module.exports = require('./plugin/build/index')
plugin/
├─ src/index.ts          # the plugin
├─ tsconfig.json         # { "extends": "expo-module-scripts/tsconfig.plugin",
│                        #   "compilerOptions": { "outDir": "build", "rootDir": "src" },
│                        #   "include": ["./src"], "exclude": ["**/__mocks__/*", "**/__tests__/*"] }
└─ build/                # tsc output — COMMITTED to git, shipped in `files`
```

`devDependencies`: `@expo/config-plugins` and `expo-module-scripts`. Neither is a runtime dependency and neither
belongs in `peerDependencies` — a bare-RN consumer must not be asked to install Expo packages.

`plugin/build/` being committed is deliberate and easy to get wrong: `prepare` runs `bob build`, which does not
touch `plugin/`, so nothing regenerates it at publish time. Edit `plugin/src/index.ts` → run `yarn build-plugin`
→ **commit `plugin/build/`** in the same commit. A forgotten rebuild ships a plugin that silently applies the
previous version's mods.

### 18.2 What the plugin must do

Compose with `withPlugins`, and take props so a host app can opt out of anything policy-sensitive:

```ts
type Props = {
  locationWhenInUsePermission?: string | false;
  locationAlwaysAndWhenInUsePermission?: string | false;
  isAndroidBackgroundLocationEnabled?: boolean;   // default true
  isAndroidForegroundServiceEnabled?: boolean;    // default false — only with enableHeadless (§6.5)
};
```

(The naming follows `expo-location`'s plugin props so the two read the same in one `app.json`.)

| Mod | Does |
|---|---|
| `withInfoPlist` | `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`, and append `location` to `UIBackgroundModes` (§7.1). Skip a key whose prop is `false`. |
| `withAppDelegate` | Insert the §7.5 relaunch hook — **only as the fallback path.** Prefer `ExpoAppDelegateSubscriber`, which needs no mod at all; see §18.3. |
| `withAndroidManifest` | Nothing for the receivers/service/permissions — the library manifest (§6.2) is merged by AGP automatically. Use it only to *remove* `ACCESS_BACKGROUND_LOCATION` / `FOREGROUND_SERVICE*` when the corresponding prop is `false`, via `tools:node="remove"`. |
| `withGradleProperties` / docs | This module needs `compileSdk >= 34` (§6.1). Expo sets that per SDK version, so **document `expo-build-properties` rather than patching gradle** — a plugin that rewrites `compileSdkVersion` fights the Expo SDK's own pin and breaks on upgrade. |

Removing a permission is worth the mod: Google Play's background-location declaration and manual review apply to
any app whose *merged* manifest requests `ACCESS_BACKGROUND_LOCATION`, even one that never calls the API. An app
that only needs foreground geofencing must be able to opt out without forking the library.

### 18.3 The launch hook — pick a mechanism, then harden it

This is the module's one *silent* failure mode (§7.5), and with `prebuild` on every build it is also the most
fragile thing the plugin does. There are three ways to get the core constructed during launch. Rank them in this
order, and record which one shipped — their failure modes have nothing in common.

| | Mechanism | Touches AppDelegate? | Breaks when |
|---|---|---|---|
| **A** | `ExpoAppDelegateSubscriber` via `expo-module.config.json` | No | Expo removes the API (public, so: with notice) |
| **B** | `withAppDelegate` string mod | Yes, rewritten every prebuild | Expo changes AppDelegate shape — **silently** |
| **C** | `+load` + `UIApplicationDidFinishLaunchingNotification` in the library | No | Static-lib stripping, or the timing proves too late |

#### A. `ExpoAppDelegateSubscriber` — preferred

Expo Modules has a first-class hook for exactly this. Declare a subscriber and Expo's autolinking calls it during
launch, with no mod to the app's `AppDelegate` at all:

```json
// expo-module.config.json at the package root
{
  "ios": {
    "appDelegateSubscribers": ["RNGeofencingAppDelegateSubscriber"]
  }
}
```

```swift
// ios/RNGeofencingAppDelegateSubscriber.swift
public class RNGeofencingAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    if launchOptions?[.location] != nil {
      RNGeofencingCore.sharedInstance()   // constructs CLLocationManager + delegate NOW (§7.5)
    }
    return true
  }
}
```

Why this wins for a prebuild-every-build project: there is no anchor to match, so there is no way for an Expo SDK
bump to break it without a deprecation warning. B's failure is a regex that quietly stops matching.

**Two things to verify before committing to A** — do this in phase 1, not phase 8, because the answer decides the
package's language set:

1. **Can the subscriber be ObjC?** `ExpoAppDelegateSubscriber` is a Swift protocol. If it is exposed to ObjC, the
   subscriber can be a `.mm` file like the rest of §11. If not, this is one `.swift` file, and the podspec's
   `s.source_files` must gain `swift` (§11 records that trade-off). One Swift file for one protocol conformance is
   an acceptable price; a Swift *core* is not.
2. **Does it require `expo-modules-core` as a dependency?** If the library must depend on it, that dependency
   lands on RN-CLI-only consumers too. If instead `expo-module.config.json` is simply ignored by non-Expo
   autolinking (the likely case), the file is free and A costs a plain-RN host nothing.

If either answer rules A out, fall to B — and if the module must also support plain RN CLI hosts, ship C alongside
whichever of A/B you picked, since neither A nor B exists outside Expo.

#### B. `withAppDelegate` — fallback

Resolve the path with `IOSConfig.Paths.getAppDelegateFilePath`, branch on `.swift` vs `.mm`, and guard every
mutation with an `includes()` check.

```ts
const withGeofenceAppDelegate: ConfigPlugin = (config) =>
  withAppDelegate(config, (config) => {
    const path = config.modRequest.projectRoot + '/ios/' +
      IOSConfig.Paths.getAppDelegateFilePath(config.modRequest.projectRoot);
    let contents = config.modResults.contents;

    if (path.endsWith('.swift')) {
      if (!contents.includes('RNGeofencingCore.sharedInstance()')) {
        // insert immediately inside didFinishLaunchingWithOptions, BEFORE super/return
      }
    } else {
      if (!contents.includes('#import "RNGeofencingCore.h"')) { /* add import */ }
      if (!contents.includes('[RNGeofencingCore sharedInstance]')) { /* add the launchOptions check */ }
    }

    config.modResults.contents = contents;
    return config;
  });
```

Three rules. Under a prebuild-every-build workflow these are **requirements, not recommendations** — each one is
the difference between a loud failure and a silent one:

1. **Throw when the anchor is missing. Never return the file unchanged.** If the insertion point cannot be found,
   `throw` from the mod so `prebuild` fails the build. Returning `config` untouched produces an app that compiles,
   installs, runs, and never delivers a killed-app event — the exact failure the plugin exists to prevent, now
   reproduced automatically on every build. A build that fails loudly on an Expo SDK bump is the cheapest possible
   outcome here.
2. **Position matters.** The core must be constructed *synchronously inside*
   `didFinishLaunchingWithOptions`, before the RN bridge is set up (§7.5) — not appended at the end of the method,
   not in `applicationDidBecomeActive`. A mod that lands the call after `return YES` satisfies rule 1's
   `includes()` check and still never fires, so assert on the *anchor*, not on the presence of the string.
3. **Idempotent, verified twice.** Every mutation `includes()`-guarded, and the §18.4 double-`prebuild` check in
   CI (§17.2). Without a committed `ios/`, a doubled import is not a one-off annoyance a developer clears by hand —
   it is a build failure that recurs until the plugin is fixed.

Keep both the Swift and ObjC branches alive, and put "re-verify the AppDelegate anchors" on the
Expo-SDK-upgrade checklist.

#### C. `+load` — no host cooperation at all

The library constructs the core itself, using the same ObjC runtime hook that `RCT_EXPORT_MODULE()` already relies
on. Useful as the plain-RN-CLI path, or as belt-and-braces behind A/B:

```objc
// ios/RNGeofencingCore.mm — NOT the class carrying RCT_EXPORT_MODULE()
+ (void)load {
  [[NSNotificationCenter defaultCenter] addObserver:self
      selector:@selector(handleLaunchNotification:)
          name:UIApplicationDidFinishLaunchingNotification object:nil];
}

+ (void)handleLaunchNotification:(NSNotification *)note {
  if (note.userInfo[UIApplicationLaunchOptionsLocationKey]) {
    [RNGeofencingCore sharedInstance];
  }
}
```

`UIApplicationDidFinishLaunchingNotification` is an `NSNotificationCenter` event — in-process, no UI, no
permission, unrelated to user-facing notifications — and its `userInfo` is the `launchOptions` dictionary. UIKit
posts it immediately after `didFinishLaunchingWithOptions` returns.

Two traps:

1. **`+load` cannot go in the same `@implementation` as `RCT_EXPORT_MODULE()`** — that macro already defines
   `+load` (it calls `RCTRegisterModule`), so a second one is a duplicate-method compile error. Put it on the core
   class.
2. **Static-library stripping.** Pods build to a `.a`; if nothing references the object file, the linker can drop
   it and `+load` never runs. Make sure the `RCT_EXPORT_MODULE()` class references `RNGeofencingCore` so it cannot
   be stripped, and verify on a **release** build (`nm` the binary for the symbol), not just debug.

C is marginally later than "inside `didFinishLaunchingWithOptions`", which is what Apple's documentation asks for.
In practice it lands in the same launch and inside the ~10 s relaunch budget, but it is the one mechanism here
whose correctness rests on timing rather than on a contract — so if C is the only mechanism, the phase-8 device
test (§14, killed-app iOS row) is what qualifies it, and a pass is not optional.

### 18.4 Verifying it

**Do not add an Expo example app to the workspace** — it duplicates `example/` (§16) for one integration
concern. Verify against a throwaway app instead, wired into CI as `expo-prebuild-check` (§17.2) rather than left
as a manual checklist item: the target workflow reruns `prebuild` on every build, so this is a per-commit
regression risk, not a setup-time one.

```bash
npx create-expo-app@latest /tmp/geo-check && cd /tmp/geo-check
yarn add file:/path/to/react-native-geofencing
# app.json → "plugins": ["react-native-geofencing", { "locationAlwaysAndWhenInUsePermission": "…" }]
npx expo prebuild --clean

# The §7.5 hook is present — assert on whichever mechanism shipped (§18.3):
#   A) grep -rn "RNGeofencingAppDelegateSubscriber" ios/Podfile.lock ios/*.xcodeproj/project.pbxproj
#   B) grep -n  "RNGeofencingCore" ios/*/AppDelegate.swift ios/*/AppDelegate.mm
#   C) nothing in ios/ — assert the symbol is linked instead (see §18.3 C, trap 2)
plutil -p ios/*/Info.plist | grep -i "location\|UIBackgroundModes"
grep -n "ACCESS_BACKGROUND_LOCATION" android/app/src/main/AndroidManifest.xml

npx expo prebuild            # second run, NO --clean: mods must be idempotent, not doubled
xcodebuild -workspace ios/*.xcworkspace -scheme geo-check build   # a doubled mod fails here
```

Three things this catches that nothing else does:

- **A missing `includes()` guard**, via the second `prebuild` without `--clean`. A doubled import or twice-inserted
  constructor call is a compile error — hence the `xcodebuild` line, which is what turns "doubled" from invisible
  into a failed job.
- **A silently un-applied mod**, if §18.3 rule 1 was not followed. Grep for the hook and fail the job when it is
  absent; do not trust the plugin to have thrown.
- **An `ACCESS_BACKGROUND_LOCATION` opt-out that stopped working** (§18.2) — run the check a second time with
  `isAndroidBackgroundLocationEnabled: false` and assert the permission is *gone*. A silently reappearing
  background-location permission is a Play Store review problem, discovered at submission time.

### 18.5 README wording

The `app.json` entry to document:

```json
"plugins": [
  ["react-native-geofencing", {
    "locationAlwaysAndWhenInUsePermission": "Allow $(PRODUCT_NAME) to use your location to notify you when you arrive.",
    "isAndroidBackgroundLocationEnabled": true
  }]
]
```

State plainly, in this order:

1. Expo Go is not supported; `prebuild` / EAS Build is.
2. The plugin writes the `Info.plist` keys for you. (A host that prefers to own them can put them in `app.json`
   under `ios.infoPlist` instead and pass `false` for the matching props — say so, because with `prebuild` on
   every build `app.json` is the natural source of truth.)
3. The §7.5 launch hook is handled **automatically** — name which mechanism from §18.3 shipped, because it
   determines what a host app must do on an Expo SDK upgrade. Mechanism A or C: nothing. Mechanism B: upgrade the
   library too, since the AppDelegate anchors are version-sensitive.
4. Google Play requires a background-location declaration for any app that keeps
   `isAndroidBackgroundLocationEnabled` on.
5. `compileSdk >= 34` (§6.1) — with Expo, via `expo-build-properties` rather than editing gradle.

Do **not** carry over an "Open `AppDelegate.m` and add this" block from another RN library's README. That
instruction is correct for libraries whose users commit `ios/`; here it tells a prebuild-every-build user to make
an edit that is deleted before it ever runs.
