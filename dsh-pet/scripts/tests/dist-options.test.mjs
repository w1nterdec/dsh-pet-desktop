import { test } from 'node:test';
import assert from 'node:assert/strict';
import { distributionOptions, electronDirectory } from '../dist-options.mjs';
import { resolve } from 'node:path';

test('distribution targets and architectures match their native platform', () => {
  assert.equal(distributionOptions('win32', 'nsis', 'x64').flag, '--win');
  assert.equal(distributionOptions('darwin', 'dmg', 'arm64').flag, '--mac');
  assert.equal(distributionOptions('linux', 'AppImage', 'x64').flag, '--linux');
  assert.throws(() => distributionOptions('linux', 'nsis'));
  assert.throws(() => distributionOptions('darwin', 'dmg', 'ia32'));
  assert.equal(
    electronDirectory(resolve('runtime/Electron.app/Contents/MacOS/Electron'), 'darwin'),
    resolve('runtime'),
  );
});
