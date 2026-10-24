package com.dshpet.android;

import android.content.Context;
import java.io.IOException;
import org.json.*;

final class AiRepository {
  private static final Object LOCK = new Object();
  private final EncryptedVault vault;

  AiRepository(Context context) {
    vault = new EncryptedVault(context);
  }

  AiSettings settings() throws Exception {
    return AiSettings.from(vault.read("ai-settings"));
  }

  void save(AiSettings settings) throws Exception {
    settings.validate(settings.enabled);
    vault.write("ai-settings", settings.json());
  }

  void clearKey() throws Exception {
    AiSettings settings = settings();
    settings.apiKey = "";
    settings.enabled = false;
    save(settings);
  }

  JSONArray history(String profileId) throws Exception {
    requireId(profileId);
    JSONObject data = vault.read("history-" + profileId);
    return data.optJSONArray("messages") == null ? new JSONArray() : data.getJSONArray("messages");
  }

  JSONArray context(String profileId, int rounds) throws Exception {
    JSONArray saved = history(profileId), result = new JSONArray();
    int count = Math.min(rounds * 2, saved.length());
    for (int i = saved.length() - count; i < saved.length(); i++) {
      JSONObject row = saved.getJSONObject(i);
      result.put(
          new JSONObject()
              .put("role", row.getString("role"))
              .put("content", row.getString("content")));
    }
    return result;
  }

  void append(String id, String user, String reply) throws Exception {
    synchronized (LOCK) {
      JSONArray old = history(id), rows = new JSONArray();
      for (int i = Math.max(0, old.length() - 98); i < old.length(); i++)
        rows.put(old.getJSONObject(i));
      rows.put(
          new JSONObject()
              .put("role", "user")
              .put("content", user)
              .put("time", System.currentTimeMillis()));
      rows.put(
          new JSONObject()
              .put("role", "assistant")
              .put("content", reply)
              .put("time", System.currentTimeMillis()));
      while (rows.length() > 2
          && rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              > 300 * 1024) {
        rows.remove(0);
        rows.remove(0);
      }
      vault.write("history-" + id, new JSONObject().put("messages", rows));
    }
  }

  void clear(String id) throws Exception {
    requireId(id);
    synchronized (LOCK) {
      vault.delete("history-" + id);
    }
  }

  private static void requireId(String id) throws IOException {
    if (!PetProfile.validId(id)) throw new IOException("对话目标无效");
  }
}
