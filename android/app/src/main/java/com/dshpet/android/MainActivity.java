package com.dshpet.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final int IMPORT = 10;
  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static volatile boolean importing;
  private static volatile String lastImportMessage;
  private final android.os.Handler refresh =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshImport =
      new Runnable() {
        @Override
        public void run() {
          importButton.setEnabled(!importing);
          if (importing) {
            status.setText(R.string.importing);
            refresh.postDelayed(this, 500);
          } else if (lastImportMessage != null) status.setText(lastImportMessage);
          else updateStatus();
        }
      };
  private TextView status;
  private Button importButton;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(content);
    setContentView(scroll);
    // Android 15+ edge-to-edge: keep controls out of system bars and cutouts.
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    TextView title = label(getString(R.string.app_name), 30);
    content.addView(title);
    content.addView(label(getString(R.string.intro), 17));
    status = label("", 18);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    content.addView(status);
    button(
        content,
        R.string.overlay_permission,
        () ->
            startActivity(
                new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
    importButton =
        button(
            content,
            R.string.import_pet,
            () -> {
              Intent intent =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .setType("*/*");
              intent.putExtra(
                  Intent.EXTRA_MIME_TYPES,
                  new String[] {
                    "application/zip",
                    "application/x-zip-compressed",
                    "image/gif",
                    "image/webp",
                    "image/png",
                    "image/jpeg"
                  });
              startActivityForResult(intent, IMPORT);
            });
    button(
        content,
        R.string.use_builtin,
        () -> {
          if (importing) return;
          lastImportMessage = null;
          stopService(new Intent(this, PetOverlayService.class));
          try {
            status.setText(PetPack.useBuiltin(this).name);
          } catch (Exception error) {
            status.setText(getString(R.string.import_failed, "无法加载内置素材"));
          }
        });
    content.addView(label(getString(R.string.size), 16));
    SeekBar size = new SeekBar(this);
    size.setContentDescription(getString(R.string.size));
    size.setMin(64);
    size.setMax(256);
    size.setProgress(getSharedPreferences("pet", 0).getInt("size", 128));
    size.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            if (fromUser) getSharedPreferences("pet", 0).edit().putInt("size", value).apply();
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            if (PetOverlayService.running)
              startService(
                  new Intent(MainActivity.this, PetOverlayService.class).setAction("resize"));
          }
        });
    content.addView(size);
    button(content, R.string.start_pet, this::showPet);
    button(
        content, R.string.stop_pet, () -> stopService(new Intent(this, PetOverlayService.class)));
    content.addView(label(getString(R.string.privacy), 14));
    updateStatus();
  }

  private void showPet() {
    if (importing) return;
    if (!Settings.canDrawOverlays(this)) {
      status.setText(R.string.permission_required);
      return;
    }
    if (PetPack.selected(this) == null) {
      status.setText(R.string.no_pet);
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
    startForegroundService(new Intent(this, PetOverlayService.class));
  }

  private TextView label(String text, int size) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setPadding(0, 12, 0, 20);
    return view;
  }

  private Button button(LinearLayout parent, int text, Runnable action) {
    Button button = new Button(this);
    button.setText(text);
    button.setOnClickListener(view -> action.run());
    parent.addView(button);
    return button;
  }

  private void updateStatus() {
    if (status == null || importing) return;
    PetPack pack = PetPack.selected(this);
    status.setText(pack == null ? getString(R.string.no_pet) : pack.name);
  }

  @Override
  protected void onResume() {
    super.onResume();
    updateStatus();
    refresh.removeCallbacks(refreshImport);
    refresh.post(refreshImport);
  }

  @Override
  protected void onPause() {
    refresh.removeCallbacks(refreshImport);
    super.onPause();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != IMPORT
        || result != RESULT_OK
        || data == null
        || data.getData() == null
        || importing) return;
    importing = true;
    importButton.setEnabled(false);
    status.setText(R.string.importing);
    lastImportMessage = null;
    refresh.removeCallbacks(refreshImport);
    refresh.postDelayed(refreshImport, 500);
    stopService(new Intent(this, PetOverlayService.class));
    Uri uri = data.getData();
    android.content.Context context = getApplicationContext();
    worker.execute(
        () -> {
          String message;
          try {
            message = PetPack.install(context, uri).name;
          } catch (Exception error) {
            message =
                getString(
                    R.string.import_failed,
                    error.getMessage() == null ? "素材损坏或权限不足" : error.getMessage());
          }
          final String text = message;
          lastImportMessage = text;
          importing = false;
          runOnUiThread(
              () -> {
                if (!isDestroyed()) {
                  status.setText(text);
                  importButton.setEnabled(true);
                }
              });
        });
  }

  @Override
  protected void onDestroy() {
    refresh.removeCallbacks(refreshImport);
    super.onDestroy();
  }
}
