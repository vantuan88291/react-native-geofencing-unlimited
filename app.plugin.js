// Expo resolves a config plugin named in `app.json` through this file (§18.1).
//
// `plugin/build/` is COMMITTED to git and shipped in `files`. `prepare` runs
// `bob build`, which does not touch `plugin/`, so nothing regenerates it at publish
// time: edit `plugin/src/index.ts`, run `yarn build-plugin`, and commit the output in
// the same commit. A forgotten rebuild ships a plugin that silently applies the
// previous version's mods.
// `.default` is unwrapped here rather than relying on Expo's interop: the compiled
// plugin is CommonJS with the plugin on `exports.default`, and unwrapping makes the
// exported shape a plain function that is easy to smoke-test.
const plugin = require('./plugin/build/index');
module.exports = plugin.default ?? plugin;
