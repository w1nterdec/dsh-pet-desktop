import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { randomUUID } from 'node:crypto';

/** freedesktop Desktop Entry quoting, followed by key-value string escaping. */
export function desktopExec(executable: string): string {
  if (!isAbsolute(executable) || /[\r\n\p{Cc}]/u.test(executable)) throw new Error('自启动程序路径无效');
  const quoted = '"' + executable.replace(/%/g, '%%').replace(/[\\"$`]/g, '\\$&') + '" --hidden';
  return quoted.replace(/\\/g, '\\\\');
}

export function createLinuxStartup(options: { configHome?: string; executable: string }) {
  const configHome =
    options.configHome && isAbsolute(options.configHome) ? options.configHome : join(homedir(), '.config');
  const directory = join(configHome, 'autostart');
  const file = join(directory, 'com.dshpet.desktop.desktop');
  return {
    enabled(): boolean {
      try {
        return existsSync(file) && !/^Hidden=true$/m.test(readFileSync(file, 'utf8'));
      } catch {
        return false;
      }
    },
    set(enabled: boolean): void {
      if (!enabled) {
        rmSync(file, { force: true });
        return;
      }
      const content = `[Desktop Entry]\nType=Application\nVersion=1.0\nName=DSH Pet\nComment=Desktop companion\nExec=${desktopExec(options.executable)}\nTerminal=false\nX-GNOME-Autostart-enabled=true\n`;
      mkdirSync(directory, { recursive: true });
      const temporary = join(directory, `.dsh-pet-${randomUUID()}.tmp`);
      try {
        writeFileSync(temporary, content, { mode: 0o600, flag: 'wx' });
        renameSync(temporary, file);
      } finally {
        rmSync(temporary, { force: true });
      }
    },
  };
}
