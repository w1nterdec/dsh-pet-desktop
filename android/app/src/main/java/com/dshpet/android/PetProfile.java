package com.dshpet.android;

import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

public final class PetProfile {
  public String id, packId, name, persona;
  public int size, x, y;
  public boolean visible, fixed, whisper;

  static boolean validId(String id) {
    return id != null && id.matches("buddy-[a-f0-9-]{36}");
  }

  static boolean validPack(String id) {
    return "builtin".equals(id) || id != null && id.matches("pet-[a-f0-9-]{36}");
  }

  static PetProfile create(String pack, String name) throws Exception {
    return from(
        new JSONObject()
            .put("id", "buddy-" + UUID.randomUUID())
            .put("packId", pack)
            .put("name", name));
  }

  public static PetProfile from(JSONObject json) throws Exception {
    PetProfile profile = new PetProfile();
    profile.id = json.getString("id");
    profile.packId = json.getString("packId");
    profile.name = json.getString("name").trim();
    profile.persona = json.optString("persona", "温柔自然地陪伴用户，说话简短，不要声称你能操作手机。");
    profile.size = json.optInt("size", 128);
    profile.x = json.optInt("x", 24);
    profile.y = json.optInt("y", 160);
    profile.visible = json.optBoolean("visible", false);
    profile.fixed = json.optBoolean("fixed", true);
    profile.whisper = json.optBoolean("whisper", false);
    if (!validId(profile.id)
        || !validPack(profile.packId)
        || profile.name.isEmpty()
        || profile.name.length() > 80
        || profile.persona.length() > 4000
        || profile.size < 64
        || profile.size > 256) throw new IOException("桌宠配置无效");
    return profile;
  }

  public JSONObject json() throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("packId", packId)
        .put("name", name)
        .put("persona", persona)
        .put("size", size)
        .put("x", x)
        .put("y", y)
        .put("visible", visible)
        .put("fixed", fixed)
        .put("whisper", whisper);
  }
}
