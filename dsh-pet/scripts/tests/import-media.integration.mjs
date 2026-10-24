import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { installPetImport } from '../../src/app/pet-import.ts';

const exec = promisify(execFile);
test(
  'real FFmpeg preserves GIF alpha, converts ordered PNG frames and rolls back malformed media',
  {
    skip: !process.env.DSH_PET_FFMPEG_PATH,
    timeout: 120000,
  },
  async () => {
    const root = await mkdtemp(join(tmpdir(), 'pet-transcode-'));
    try {
      const source = resolve('../android/app/src/main/assets/default/companion.gif');
      const imported = await installPetImport(source, join(root, 'pets'));
      const video = join(root, 'pets', `${imported.id}-animation`, 'idle.webm');
      const { stdout: alpha } = await exec(
        process.env.DSH_PET_FFMPEG_PATH,
        [
          '-v',
          'error',
          '-c:v',
          'libvpx-vp9',
          '-i',
          video,
          '-vf',
          'alphaextract',
          '-frames:v',
          '1',
          '-f',
          'rawvideo',
          '-',
        ],
        { encoding: 'buffer', maxBuffer: 1024 * 1024, windowsHide: true },
      );
      assert.ok(
        alpha.some((byte) => byte === 0),
        'transparent pixels survive VP9 encoding',
      );
      const sequence = join(root, 'sequence');
      await mkdir(sequence);
      await exec(
        process.env.DSH_PET_FFMPEG_PATH,
        ['-v', 'error', '-i', source, '-frames:v', '1', join(sequence, '1.png')],
        { windowsHide: true },
      );
      await writeFile(
        join(sequence, 'pet.json'),
        JSON.stringify({
          format: 'dsh-pet/pack-v1',
          name: 'Frame test',
          animations: [{ name: 'idle', frames: ['1.png', '1.png'], fps: 12 }],
        }),
      );
      await installPetImport(sequence, join(root, 'pets'));
      const before = await readdir(join(root, 'pets'));
      const invalid = join(root, 'invalid.gif');
      await writeFile(invalid, 'file /private/not-media');
      await assert.rejects(installPetImport(invalid, join(root, 'pets')), /转换失败/);
      assert.deepEqual(await readdir(join(root, 'pets')), before);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  },
);
