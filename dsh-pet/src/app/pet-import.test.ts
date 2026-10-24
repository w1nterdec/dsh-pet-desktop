import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, writeFile, readdir, rm, symlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { installPetImport, planPetImport, readImportSource } from './pet-import.ts';
import { readAllConfig } from '../host/config.ts';

const webm = Buffer.from([0x1a, 0x45, 0xdf, 0xa3, 0x80]);

test('portable manifest resolves nested clips and ordered PNG frames without evaluating behavior', () => {
  const files = new Map([
    [
      'fox/pet.json',
      Buffer.from(
        JSON.stringify({
          format: 'dsh-pet/pack-v1',
          name: '小狐',
          animations: [
            { name: 'idle', file: 'idle.webm' },
            { name: 'wave', frames: ['2.png', '1.png'], fps: 12 },
          ],
        }),
      ),
    ],
    ['fox/idle.webm', webm],
    ['fox/1.png', Buffer.from('')],
    ['fox/2.png', Buffer.from('')],
  ]);
  const plan = planPetImport(files, 'fallback');
  assert.equal(plan.name, '小狐');
  assert.deepEqual(plan.clips[1].frames, ['fox/2.png', 'fox/1.png']);
  const bad = JSON.parse(files.get('fox/pet.json')!.toString());
  bad.animations[0].file = '../escape.webm';
  files.set('fox/pet.json', Buffer.from(JSON.stringify(bad)));
  assert.throws(() => planPetImport(files, 'fallback'), /路径/);
});

test('Shimeji adapter sorts numbered frames and explains behavioral limits', () => {
  const plan = planPetImport(
    new Map(['shime10.png', 'shime2.png', 'shime1.png'].map((name) => ['img/' + name, Buffer.from('')])),
    'shimeji',
  );
  assert.deepEqual(plan.clips[0].frames, ['img/shime1.png', 'img/shime2.png', 'img/shime10.png']);
  assert.match(plan.warnings[0], /XML/);
  assert.throws(() => planPetImport(new Map([['fox.model3.json', Buffer.from('{}')]]), 'fox'), /专用模型/);
});

test('install publishes unique file-pet configurations after assets, preserving the source', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pet-import-'));
  try {
    const source = join(root, 'companion.webm'),
      pets = join(root, 'pet');
    await writeFile(source, webm);
    const first = await installPetImport(source, pets),
      second = await installPetImport(source, pets);
    assert.notEqual(first.id, second.id);
    const config = readAllConfig({
      defaultFile: resolve('assets/config.jsonc'),
      userFile: join(root, 'main-config.jsonc'),
      petDir: pets,
    });
    assert.equal((config[first.id].pets as Array<{ id: string }>)[0].id, first.id);
    assert.deepEqual((config[first.id].animations as { idle: string[] }).idle, ['idle']);
    assert.equal(config[first.id].whisperEnabled, undefined);
    assert.ok((await readdir(pets)).every((name) => !name.startsWith('.import-')));
    const invalid = join(root, 'invalid.webm');
    await writeFile(invalid, 'not video');
    await assert.rejects(installPetImport(invalid, pets), /文件头/);
    assert.equal((await readdir(pets)).length, 4);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('directory imports refuse symlinks and do not publish failed manifests', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'pet-source-'));
  try {
    const source = join(root, 'source');
    await mkdir(source);
    await writeFile(join(source, 'pet.json'), JSON.stringify({ format: 'bad' }));
    await assert.rejects(installPetImport(source, join(root, 'pets')), /格式/);
    const target = join(root, 'target.webm');
    await writeFile(target, webm);
    try {
      await symlink(target, join(source, 'link.webm'));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'EPERM') {
        context.diagnostic('Windows symlink creation requires developer mode; symlink case skipped');
        return;
      }
      throw error;
    }
    await assert.rejects(readImportSource(source), /链接/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
