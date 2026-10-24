package com.dshpet.android;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Data-only importer. A pack is made visible only by an atomic staging-directory rename. */
public final class PetPack {
  private static final long MAX_FILE = 32L * 1024 * 1024, MAX_TOTAL = 128L * 1024 * 1024;
  public final File directory;
  public final String name;
  public final JSONArray animations;

  private PetPack(File directory, JSONObject json) throws Exception {
    this.directory = directory;
    name = json.getString("name");
    animations = json.getJSONArray("animations");
  }

  public static PetPack selected(Context context) {
    String id = context.getSharedPreferences("pet", 0).getString("selected", "");
    if (id == null || id.isEmpty()) {
      try {
        return useBuiltin(context);
      } catch (Exception ignored) {
        return null;
      }
    }
    if (!id.equals("builtin") && !id.matches("pet-[a-f0-9-]{36}")) return null;
    try {
      File directory = new File(context.getFilesDir(), "pets/" + id);
      return new PetPack(
          directory, new JSONObject(readText(new File(directory, "installed.json"))));
    } catch (Exception ignored) {
      return null;
    }
  }

  public static PetPack useBuiltin(Context context) throws Exception {
    File directory = new File(context.getFilesDir(), "pets/builtin");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建内置桌宠目录");
    File animation = new File(directory, "companion.gif");
    if (!animation.isFile()) {
      File temporary = new File(directory, "companion.tmp");
      try (InputStream input = context.getAssets().open("default/companion.gif")) {
        copy(input, temporary, MAX_FILE);
      }
      if (!temporary.renameTo(animation)) throw new IOException("无法初始化内置桌宠");
    }
    JSONObject manifest =
        new JSONObject()
            .put("name", "鲸鲸")
            .put(
                "animations",
                new JSONArray()
                    .put(new JSONObject().put("name", "idle").put("file", "companion.gif")));
    try (Writer writer =
        new OutputStreamWriter(
            new FileOutputStream(new File(directory, "installed.json")),
            java.nio.charset.StandardCharsets.UTF_8)) {
      writer.write(manifest.toString());
    }
    context.getSharedPreferences("pet", 0).edit().putString("selected", "builtin").apply();
    return new PetPack(directory, manifest);
  }

  public static PetPack install(Context context, Uri uri) throws Exception {
    File parent = new File(context.getFilesDir(), "pets");
    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建素材目录");
    File stage = new File(parent, ".import-" + UUID.randomUUID());
    if (!stage.mkdir()) throw new IOException("无法创建导入目录");
    boolean committed = false;
    try {
      Map<String, File> files = new LinkedHashMap<>();
      try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
        if (raw == null) throw new IOException("无法读取所选文件");
        BufferedInputStream buffered = new BufferedInputStream(new LimitedStream(raw, MAX_TOTAL));
        buffered.mark(4);
        int first = buffered.read(), second = buffered.read();
        buffered.reset();
        if (first == 'P' && second == 'K') {
          try (ZipInputStream zip = new ZipInputStream(buffered)) {
            Set<String> names = new HashSet<>();
            long total = 0;
            int count = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
              if (++count > 512) throw new IOException("素材包最多包含 512 个条目");
              String path =
                  PackPaths.safe(
                      entry.isDirectory()
                          ? entry.getName().substring(0, entry.getName().length() - 1)
                          : entry.getName());
              if (!names.add(PackPaths.key(path))) throw new IOException("重复的素材路径");
              File output = new File(stage, path);
              if (entry.isDirectory()) {
                if (zip.read() != -1) throw new IOException("ZIP 目录条目不能包含数据");
                if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建目录");
              } else {
                if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs())
                  throw new IOException("无法创建目录");
                total += copy(zip, output, Math.min(MAX_FILE, MAX_TOTAL - total));
                files.put(path, output);
              }
              zip.closeEntry();
            }
          }
        } else {
          String type = context.getContentResolver().getType(uri);
          String extension =
              "image/gif".equals(type)
                  ? "gif"
                  : "image/webp".equals(type)
                      ? "webp"
                      : "image/png".equals(type) ? "png" : "image/jpeg".equals(type) ? "jpg" : null;
          if (extension == null) throw new IOException("Android 支持 GIF、WebP、PNG、JPEG 或 ZIP 动作包");
          String path = "companion." + extension;
          File output = new File(stage, path);
          copy(buffered, output, MAX_FILE);
          files.put(path, output);
        }
      }
      JSONObject manifest = normalize(files);
      try (Writer writer =
          new OutputStreamWriter(
              new FileOutputStream(new File(stage, "installed.json")),
              java.nio.charset.StandardCharsets.UTF_8)) {
        writer.write(manifest.toString());
      }
      File destination = new File(parent, "pet-" + UUID.randomUUID());
      if (!stage.renameTo(destination)) throw new IOException("无法完成导入");
      committed = true;
      context
          .getSharedPreferences("pet", 0)
          .edit()
          .putString("selected", destination.getName())
          .apply();
      // Keep only the selected pack; the overlay service is stopped by the caller before replacing
      // it.
      File[] old = parent.listFiles();
      if (old != null)
        for (File file : old)
          if (file.isDirectory() && file.getName().startsWith("pet-") && !file.equals(destination))
            delete(file);
      return new PetPack(destination, manifest);
    } finally {
      if (!committed) delete(stage);
    }
  }

  private static JSONObject normalize(Map<String, File> files) throws Exception {
    String manifestPath = null;
    for (String path : files.keySet())
      if (path.equals("pet.json") || path.endsWith("/pet.json")) {
        if (manifestPath != null) throw new IOException("请选择单只桌宠的素材包");
        manifestPath = path;
      }
    JSONArray clips = new JSONArray();
    String name = "我的小伙伴";
    if (manifestPath != null) {
      JSONObject raw = new JSONObject(readText(files.get(manifestPath)));
      if (!"dsh-pet/pack-v1".equals(raw.optString("format")))
        throw new IOException("不支持的 pet.json 版本");
      name = raw.getString("name");
      JSONArray animations = raw.getJSONArray("animations");
      if (name.trim().isEmpty()
          || name.length() > 80
          || animations.length() < 1
          || animations.length() > 64) throw new IOException("动作包名称或动作数量无效");
      String prefix =
          manifestPath.contains("/")
              ? manifestPath.substring(0, manifestPath.lastIndexOf('/') + 1)
              : "";
      Set<String> clipNames = new HashSet<>();
      for (int i = 0; i < animations.length(); i++) {
        JSONObject source = animations.getJSONObject(i);
        String clipName = source.getString("name");
        if (!clipName.matches("[\\p{L}\\p{N}_-]{1,60}") || !clipNames.add(PackPaths.key(clipName)))
          throw new IOException("动作名称无效或重复");
        JSONObject clip = new JSONObject().put("name", clipName);
        if (source.has("frames")) {
          JSONArray frames = source.getJSONArray("frames"), normalized = new JSONArray();
          Object rawFps = source.get("fps");
          if (!(rawFps instanceof Number)
              || ((Number) rawFps).doubleValue() != Math.rint(((Number) rawFps).doubleValue()))
            throw new IOException("帧率须为 1～30 的整数");
          int fps = ((Number) rawFps).intValue();
          if (source.has("file")
              || frames.length() < 1
              || frames.length() > 300
              || fps < 1
              || fps > 30) throw new IOException("PNG 序列或帧率无效");
          for (int f = 0; f < frames.length(); f++) {
            String path = requireImage(files, prefix, frames.getString(f));
            if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) throw new IOException("序列帧须为 PNG");
            normalized.put(path);
          }
          clip.put("frames", normalized).put("fps", fps);
        } else clip.put("file", requireImage(files, prefix, source.getString("file")));
        clips.put(clip);
      }
    } else {
      for (String path : files.keySet())
        if (path.toLowerCase(Locale.ROOT).matches(".*\\.(model3?\\.json|moc3?|vrm)$"))
          throw new IOException("Live2D/VRM 模型需要专用运行时，请先转换为 GIF/WebP/PNG 序列");
      List<String> images = new ArrayList<>();
      for (String path : files.keySet()) if (PackPaths.image(path)) images.add(path);
      images.sort(
          (a, b) -> {
            java.util.regex.Matcher ma =
                java.util.regex.Pattern.compile(
                        ".*shime(\\d+)\\.png", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(a);
            java.util.regex.Matcher mb =
                java.util.regex.Pattern.compile(
                        ".*shime(\\d+)\\.png", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(b);
            if (ma.matches() && mb.matches())
              return Integer.compare(Integer.parseInt(ma.group(1)), Integer.parseInt(mb.group(1)));
            return a.compareTo(b);
          });
      if (images.isEmpty()) throw new IOException("未找到图片；视频或 Live2D/VRM 模型请先转换为 GIF/WebP/PNG 序列");
      List<String> shimeji = new ArrayList<>();
      for (String image : images)
        if (image.matches("(?i)(.*/)?shime\\d+\\.png")) shimeji.add(image);
      if (!shimeji.isEmpty()) {
        if (shimeji.size() > 300) throw new IOException("单个动作最多包含 300 帧");
        clips.put(
            new JSONObject()
                .put("name", "idle")
                .put("frames", new JSONArray(shimeji))
                .put("fps", 12));
      } else {
        if (images.size() > 64) throw new IOException("请使用 pet.json 定义 PNG 序列，最多 64 个动作");
        for (String image : images)
          clips.put(new JSONObject().put("name", "action-" + clips.length()).put("file", image));
      }
    }
    // Decode bounds before publication to reject corrupt images and decompression bombs.
    Set<String> referenced = new HashSet<>();
    for (int i = 0; i < clips.length(); i++) {
      JSONObject clip = clips.getJSONObject(i);
      if (clip.has("file")) referenced.add(clip.getString("file"));
      else {
        JSONArray frames = clip.getJSONArray("frames");
        for (int j = 0; j < frames.length(); j++) referenced.add(frames.getString(j));
      }
    }
    for (String path : referenced) {
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeFile(files.get(path).getPath(), bounds);
      if (bounds.outWidth < 1
          || bounds.outHeight < 1
          || bounds.outWidth > 2048
          || bounds.outHeight > 2048) throw new IOException("图片无法解码或尺寸超过 2048 像素");
    }
    return new JSONObject().put("name", name).put("animations", clips);
  }

  private static String requireImage(Map<String, File> files, String prefix, String value)
      throws IOException {
    String path = prefix + PackPaths.safe(value);
    if (!files.containsKey(path) || !PackPaths.image(path))
      throw new IOException("动作素材缺失或不是 Android 支持的图片格式");
    return path;
  }

  private static String readText(File file) throws IOException {
    if (file.length() > 65536) throw new IOException("动作清单不能超过 64 KB");
    try (InputStream input = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (bytes.size() + count > 65536) throw new IOException("动作清单过大");
        bytes.write(buffer, 0, count);
      }
      return bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }
  }

  private static long copy(InputStream input, File output, long limit) throws IOException {
    long size = 0;
    byte[] buffer = new byte[16384];
    int count;
    try (OutputStream target = new FileOutputStream(output)) {
      while ((count = input.read(buffer)) != -1) {
        size += count;
        if (size > limit) throw new IOException("素材包超过大小限制");
        target.write(buffer, 0, count);
      }
    }
    return size;
  }

  private static void delete(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) delete(child);
    if (!file.delete() && file.exists())
      android.util.Log.w("DSHPet", "Unable to remove stale import");
  }

  private static final class LimitedStream extends FilterInputStream {
    private long remaining;

    LimitedStream(InputStream input, long limit) {
      super(input);
      remaining = limit;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value != -1 && --remaining < 0) throw new IOException("压缩文件过大");
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, length);
      if (count > 0 && (remaining -= count) < 0) throw new IOException("压缩文件过大");
      return count;
    }
  }
}
