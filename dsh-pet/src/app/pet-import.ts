import { PetImportError } from './import-error';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { lstat, readdir, readFile, mkdir, mkdtemp, writeFile, rename, rm } from 'node:fs/promises';
import { basename, dirname, extname, join } from 'node:path';
import { PACK_LIMITS, readPackZip, safePackPath } from './pack-archive';

const MEDIA = /\.(webm|mov|mp4|gif|png|apng|webp|jpe?g)$/i;
interface Clip {
  name: string;
  file?: string;
  frames?: string[];
  fps?: number;
  sheet?: { file: string; frameWidth: number; frameHeight: number; columns: number; count: number; fps: number };
  role?: string;
}
export interface ImportPlan {
  name: string;
  clips: Clip[];
  warnings: string[];
}

async function boundedRead(path: string, limit: number): Promise<Buffer> {
  const stat = await lstat(path);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > limit) throw new PetImportError('素材文件过大或属于链接');
  const content = await readFile(path);
  if (content.length > limit) throw new PetImportError('素材文件过大');
  return content;
}

export async function readImportSource(source: string): Promise<Map<string, Buffer>> {
  const stat = await lstat(source);
  if (stat.isSymbolicLink()) throw new PetImportError('不能导入符号链接');
  if (!stat.isDirectory()) {
    const content = await boundedRead(source, PACK_LIMITS.totalBytes);
    if (extname(source).toLowerCase() === '.zip') return readPackZip(content);
    if (!MEDIA.test(source) || content.length > PACK_LIMITS.fileBytes)
      throw new PetImportError('请选择 ZIP、素材文件或素材目录');
    return new Map([[safePackPath(basename(source)), content]]);
  }
  const files = new Map<string, Buffer>();
  const seen = new Set<string>();
  let total = 0,
    entries = 0;
  async function walk(relative = '', depth = 0): Promise<void> {
    if (depth > 12) throw new PetImportError('素材目录层级过深');
    for (const entry of await readdir(join(source, relative), { withFileTypes: true })) {
      if (++entries > PACK_LIMITS.files) throw new PetImportError('素材包最多包含 512 个文件及目录');
      const name = safePackPath(relative ? `${relative}/${entry.name}` : entry.name);
      const key = name.normalize('NFC').toLowerCase();
      if (seen.has(key)) throw new PetImportError('素材目录包含跨平台重名路径');
      seen.add(key);
      if (entry.isSymbolicLink()) throw new PetImportError('素材包不能包含链接');
      if (entry.isDirectory()) await walk(name, depth + 1);
      else {
        const bytes = await boundedRead(join(source, name), PACK_LIMITS.fileBytes);
        if ((total += bytes.length) > PACK_LIMITS.totalBytes) throw new PetImportError('素材包解压后不能超过 128 MB');
        files.set(name, bytes);
      }
    }
  }
  await walk();
  return files;
}

/** Data-only adapters. Legacy executable behavior (Shimeji XML/Java, Live2D scripts) is never run. */
export function planPetImport(files: Map<string, Buffer>, suggestedName: string): ImportPlan {
  const manifests = [...files.keys()].filter((name) => /(^|\/)pet\.json$/i.test(name));
  if (manifests.length > 1) throw new PetImportError('素材包包含多个 pet.json，请选择单只桌宠的目录');
  if (manifests[0]) {
    const path = manifests[0],
      bytes = files.get(path)!;
    if (bytes.length > 64 * 1024) throw new PetImportError('pet.json 不能超过 64 KB');
    let manifest;
    try {
      manifest = JSON.parse(bytes.toString('utf8'));
    } catch {
      throw new PetImportError('pet.json 不是有效的 JSON');
    }
    if (
      !manifest ||
      manifest.format !== 'dsh-pet/pack-v1' ||
      typeof manifest.name !== 'string' ||
      !manifest.name.trim() ||
      manifest.name.length > 80 ||
      !Array.isArray(manifest.animations) ||
      !manifest.animations.length ||
      manifest.animations.length > 128
    )
      throw new PetImportError('pet.json 格式无效');
    const prefix = path.includes('/') ? path.slice(0, path.lastIndexOf('/') + 1) : '';
    const seen = new Set<string>();
    const clips: Clip[] = manifest.animations.map((raw: Clip, index: number) => {
      if (
        !raw ||
        typeof raw !== 'object' ||
        typeof raw.name !== 'string' ||
        !/^[\p{L}\p{N}_-]{1,60}$/u.test(raw.name) ||
        seen.has(raw.name.toLowerCase())
      )
        throw new PetImportError('动作名称无效或重复');
      seen.add(raw.name.toLowerCase());
      const sourcePath = (value: unknown): string => {
        if (typeof value !== 'string') throw new PetImportError('动作缺少素材路径');
        const name = prefix + safePackPath(value);
        if (!files.has(name) || !MEDIA.test(name))
          throw new PetImportError(`动作 ${index + 1} 的素材不存在或格式不受支持`);
        return name;
      };
      const role =
        typeof raw.role === 'string' && ['idle', 'click', 'drag', 'move', 'action'].includes(raw.role)
          ? raw.role
          : undefined;
      if (raw.sheet !== undefined) {
        if (!raw.sheet || typeof raw.sheet !== 'object' || raw.file !== undefined || raw.frames !== undefined)
          throw new PetImportError('精灵图动作只能指定 sheet 素材来源');
        const sheet = raw.sheet;
        const valid = (value: unknown, max: number) =>
          typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= max;
        if (
          !valid(sheet.frameWidth, 2048) ||
          !valid(sheet.frameHeight, 2048) ||
          !valid(sheet.columns, 64) ||
          !valid(sheet.count, 300) ||
          !valid(sheet.fps, 30)
        )
          throw new PetImportError('精灵图网格、帧数或帧率无效');
        const file = sourcePath(sheet.file);
        if (!/\.(png|webp|jpe?g)$/i.test(file)) throw new PetImportError('精灵图须为 PNG、WebP 或 JPEG');
        return {
          name: raw.name,
          role,
          sheet: {
            file,
            frameWidth: sheet.frameWidth,
            frameHeight: sheet.frameHeight,
            columns: sheet.columns,
            count: sheet.count,
            fps: sheet.fps,
          },
        };
      }
      if (raw.frames !== undefined) {
        if (
          raw.file !== undefined ||
          !Array.isArray(raw.frames) ||
          !raw.frames.length ||
          raw.frames.length > 300 ||
          raw.frames.some((name) => typeof name !== 'string' || !/\.png$/i.test(name))
        )
          throw new PetImportError('序列帧须为 1～300 张 PNG');
        if (!Number.isInteger(raw.fps) || raw.fps! < 1 || raw.fps! > 30)
          throw new PetImportError('帧率须为 1～30 的整数');
        return { name: raw.name, role, frames: raw.frames.map(sourcePath), fps: raw.fps };
      }
      return { name: raw.name, role, file: sourcePath(raw.file) };
    });
    return { name: manifest.name.trim(), clips, warnings: [] };
  }
  if ([...files.keys()].some((name) => /\.(model3?\.json|moc3?|vrm)$/i.test(name)))
    throw new PetImportError('该桌宠需要专用模型或可执行运行时；请先导出为透明视频、GIF 或 PNG 序列');
  const media = [...files.keys()]
    .filter((name) => MEDIA.test(name))
    .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
  if (!media.length) throw new PetImportError('未找到支持的桌宠素材');
  const shimeji = media.filter((name) => /(^|\/)shime\d+\.png$/i.test(name));
  if (shimeji.length > 300) throw new PetImportError('单个动作最多包含 300 帧');
  if (shimeji.length)
    return {
      name: suggestedName.slice(0, 80),
      clips: [{ name: 'idle', frames: shimeji, fps: 12 }],
      warnings: ['已按文件编号导入 Shimeji 外观序列；原 XML / Java 行为不会执行，可通过 pet.json 定义动作分组。'],
    };
  if (media.length > 128) throw new PetImportError('素材过多，请用 pet.json 明确指定动作（最多 128 个）');
  return {
    name: suggestedName.slice(0, 80),
    clips: media.map((file, index) => ({ name: index === 0 ? 'idle' : `action-${index}`, file })),
    warnings: media.length > 1 ? ['未提供 pet.json，已按文件名排序，将首个素材用作待机动作。'] : [],
  };
}

/** Fixed arguments and protocol whitelist: filenames never become shell commands or network URLs. */
export function transcode(args: string[], executable = process.env.DSH_PET_FFMPEG_PATH || 'ffmpeg'): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(
      executable,
      ['-hide_banner', '-loglevel', 'error', '-nostdin', '-max_alloc', '67108864', '-y', ...args],
      {
        shell: false,
        windowsHide: true,
        stdio: 'ignore',
      },
    );
    const stopConverter = () => {
      child.kill();
    };
    process.once('exit', stopConverter);
    const timer = setTimeout(() => child.kill(), 120000);
    child.once('error', () => {
      clearTimeout(timer);
      process.removeListener('exit', stopConverter);
      reject(new PetImportError('该格式需要 FFmpeg，请安装后加入 PATH，或设置 DSH_PET_FFMPEG_PATH'));
    });
    child.once('close', (code) => {
      clearTimeout(timer);
      process.removeListener('exit', stopConverter);
      if (code === 0) resolve();
      else reject(new PetImportError('素材转换失败或超时，请检查素材格式（单动作最长 30 秒）'));
    });
  });
}

export interface InstalledPet {
  id: string;
  name: string;
  warnings: string[];
}
export async function installPetImport(source: string, petDirectory: string): Promise<InstalledPet> {
  const files = await readImportSource(source);
  const plan = planPetImport(files, basename(source, extname(source)) || '新伙伴');
  await mkdir(petDirectory, { recursive: true });
  const stage = await mkdtemp(join(petDirectory, '.import-'));
  const id = 'import-' + randomUUID();
  const animationDirectory = join(petDirectory, `${id}-animation`);
  let moved = false;
  try {
    const input = join(stage, 'input'),
      output = join(stage, 'output');
    await mkdir(input);
    await mkdir(output);
    for (const [name, bytes] of files) {
      const file = join(input, name);
      await mkdir(dirname(file), { recursive: true });
      await writeFile(file, bytes, { flag: 'wx' });
    }
    let outputBytes = 0;
    for (const clip of plan.clips) {
      const target = join(output, `${clip.name}.webm`);
      if (clip.file && /\.webm$/i.test(clip.file)) {
        const bytes = files.get(clip.file)!;
        if (bytes.length < 4 || bytes.readUInt32BE(0) !== 0x1a45dfa3) throw new PetImportError('WebM 文件头无效');
        await writeFile(target, bytes);
      } else {
        let args: string[];
        if (clip.sheet) {
          args = [
            '-protocol_whitelist',
            'file,pipe',
            '-f',
            'image2',
            '-loop',
            '1',
            '-framerate',
            String(clip.sheet.fps),
            '-max_pixels',
            '8388608',
            '-i',
            join(input, clip.sheet.file),
          ];
        } else if (clip.frames) {
          const frames = join(stage, 'frames');
          await mkdir(frames, { recursive: true });
          for (let i = 0; i < clip.frames.length; i++)
            await writeFile(join(frames, `${String(i).padStart(4, '0')}.png`), files.get(clip.frames[i])!);
          args = [
            '-protocol_whitelist',
            'file,pipe',
            '-f',
            'image2',
            '-framerate',
            String(clip.fps),
            '-i',
            join(frames, '%04d.png'),
          ];
        } else {
          const still = /\.(png|jpe?g)$/i.test(clip.file!);
          const extension = extname(clip.file!).toLowerCase();
          const format = (
            {
              '.gif': 'gif',
              '.png': 'image2',
              '.jpg': 'image2',
              '.jpeg': 'image2',
              '.webp': 'webp_pipe',
              '.apng': 'apng',
              '.mov': 'mov',
              '.mp4': 'mov',
            } as Record<string, string>
          )[extension];
          args = [
            '-protocol_whitelist',
            'file,pipe',
            '-f',
            format,
            ...(still ? ['-loop', '1'] : []),
            '-i',
            join(input, clip.file!),
          ];
        }
        // FFmpeg normally clamps out-of-bounds crop positions. Fail an undersized atlas
        // instead of silently repeating edge cells when a manifest declares a larger grid.
        const sheet = clip.sheet;
        const sheetFilter = sheet
          ? `trim=end_frame=${sheet.count},crop=if(gte(iw\\,${sheet.frameWidth * sheet.columns})*gte(ih\\,${sheet.frameHeight * Math.ceil(sheet.count / sheet.columns)})*lte(iw\\,4096)*lte(ih\\,4096)\\,${sheet.frameWidth}\\,0):${sheet.frameHeight}:mod(n\\,${sheet.columns})*${sheet.frameWidth}:floor(n/${sheet.columns})*${sheet.frameHeight},`
          : '';
        await transcode([
          ...args,
          ...(clip.sheet ? ['-frames:v', String(Math.ceil((clip.sheet.count * 24) / clip.sheet.fps))] : []),
          '-t',
          '30',
          '-an',
          '-vf',
          sheetFilter + 'scale=640:640:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1',
          '-r',
          '24',
          '-c:v',
          'libvpx-vp9',
          '-pix_fmt',
          'yuva420p',
          '-auto-alt-ref',
          '0',
          '-b:v',
          '0',
          '-crf',
          '32',
          target,
        ]);
        if (clip.frames) await rm(join(stage, 'frames'), { recursive: true, force: true });
      }
      const produced = await lstat(target);
      if (
        !produced.size ||
        produced.size > PACK_LIMITS.fileBytes ||
        (outputBytes += produced.size) > PACK_LIMITS.totalBytes
      )
        throw new PetImportError('转换后的素材超出大小限制');
    }
    const idle = (plan.clips.find((clip) => clip.role === 'idle') ?? plan.clips[0]).name;
    const drag = plan.clips.filter((clip) => clip.role === 'drag').map((clip) => clip.name);
    const clicks = plan.clips.filter((clip) => clip.role === 'click').map((clip) => clip.name);
    const config = {
      pets: [
        {
          id,
          name: plan.name,
          display: 'desktop',
          size: 240,
          balanceEnabled: false,
          whisperEnabled: false,
          workStatusEnabled: false,
          fixedEnabled: true,
          position: { corner: 'bottom-right', marginX: 30, marginY: 30 },
        },
      ],
      animations: {
        idle: [idle],
        turn: [],
        drag: drag.length ? drag : [idle],
        clicks: clicks.length ? clicks : [plan.clips[1]?.name ?? idle],
        moves: { default: {}, actions: [] },
        categories:
          plan.clips.length > 1
            ? [{ id: 'imported', weight: 1, actions: plan.clips.slice(1).map((clip) => clip.name) }]
            : [],
        events: { balance: [idle], whisper: [idle], workStatus: [idle] },
      },
      animationWeights: { idle: 1, turn: 0, move: 0 },
      memes: {},
    };
    await writeFile(join(stage, 'config.json'), JSON.stringify(config, null, 2));
    await rename(output, animationDirectory);
    moved = true;
    // The config is the discovery point: publish it only after every asset has succeeded.
    await rename(join(stage, 'config.json'), join(petDirectory, `${id}-config.json`));
    return { id, name: plan.name, warnings: plan.warnings };
  } catch (error) {
    if (moved) await rm(animationDirectory, { recursive: true, force: true });
    throw error;
  } finally {
    await rm(stage, { recursive: true, force: true });
  }
}
