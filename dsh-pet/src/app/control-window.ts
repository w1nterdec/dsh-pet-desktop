import { createRequire } from 'node:module';

interface ControlWindow {
  loadURL(url: string): Promise<void>;
  show(): void;
  hide(): void;
  focus(): void;
  restore(): void;
  isMinimized(): boolean;
  isDestroyed(): boolean;
  on(event: 'close', listener: (event: { preventDefault(): void }) => void): void;
  once(event: 'ready-to-show', listener: () => void): void;
  webContents: {
    on(event: 'will-navigate', listener: (event: { preventDefault(): void }, url: string) => void): void;
    setWindowOpenHandler(handler: () => { action: 'deny' }): void;
  };
}

/** One recoverable settings window; closing it keeps the tray and pet windows alive. */
export function installControlWindow(origin: string, icon: string, token: string): void {
  const { app, BrowserWindow, Menu } = createRequire(import.meta.url)('electron') as {
    app: { on(event: string, listener: () => void): void };
    BrowserWindow: new (options: Record<string, unknown>) => ControlWindow;
    Menu: { setApplicationMenu(menu: null): void };
  };
  let window: ControlWindow | undefined;
  let quitting = false;
  const show = (): void => {
    if (window && !window.isDestroyed()) {
      if (window.isMinimized()) window.restore();
      window.show();
      window.focus();
      return;
    }
    window = new BrowserWindow({
      title: '桌宠之家',
      width: 1080,
      height: 820,
      minWidth: 700,
      minHeight: 560,
      show: false,
      backgroundColor: '#f7f8f3',
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
      if (!quitting) {
        event.preventDefault();
        current.hide();
      }
    });
    current.once('ready-to-show', () => current.show());
    void current.loadURL(origin + '/#token=' + token).catch(() => console.error('[dsh-pet-app] 控制中心加载失败'));
  };
  Menu.setApplicationMenu(null);
  app.on('before-quit', () => {
    quitting = true;
  });
  app.on('second-instance', show);
  app.on('activate', show);
  app.on('dsh-pet:open-control', show);
  show();
}
