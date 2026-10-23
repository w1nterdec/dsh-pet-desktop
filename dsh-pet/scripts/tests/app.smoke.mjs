/** Native Electron smoke test. Uses an isolated home; never touches the user's pets or API keys. */
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { _electron } from 'playwright';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const packaged = process.env.DSH_PET_APP_EXECUTABLE;
const executablePath =
  packaged || process.env.DSH_PET_ELECTRON_PATH || resolve(root, '../.tools/runtime/electron/electron.exe');
const home = await mkdtemp(join(tmpdir(), 'dsh-pet-app-test-'));
const output = resolve(root, '../.tools/qa');
await mkdir(output, { recursive: true });
const env = {
  ...process.env,
  DSH_HOME: home,
  APPDATA: join(home, 'appdata'),
  DSH_PET_APP_USER_DATA: join(home, 'chromium'),
};
delete env.ELECTRON_RUN_AS_NODE;
await mkdir(env.APPDATA, { recursive: true });
await mkdir(env.DSH_PET_APP_USER_DATA, { recursive: true });
const application = await _electron.launch({
  executablePath,
  args: packaged ? [] : [join(root, 'lib/app.js')],
  cwd: packaged ? home : root,
  env,
  timeout: 60000,
});
try {
  const control = await application
    .waitForEvent('window', { predicate: (page) => page.url().startsWith('http:'), timeout: 30000 })
    .catch(async () => application.windows().find((page) => page.url().startsWith('http:')));
  assert.ok(control, 'native control window appears');
  await control.getByRole('heading', { name: '让桌面，多一点陪伴。' }).waitFor();
  await control.waitForFunction(() => !document.getElementById('save').disabled);
  assert.equal(await control.locator('.pet').count(), 1);
  await control.waitForFunction(() => document.getElementById('hero-pet').readyState >= 2);
  await control.screenshot({ path: join(output, 'control-center.png'), fullPage: true });

  // Both native settings requests and delegated plugin writes must be authenticated.
  await control.getByRole('link', { name: 'AI 与对话' }).click();
  await control.waitForFunction(() => !document.getElementById('save')?.disabled);
  assert.equal(await control.getByLabel('启用 AI 对话').isChecked(), false);
  await control.screenshot({ path: join(output, 'ai-settings.png'), fullPage: true });
  await control.getByRole('link', { name: '返回我的桌宠' }).click();
  await control.waitForFunction(() => !document.getElementById('save').disabled);
  await control.getByLabel('大小 · px', { exact: true }).fill('320');
  await control.getByRole('button', { name: '保存更改' }).click();
  await control.waitForFunction(() => document.getElementById('message').textContent.includes('已保存'));
  await control.getByRole('button', { name: '添加桌宠' }).click();
  assert.equal(await control.locator('.pet').count(), 2);
  await control.getByRole('button', { name: '保存更改' }).click();
  await control.waitForFunction(() => document.getElementById('message').textContent.includes('已保存'));
  await control.waitForTimeout(1500);
  assert.equal(
    await application.evaluate(
      ({ BrowserWindow }) =>
        BrowserWindow.getAllWindows().filter((win) => win.webContents.getURL().startsWith('file:')).length,
    ),
    2,
  );
  const pet = application.windows().find((page) => page.url().startsWith('file:'));
  assert.ok(pet, 'pet renderer exists');
  await pet.waitForFunction(() => [...document.querySelectorAll('video')].some((video) => video.readyState >= 2), {
    timeout: 30000,
  });
  await pet.screenshot({ path: join(output, 'pet-window.png') });

  await control.locator('.remove').last().click();
  await control.locator('#confirm-yes').click();
  await control.getByRole('button', { name: '保存更改' }).click();
  await control.waitForFunction(() => document.getElementById('message').textContent.includes('已保存'));
  await control.waitForTimeout(1000);
  assert.equal(
    await application.evaluate(
      ({ BrowserWindow }) =>
        BrowserWindow.getAllWindows().filter((win) => win.webContents.getURL().startsWith('file:')).length,
    ),
    1,
  );

  await control.getByRole('button', { name: '应用设置', exact: true }).click();
  await control.getByLabel('界面主题', { exact: true }).selectOption('dark');
  await control.getByRole('button', { name: '保存应用设置', exact: true }).click();
  await control.waitForFunction(() => document.getElementById('preferences-message').textContent.includes('已保存'));
  assert.equal(await control.locator('html').getAttribute('data-theme'), 'dark');
  await control.screenshot({ path: join(output, 'app-settings-dark.png'), fullPage: true });
  await control.reload();
  await control.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
  await control.getByRole('button', { name: '应用设置', exact: true }).click();
  await control.getByLabel('界面主题', { exact: true }).selectOption('light');
  await control.getByRole('button', { name: '保存应用设置', exact: true }).click();
  await control.waitForFunction(() => document.getElementById('preferences-message').textContent.includes('已保存'));
  await control.getByRole('button', { name: '关于应用', exact: true }).click();
  await control.getByRole('heading', { name: '关于桌宠之家', exact: true }).waitFor();
  assert.ok(await control.locator('#system-info').textContent());
  await control.getByRole('button', { name: '我的桌宠', exact: false }).click();
  await control.getByRole('button', { name: '召回桌宠', exact: false }).click();
  await control.waitForFunction(() => document.getElementById('toast').textContent.includes('已回到'));

  const nativeCount = await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length);
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()
      .find((win) => win.webContents.getURL().startsWith('http:'))
      .close(),
  );
  assert.equal(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length), nativeCount);
  assert.equal(
    await application.evaluate(({ BrowserWindow }) =>
      BrowserWindow.getAllWindows()
        .find((win) => win.webContents.getURL().startsWith('http:'))
        .isVisible(),
    ),
    false,
  );
  await application.evaluate(({ app }) => app.emit('dsh-pet:open-control'));
  assert.equal(
    await application.evaluate(({ BrowserWindow }) =>
      BrowserWindow.getAllWindows()
        .find((win) => win.webContents.getURL().startsWith('http:'))
        .isVisible(),
    ),
    true,
  );
  console.log('PASS: native window, AI settings auth, save, add/remove, video decode, hide and tray recovery');
} finally {
  await application.close();
  await rm(home, { recursive: true, force: true });
}
