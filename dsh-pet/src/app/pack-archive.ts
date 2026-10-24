import { PetImportError } from './import-error';
import { inflateRawSync } from 'node:zlib';

export const PACK_LIMITS = { files: 512, fileBytes: 32 * 1024 * 1024, totalBytes: 128 * 1024 * 1024 };

/** Reject ambiguous Windows names as well as POSIX traversal, on every OS. */
export function safePackPath(value: string): string {
  const parts = value.split('/');
  if (
    !value ||
    value.length > 240 ||
    value.includes('\\') ||
    parts.some(
      (part) =>
        !part ||
        part === '.' ||
        part === '..' ||
        /[<>:"|?*\p{Cc}]/u.test(part) ||
        /[. ]$/.test(part) ||
        /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part),
    )
  )
    throw new PetImportError('素材包包含不安全的路径');
  return value;
}

export function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/** Bounded ZIP reader: no extraction paths, links, encryption, ZIP64 or executable loading. */
export function readPackZip(zip: Buffer): Map<string, Buffer> {
  if (zip.length > PACK_LIMITS.totalBytes || zip.length < 22) throw new PetImportError('ZIP 大小无效');
  let end = zip.length - 22;
  while (end >= Math.max(0, zip.length - 65557)) {
    if (zip.readUInt32LE(end) === 0x06054b50 && end + 22 + zip.readUInt16LE(end + 20) === zip.length) break;
    end--;
  }
  if (end < Math.max(0, zip.length - 65557)) throw new PetImportError('ZIP 目录损坏');
  const count = zip.readUInt16LE(end + 10);
  const start = zip.readUInt32LE(end + 16);
  if (
    zip.readUInt32LE(end + 4) !== 0 ||
    zip.readUInt16LE(end + 8) !== count ||
    count > PACK_LIMITS.files ||
    count === 0 ||
    start + zip.readUInt32LE(end + 12) !== end
  )
    throw new PetImportError('不支持分卷、ZIP64 或过大的素材包');
  const files = new Map<string, Buffer>();
  const seen = new Set<string>();
  let cursor = start,
    total = 0;
  for (let index = 0; index < count; index++) {
    if (cursor + 46 > end || zip.readUInt32LE(cursor) !== 0x02014b50) throw new PetImportError('ZIP 目录损坏');
    const flags = zip.readUInt16LE(cursor + 8),
      method = zip.readUInt16LE(cursor + 10);
    const compressed = zip.readUInt32LE(cursor + 20),
      size = zip.readUInt32LE(cursor + 24);
    const nameLength = zip.readUInt16LE(cursor + 28);
    const next = cursor + 46 + nameLength + zip.readUInt16LE(cursor + 30) + zip.readUInt16LE(cursor + 32);
    if (
      next > end ||
      flags & 1 ||
      ![0, 8].includes(method) ||
      size > PACK_LIMITS.fileBytes ||
      (total += size) > PACK_LIMITS.totalBytes ||
      zip.readUInt16LE(cursor + 34) !== 0
    )
      throw new PetImportError('ZIP 加密、体积或压缩方式不受支持');
    const rawName = zip.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    const directory = rawName.endsWith('/');
    const name = safePackPath(directory ? rawName.slice(0, -1) : rawName);
    const key = name.normalize('NFC').toLowerCase();
    const mode = (zip.readUInt32LE(cursor + 38) >>> 16) & 0xf000;
    if (seen.has(key) || ![0, 0x8000, 0x4000].includes(mode)) throw new PetImportError('ZIP 包含重复路径或链接');
    seen.add(key);
    const local = zip.readUInt32LE(cursor + 42);
    if (local + 30 > start || zip.readUInt32LE(local) !== 0x04034b50) throw new PetImportError('ZIP 文件头损坏');
    const dataStart = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    if (
      dataStart + compressed > start ||
      dataStart > start ||
      zip.readUInt16LE(local + 8) !== method ||
      zip.readUInt16LE(local + 6) !== flags ||
      zip.subarray(local + 30, local + 30 + zip.readUInt16LE(local + 26)).toString('utf8') !== rawName
    )
      throw new PetImportError('ZIP 文件头不一致');
    const packed = zip.subarray(dataStart, dataStart + compressed);
    const content = method === 0 ? packed : inflateRawSync(packed, { maxOutputLength: Math.max(1, size) });
    if (content.length !== size || crc32(content) !== zip.readUInt32LE(cursor + 16))
      throw new PetImportError('ZIP 校验失败');
    if (!directory) files.set(name, content);
    cursor = next;
  }
  if (cursor !== end) throw new PetImportError('ZIP 目录长度不一致');
  return files;
}
