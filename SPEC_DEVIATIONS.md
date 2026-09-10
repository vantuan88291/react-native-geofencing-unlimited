# Deviations from `GEOFENCE_NATIVE_MODULE_SPEC.md`

The spec is the source of truth. Everywhere the implementation departs from it, the reason is
recorded here. Most of these are the spec's own warning coming true: *"Where this doc names a tool
version, an SDK level or an RN internal, treat it as 'true of some released version, verify against
yours'."*

Nothing in §0 (the eleven invariants) is deviated from.

## 1. `create-react-native-library` no longer has `module-mixed` — §11.0

The spec's bootstrap command is:

```sh
npx create-react-native-library@latest --type module-mixed --languages kotlin-objc
```

CLI **0.63.1** has no `module-mixed`. The `--type` choices are now `turbo-module`, `fabric-view`,
`nitro-module`, `nitro-view`, `library`.

**What was done:** scaffolded `--type turbo-module --languages kotlin-objc`, and hand-wrote the
newarch/oldarch split of §15.3 (`android/src/newarch/GeofencingSpec.kt` and
`android/src/oldarch/GeofencingSpec.kt`) that `module-mixed` used to emit. Two ~30-line abstract
classes; the split itself is unchanged from §15.3.

The scaffold also had to run outside a network sandbox — its child process fetches the React Native
template directly.

## 2. iOS launch hook: mechanism **C**, not **A** — §18.3

§18.3 ranks `ExpoAppDelegateSubscriber` (A) first. **A was rejected**, on the spec's own two
verification questions:

1. `ExpoAppDelegateSubscriber` is a **Swift** protocol, so A means adding Swift to a `kotlin-objc`
   package and widening the podspec glob.
2. It requires **`expo-modules-core`**, which would land that dependency on every plain React
   Native CLI consumer — effectively making the library Expo-only.

The requirement here is that the module work identically under the RN CLI *and* Expo, so mechanism
**C** (`+load` + `UIApplicationDidFinishLaunchingNotification`, in `ios/RNGeofencingCore.mm`)
ships instead. It needs no host cooperation on either workflow, has no anchor for an Expo SDK bump
to break, and keeps the package Swift-free.

Consequences, per §18.3:

- The Expo plugin has **no `withAppDelegate` mod** at all. It only writes `Info.plist` keys and
  handles the Android permission opt-outs.
- C's correctness rests on timing rather than a contract, so **the phase-8 device test is not
  optional**: the iOS killed-app row of §14 is what qualifies this choice. Not yet run — see
  [Not yet verified](#not-yet-verified).
- Trap 2 of §18.3 (static-library stripping) still needs checking on a **release** build:
  `nm` the binary for the `RNGeofencingCore` symbol. The `RCT_EXPORT_MODULE()` class references it
  (`RNGeofencing.mm` calls `RNGeofencingCore.sharedInstance` in `-init`), which is what should keep
  it from being dropped.

## 3. Object-typed TurboModule **parameters** are `Object`, not structured — §8.1

§8.1 declares `ready(config: ConfigSpec)`, `addGeofence(geofence: GeofenceSpec)` and so on.

Codegen turns a *structured* object parameter into a generated C++ struct on iOS
(`JS::NativeGeofencing::ConfigSpec &`). Implementing the protocol exactly would mean writing
against a generated type whose shape moves between RN versions, and it would stop **one**
implementation from serving both architectures (§15.3).

**What was done:** only the object *parameters* are declared `Object`, which codegen maps to a
stable `NSDictionary *`. Return types keep their structured aliases — a `Promise` resolves through
an untyped block on both platforms, so those are documentation either way. Nothing is lost on
Android, where structured objects map to `ReadableMap` regardless.

Verified against generated output: the emitted protocol is exactly
`- (void)ready:(NSDictionary *)config resolve:…reject:…`, and the Android spec is exactly
`public abstract void ready(ReadableMap config, Promise promise)`.

This is what §15.5 prescribes anyway — *"write the wrapper's types by hand and treat them as the
contract"*. The real contract for callers is `src/type.ts`.

## 4. bob's `typescript` target rejects `esm: true` — §17.1

§17.1 gives:

```json
["typescript", { "project": "tsconfig.build.json", "esm": true }]
```

`react-native-builder-bob` **0.43** fails the build with
`targets[2][1].esm must be removed`.

**What was done:** dropped the flag. The split output §17.1 warns about still happens — because
both `commonjs` and `module` targets are present — so the `types` path **is**
`lib/typescript/module/src/index.d.ts`, exactly as the spec insists. Verified by building and
asserting every declared entry point exists.

Two `files` entries were also added beyond §17.1's list, after auditing the packed tarball:
`!android/src/test` (the `!**/__tests__` pattern does not match Android's `src/test` source set,
so the JVM unit tests were shipping) and `!**/*.tsbuildinfo`.

## 5. Android SDK/Kotlin versions come from `ext`, not `gradle.properties` — §6.1

§6.1 specifies `android/gradle.properties` with `Geofencing_*` keys read via `getExtOrDefault`.
The current scaffold has no `gradle.properties` and uses an `ext.Geofencing = [...]` map with the
same `getExtOrDefault` helper.

**What was done:** kept the scaffold's shape. The semantics are identical — a host app's
`rootProject.ext` still overrides every value — and the §6.1 floors are satisfied and documented
inline as floors: `compileSdk 36` (≥ 34), Kotlin `2.0.21` (≥ 1.9), `minSdk 24` (≥ 21).

## 6. Android event emission uses `emitDeviceEvent`, so the floor is RN 0.74 — §15.4, §17.3

§15.4 offers `reactContext.emitDeviceEvent(...)` for RN 0.74+ and the
`getJSModule(RCTDeviceEventEmitter)` form below that. Only one can be *compiled*, because the
library is compiled by the host app against the host's React Native.

**What was done:** `emitDeviceEvent`, and the README states **RN 0.74+** as the minimum rather than
§15.1's legacy floor of 0.60. §17.3 anticipates exactly this — *"keep it honest —
`emitDeviceEvent` needs 0.74+"*. The one-line change for an older host is commented at the call
site in `ReactEventDelivery.kt`.

The legacy-bridge path itself (§15 Option B) is fully present and is a CI matrix axis; 0.74 is a
floor on the *emitter API*, not on the architecture.

## 7. Two seams added for testability — §9.6, §14

§14 requires `computeActiveSet`, the transition gate and the `applyActiveSet` diff ordering to be
properly unit-tested. Two interfaces make that possible without a device:

- `RegionRegistry` (in `core/PlatformRegistry.kt`), implemented by `PlatformRegistry`. This is what
  lets a fake record the call order and prove **add-before-remove** (invariant 3) and the
  mark-not-armed-on-failure rule (invariant 9).
- `Logger` guards every `android.util.Log` call in a `try`/`catch`, because `Log` throws "not
  mocked" under a plain JVM test and both the engine and the gate log.

`GeofenceStore` was already an interface in §9.6.

**43 JVM tests** (rotation engine, transition gate, geometry) and **12 JS tests** (the `extras`
JSON seam and the event fan-out) pass.

## 8. `expo-module-scripts` is not a dependency — §18.1

§18.1 lists `@expo/config-plugins` and `expo-module-scripts` as devDependencies, the latter only to
provide `tsconfig.plugin`.

**What was done:** `plugin/tsconfig.json` is written out directly (about ten lines) and
`expo-module-scripts` is not installed. One fewer Expo toolchain in a package that must stay usable
by plain React Native consumers.

## 9. The podspec is named after the npm package — §11.1

§11.1's layout names the file `RNGeofencing.podspec`, which is also what the scaffold
emitted (as `Geofencing.podspec`).

**What was done:** renamed to `react-native-geofencing-unlimited.podspec`, with
`s.name = package["name"]` so the pod name is *derived* rather than restated and the
two can never drift apart again. This is the dominant convention among React Native
libraries, and it is what `pod install` and the Xcode "Development Pods" group display.

It changes nothing at runtime. The pod name is a CocoaPods packaging identifier only:
the name React binds to is still `RNGeofencing` (`RCT_EXPORT_MODULE` in
`ios/RNGeofencing.mm`, matched against `TurboModuleRegistry.get('RNGeofencing')` and
`getName()` on Android, per §15.2). The ObjC classes keep their `RNGeofencing` prefix
because the Objective-C runtime has a single flat class namespace.

One consequence worth knowing: under `use_frameworks!` the module name becomes
`react_native_geofencing_unlimited` (CocoaPods maps dashes to underscores), so a host
app importing the public headers by angle bracket would write
`<react_native_geofencing_unlimited/RNGeofencingCore.h>`.

## 10. The example app gained one dependency — §16

§16 requires the event log to **survive a process kill**, and to be written from the headless task
as well as the React tree. That needs real persistence, so the example app depends on
`@react-native-async-storage/async-storage`.

This is a **harness** dependency, in `example/package.json` only. The library itself still has no
runtime dependencies. §16's actual constraint — no map dependency, so the app installs without API
keys — is respected: distances are measured from the rotation centre that `getState()` already
reports, so the example needs no location library either.

---

## Not yet verified

Everything below needs hardware or a full app build, and none of it has been run.

| | Status |
|---|---|
| Kotlin compiles; 43 JVM unit tests | **passing** |
| JS typecheck, lint, 12 jest tests | **passing** |
| Codegen (iOS + Android) produces the expected specs | **verified** |
| bob build; every declared entry point exists | **verified** |
| Packed tarball contents | **verified** |
| Expo plugin mods, run twice for idempotence | **verified** (§18.4's doubling failure) |
| iOS sources, `-Wall` syntax check against the iOS 26.5 SDK | **passing** for all 7 React-independent files |
| `ios/RNGeofencing.mm` (the `RCTEventEmitter` surface) | **compiles clean under BOTH architectures** — exercises both sides of the `__has_include` guard in `RNGeofencing.h` (§15.4) |
| `pod install` resolves and autolinks the pod | **verified** |
| Full `example` Android build, both architectures | **not run** |
| Full `example` iOS build, both architectures | **not run** |
| Every device test in §14 | **not run** |
| The §10 limits table on real hardware | **not verified** |

The device checklist in §14 is a release gate a human signs off. Green CI does not stand in for it,
and for mechanism C (item 2 above) the iOS killed-app test is what qualifies the design.
