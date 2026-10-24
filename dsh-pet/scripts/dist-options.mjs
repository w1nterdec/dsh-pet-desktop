import { dirname, resolve } from 'node:path';

export const targetsFor = {
  win32: ['dir', 'nsis'],
  darwin: ['dir', 'dmg', 'zip'],
  linux: ['dir', 'AppImage', 'deb', 'tar.gz'],
};
export function distributionOptions(platform, target = 'dir', arch = process.arch) {
  if (!Object.hasOwn(targetsFor, platform)) throw new Error(`不支持的平台：${platform}`);
  if (!targetsFor[platform].includes(target))
    throw new Error(`${platform} 可用目标：${targetsFor[platform].join(' / ')}`);
  if (!['x64', 'arm64'].includes(arch)) throw new Error('发行构建仅支持 x64 / arm64');
  return { flag: { win32: '--win', darwin: '--mac', linux: '--linux' }[platform], target, arch };
}
export function electronDirectory(executable, platform) {
  return platform === 'darwin' ? resolve(dirname(executable), '../../..') : dirname(executable);
}
