package com.dshpet.android;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.provider.Settings;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Owns several independent overlay sprites and one user-visible foreground notification. */
public final class PetOverlayService extends Service {
  static final String STATE_CHANGED = "com.dshpet.android.STATE_CHANGED";
  public static volatile boolean running;
  public static volatile int runningCount;
  private static WeakReference<PetOverlayService> current = new WeakReference<>(null);
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, PetSpriteOverlay> sprites = new LinkedHashMap<>();
  private final Map<String, Long> nextWhispers = new HashMap<>();
  private final List<AiCoordinator.Task> whisperTasks = new ArrayList<>();
  private ExecutorService decoder;
  private PetLibrary library;
  private boolean paused, registered;
  private long lastTick, lastPermission;

  public static void bubble(Context context, String id, String message) {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              PetOverlayService service = current.get();
              if (service != null) {
                PetSpriteOverlay sprite = service.sprites.get(id);
                if (sprite != null) sprite.say(message);
              }
            });
  }

  private final BroadcastReceiver screen =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          paused = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
          handler.removeCallbacks(tick);
          if (paused) for (AiCoordinator.Task task : whisperTasks) task.cancel();
          for (PetSpriteOverlay sprite : sprites.values()) sprite.pause(paused);
          if (!paused) {
            lastTick = SystemClock.elapsedRealtime();
            handler.post(tick);
          }
        }
      };
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (paused || sprites.isEmpty()) return;
          long now = SystemClock.elapsedRealtime();
          if (now - lastPermission > 1000) {
            lastPermission = now;
            if (!Settings.canDrawOverlays(PetOverlayService.this)) {
              stopSelf();
              return;
            }
          }
          double seconds = Math.min(.05, Math.max(0, (now - lastTick) / 1000.0));
          lastTick = now;
          for (PetSpriteOverlay sprite : new ArrayList<>(sprites.values()))
            sprite.tick(now, seconds);
          if (getSharedPreferences("app", 0).getBoolean("collision", false)) {
            List<PetSpriteOverlay> list = new ArrayList<>(sprites.values());
            for (int i = 0; i < list.size(); i++)
              for (int j = i + 1; j < list.size(); j++) list.get(i).collide(list.get(j));
          }
          maybeWhisper(now);
          handler.postDelayed(
              this, getSharedPreferences("app", 0).getBoolean("battery", false) ? 66 : 33);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    current = new WeakReference<>(this);
    decoder = Executors.newFixedThreadPool(2);
    library = new PetLibrary(this);
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            "pet", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
    startForeground(1, notification());
    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_SCREEN_ON);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screen, filter);
    registered = true;
    paused = !getSystemService(PowerManager.class).isInteractive();
  }

  private Notification notification() {
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
    return new Notification.Builder(this, "pet")
        .setSmallIcon(R.drawable.ic_pet)
        .setContentTitle("桌宠之家 · " + sprites.size() + " 位伙伴")
        .setContentText("点击打开控制中心，或停止全部陪伴")
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "停止全部", stop).build())
        .build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "stop".equals(intent.getAction())) {
      try {
        library.hideAll();
      } catch (Exception ignored) {
      }
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!Settings.canDrawOverlays(this)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      List<PetProfile> profiles = library.list();
      Set<String> visible = new HashSet<>();
      for (PetProfile profile : profiles)
        if (profile.visible && visible.size() < PetLibrary.MAX_VISIBLE) {
          visible.add(profile.id);
          PetSpriteOverlay sprite = sprites.get(profile.id);
          if (sprite == null) {
            try {
              sprite =
                  new PetSpriteOverlay(
                      this, profile, PetPack.load(this, profile.packId), decoder, () -> refresh());
              sprites.put(profile.id, sprite);
              sprite.pause(paused);
            } catch (Exception error) {
              library.visibility(profile.id, false);
              Ui.message(this, Ui.safeError(error));
            }
          } else sprite.update(profile);
        }
      for (String id : new ArrayList<>(sprites.keySet()))
        if (!visible.contains(id)) {
          sprites.remove(id).dispose();
          nextWhispers.remove(id);
          for (AiCoordinator.Task task : whisperTasks) if (task.profile.equals(id)) task.cancel();
        }
      runningCount = sprites.size();
      running = runningCount > 0;
      sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
      if (!running) {
        stopSelf();
        return START_NOT_STICKY;
      }
      if (intent != null && "play".equals(intent.getAction())) {
        PetSpriteOverlay target = sprites.get(intent.getStringExtra("profileId"));
        if (target != null) target.playName(intent.getStringExtra("clip"));
      }
      getSystemService(NotificationManager.class).notify(1, notification());
      handler.removeCallbacks(tick);
      lastTick = SystemClock.elapsedRealtime();
      if (!paused) handler.post(tick);
    } catch (Exception error) {
      Ui.message(this, Ui.safeError(error));
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void refresh() {
    onStartCommand(new Intent(this, PetOverlayService.class).setAction("refresh"), 0, 0);
  }

  private void maybeWhisper(long now) {
    if (AiCoordinator.busy()) return;
    for (PetSpriteOverlay sprite : sprites.values()) {
      PetProfile profile = sprite.profile();
      if (!profile.whisper) continue;
      Long next = nextWhispers.get(profile.id);
      if (next == null) {
        nextWhispers.put(profile.id, now + 600000);
        continue;
      }
      if (now < next) continue;
      nextWhispers.put(profile.id, now + 600000);
      try {
        if (!new AiRepository(this).settings().enabled) continue;
        AiCoordinator.Task task =
            AiCoordinator.send(
                this,
                profile.id,
                "用一句话和我打个招呼吧。",
                true,
                new AiCoordinator.Listener() {
                  @Override
                  public void partial(String text) {}

                  @Override
                  public void complete(String text) {
                    sprite.say(text);
                  }

                  @Override
                  public void failed(String text) {
                    sprite.say(text);
                  }
                });
        whisperTasks.removeIf(old -> old.cancelled);
        whisperTasks.add(task);
        if (whisperTasks.size() > 4) whisperTasks.remove(0);
      } catch (Exception error) {
        sprite.say(Ui.safeError(error));
      }
      break;
    }
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    for (PetSpriteOverlay sprite : sprites.values()) sprite.relayout();
  }

  @Override
  public void onDestroy() {
    running = false;
    runningCount = 0;
    sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
    current.clear();
    handler.removeCallbacksAndMessages(null);
    for (AiCoordinator.Task task : whisperTasks) task.cancel();
    for (PetSpriteOverlay sprite : sprites.values()) sprite.dispose();
    sprites.clear();
    if (decoder != null) decoder.shutdownNow();
    if (registered) unregisterReceiver(screen);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
