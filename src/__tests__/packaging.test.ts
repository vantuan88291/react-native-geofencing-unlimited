import { describe, expect, it } from '@jest/globals';
import pkg from '../../package.json';

/**
 * Packaging invariants that nothing else can catch.
 *
 * The `exports` map is a *deny*-list by omission: once it exists, a subpath missing
 * from it cannot be required at all. Expo resolves this library's config plugin as
 * the subpath `react-native-geofencing-unlimited/app.plugin.js`, and when that
 * resolution fails it silently falls back to the package `main` — the library entry,
 * which imports `react-native` and dies on its Flow syntax. The user sees
 * `PluginError: does not contain a valid config plugin / Unexpected token 'typeof'`,
 * which names neither the real file nor the real cause.
 *
 * Typecheck, lint and the unit tests all pass with that bug in place. Only a real
 * Expo `prebuild` shows it. Hence these.
 */
describe('package exports', () => {
  const exportsMap = pkg.exports as Record<string, unknown>;

  /** Root-level `.js` files a host resolves by subpath rather than by file path. */
  const RESOLVED_BY_SUBPATH = ['app.plugin.js', 'react-native.config.js'];

  it.each(RESOLVED_BY_SUBPATH)(
    'exposes ./%s through the exports map',
    (file) => {
      expect(exportsMap[`./${file}`]).toBe(`./${file}`);
    }
  );

  it('ships every subpath it exports', () => {
    const shipped = new Set(pkg.files);
    // npm puts package.json in every tarball whatever `files` says.
    const ALWAYS_SHIPPED = new Set(['package.json']);

    for (const key of Object.keys(exportsMap)) {
      if (key === '.') continue;
      const file = key.replace(/^\.\//, '');
      if (ALWAYS_SHIPPED.has(file)) continue;
      // Either listed verbatim, or covered by a directory entry in `files`.
      const covered =
        shipped.has(file) ||
        [...shipped].some((entry) => file.startsWith(`${entry}/`));
      expect(covered).toBe(true);
    }
  });

  it('keeps the plugin entry pointing at committed build output', () => {
    // `prepare` runs bob, which does not touch `plugin/`. The compiled plugin is
    // committed, so a forgotten `yarn build-plugin` ships stale mods.
    expect(shipsPath('plugin')).toBe(true);
    expect(shipsPath('!plugin/src')).toBe(true);
  });

  function shipsPath(entry: string): boolean {
    return (pkg.files as string[]).includes(entry);
  }
});
