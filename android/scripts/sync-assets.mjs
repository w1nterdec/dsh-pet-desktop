/** Reuse the project's licensed GIF library; no generated placeholder media. */
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const source = resolve(root, 'dsh-pet/assets/preview');
const destination = resolve(root, 'android/app/src/main/assets/builtin');
const python = await readFile(resolve(root, 'scripts/encode_preview_gifs.py'), 'utf8');
const mapping = new Map([...python.matchAll(/^\s*"([^"]+)": "([^"]+)",?$/gm)].map((match) => [match[2], match[1]]));
const files = (await readdir(source)).filter((file) => file.endsWith('.gif')).sort();
if (files.length < 90 || files.length > 128) throw new Error('Unexpected builtin animation count');
await mkdir(destination, { recursive: true });
const animations = [];
for (const [index, file] of files.entries()) {
  const stem = file.slice(0, -4);
  const role = stem === 'daiji-huxi-xiuxian' ? 'idle' : stem.startsWith('dianji-') ? 'click'
    : stem.startsWith('beishubiao-') ? 'drag' : /zoulu|benpao/.test(stem) ? 'move' : 'action';
  animations.push({ name: `clip-${String(index + 1).padStart(3, '0')}`, label: mapping.get(stem) || stem, role, file, noMirror: true });
}
animations.sort((a, b) => Number(b.role === 'idle') - Number(a.role === 'idle'));
await writeFile(resolve(destination, 'pet.json'), JSON.stringify({ format: 'dsh-pet/pack-v1', name: '蓝毛小女仆', animations }, null, 2) + '\n');
console.log(`Synced ${animations.length} playable animations and their original action names.`);
