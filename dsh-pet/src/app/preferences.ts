import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export interface AppPreferences {
  theme: 'system' | 'light' | 'dark';
  closeToTray: boolean;
  startHidden: boolean;
  launchAtLogin: boolean;
}
export interface WindowState {
  width: number;
  height: number;
  x?: number;
  y?: number;
}
export const DEFAULT_PREFERENCES: AppPreferences = {
  theme: 'system',
  closeToTray: true,
  startHidden: false,
  launchAtLogin: false,
};

export function validatePreferences(input: unknown): AppPreferences {
  if (!input || typeof input !== 'object') throw new Error('应用设置格式不正确');
  const value = input as Record<string, unknown>;
  if (
    Object.keys(value).some((key) => !['theme', 'closeToTray', 'startHidden', 'launchAtLogin'].includes(key)) ||
    !['system', 'light', 'dark'].includes(String(value.theme)) ||
    ['closeToTray', 'startHidden', 'launchAtLogin'].some((key) => typeof value[key] !== 'boolean')
  ) {
    throw new Error('请检查主题与应用开关');
  }
  return {
    theme: value.theme as AppPreferences['theme'],
    closeToTray: value.closeToTray as boolean,
    startHidden: value.startHidden as boolean,
    launchAtLogin: value.launchAtLogin as boolean,
  };
}

export function atomicJson(file: string, value: unknown): void {
  mkdirSync(dirname(file), { recursive: true });
  const temporary = file + '.tmp';
  writeFileSync(temporary, JSON.stringify(value, null, 2), { encoding: 'utf8', mode: 0o600 });
  renameSync(temporary, file);
}

export function createPreferenceStore(file: string) {
  let preferences = { ...DEFAULT_PREFERENCES };
  let window: WindowState = { width: 1160, height: 840 };
  if (existsSync(file)) {
    try {
      const data = JSON.parse(readFileSync(file, 'utf8'));
      preferences = validatePreferences(data.preferences);
      const bounds = data.window;
      if (
        bounds &&
        Number.isInteger(bounds.width) &&
        Number.isInteger(bounds.height) &&
        bounds.width >= 760 &&
        bounds.width <= 4000 &&
        bounds.height >= 600 &&
        bounds.height <= 3000
      ) {
        window = { width: bounds.width, height: bounds.height };
        if (Number.isInteger(bounds.x) && Number.isInteger(bounds.y))
          Object.assign(window, { x: bounds.x, y: bounds.y });
      }
    } catch {
      console.warn('[dsh-pet-app] 应用偏好无法读取，使用默认设置');
    }
  }
  const persist = () => atomicJson(file, { schemaVersion: 1, preferences, window });
  return {
    get: () => ({ ...preferences }),
    save(input: unknown) {
      const next = validatePreferences(input);
      atomicJson(file, { schemaVersion: 1, preferences: next, window });
      preferences = next;
      return { ...preferences };
    },
    window: () => ({ ...window }),
    saveWindow(bounds: WindowState) {
      window = { ...bounds };
      persist();
    },
  };
}
export type PreferenceStore = ReturnType<typeof createPreferenceStore>;
