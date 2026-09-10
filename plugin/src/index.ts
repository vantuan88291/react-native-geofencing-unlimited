import {
  createRunOncePlugin,
  withAndroidManifest,
  withInfoPlist,
  type ConfigPlugin,
} from '@expo/config-plugins';

const pkg = require('../../package.json') as { name: string; version: string };

/**
 * Props follow `expo-location`'s plugin naming, so the two read the same inside one
 * `app.json` (§18.2).
 */
export type GeofencingPluginProps = {
  /** `false` skips the key entirely, for a host that owns its own Info.plist. */
  locationWhenInUsePermission?: string | false;
  locationAlwaysAndWhenInUsePermission?: string | false;
  /**
   * Default `true`. Turning it off strips `ACCESS_BACKGROUND_LOCATION` from the
   * merged manifest, which is what an app that only needs foreground geofencing must
   * be able to do without forking the library (§18.2).
   */
  isAndroidBackgroundLocationEnabled?: boolean;
  /**
   * Default `false` — only useful alongside `enableHeadless: true` (§6.5). When off,
   * the foreground-service and notification permissions are stripped.
   */
  isAndroidForegroundServiceEnabled?: boolean;
};

const DEFAULT_WHEN_IN_USE =
  'Allow $(PRODUCT_NAME) to use your location to notify you when you arrive at or leave a place.';
const DEFAULT_ALWAYS_AND_WHEN_IN_USE =
  'Allow $(PRODUCT_NAME) to use your location, even in the background, to notify you when you arrive at or leave a place.';

/**
 * `Info.plist` keys and the `location` background mode (§7.1, §18.2).
 *
 * The background mode is the one that matters most: without it,
 * `allowsBackgroundLocationUpdates` cannot be set and background delivery is
 * limited. `NSLocationAlwaysAndWhenInUseUsageDescription` is what lets the module ask
 * for `authorizedAlways`, which region monitoring in the background **requires**.
 */
const withGeofencingInfoPlist: ConfigPlugin<GeofencingPluginProps> = (
  config,
  props
) =>
  withInfoPlist(config, (modConfig) => {
    const plist = modConfig.modResults;

    if (props.locationWhenInUsePermission !== false) {
      plist.NSLocationWhenInUseUsageDescription =
        props.locationWhenInUsePermission ??
        plist.NSLocationWhenInUseUsageDescription ??
        DEFAULT_WHEN_IN_USE;
    }

    if (props.locationAlwaysAndWhenInUsePermission !== false) {
      plist.NSLocationAlwaysAndWhenInUseUsageDescription =
        props.locationAlwaysAndWhenInUsePermission ??
        plist.NSLocationAlwaysAndWhenInUseUsageDescription ??
        DEFAULT_ALWAYS_AND_WHEN_IN_USE;
    }

    // Appended, never replaced: another library may already have asked for a mode
    // here, and `prebuild` runs on every build, so this has to be idempotent (§18.3
    // rule 3).
    const modes = Array.isArray(plist.UIBackgroundModes)
      ? (plist.UIBackgroundModes as string[])
      : [];
    if (!modes.includes('location')) {
      plist.UIBackgroundModes = [...modes, 'location'];
    }

    return modConfig;
  });

const BACKGROUND_LOCATION = 'android.permission.ACCESS_BACKGROUND_LOCATION';
const FOREGROUND_SERVICE_PERMISSIONS = [
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_LOCATION',
  'android.permission.POST_NOTIFICATIONS',
  // React Native's HeadlessJsTaskService acquires a wake lock unconditionally, so
  // this one is part of the headless story and leaves with it.
  'android.permission.WAKE_LOCK',
];

/**
 * Android manifest opt-outs (§18.2).
 *
 * Nothing here *adds* the receivers, the service or the permissions: the library's own
 * manifest declares them and AGP merges it into the app's, so a host app needs no
 * mod for that at all. This exists only to **remove** what a host has opted out of.
 *
 * Worth the mod because Google Play's background-location declaration and manual
 * review apply to any app whose *merged* manifest requests
 * `ACCESS_BACKGROUND_LOCATION` — even one that never calls the API.
 */
const withGeofencingAndroidManifest: ConfigPlugin<GeofencingPluginProps> = (
  config,
  props
) =>
  withAndroidManifest(config, (modConfig) => {
    const manifest = modConfig.modResults.manifest;

    const toRemove: string[] = [];
    if (props.isAndroidBackgroundLocationEnabled === false) {
      toRemove.push(BACKGROUND_LOCATION);
    }
    if (props.isAndroidForegroundServiceEnabled !== true) {
      toRemove.push(...FOREGROUND_SERVICE_PERMISSIONS);
    }

    if (toRemove.length === 0) {
      return modConfig;
    }

    // `tools:node="remove"` needs the tools namespace declared on <manifest>.
    manifest.$ = manifest.$ ?? ({} as typeof manifest.$);
    if (!manifest.$['xmlns:tools']) {
      manifest.$['xmlns:tools'] = 'http://schemas.android.com/tools';
    }

    const permissions = manifest['uses-permission'] ?? [];
    for (const name of toRemove) {
      const existing = permissions.find(
        (permission) => permission.$?.['android:name'] === name
      );
      if (existing) {
        // Idempotent: a second `prebuild` without `--clean` must not add a duplicate
        // entry (§18.3 rule 3, §18.4).
        existing.$['tools:node'] = 'remove';
      } else {
        permissions.push({
          $: { 'android:name': name, 'tools:node': 'remove' },
        } as (typeof permissions)[number]);
      }
    }
    manifest['uses-permission'] = permissions;

    return modConfig;
  });

/**
 * Note what is **not** here: a `withAppDelegate` mod.
 *
 * The §7.5 launch hook — the module's only silent failure mode — is handled inside
 * the library itself, by `+load` in `RNGeofencingCore.mm` (mechanism **C** of §18.3).
 * So there is no anchor for an Expo SDK bump to stop matching, nothing for a
 * `prebuild` to wipe, and a bare React Native CLI host gets the identical behaviour
 * with no plugin at all.
 *
 * `compileSdkVersion` is likewise not patched: Expo pins it per SDK version, and a
 * plugin that rewrites it fights the Expo SDK's own pin and breaks on upgrade. The
 * README documents `expo-build-properties` for the `compileSdk >= 34` requirement
 * instead (§18.2).
 */
const withGeofencing: ConfigPlugin<GeofencingPluginProps | void> = (
  config,
  props
) => {
  const resolved: GeofencingPluginProps = props ?? {};
  config = withGeofencingInfoPlist(config, resolved);
  config = withGeofencingAndroidManifest(config, resolved);
  return config;
};

// Guards against the plugin being applied twice when a host lists it more than once.
export default createRunOncePlugin(withGeofencing, pkg.name, pkg.version);
