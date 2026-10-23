import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { test } from 'node:test';
import { createAppService, type NativeServices } from './app-service.ts';
import { createPreferenceStore, DEFAULT_PREFERENCES } from './preferences.ts';

test('native backup exports only pet settings, imports with confirmation and preserves unrelated user configuration', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pet-service-'));
  const data = join(home, 'dsh-pet');
  mkdirSync(data);
  const file = join(home, 'backup.json');
  let confirm = 0;
  let changes = 0;
  const opened: string[] = [];
  let startupWrites = 0;
  let startupEnabled = false;
  const native: NativeServices = {
    app: {
      isPackaged: true,
      getLoginItemSettings: () => ({ openAtLogin: startupEnabled }),
      setLoginItemSettings: (value) => {
        startupWrites++;
        startupEnabled = value.openAtLogin;
      },
      quit: () => {},
      relaunch: () => {},
    },
    shell: {
      openPath: async (path) => {
        opened.push(path);
        return '';
      },
    },
    dialog: {
      showSaveDialog: async () => ({ canceled: false, filePath: file }),
      showOpenDialog: async () => ({ canceled: false, filePaths: [file] }),
      showMessageBox: async () => ({ response: confirm }),
    },
  };
  const service = createAppService({
    home,
    root: resolve('.'),
    version: 'test',
    store: createPreferenceStore(join(data, 'desktop-settings.json')),
    changed: () => {
      changes++;
    },
    flushLogs: async () => {},
    native,
  });
  try {
    writeFileSync(join(data, 'standalone-ai.json'), JSON.stringify({ apiKey: 'PRIVATE_KEY_SENTINEL' }));
    await service.action({ action: 'export-config' });
    const exported = readFileSync(file, 'utf8');
    assert.ok(!exported.includes('PRIVATE_KEY_SENTINEL'));
    const backup = JSON.parse(exported);
    assert.equal(backup.format, 'dsh-pet-desktop/pets-v1');
    assert.ok(backup.config.pets.length);
    assert.deepEqual(Object.keys(backup.config).sort(), ['confineToScreen', 'pets']);
    backup.config.pets[0].name = 'Imported pet';
    writeFileSync(file, JSON.stringify(backup));
    writeFileSync(join(data, 'main-config.jsonc'), JSON.stringify({ pets: backup.config.pets, chatMemoryRounds: 7 }));
    const before = readFileSync(join(data, 'main-config.jsonc'), 'utf8');
    assert.deepEqual(await service.action({ action: 'import-config' }), { ok: true, canceled: true });
    assert.equal(readFileSync(join(data, 'main-config.jsonc'), 'utf8'), before);
    confirm = 1;
    await service.action({ action: 'import-config' });
    assert.equal(changes, 1);
    assert.equal(JSON.parse(readFileSync(join(data, 'main-config.jsonc'), 'utf8')).chatMemoryRounds, 7);
    assert.equal(JSON.parse(readFileSync(join(data, 'main-config.before-import.jsonc'), 'utf8')).chatMemoryRounds, 7);
    backup.config.apiKey = 'untrusted';
    writeFileSync(file, JSON.stringify(backup));
    await assert.rejects(service.action({ action: 'import-config' }));
    await service.action({ action: 'reset-positions' });
    assert.equal(changes, 2);
    const restored = JSON.parse(readFileSync(join(data, 'main-config.jsonc'), 'utf8'));
    assert.equal(restored.pets[0].display, 'desktop');
    assert.equal(restored.pets[0].position.corner, 'bottom-right');
    service.savePreferences({ ...DEFAULT_PREFERENCES, theme: 'dark' });
    assert.equal(startupWrites, 0);
    if (['win32', 'darwin'].includes(process.platform)) {
      service.savePreferences({ ...DEFAULT_PREFERENCES, launchAtLogin: true });
      service.savePreferences({ ...DEFAULT_PREFERENCES, launchAtLogin: true, theme: 'dark' });
      assert.equal(startupWrites, 1);
      assert.equal(startupEnabled, true);
      service.savePreferences(DEFAULT_PREFERENCES);
      assert.equal(startupWrites, 2);
    }
    await service.action({ action: 'open-data', path: 'C:/untrusted' });
    assert.deepEqual(opened, [data]);
    await assert.rejects(service.action({ action: 'arbitrary-executable' }));
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});
