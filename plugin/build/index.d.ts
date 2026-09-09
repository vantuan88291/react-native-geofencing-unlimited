import { type ConfigPlugin } from '@expo/config-plugins';
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
declare const _default: ConfigPlugin<void | GeofencingPluginProps>;
export default _default;
