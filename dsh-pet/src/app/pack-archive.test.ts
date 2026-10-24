import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deflateRawSync } from 'node:zlib';
import { crc32, PACK_LIMITS, readPackZip, safePackPath } from './pack-archive.ts';

function zipOf(entries: Array<{ name: string; bytes?: Buffer; mode?: number; method?: number }>): Buffer {
  const locals: Buffer[] = [],
    central: Buffer[] = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name),
      bytes = entry.bytes ?? Buffer.from('pet');
    const method = entry.method ?? 0,
      packed = method === 8 ? deflateRawSync(bytes) : bytes;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc32(bytes), 14);
    local.writeUInt32LE(packed.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(name.length, 26);
    const record = Buffer.alloc(46);
    record.writeUInt32LE(0x02014b50);
    record.writeUInt16LE(method, 10);
    record.writeUInt32LE(crc32(bytes), 16);
    record.writeUInt32LE(packed.length, 20);
    record.writeUInt32LE(bytes.length, 24);
    record.writeUInt16LE(name.length, 28);
    record.writeUInt32LE(((entry.mode ?? 0x8000) << 16) >>> 0, 38);
    record.writeUInt32LE(offset, 42);
    locals.push(local, name, packed);
    central.push(record, name);
    offset += local.length + name.length + packed.length;
  }
  const directory = Buffer.concat(central),
    end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

test('ZIP reader supports stored and deflated Unicode media and verifies CRC', () => {
  const zip = zipOf([{ name: '伙伴/idle.webm' }, { name: 'pet.json', bytes: Buffer.from('{}'), method: 8 }]);
  assert.equal(readPackZip(zip).get('伙伴/idle.webm')?.toString(), 'pet');
  assert.equal(readPackZip(zip).get('pet.json')?.toString(), '{}');
  zip[30 + Buffer.byteLength('伙伴/idle.webm')] ^= 1;
  assert.throws(() => readPackZip(zip), /校验/);
});

test('portable paths reject traversal, absolute names, ADS and reserved Windows names', () => {
  for (const path of ['../a', '/a', 'a\\b', 'C:/a', 'a//b', 'a/./b', 'a. /b', 'a:/b', 'CON.png', 'a/COM1', 'a\0b'])
    assert.throws(() => safePackPath(path), /路径/);
  assert.equal(safePackPath('动作/idle.png'), '动作/idle.png');
});

test('ZIP reader rejects links, duplicate cross-platform names, traversal and truncation', () => {
  for (const entries of [
    [{ name: '../escape' }],
    [{ name: 'link', mode: 0xa000 }],
    [{ name: 'A.png' }, { name: 'a.png' }],
    [{ name: 'é.png' }, { name: 'e\u0301.png' }],
  ])
    assert.throws(() => readPackZip(zipOf(entries)));
  const valid = zipOf([{ name: 'idle.webm' }]);
  for (const length of [0, 4, 21, valid.length - 1]) assert.throws(() => readPackZip(valid.subarray(0, length)));
});

test('ZIP reader rejects declared bombs, encrypted entries and mismatched headers', () => {
  const original = zipOf([{ name: 'a', method: 8, bytes: Buffer.alloc(10000) }]);
  const directory = original.indexOf(Buffer.from([0x50, 0x4b, 0x01, 0x02]));
  for (const mutate of [
    (zip: Buffer) => zip.writeUInt32LE(PACK_LIMITS.fileBytes + 1, directory + 24),
    (zip: Buffer) => zip.writeUInt16LE(1, directory + 8),
    (zip: Buffer) => zip.writeUInt16LE(99, 8),
    (zip: Buffer) => zip.writeUInt32LE(1, directory + 24),
  ]) {
    const zip = Buffer.from(original);
    mutate(zip);
    assert.throws(() => readPackZip(zip));
  }
});
