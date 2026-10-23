import { createRequire } from 'node:module';
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { readAllConfig, readUserConfig, saveUserConfig, userConfigUnparsable } from '../host/config';
import { configPathsFor } from '../standalone/options';
import { atomicJson, type PreferenceStore } from './preferences';

export interface AppService {
  info(): unknown;
  preferences(): unknown;
  savePreferences(input: unknown): unknown;
  action(input: unknown): Promise<unknown>;
}

interface NativeApp {
  isPackaged: boolean;
  getLoginItemSettings(): { openAtLogin: boolean };
  setLoginItemSettings(options: { openAtLogin: boolean; args: string[] }): void;
  quit(): void;
  relaunch(): void;
}
interface NativeDialog {
  showSaveDialog(options: unknown): Promise<{ canceled: boolean; filePath?: string }>;
  showOpenDialog(options: unknown): Promise<{ canceled: boolean; filePaths: string[] }>;
  showMessageBox(options: unknown): Promise<{ response: number }>;
}
export interface NativeServices {
  app: NativeApp;
  shell: { openPath(path: string): Promise<string> };
  dialog: NativeDialog;
}

export function createAppService(options: {
  home: string;
  root: string;
  version: string;
  store: PreferenceStore;
  changed(): void;
  flushLogs(): Promise<void>;
  native?: NativeServices;
}): AppService {
  const { app, shell, dialog } = options.native ?? (createRequire(import.meta.url)('electron') as NativeServices);
  const paths = configPathsFor(options.root, options.home);
  const dataRoot = join(options.home, 'dsh-pet');
  const supported = app.isPackaged && ['win32', 'darwin'].includes(process.platform);
  function prefs() {
    return {
      ...options.store.get(),
      launchAtLogin: supported ? app.getLoginItemSettings().openAtLogin : false,
      startupSupported: supported,
    };
  }
  const service: AppService = {
    info: () => ({
      version: options.version,
      platform: process.platform,
      arch: process.arch,
      electron: process.versions.electron,
      dataDirectory: dataRoot,
      packaged: app.isPackaged,
      configFile: paths.userFile,
      logFile: join(dataRoot, 'logs.txt'),
    }),
    preferences: prefs,
    savePreferences(input) {
      const previous = options.store.get();
      const value = options.store.save(input);
      try {
        if (supported) {
          if (value.launchAtLogin !== app.getLoginItemSettings().openAtLogin)
            app.setLoginItemSettings({ openAtLogin: value.launchAtLogin, args: ['--hidden'] });
        } else if (value.launchAtLogin) throw new Error('开机启动仅在已打包的 Windows / macOS 应用中可用');
      } catch (error) {
        options.store.save(previous);
        throw error;
      }
      return prefs();
    },
    async action(input) {
      const action = input && typeof input === 'object' ? (input as Record<string, unknown>).action : undefined;
      if (action === 'open-data' || action === 'open-logs') {
        mkdirSync(dataRoot, { recursive: true });
        await options.flushLogs();
        const error = await shell.openPath(action === 'open-data' ? dataRoot : join(dataRoot, 'logs.txt'));
        if (error) throw new Error('系统无法打开该位置');
        return { ok: true };
      }
      if (action === 'export-config') {
        const chosen = await dialog.showSaveDialog({
          title: '导出桌宠配置',
          defaultPath: 'DSH-Pet-配置.json',
          filters: [{ name: 'JSON 配置', extensions: ['json'] }],
        });
        if (chosen.canceled || !chosen.filePath) return { ok: true, canceled: true };
        const main = readAllConfig(paths).main;
        const clean = saveUserConfig({ pets: main.pets, confineToScreen: main.confineToScreen });
        if (!clean) throw new Error('当前宠物配置无法导出');
        atomicJson(chosen.filePath, {
          format: 'dsh-pet-desktop/pets-v1',
          exportedAt: new Date().toISOString(),
          config: clean,
        });
        return { ok: true };
      }
      if (action === 'import-config') {
        const chosen = await dialog.showOpenDialog({
          title: '导入桌宠配置',
          properties: ['openFile'],
          filters: [{ name: 'JSON 配置', extensions: ['json'] }],
        });
        if (chosen.canceled || !chosen.filePaths[0]) return { ok: true, canceled: true };
        const file = chosen.filePaths[0];
        if (statSync(file).size > 1024 * 1024) throw new Error('配置文件不能超过 1 MB');
        const data = JSON.parse(readFileSync(file, 'utf8'));
        if (data.format !== 'dsh-pet-desktop/pets-v1') throw new Error('请选择本应用导出的桌宠配置');
        if (userConfigUnparsable(paths)) throw new Error('现有配置无法解析，请先修复以免覆盖数据');
        const payload = data.config;
        if (
          !Array.isArray(payload?.pets) ||
          payload.pets.length > 12 ||
          Object.keys(payload).some((key) => !['pets', 'confineToScreen'].includes(key))
        )
          throw new Error('配置内容无效');
        const clean = saveUserConfig(payload, readUserConfig(paths));
        if (!clean) throw new Error('配置内容无效');
        const answer = await dialog.showMessageBox({
          type: 'question',
          title: '导入桌宠配置',
          message: '替换当前的内置桌宠设置？',
          detail: '现有配置会先备份为 main-config.before-import.jsonc。AI 设置与密钥不受影响。',
          buttons: ['取消', '导入'],
          defaultId: 0,
          cancelId: 0,
        });
        if (answer.response !== 1) return { ok: true, canceled: true };
        if (existsSync(paths.userFile))
          atomicJson(join(dataRoot, 'main-config.before-import.jsonc'), readUserConfig(paths));
        atomicJson(paths.userFile, clean);
        options.changed();
        return { ok: true };
      }
      if (action === 'reset-positions') {
        if (userConfigUnparsable(paths)) throw new Error('当前配置无法解析，请先修复');
        const main = readAllConfig(paths).main;
        const pets = (main.pets as Array<Record<string, unknown>>).map((pet, index) => ({
          ...pet,
          display: 'desktop',
          position: { corner: 'bottom-right', marginX: 24 + index * 36, marginY: 30 },
        }));
        const clean = saveUserConfig({ pets }, readUserConfig(paths));
        if (!clean) throw new Error('无法召回桌宠');
        atomicJson(paths.userFile, clean);
        options.changed();
        return { ok: true };
      }
      if (action === 'restart' || action === 'quit') {
        setTimeout(() => {
          if (action === 'restart') app.relaunch();
          app.quit();
        }, 150);
        return { ok: true };
      }
      throw new Error('不支持的应用操作');
    },
  };
  return service;
}
