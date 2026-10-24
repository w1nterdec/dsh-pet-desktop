import { createRequire } from 'node:module';
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { readAllConfig, readUserConfig, saveUserConfig, userConfigUnparsable } from '../host/config';
import { configPathsFor } from '../standalone/options';
import { atomicJson, type PreferenceStore } from './preferences';
import { installPetImport } from './pet-import';
import { platformCapabilities } from './platform';
import { createLinuxStartup } from './linux-startup';
import { PetImportError } from './import-error';

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
  linuxStartup?: ReturnType<typeof createLinuxStartup>;
}): AppService {
  const { app, shell, dialog } = options.native ?? (createRequire(import.meta.url)('electron') as NativeServices);
  const paths = configPathsFor(options.root, options.home);
  const dataRoot = join(options.home, 'dsh-pet');
  const supported = app.isPackaged && ['win32', 'darwin', 'linux'].includes(process.platform);
  const linuxStartup =
    options.linuxStartup ??
    createLinuxStartup({
      configHome: process.env.XDG_CONFIG_HOME,
      executable: process.env.APPIMAGE || process.execPath,
    });
  const startupEnabled = () =>
    process.platform === 'linux' ? linuxStartup.enabled() : app.getLoginItemSettings().openAtLogin;
  let importing = false;
  function importedPets() {
    return Object.entries(readAllConfig(paths))
      .filter(([key]) => /^import-[a-f0-9-]{36}$/.test(key))
      .flatMap(([key, entry]) =>
        (entry.pets as Array<Record<string, unknown>>).map((pet) => ({
          id: key,
          name: pet.name,
          size: pet.size,
          display: pet.display,
        })),
      );
  }
  function prefs() {
    return {
      ...options.store.get(),
      launchAtLogin: supported ? startupEnabled() : false,
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
      capabilities: platformCapabilities(),
      importedPets: importedPets(),
    }),
    preferences: prefs,
    savePreferences(input) {
      const previous = options.store.get();
      const value = options.store.save(input);
      try {
        if (supported) {
          if (value.launchAtLogin !== startupEnabled()) {
            if (process.platform === 'linux') linuxStartup.set(value.launchAtLogin);
            else app.setLoginItemSettings({ openAtLogin: value.launchAtLogin, args: ['--hidden'] });
          }
        } else if (value.launchAtLogin) throw new Error('开机启动仅在已打包的桌面应用中可用');
      } catch (error) {
        options.store.save(previous);
        throw error;
      }
      return prefs();
    },
    async action(input) {
      const action = input && typeof input === 'object' ? (input as Record<string, unknown>).action : undefined;
      if (action === 'import-pet' || action === 'import-pet-directory') {
        if (importing) throw new PetImportError('已有素材正在导入，请等待完成');
        importing = true;
        try {
          const chosen = await dialog.showOpenDialog({
            title: '导入桌宠素材（GIF / WebM / 视频 / PNG 序列）',
            properties: [action === 'import-pet-directory' ? 'openDirectory' : 'openFile'],
            ...(action === 'import-pet'
              ? {
                  filters: [
                    {
                      name: '桌宠素材包与媒体',
                      extensions: ['zip', 'webm', 'mov', 'mp4', 'gif', 'png', 'apng', 'webp', 'jpg', 'jpeg'],
                    },
                  ],
                }
              : {}),
          });
          if (chosen.canceled || !chosen.filePaths[0]) return { ok: true, canceled: true };
          if (importedPets().length >= 12) throw new PetImportError('最多导入 12 只桌宠，请先移除不再使用的伙伴');
          const imported = await installPetImport(chosen.filePaths[0], paths.petDir);
          options.changed();
          return { ok: true, imported };
        } finally {
          importing = false;
        }
      }
      if (action === 'toggle-imported-pet' || action === 'remove-imported-pet' || action === 'update-imported-pet') {
        const id = (input as Record<string, unknown>).id;
        if (
          typeof id !== 'string' ||
          !/^import-[a-f0-9-]{36}$/.test(id) ||
          !importedPets().some((pet) => pet.id === id)
        )
          throw new Error('导入的桌宠不存在');
        const file = join(paths.petDir, `${id}-config.json`);
        const config = JSON.parse(readFileSync(file, 'utf8'));
        if (action === 'update-imported-pet') {
          const { name, size } = input as Record<string, unknown>;
          if (
            typeof name !== 'string' ||
            !name.trim() ||
            name.length > 80 ||
            typeof size !== 'number' ||
            !Number.isInteger(size) ||
            size < 64 ||
            size > 800
          )
            throw new PetImportError('桌宠名称须为 1～80 字符，大小须为 64～800 的整数');
          config.pets[0].name = name.trim();
          config.pets[0].size = size;
          atomicJson(file, config);
        } else if (action === 'toggle-imported-pet') {
          config.pets[0].display = config.pets[0].display === 'none' ? 'desktop' : 'none';
          atomicJson(file, config);
        } else {
          const answer = await dialog.showMessageBox({
            type: 'question',
            title: '移除导入的桌宠',
            message: '将这只桌宠移出桌面？',
            detail: '素材会保留在数据目录中，配置备份为 .removed.json。',
            buttons: ['取消', '移除'],
            defaultId: 0,
            cancelId: 0,
          });
          if (answer.response !== 1) return { ok: true, canceled: true };
          const { renameSync } = await import('node:fs');
          renameSync(file, join(paths.petDir, `${id}.removed.json`));
        }
        options.changed();
        return { ok: true };
      }
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
