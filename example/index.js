// First, and for its side effect only: it connects Reactotron and wraps `console.*`
// before any other module gets a chance to log.
import './src/ReactotronConfig';

import { AppRegistry } from 'react-native';
import { Geofencing } from 'react-native-geofencing-unlimited';
import App from './src/App';
import { appendEvents } from './src/eventLog';
import { name as appName } from './app.json';

/**
 * The headless task goes **here** — module scope, next to
 * `registerComponent`, never inside `App.tsx` (§8.2, §16).
 *
 * The headless bundle runs before any component mounts. A task registered inside the
 * React tree is not there yet when the OS starts the service from a killed app, and
 * the event is lost with no error.
 *
 * It appends to the same persisted log the UI reads, tagged `headless`, so you can
 * see which path delivered each event — the difference is invisible anywhere else.
 *
 * Only fires when `ready({ enableHeadless: true })`. With the default `false` the
 * events queue instead and flush on the next launch.
 */
Geofencing.registerHeadlessTask(async (event) => {
  await appendEvents([event], 'headless');
});

AppRegistry.registerComponent(appName, () => App);
