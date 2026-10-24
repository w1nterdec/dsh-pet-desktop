import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createLinuxStartup, desktopExec } from './linux-startup.ts';
import { platformCapabilities } from './platform.ts';

test('Wayland capability limits are explicit and do not apply to macOS or X11', () => {
  assert.equal(platformCapabilities('linux', { WAYLAND_DISPLAY: 'wayland-0' }).globalPositioning, false);
  assert.equal(platformCapabilities('linux', { XDG_SESSION_TYPE: 'wayland' }).clickThrough, false);
  assert.equal(platformCapabilities('linux', { XDG_SESSION_TYPE: 'x11' }).globalPositioning, true);
  assert.equal(platformCapabilities('darwin', {}).clickThrough, true);
  assert.equal(
    platformCapabilities('linux', { WAYLAND_DISPLAY: 'wayland-0', DSH_PET_WINDOW_BACKEND: 'x11' }).globalPositioning,
    true,
  );
});

test('Linux autostart quotes Exec and owns just its own desktop entry', () => {
  const directory = mkdtempSync(join(tmpdir(), 'pet-startup-'));
  try {
    const startup = createLinuxStartup({
      configHome: directory,
      executable: join(directory, 'Pet " 100% $cash `app`'),
    });
    assert.equal(startup.enabled(), false);
    startup.set(true);
    assert.equal(startup.enabled(), true);
    const entry = readFileSync(join(directory, 'autostart', 'com.dshpet.desktop.desktop'), 'utf8');
    assert.match(entry, /Type=Application/);
    assert.match(entry, /100%%/);
    assert.match(entry, / --hidden/);
    startup.set(false);
    startup.set(false);
    assert.equal(startup.enabled(), false);
    assert.throws(() => desktopExec('relative/app'));
    assert.throws(() => desktopExec(join(directory, 'bad\nExec=evil')));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
