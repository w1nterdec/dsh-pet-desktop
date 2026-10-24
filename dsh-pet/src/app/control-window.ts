import { createRequire } from 'node:module';
import type { PreferenceStore, WindowState } from './preferences';

interface ControlWindow {
  loadURL(url: string): Promise<void>;
  show(): void;
  hide(): void;
  focus(): void;
  restore(): void;
  isMinimized(): boolean;
  isDestroyed(): boolean;
  getNormalBounds(): WindowState;
  on(event: 'close', listener: (event: { preventDefault(): void }) => void): void;
  once(event: 'ready-to-show', listener: () => void): void;
  webContents: {
    on(event: 'will-navigate', listener: (event: { preventDefault(): void }, url: string) => void): void;
    setWindowOpenHandler(handler: () => { action: 'deny' }): void;
  };
}

/** One recoverable settings window; closing it keeps the tray and pet windows alive. */
export function installControlWindow(origin: string, icon: string, token: string, store: PreferenceStore): void {
  const { app, BrowserWindow, Menu, screen } = createRequire(import.meta.url)('electron') as {
    app: { on(event: string, listener: () => void): void; quit(): void };
    BrowserWindow: new (options: Record<string, unknown>) => ControlWindow;
    Menu: { setApplicationMenu(menu: unknown): void; buildFromTemplate(template: unknown[]): unknown };
    screen: { getAllDisplays(): Array<{ workArea: { x: number; y: number; width: number; height: number } }> };
  };
  let window: ControlWindow | undefined;
  let quitting = false;
  let trayAvailable = true;
  const show = (hidden = false): void => {
    if (window && !window.isDestroyed()) {
      if (window.isMinimized()) window.restore();
      window.show();
      window.focus();
      return;
    }
    const bounds = store.window();
    const visible = screen
      .getAllDisplays()
      .some(
        ({ workArea: area }) =>
          bounds.x !== undefined &&
          bounds.y !== undefined &&
          bounds.x + 100 > area.x &&
          bounds.y + 100 > area.y &&
          bounds.x < area.x + area.width - 100 &&
          bounds.y < area.y + area.height - 100,
      );
    window = new BrowserWindow({
      title: '桌宠之家',
      width: bounds.width,
      height: bounds.height,
      ...(visible ? { x: bounds.x, y: bounds.y } : {}),
      minWidth: 760,
      minHeight: 600,
      show: false,
      backgroundColor: '#f5f6fb',
      icon,
      webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, spellcheck: false },
    });
    const current = window;
    current.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    current.webContents.on('will-navigate', (event, url) => {
      const target = new URL(url);
      if (target.origin !== origin || !['/', '/standalone-ai/'].includes(target.pathname)) {
        event.preventDefault();
      } else if (!target.hash) {
        event.preventDefault();
        void current.loadURL(target.href + '#token=' + token);
      }
    });
    current.on('close', (event) => {
      try {
        store.saveWindow(current.getNormalBounds());
      } catch {
        console.error('[dsh-pet-app] 窗口位置保存失败');
      }
      if (!quitting) {
        event.preventDefault();
        if (store.get().closeToTray && trayAvailable) current.hide();
        else app.quit();
      }
    });
    current.once('ready-to-show', () => {
      if (!hidden) current.show();
    });
    void current.loadURL(origin + '/#token=' + token).catch(() => console.error('[dsh-pet-app] 控制中心加载失败'));
  };
  Menu.setApplicationMenu(
    process.platform === 'darwin'
      ? Menu.buildFromTemplate([
          {
            label: 'DSH Pet',
            submenu: [
              { role: 'about' },
              { type: 'separator' },
              { role: 'hide' },
              { role: 'hideOthers' },
              { role: 'unhide' },
              { type: 'separator' },
              { role: 'quit' },
            ],
          },
          { role: 'editMenu' },
          { role: 'windowMenu' },
        ])
      : null,
  );
  app.on('before-quit', () => {
    quitting = true;
  });
  app.on('second-instance', () => show());
  app.on('activate', () => show());
  app.on('dsh-pet:open-control', () => show());
  app.on('dsh-pet:tray-unavailable', () => {
    trayAvailable = false;
    show();
  });
  show(store.get().startHidden || process.argv.includes('--hidden'));
}
