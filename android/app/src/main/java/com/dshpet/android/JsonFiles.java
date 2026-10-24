package com.dshpet.android;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class JsonFiles {
  private JsonFiles() {}

  static byte[] read(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (output.size() + count > limit) throw new IOException("文件或响应超出大小限制");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  static JSONObject read(File file, int limit) throws Exception {
    try (InputStream input = new AtomicFile(file).openRead()) {
      return new JSONObject(new String(read(input, limit), StandardCharsets.UTF_8));
    }
  }

  static void write(File file, JSONObject data) throws IOException {
    if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
      throw new IOException("无法创建数据目录");
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream output = null;
    try {
      output = atomic.startWrite();
      output.write(data.toString().getBytes(StandardCharsets.UTF_8));
      atomic.finishWrite(output);
    } catch (IOException error) {
      if (output != null) atomic.failWrite(output);
      throw error;
    }
  }
}
