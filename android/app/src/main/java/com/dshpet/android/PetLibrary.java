package com.dshpet.android;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** One atomic metadata document; updates preserve positions written by active overlays. */
public final class PetLibrary {
  public static final int MAX_PETS = 12, MAX_VISIBLE = 4;
  private static final Object LOCK = new Object();
  private final Context context;
  private final File file;

  public PetLibrary(Context context) {
    this.context = context.getApplicationContext();
    file = new File(context.getFilesDir(), "pets/library.json");
  }

  public List<PetProfile> list() throws Exception {
    synchronized (LOCK) {
      if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
        PetPack legacy = PetPack.selected(context);
        if (legacy == null) legacy = PetPack.useBuiltin(context);
        PetProfile first = PetProfile.create(legacy.directory.getName(), legacy.name);
        place(first, 0);
        first.x = context.getSharedPreferences("pet", 0).getInt("x", first.x);
        first.y = context.getSharedPreferences("pet", 0).getInt("y", first.y);
        first.size =
            Math.max(64, Math.min(256, context.getSharedPreferences("pet", 0).getInt("size", 128)));
        save(Collections.singletonList(first));
      }
      JSONObject data = JsonFiles.read(file, 128 * 1024);
      if (data.optInt("version") != 1) throw new IOException("不支持的桌宠库版本，请保留文件并检查安装版本");
      JSONArray array = data.getJSONArray("pets");
      if (array.length() > MAX_PETS) throw new IOException("桌宠库数量异常");
      List<PetProfile> profiles = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < array.length(); i++) {
        PetProfile profile = PetProfile.from(array.getJSONObject(i));
        if (!ids.add(profile.id)) throw new IOException("桌宠编号重复");
        profiles.add(profile);
      }
      return profiles;
    }
  }

  private void save(List<PetProfile> profiles) throws Exception {
    JSONArray array = new JSONArray();
    int visible = 0;
    for (PetProfile profile : profiles) {
      array.put(profile.json());
      if (profile.visible) visible++;
    }
    if (visible > MAX_VISIBLE) throw new IOException("为控制耗电，最多同时显示 4 只桌宠");
    JsonFiles.write(file, new JSONObject().put("version", 1).put("pets", array));
  }

  public PetProfile find(String id) throws Exception {
    for (PetProfile profile : list()) if (profile.id.equals(id)) return profile;
    throw new IOException("桌宠已被移除");
  }

  public PetProfile add(String packId, String name) throws Exception {
    synchronized (LOCK) {
      PetPack.load(context, packId);
      List<PetProfile> profiles = list();
      if (profiles.size() >= MAX_PETS) throw new IOException("最多保存 12 只桌宠");
      PetProfile profile = PetProfile.create(packId, name);
      place(profile, profiles.size());
      profiles.add(profile);
      save(profiles);
      return profile;
    }
  }

  public void edit(String id, String name, int size, String persona, boolean fixed, boolean whisper)
      throws Exception {
    synchronized (LOCK) {
      List<PetProfile> profiles = list();
      PetProfile found = null;
      for (PetProfile profile : profiles)
        if (profile.id.equals(id)) {
          found = profile;
          profile.name = name.trim();
          profile.size = size;
          profile.persona = persona;
          profile.fixed = fixed;
          profile.whisper = whisper;
          PetProfile.from(profile.json());
        }
      if (found == null) throw new IOException("桌宠已被移除");
      save(profiles);
    }
  }

  public void visibility(String id, boolean visible) throws Exception {
    synchronized (LOCK) {
      List<PetProfile> profiles = list();
      for (PetProfile profile : profiles) if (profile.id.equals(id)) profile.visible = visible;
      save(profiles);
    }
  }

  public void position(String id, int x, int y) throws Exception {
    synchronized (LOCK) {
      List<PetProfile> profiles = list();
      for (PetProfile profile : profiles)
        if (profile.id.equals(id)) {
          profile.x = x;
          profile.y = y;
        }
      save(profiles);
    }
  }

  public void recall() throws Exception {
    synchronized (LOCK) {
      List<PetProfile> profiles = list();
      int index = 0;
      for (PetProfile profile : profiles)
        if (profile.visible) {
          place(profile, index++);
        }
      save(profiles);
    }
  }

  private void place(PetProfile profile, int index) {
    float density = context.getResources().getDisplayMetrics().density;
    int width = context.getResources().getDisplayMetrics().widthPixels;
    int height = context.getResources().getDisplayMetrics().heightPixels;
    int step = Math.round((profile.size + 12) * density);
    int margin = Math.round(12 * density);
    int columns = Math.max(1, (width - margin) / step);
    profile.x = margin + (index % columns) * step;
    profile.y =
        Math.min(
            Math.round(160 * density) + (index / columns) * step, Math.max(margin, height - step));
  }

  public void hideAll() throws Exception {
    synchronized (LOCK) {
      List<PetProfile> profiles = list();
      for (PetProfile profile : profiles) profile.visible = false;
      save(profiles);
    }
  }

  public void remove(String id) throws Exception {
    synchronized (LOCK) {
      List<PetProfile> profiles = list();
      profiles.removeIf(profile -> profile.id.equals(id));
      save(profiles);
    }
  }
}
