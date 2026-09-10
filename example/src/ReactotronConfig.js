import Reactotron from 'reactotron-react-native';

/**
 * Reactotron for the example app.
 *
 * Why this rather than the Metro terminal: Reactotron opens **its own socket on port
 * 9090**, independent of Metro's `/hot` websocket. React Native 0.87 no longer forwards
 * `console.*` to the Metro terminal at all — the code is still in
 * `Libraries/Core/setUpDeveloperTools.js` but gated behind `console._isPolyfilled`,
 * which nothing sets any more — so this is the reliable route.
 *
 * Imported for its side effect, first, in `index.js`: it has to install the console
 * wrapper before any other module gets a chance to log.
 *
 * Dev only. `__DEV__` is inlined to `false` in a release bundle, so the whole block
 * (and the import above, via dead-code elimination) is dropped.
 *
 * ## Android needs the port forwarded
 *
 * ```sh
 * adb reverse tcp:9090 tcp:9090
 * ```
 *
 * Without it the app cannot reach the desktop app and nothing appears. The emulator
 * usually survives without it; a physical device never does.
 */
if (__DEV__) {
  const reactotron = Reactotron.configure({ name: 'GeofencingExample' })
    .useReactNative({
      // The geofencing module talks to JS through the native event emitter, not the
      // network, so the networking panel would only ever show your own API calls.
      networking: { ignoreUrls: /symbolicate|logs/ },
      errors: { veto: () => false },
    })
    .connect();

  reactotron.clear();

  // `console.tron.log(...)` for anything you want to send explicitly.
  console.tron = reactotron;

  // Mirror plain `console.*` into Reactotron as well, so existing logging shows up
  // without touching any call sites.
  //
  // Fast Refresh can re-evaluate this module; wrapping twice would double every line.
  if (!console.__reactotronInstalled) {
    console.__reactotronInstalled = true;

    const LEVELS = ['log', 'info', 'debug', 'warn', 'error'];
    LEVELS.forEach((level) => {
      const original = console[level];
      if (typeof original !== 'function') {
        return;
      }
      console[level] = function (...args) {
        // Reactotron buffers until the desktop app connects, so this needs no guard.
        if (level === 'warn' || level === 'error') {
          reactotron.display({
            name: level.toUpperCase(),
            preview: String(args[0]),
            value: args.length === 1 ? args[0] : args,
            important: level === 'error',
          });
        } else {
          reactotron.log(...args);
        }
        original.apply(console, args);
      };
    });
  }

  console.log('[reactotron] connected — logs from this app appear here');
}
