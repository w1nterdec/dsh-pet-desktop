package com.dshpet.android;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;

/** The same portable path restrictions as the desktop importer. */
public final class PackPaths {
  private static final java.util.regex.Pattern FORBIDDEN =
      java.util.regex.Pattern.compile("[<>:\"|?*\\p{Cc}]");

  private PackPaths() {}

  public static String safe(String name) throws IOException {
    if (name == null || name.isEmpty() || name.length() > 240 || name.contains("\\"))
      throw new IOException("不安全的素材路径");
    for (String part : name.split("/", -1)) {
      if (part.isEmpty()
          || part.equals(".")
          || part.equals("..")
          || FORBIDDEN.matcher(part).find()
          || part.endsWith(".")
          || part.endsWith(" ")
          || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))
        throw new IOException("不安全的素材路径");
    }
    return name;
  }

  public static String key(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }

  public static boolean image(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(png|gif|webp|jpg|jpeg)$");
  }
}
