import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createPreferenceStore, DEFAULT_PREFERENCES, validatePreferences } from './preferences.ts';

test('preferences survive restart and window persistence does not replace application settings', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pet-preferences-'));
  try {
    const file = join(dir, 'preferences.json');
    const store = createPreferenceStore(file);
    assert.deepEqual(store.get(), DEFAULT_PREFERENCES);
    store.save({ ...DEFAULT_PREFERENCES, theme: 'dark', closeToTray: false });
    store.saveWindow({ width: 1000, height: 800, x: -1000, y: 20 });
    const reloaded = createPreferenceStore(file);
    assert.equal(reloaded.get().theme, 'dark');
    assert.equal(reloaded.get().closeToTray, false);
    assert.deepEqual(reloaded.window(), { width: 1000, height: 800, x: -1000, y: 20 });
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).schemaVersion, 1);
    assert.throws(() => store.save({ ...DEFAULT_PREFERENCES, theme: 'invalid' }));
    assert.equal(store.get().theme, 'dark');
    writeFileSync(file, 'broken');
    assert.deepEqual(createPreferenceStore(file).get(), DEFAULT_PREFERENCES);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
test('application settings reject unknown fields, string booleans and missing switches', () => {
  for (const input of [
    null,
    {},
    { ...DEFAULT_PREFERENCES, launchAtLogin: 'true' },
    { ...DEFAULT_PREFERENCES, apiKey: 'secret' },
  ])
    assert.throws(() => validatePreferences(input));
});
