#!/usr/bin/env node
/**
 * dist.mjs —— 打包双击可运行的桌宠应用（electron-builder 的薄封装）。
 *
 * 用法：
 *   node scripts/dist.mjs            # 出解包目录 dist-app\win-unpacked（分发时压成 zip 即可）
 *   node scripts/dist.mjs dir        # 同上，显式写法
 *
 * 只出「解包目录」这一种形态：
 *   - portable（单文件）是 NSIS 自解压壳，每次启动都要把整个应用目录解压到 %TEMP%
 *     （约 400 MB、几十秒起），对 Electron 这种体量得不偿失；
 *   - nsis：使用 `node scripts/dist.mjs nsis` 生成带快捷方式和卸载入口的安装包。
 *   目录版与 zip 分发都是「摊开」的，启动只要 2–3 秒。
 *
 * 为什么要这层封装：electron-builder 默认从 GitHub 取 Electron 与打包二进制，而本仓库所在的
 * 机器 github.com 不通。这里统一注入三件事：
 *   1. electronDist = 本机已解压的 Electron（默认 $DSH_HOME/electron），完全不下载；
 *   2. ELECTRON_BUILDER_BINARIES_MIRROR = npmmirror（winCodeSign 等打包二进制走镜像）；
 *   3. ELECTRON_SKIP_BINARY_DOWNLOAD = 1（杜绝 postinstall 阶段再拉一次）。
 */
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { distributionOptions, electronDirectory } from './dist-options.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
let distribution;
try {
  distribution = distributionOptions(
    process.platform,
    process.argv[2],
    process.env.DSH_PET_TARGET_ARCH || process.arch,
  );
} catch (error) {
  console.error(`[dist] ${error.message}`);
  process.exit(2);
}

/** Electron 可执行文件相对解压目录的路径（按平台） */
const ELECTRON_REL =
  process.platform === 'win32'
    ? 'electron.exe'
    : process.platform === 'darwin'
      ? join('Electron.app', 'Contents', 'MacOS', 'Electron')
      : 'electron';

/**
 * 解析本机 Electron 解压目录。
 *
 * 本机（有 ~/.dsh/electron）走本地目录，一个字节都不下载；CI 上通常没有这份目录，
 * 此时设 `DSH_PET_ALLOW_ELECTRON_DOWNLOAD=1` 让 electron-builder 自己去取（GitHub runner
 * 能访问 github.com / 也可用 ELECTRON_MIRROR 换镜像）。默认仍然**明确失败**，
 * 避免本地开发时被静默拉走 110 MB。
 *
 * @returns 解压目录；允许下载且本机没有时返回 undefined（= 不传 electronDist）
 */
function resolveElectronDist() {
  const dshHome = process.env.DSH_HOME || join(homedir(), '.dsh');
  const candidates = [
    process.env.DSH_PET_ELECTRON_DIST,
    process.env.DSH_PET_ELECTRON_PATH && distribution.arch === process.arch
      ? electronDirectory(process.env.DSH_PET_ELECTRON_PATH, process.platform)
      : undefined,
    distribution.arch === process.arch ? join(dshHome, 'electron') : undefined,
    distribution.arch === process.arch ? join(root, '..', '.tools', 'runtime', 'electron') : undefined,
  ].filter((value) => Boolean(value));
  for (const dir of candidates) {
    if (existsSync(join(dir, ELECTRON_REL))) return dir;
  }
  if (process.env.DSH_PET_ALLOW_ELECTRON_DOWNLOAD === '1') {
    console.warn('[dist] 本机没有 Electron 解压目录，交给 electron-builder 下载（CI 模式）。');
    return undefined;
  }
  console.error(
    '[dist] 找不到本机 Electron 解压目录。先跑 `npm run ensure:electron`，' +
      '或设置 DSH_PET_ELECTRON_DIST 指向解压目录。',
  );
  process.exit(1);
}

const target = distribution.target;

const electronDist = resolveElectronDist();
/** mirror 默认 npmmirror：本机 github.com 不通；CI 上可用环境变量覆盖成官方源 */
const env = {
  ...process.env,
  ELECTRON_BUILDER_BINARIES_MIRROR:
    process.env.ELECTRON_BUILDER_BINARIES_MIRROR || 'https://npmmirror.com/mirrors/electron-builder-binaries/',
  ELECTRON_MIRROR: process.env.ELECTRON_MIRROR || 'https://npmmirror.com/mirrors/electron/',
  // 产物里不需要 electron 的 symbols / 调试信息，省 200 MB
  CSC_IDENTITY_AUTO_DISCOVERY: process.env.CSC_IDENTITY_AUTO_DISCOVERY || 'false',
};
// 有本地解压目录时才禁止 Electron 下载；CI 的回退路径反过来必须允许下载
if (electronDist) {
  env.DSH_PET_ELECTRON_DIST = electronDist;
  env.ELECTRON_SKIP_BINARY_DOWNLOAD = '1';
} else {
  delete env.DSH_PET_ELECTRON_DIST;
  delete env.ELECTRON_SKIP_BINARY_DOWNLOAD;
}

console.log(`[dist] electron dist: ${electronDist ?? '(无 → 由 electron-builder 下载)'}`);
console.log(`[dist] target:        ${process.platform}:${target}:${distribution.arch}`);
// 直接调用 electron-builder 的 CLI 文件而不是 `pnpm exec`：路径里有 `p'c` 这种单引号，
// 走 shell 传参会把参数切碎；node 直接 spawn 没有这层转义问题。
const cli = join(root, 'node_modules', 'electron-builder', 'out', 'cli', 'cli.js');
// Publishing is owned by the release workflow; CI must only build an installer here.
const args = [cli, distribution.flag, target, `--${distribution.arch}`, '--publish', 'never'];
if (process.env.DSH_PET_DIST_OUTPUT)
  args.push(`--config.directories.output=${resolve(root, process.env.DSH_PET_DIST_OUTPUT)}`);
if (electronDist) args.push(`--config.electronDist=${electronDist}`);
const child = spawn(process.execPath, args, {
  cwd: root,
  env,
  stdio: 'inherit',
});
child.on('error', (error) => {
  console.error(`[dist] ${error.message}`);
  process.exitCode = 1;
});
child.on('exit', (code, signal) => {
  process.exitCode = signal ? 1 : (code ?? 1);
});
