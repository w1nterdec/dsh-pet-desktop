package com.dshpet.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AndroidIntegrationTest {
  private Context context;

  @Before
  public void setUp() {
    Assume.assumeTrue(
        "Run data-mutating fixtures only on an isolated emulator",
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private void screenshot(String name) {
    File directory = new File(context.getExternalFilesDir(null), "qa");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    assertTrue(
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(new File(directory, name + ".png")));
  }

  private File zip(String name, Map<String, byte[]> files) throws Exception {
    File output = new File(context.getCacheDir(), name);
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
      for (Map.Entry<String, byte[]> item : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(item.getKey()));
        zip.write(item.getValue());
        zip.closeEntry();
      }
    }
    return output;
  }

  private byte[] png() throws Exception {
    Bitmap image = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(image);
    Paint paint = new Paint();
    paint.setColor(Color.RED);
    canvas.drawRect(0, 0, 32, 32, paint);
    paint.setColor(Color.BLUE);
    canvas.drawRect(32, 0, 64, 32, paint);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
    image.recycle();
    return output.toByteArray();
  }

  @Test
  public void darkChatRejectsDisabledAiWithoutWritingHistory() throws Exception {
    AiRepository repository = new AiRepository(context);
    AiSettings saved = repository.settings();
    AiSettings disabled = AiSettings.from(saved.json());
    disabled.enabled = false;
    repository.save(disabled);
    String theme = context.getSharedPreferences("app", 0).getString("theme", "system");
    context.getSharedPreferences("app", 0).edit().putString("theme", "dark").commit();
    PetProfile profile = new PetLibrary(context).add("builtin", "夜间伙伴");
    Activity activity = null;
    try {
      activity =
          InstrumentationRegistry.getInstrumentation()
              .startActivitySync(
                  new Intent(context, ChatActivity.class)
                      .putExtra("profileId", profile.id)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Activity chat = activity;
      assertTrue(Ui.dark(chat));
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                ((EditText) chat.findViewById(R.id.chat_input)).setText("测试消息");
                chat.findViewById(R.id.chat_send).performClick();
                chat.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
              });
      assertTrue(
          UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
              .wait(Until.hasObject(By.textContains("AI 尚未启用")), 5000));
      assertEquals(0, repository.history(profile.id).length());
      SystemClock.sleep(500);
      screenshot("dark-chat-disabled-ai");
    } finally {
      if (activity != null) {
        Activity chat = activity;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(chat::finish);
      }
      new PetLibrary(context).remove(profile.id);
      repository.save(saved);
      context.getSharedPreferences("app", 0).edit().putString("theme", theme).commit();
    }
  }

  @Test
  public void encryptedVaultAuthenticatesDocumentsAndNeverStoresPlainCredentials()
      throws Exception {
    EncryptedVault vault = new EncryptedVault(context);
    try {
      vault.write(
          "instrument-secret", new JSONObject().put("apiKey", "INSTRUMENT-SECRET-SENTINEL"));
      File file = new File(context.getNoBackupFilesDir(), "private/instrument-secret.enc");
      byte[] bytes;
      try (InputStream input = new FileInputStream(file)) {
        bytes = JsonFiles.read(input, 65536);
      }
      assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("INSTRUMENT-SECRET"));
      assertEquals(
          "INSTRUMENT-SECRET-SENTINEL", vault.read("instrument-secret").getString("apiKey"));
      try (OutputStream copy =
          new FileOutputStream(new File(file.getParentFile(), "instrument-other.enc"))) {
        copy.write(bytes);
      }
      assertThrows(Exception.class, () -> vault.read("instrument-other"));
      bytes[bytes.length - 1] ^= 1;
      try (OutputStream copy = new FileOutputStream(file)) {
        copy.write(bytes);
      }
      assertThrows(Exception.class, () -> vault.read("instrument-secret"));
    } finally {
      vault.delete("instrument-secret");
      vault.delete("instrument-other");
    }
  }

  @Test
  public void packRoundTripPreservesOldImportsAndSpriteSheetMetadata() throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    JSONObject manifest =
        new JSONObject()
            .put("format", "dsh-pet/pack-v1")
            .put("name", "测试伙伴")
            .put(
                "animations",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("name", "idle")
                            .put("label", "精灵图待机")
                            .put("role", "idle")
                            .put(
                                "sheet",
                                new JSONObject()
                                    .put("file", "sheet.png")
                                    .put("frameWidth", 32)
                                    .put("frameHeight", 32)
                                    .put("columns", 2)
                                    .put("count", 2)
                                    .put("fps", 12))));
    files.put("fox/pet.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
    files.put("fox/sheet.png", png());
    PetPack first = PetPack.install(context, Uri.fromFile(zip("sprite.zip", files)));
    assertEquals(2, first.animations.getJSONObject(0).getJSONObject("sheet").getInt("count"));
    File exported = new File(context.getCacheDir(), "roundtrip.zip");
    try (OutputStream output = new FileOutputStream(exported)) {
      first.export(context, output, "导出伙伴");
    }
    PetPack second = PetPack.install(context, Uri.fromFile(exported));
    assertTrue(first.directory.isDirectory());
    assertNotEquals(first.directory, second.directory);
    assertEquals("导出伙伴", second.name);
    assertEquals("精灵图待机", second.animations.getJSONObject(0).getString("label"));
    assertThrows(
        Exception.class,
        () ->
            PetPack.install(
                context,
                Uri.fromFile(
                    zip("traversal.zip", Collections.singletonMap("../escape.png", png())))));
    files.clear();
    files.put("fox.model3.json", "{}".getBytes(StandardCharsets.UTF_8));
    files.put("texture.png", png());
    assertThrows(
        Exception.class, () -> PetPack.install(context, Uri.fromFile(zip("model.zip", files))));
  }

  @Test
  public void libraryKeepsIndependentProfilesAndCapsSimultaneousWindows() throws Exception {
    PetLibrary library = new PetLibrary(context);
    List<PetProfile> created = new ArrayList<>();
    try {
      library.hideAll();
      for (int i = 0; i < 5; i++) created.add(library.add("builtin", "伙伴" + i));
      library.edit(created.get(0).id, "小狐", 144, "测试人设", true, false);
      library.position(created.get(0).id, 100, 200);
      for (int i = 0; i < 4; i++) library.visibility(created.get(i).id, true);
      assertThrows(Exception.class, () -> library.visibility(created.get(4).id, true));
      assertEquals(100, library.find(created.get(0).id).x);
      assertEquals("小狐", library.find(created.get(0).id).name);
      AiRepository ai = new AiRepository(context);
      ai.append(created.get(0).id, "你好", "你好呀");
      assertEquals(0, ai.history(created.get(1).id).length());
      assertEquals(0, ai.context(created.get(0).id, 0).length());
      assertEquals(2, ai.context(created.get(0).id, 5).length());
    } finally {
      library.hideAll();
      for (PetProfile profile : created) {
        new AiRepository(context).clear(profile.id);
        library.remove(profile.id);
      }
    }
  }

  @Test
  public void realControlCentreRendersFullActionsAndManagesTwoOverlayWindows() throws Exception {
    Assume.assumeTrue(
        android.os.Build.HARDWARE.contains("ranchu")
            || android.os.Build.HARDWARE.contains("goldfish"));
    try (ParcelFileDescriptor command =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(
                    "appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(command)) {
      JsonFiles.read(input, 65536);
    }
    PetLibrary library = new PetLibrary(context);
    library.hideAll();
    PetProfile first = library.add("builtin", "模拟器伙伴 A"),
        second = library.add("builtin", "模拟器伙伴 B");
    Activity activity =
        InstrumentationRegistry.getInstrumentation()
            .startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screenshot("control-centre");
      assertNotNull(activity.findViewById(R.id.pet_list));
      assertTrue(PetPack.load(context, "builtin").animations.length() >= 100);
      library.visibility(first.id, true);
      library.visibility(second.id, true);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> context.startForegroundService(new Intent(context, PetOverlayService.class)));
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (PetOverlayService.runningCount != 2 && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(100);
      assertEquals(2, PetOverlayService.runningCount);
      SystemClock.sleep(1000);
      screenshot("two-pets");
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () ->
                  context.startService(
                      new Intent(context, PetOverlayService.class)
                          .setAction("play")
                          .putExtra("profileId", first.id)
                          .putExtra("clip", PetPackClip.first(context))));
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> activity.findViewById(R.id.nav_actions).performClick());
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      screenshot("actions");
      Activity settings =
          InstrumentationRegistry.getInstrumentation()
              .startActivitySync(
                  new Intent(context, AiSettingsActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      assertNotNull(settings.findViewById(R.id.ai_key));
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () ->
                  settings
                      .getWindow()
                      .clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE));
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      SystemClock.sleep(500);
      screenshot("ai-settings");
      InstrumentationRegistry.getInstrumentation().runOnMainSync(settings::finish);
    } finally {
      context.stopService(new Intent(context, PetOverlayService.class));
      library.visibility(first.id, false);
      library.visibility(second.id, false);
      library.remove(first.id);
      library.remove(second.id);
      InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
    }
  }

  private static final class PetPackClip {
    static String first(Context context) {
      try {
        return PetPack.load(context, "builtin").animations.getJSONObject(0).getString("name");
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }
  }
}
