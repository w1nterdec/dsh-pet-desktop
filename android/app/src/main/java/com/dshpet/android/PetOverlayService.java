package com.dshpet.android;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.File;
import org.json.*;

/** User-started, non-sticky overlay with an always available stop action. No boot receiver. */
public final class PetOverlayService extends Service {
  public static volatile boolean running;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private WindowManager manager;
  private WindowManager.LayoutParams layout;
  private OverlayImageView image;
  private PetPack pack;
  private Drawable drawable;
  private int clipIndex, frameIndex;
  private boolean paused, receiverRegistered;
  private final BroadcastReceiver screenState =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          paused = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
          handler.removeCallbacks(frameTick);
          if (drawable instanceof Animatable) {
            if (paused) ((Animatable) drawable).stop();
            else ((Animatable) drawable).start();
          }
          if (!paused) scheduleFrame();
        }
      };
  private final Runnable frameTick =
      new Runnable() {
        @Override
        public void run() {
          if (!paused && image != null) {
            frameIndex++;
            displayFrame();
            scheduleFrame();
          }
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel(
            "pet", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, PetOverlayService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    startForeground(
        1,
        new Notification.Builder(this, "pet")
            .setSmallIcon(R.drawable.ic_pet)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                new Notification.Action.Builder(null, getString(R.string.stop_pet), stop).build())
            .build());
  }

  @android.annotation.SuppressLint(
      "RtlHardcoded") // Saved positions use physical left-origin screen coordinates in all locales.
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "stop".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!Settings.canDrawOverlays(this)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (image != null) {
      resize();
      return START_NOT_STICKY;
    }
    pack = PetPack.selected(this);
    if (pack == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      manager = getSystemService(WindowManager.class);
      image = new OverlayImageView(this);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      image.setContentDescription(pack.name);
      layout =
          new WindowManager.LayoutParams(
              128,
              128,
              WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
              PixelFormat.TRANSLUCENT);
      layout.gravity = Gravity.TOP | Gravity.LEFT;
      layout.x = getSharedPreferences("pet", 0).getInt("x", 24);
      layout.y = getSharedPreferences("pet", 0).getInt("y", 160);
      int size = petSize();
      layout.width = size;
      layout.height = size;
      clamp();
      manager.addView(image, layout);
      attachDrag();
      playClip();
      IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
      filter.addAction(Intent.ACTION_SCREEN_ON);
      if (Build.VERSION.SDK_INT >= 33)
        registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(screenState, filter);
      receiverRegistered = true;
      running = true;
    } catch (RuntimeException error) {
      Toast.makeText(this, R.string.overlay_failed, Toast.LENGTH_LONG).show();
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private int petSize() {
    return Math.round(
        Math.max(64, Math.min(256, getSharedPreferences("pet", 0).getInt("size", 128)))
            * getResources().getDisplayMetrics().density);
  }

  private void resize() {
    if (image == null) return;
    layout.width = petSize();
    layout.height = layout.width;
    clamp();
    try {
      manager.updateViewLayout(image, layout);
    } catch (RuntimeException ignored) {
      stopSelf();
    }
  }

  private void clamp() {
    Point bounds = new Point();
    manager.getDefaultDisplay().getSize(bounds);
    layout.x = Math.max(0, Math.min(layout.x, bounds.x - layout.width));
    layout.y = Math.max(0, Math.min(layout.y, bounds.y - layout.height));
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    resize();
  }

  private void attachDrag() {
    image.setOnTouchListener(
        new View.OnTouchListener() {
          float downX, downY;
          int initialX, initialY;
          boolean moved, longPressed;
          final Runnable longPress =
              () -> {
                longPressed = true;
                image.performLongClick();
              };

          @Override
          public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                initialX = layout.x;
                initialY = layout.y;
                moved = false;
                longPressed = false;
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
              case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (Math.hypot(dx, dy)
                    > ViewConfiguration.get(PetOverlayService.this).getScaledTouchSlop()) {
                  moved = true;
                  handler.removeCallbacks(longPress);
                }
                if (moved) {
                  layout.x = initialX + Math.round(dx);
                  layout.y = initialY + Math.round(dy);
                  clamp();
                  try {
                    manager.updateViewLayout(image, layout);
                  } catch (RuntimeException ignored) {
                    stopSelf();
                  }
                }
                return true;
              case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                getSharedPreferences("pet", 0)
                    .edit()
                    .putInt("x", layout.x)
                    .putInt("y", layout.y)
                    .apply();
                if (!moved && !longPressed) view.performClick();
                return true;
              case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                return true;
              default:
                return false;
            }
          }
        });
    image.setOnClickListener(
        view -> {
          clipIndex = (clipIndex + 1) % pack.animations.length();
          playClip();
        });
    image.setOnLongClickListener(
        view -> {
          startActivity(
              new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
          return true;
        });
  }

  private JSONObject clip() throws JSONException {
    return pack.animations.getJSONObject(clipIndex);
  }

  private void playClip() {
    handler.removeCallbacks(frameTick);
    frameIndex = 0;
    if (drawable instanceof Animatable) ((Animatable) drawable).stop();
    try {
      if (clip().has("frames")) {
        displayFrame();
        scheduleFrame();
      } else {
        ImageDecoder.Source source =
            ImageDecoder.createSource(new File(pack.directory, clip().getString("file")));
        drawable =
            ImageDecoder.decodeDrawable(
                source,
                (decoder, info, src) -> {
                  int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                  float ratio = Math.min(1f, 512f / Math.max(width, height));
                  decoder.setTargetSize(
                      Math.max(1, Math.round(width * ratio)),
                      Math.max(1, Math.round(height * ratio)));
                });
        image.setImageDrawable(drawable);
        if (drawable instanceof AnimatedImageDrawable) {
          ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
          if (!paused) ((AnimatedImageDrawable) drawable).start();
        }
      }
    } catch (Exception error) {
      Toast.makeText(this, R.string.overlay_failed, Toast.LENGTH_LONG).show();
      stopSelf();
    }
  }

  private void displayFrame() {
    try {
      JSONArray frames = clip().getJSONArray("frames");
      File file = new File(pack.directory, frames.getString(frameIndex % frames.length()));
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getPath(), options);
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
        options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
      if (bitmap == null) throw new IllegalStateException("Invalid frame");
      drawable = new BitmapDrawable(getResources(), bitmap);
      image.setImageDrawable(drawable);
    } catch (Exception error) {
      stopSelf();
    }
  }

  private void scheduleFrame() {
    try {
      if (!paused && clip().has("frames"))
        handler.postDelayed(frameTick, 1000 / clip().getInt("fps"));
    } catch (JSONException ignored) {
      stopSelf();
    }
  }

  @Override
  public void onDestroy() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    if (drawable instanceof Animatable) ((Animatable) drawable).stop();
    if (receiverRegistered) unregisterReceiver(screenState);
    if (image != null && image.isAttachedToWindow()) manager.removeView(image);
    image = null;
    drawable = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private static final class OverlayImageView extends ImageView {
    OverlayImageView(Context context) {
      super(context);
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
