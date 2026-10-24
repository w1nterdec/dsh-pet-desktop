package com.dshpet.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native control centre for independently configured pets, actions, imports and AI. */
public final class MainActivity extends Activity {
  private static final int IMPORT = 10, EXPORT = 12;
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static volatile boolean busy;
  private static volatile String operationMessage = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private PetLibrary library;
  private LinearLayout body;
  private LinearLayout navigation;
  private boolean receiverRegistered;
  private final BroadcastReceiver serviceState =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (page.equals("pets")) render();
        }
      };
  private TextView operation;
  private String page = "pets", exportPack, exportName;
  private final Runnable progress =
      new Runnable() {
        @Override
        public void run() {
          if (operation != null) {
            operation.setText(operationMessage);
            operation.setVisibility(operationMessage.isEmpty() ? View.GONE : View.VISIBLE);
          }
          if (busy) handler.postDelayed(this, 400);
          else render();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    Ui.theme(this);
    super.onCreate(state);
    library = new PetLibrary(this);
    if (getIntent().getStringExtra("page") != null) page = getIntent().getStringExtra("page");
    if (state != null) {
      page = state.getString("page", "pets");
      exportPack = state.getString("exportPack");
      exportName = state.getString("exportName");
    }
    android.widget.LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.background(this));
    setContentView(root);
    Ui.insets(root);
    TextView brand = Ui.text(this, "◕  桌宠之家", 23);
    brand.setTypeface(null, android.graphics.Typeface.BOLD);
    brand.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), 0, Ui.dp(this, 8));
    root.addView(brand);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    body = Ui.column(this);
    body.setId(R.id.main_content);
    body.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    navigation = Ui.row(this);
    navigation.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), Ui.dp(this, 8));
    nav(navigation, "伙伴", "pets", R.id.nav_pets);
    nav(navigation, "动作", "actions", R.id.nav_actions);
    nav(navigation, "设置", "settings", R.id.nav_settings);
    root.addView(navigation);
    render();
  }

  private void nav(LinearLayout row, String label, String key, int id) {
    Button button =
        Ui.button(
            row,
            label,
            () -> {
              page = key;
              render();
            });
    button.setId(id);
    button.setTag(key);
  }

  private void render() {
    if (body == null || isDestroyed()) return;
    body.removeAllViews();
    if (navigation != null)
      for (int i = 0; i < navigation.getChildCount(); i++)
        navigation.getChildAt(i).setSelected(page.equals(navigation.getChildAt(i).getTag()));
    operation = Ui.text(this, operationMessage, 14);
    operation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    operation.setVisibility(operationMessage.isEmpty() ? View.GONE : View.VISIBLE);
    body.addView(operation);
    try {
      if (page.equals("actions")) actions();
      else if (page.equals("settings")) settings();
      else pets();
    } catch (Exception error) {
      body.addView(Ui.text(this, Ui.safeError(error) + "。原有配置未被覆盖。", 16));
    }
  }

  private void pets() throws Exception {
    List<PetProfile> profiles = library.list();
    long visible =
        PetOverlayService.running
            ? profiles.stream().filter(profile -> profile.visible).count()
            : 0;
    Ui.heading(body, "让陪伴，跟着你走。", profiles.size() + " 位小伙伴 · " + visible + " 位显示中 · 最多同时显示 4 位");
    LinearLayout tools = Ui.card(body);
    Ui.button(tools, "＋ 添加内置桌宠 · 106 个动作", () -> perform(() -> library.add("builtin", "蓝毛小女仆")))
        .setId(R.id.add_builtin);
    Ui.button(tools, "导入 GIF / WebP / 图片 / ZIP 素材包", this::chooseImport);
    Ui.button(tools, "添加已导入素材中的伙伴", this::choosePack);
    Ui.button(
        tools,
        "召回正在显示的伙伴",
        () ->
            perform(
                () -> {
                  library.recall();
                  refreshOverlay(false);
                }));
    LinearLayout list = Ui.column(this);
    list.setId(R.id.pet_list);
    body.addView(list);
    for (PetProfile profile : profiles) {
      boolean showing = profile.visible && PetOverlayService.running;
      LinearLayout card = Ui.card(list);
      Ui.cardHeading(
          card,
          profile.name,
          profile.packId.equals("builtin") ? "内置伙伴 · 独立人设与聊天记忆" : "导入伙伴 · 原素材保存在本机");
      card.addView(
          Ui.text(
              this,
              profile.size
                  + " dp · "
                  + (showing ? "正在陪伴" : "已收起")
                  + (profile.fixed ? " · 原地待着" : " · 自主漫游"),
              14));
      LinearLayout mainActions = Ui.row(this);
      card.addView(mainActions);
      Ui.button(
          mainActions,
          showing ? "收起" : "显示",
          () -> {
            if (busy) {
              Ui.message(this, "请等待素材操作完成");
              return;
            }
            if (!showing && !overlayPermission()) return;
            perform(
                () -> {
                  library.visibility(profile.id, !showing);
                  refreshOverlay(!showing);
                });
          });
      Ui.button(mainActions, "调整", () -> edit(profile));
      LinearLayout moreActions = Ui.row(this);
      card.addView(moreActions);
      Ui.button(
          moreActions,
          "聊天",
          () ->
              startActivity(
                  new Intent(this, ChatActivity.class).putExtra("profileId", profile.id)));
      Ui.button(moreActions, "导出素材包", () -> chooseExport(profile));
      Ui.button(
          card,
          "移除伙伴",
          () ->
              Ui.confirm(
                  this,
                  "移除伙伴",
                  "停止显示并移除这只伙伴及其本机聊天记忆。素材包会保留，其他伙伴不受影响。",
                  () ->
                      perform(
                          () -> {
                            AiCoordinator.clear(this, profile.id);
                            library.remove(profile.id);
                            refreshOverlay(false);
                          })));
    }
    Ui.button(
        body,
        "停止所有桌宠",
        () ->
            perform(
                () -> {
                  library.hideAll();
                  stopService(new Intent(this, PetOverlayService.class));
                }));
  }

  private void edit(PetProfile profile) {
    LinearLayout content = Ui.column(this);
    int padding = Ui.dp(this, 20);
    content.setPadding(padding, 0, padding, padding);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    EditText name = Ui.field(content, "伙伴名字", profile.name, false);
    name.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
    EditText size = Ui.field(content, "大小（64～256 dp）", String.valueOf(profile.size), false);
    size.setInputType(InputType.TYPE_CLASS_NUMBER);
    EditText persona = Ui.field(content, "性格与人设", profile.persona, true);
    persona.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(4000)});
    Switch fixed = Ui.toggle(content, "原地待着（仍可拖拽和抛掷）", profile.fixed);
    Switch whisper = Ui.toggle(content, "自动问候（AI 启用时，每 10 分钟最多一次，会产生调用费用）", profile.whisper);
    TextView error = Ui.text(this, "", 14);
    content.addView(error);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("调整小伙伴")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      try {
                        library.edit(
                            profile.id,
                            name.getText().toString(),
                            Integer.parseInt(size.getText().toString()),
                            persona.getText().toString(),
                            fixed.isChecked(),
                            whisper.isChecked());
                        refreshOverlay(false);
                        dialog.dismiss();
                        render();
                      } catch (NumberFormatException invalid) {
                        error.setText("大小须为 64～256 的整数");
                      } catch (Exception invalid) {
                        error.setText(Ui.safeError(invalid));
                      }
                    }));
    dialog.show();
  }

  private void choosePack() {
    if (busy) return;
    try {
      File root = new File(getFilesDir(), "pets");
      File[] directories = root.listFiles();
      List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
      if (directories != null)
        for (File file : directories)
          if (file.isDirectory() && file.getName().matches("pet-[a-f0-9-]{36}")) {
            try {
              PetPack pack = PetPack.load(this, file.getName());
              ids.add(file.getName());
              labels.add(pack.name);
            } catch (Exception ignored) {
              /* Keep damaged files for manual recovery. */
            }
          }
      if (ids.isEmpty()) {
        Ui.message(this, "还没有导入素材包，请先导入一个 ZIP 或图片");
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("选择已导入的素材")
          .setItems(
              labels.toArray(new String[0]),
              (dialog, which) -> perform(() -> library.add(ids.get(which), labels.get(which))))
          .setNegativeButton("取消", null)
          .show();
    } catch (Exception error) {
      Ui.message(this, Ui.safeError(error));
    }
  }

  private void actions() throws Exception {
    Ui.heading(body, "每个动作，都有小情绪。", "完整内置动作库可点播；导入素材包中的动作也会出现在这里。");
    List<PetProfile> profiles = library.list();
    if (profiles.isEmpty()) {
      body.addView(Ui.text(this, "请先添加一只伙伴", 16));
      return;
    }
    Spinner buddies = new Spinner(this);
    buddies.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            profiles.stream().map(profile -> profile.name).toArray(String[]::new)));
    body.addView(buddies);
    LinearLayout selection = Ui.card(body);
    buddies.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          @Override
          public void onNothingSelected(android.widget.AdapterView<?> view) {}

          @Override
          public void onItemSelected(
              android.widget.AdapterView<?> view, View row, int index, long id) {
            selection.removeAllViews();
            try {
              PetProfile profile = profiles.get(index);
              PetPack pack = PetPack.load(MainActivity.this, profile.packId);
              List<String> names = new ArrayList<>();
              for (int i = 0; i < pack.animations.length(); i++) {
                JSONObject clip = pack.animations.getJSONObject(i);
                names.add(clip.optString("label", clip.getString("name")));
              }
              selection.addView(Ui.text(MainActivity.this, names.size() + " 个动作 · 可输入关键字查找", 14));
              AutoCompleteTextView clip = new AutoCompleteTextView(MainActivity.this);
              clip.setContentDescription("查找动作");
              clip.setHint("例如：待机、跳跃、写代码");
              clip.setThreshold(0);
              clip.setAdapter(
                  new ArrayAdapter<>(
                      MainActivity.this, android.R.layout.simple_dropdown_item_1line, names));
              selection.addView(clip);
              clip.setText(names.get(0), false);
              Ui.button(selection, "展开动作清单", clip::showDropDown);
              Ui.button(
                  selection,
                  "播放选择动作",
                  () -> {
                    int selected = names.indexOf(clip.getText().toString());
                    if (selected < 0) {
                      Ui.message(MainActivity.this, "请从动作清单中选择");
                      return;
                    }
                    if (!overlayPermission()) return;
                    perform(
                        () -> {
                          library.visibility(profile.id, true);
                          startForegroundService(
                              new Intent(MainActivity.this, PetOverlayService.class)
                                  .setAction("play")
                                  .putExtra("profileId", profile.id)
                                  .putExtra(
                                      "clip",
                                      pack.animations.getJSONObject(selected).getString("name")));
                        });
                  });
            } catch (Exception error) {
              selection.addView(Ui.text(MainActivity.this, Ui.safeError(error), 15));
            }
          }
        });
    String selectedId = getIntent().getStringExtra("profileId");
    for (int i = 0; i < profiles.size(); i++)
      if (profiles.get(i).id.equals(selectedId)) buddies.setSelection(i);
  }

  private void settings() {
    Ui.heading(body, "按你的习惯来。", "设置只作用于当前手机，不会自动上传。");
    LinearLayout ai = Ui.card(body);
    Ui.heading(ai, "AI 与对话", "独立记忆、流式回复、取消生成、OpenAI 兼容 / Responses / Anthropic。");
    Ui.button(ai, "配置 AI 服务商", () -> startActivity(new Intent(this, AiSettingsActivity.class)));
    LinearLayout appearance = Ui.card(body);
    appearance.addView(Ui.text(this, "界面主题", 18));
    Spinner theme = new Spinner(this);
    theme.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"跟随系统", "浅色", "深色"}));
    String saved = getSharedPreferences("app", 0).getString("theme", "system");
    theme.setSelection(saved.equals("dark") ? 2 : saved.equals("light") ? 1 : 0);
    appearance.addView(theme);
    Ui.button(
        appearance,
        "应用主题",
        () -> {
          getSharedPreferences("app", 0)
              .edit()
              .putString(
                  "theme",
                  theme.getSelectedItemPosition() == 2
                      ? "dark"
                      : theme.getSelectedItemPosition() == 1 ? "light" : "system")
              .apply();
          recreate();
        });
    LinearLayout behaviour = Ui.card(body);
    preference(behaviour, "拖放后启用重力与反弹", "physics", true);
    preference(behaviour, "允许不同伙伴碰撞", "collision", false);
    preference(behaviour, "省电模式（降低移动更新频率）", "battery", false);
    Ui.button(
        behaviour,
        "管理悬浮窗权限",
        () ->
            startActivity(
                new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
    if (Build.VERSION.SDK_INT >= 33)
      Ui.button(
          behaviour,
          "允许通知栏停止入口",
          () -> requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 20));
    LinearLayout about = Ui.card(body);
    Ui.heading(about, "关于与隐私", "Android 0.2.0 · 本机优先");
    about.addView(
        Ui.text(
            this,
            "素材和配置保存在应用私有目录。AI 密钥与聊天记忆使用 Android Keystore 加密。只有用户启用的 AI 请求会联网，关闭 AI"
                + " 可继续离线陪伴。没有统计上报、开机自启或无障碍权限。",
            14));
    Ui.button(
        about,
        "开源许可",
        () -> {
          try (InputStream input = getAssets().open("LICENSE.txt")) {
            new AlertDialog.Builder(this)
                .setTitle("MIT · PC2005-cloud / dsh-pet")
                .setMessage(
                    new String(
                        JsonFiles.read(input, 32768), java.nio.charset.StandardCharsets.UTF_8))
                .setPositiveButton("关闭", null)
                .show();
          } catch (Exception error) {
            Ui.message(this, Ui.safeError(error));
          }
        });
  }

  private void preference(LinearLayout parent, String label, String key, boolean fallback) {
    Switch toggle =
        Ui.toggle(parent, label, getSharedPreferences("app", 0).getBoolean(key, fallback));
    toggle.setOnCheckedChangeListener(
        (control, value) -> {
          getSharedPreferences("app", 0).edit().putBoolean(key, value).apply();
          refreshOverlay(false);
        });
  }

  private boolean overlayPermission() {
    if (!Settings.canDrawOverlays(this)) {
      Ui.message(this, "请先在系统设置中允许显示在其他应用上层");
      startActivity(
          new Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
      return false;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 20);
    return true;
  }

  private void refreshOverlay(boolean start) {
    if (start) startForegroundService(new Intent(this, PetOverlayService.class));
    else if (PetOverlayService.running)
      startService(new Intent(this, PetOverlayService.class).setAction("refresh"));
  }

  private interface Operation {
    void run() throws Exception;
  }

  private void perform(Operation operation) {
    try {
      operation.run();
      render();
    } catch (Exception error) {
      Ui.message(this, Ui.safeError(error));
    }
  }

  private void chooseImport() {
    if (busy) {
      Ui.message(this, "请等待素材操作完成");
      return;
    }
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[] {
                  "application/zip",
                  "application/x-zip-compressed",
                  "image/gif",
                  "image/webp",
                  "image/png",
                  "image/jpeg"
                }),
        IMPORT);
  }

  private void chooseExport(PetProfile profile) {
    if (busy) return;
    exportPack = profile.packId;
    exportName = profile.name;
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(
                Intent.EXTRA_TITLE,
                "DSH-Pet-" + profile.name.replaceAll("[\\/:*?\"<>|]", "_") + ".zip"),
        EXPORT);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
    Uri uri = data.getData();
    Context context = getApplicationContext();
    String packId = exportPack, name = exportName;
    busy = true;
    operationMessage = request == IMPORT ? "正在校验并导入素材…" : "正在导出素材包…";
    handler.removeCallbacks(progress);
    handler.post(progress);
    WORKER.execute(
        () -> {
          try {
            if (request == IMPORT) {
              PetLibrary library = new PetLibrary(context);
              if (library.list().size() >= PetLibrary.MAX_PETS)
                throw new IOException("请先移除不再使用的伙伴");
              PetPack pack = PetPack.install(context, uri);
              library.add(pack.directory.getName(), pack.name);
              operationMessage = "导入成功，已有素材和伙伴已保留";
            } else if (request == EXPORT && packId != null) {
              try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("无法写入所选位置");
                PetPack.load(context, packId).export(context, output, name);
                operationMessage = "素材包已导出，不包含 API 密钥或聊天记录";
              }
            }
          } catch (Exception error) {
            operationMessage = Ui.safeError(error);
          } finally {
            busy = false;
          }
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    androidx.core.content.ContextCompat.registerReceiver(
        this,
        serviceState,
        new IntentFilter(PetOverlayService.STATE_CHANGED),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    receiverRegistered = true;
    render();
    handler.removeCallbacks(progress);
    if (busy) handler.post(progress);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    page = intent.getStringExtra("page") == null ? "pets" : intent.getStringExtra("page");
    render();
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(progress);
    if (receiverRegistered) {
      unregisterReceiver(serviceState);
      receiverRegistered = false;
    }
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page", page);
    state.putString("exportPack", exportPack);
    state.putString("exportName", exportName);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
