package com.dshpet.android;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One independent pet window. Decoding happens on bounded workers, never on the UI thread. */
final class PetSpriteOverlay {
  private static final android.util.LruCache<String, Bitmap> FRAMES =
      new android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
      };
  private final Context context;
  private final WindowManager manager;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService decoder;
  private final Runnable refresh;
  private final PetPack pack;
  private PetProfile profile;
  private final PetImage image;
  private final WindowManager.LayoutParams layout;
  private final PetPhysics physics = new PetPhysics();
  private Drawable drawable;
  private Future<?> decoding;
  private int generation, clipIndex, lastFrame = -1;
  private boolean disposed, paused, dragging, moving, oneShot, wandering;
  private long clipStart, nextAction, wanderEnd, lastPositionSave;
  private TextView bubble;
  private PopupWindow menu;
  private final Random random = new Random();

  @android.annotation.SuppressLint(
      "RtlHardcoded") // Physical screen coordinates have a left origin in every locale.
  PetSpriteOverlay(
      Context context, PetProfile profile, PetPack pack, ExecutorService decoder, Runnable refresh)
      throws Exception {
    this.context = context;
    this.profile = profile;
    this.pack = pack;
    this.decoder = decoder;
    this.refresh = refresh;
    manager = context.getSystemService(WindowManager.class);
    image = new PetImage(context);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(profile.name);
    layout =
        new WindowManager.LayoutParams(
            size(),
            size(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    physics.x = profile.x;
    physics.y = profile.y;
    relayout();
    manager.addView(image, layout);
    attachTouch();
    play(role("idle"), false);
    nextAction = SystemClock.elapsedRealtime() + 20000 + random.nextInt(15000);
  }

  PetProfile profile() {
    return profile;
  }

  private int size() {
    return Ui.dp(context, profile.size);
  }

  private JSONObject clip() throws JSONException {
    return pack.animations.getJSONObject(clipIndex);
  }

  private int role(String role) {
    List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < pack.animations.length(); i++)
      if (pack.animations.optJSONObject(i).optString("role").equals(role)) candidates.add(i);
    return candidates.isEmpty() ? 0 : candidates.get(random.nextInt(candidates.size()));
  }

  void update(PetProfile next) {
    boolean positionChanged = next.x != profile.x || next.y != profile.y;
    profile = next;
    image.setContentDescription(next.name);
    if (positionChanged && !dragging) {
      physics.x = next.x;
      physics.y = next.y;
      physics.vx = physics.vy = 0;
      moving = false;
    }
    relayout();
  }

  void relayout() {
    Point bounds = new Point();
    manager.getDefaultDisplay().getSize(bounds);
    physics.bounds(bounds.x, bounds.y, size());
    layout.width = size();
    layout.height = size();
    layout.x = (int) physics.x;
    layout.y = (int) physics.y;
    if (image.isAttachedToWindow()) updateWindow();
  }

  void tick(long now, double seconds) {
    if (disposed || paused) return;
    try {
      JSONObject clip = clip();
      if (clip.has("frames") || clip.has("sheet")) {
        int count =
            clip.has("sheet")
                ? clip.getJSONObject("sheet").getInt("count")
                : clip.getJSONArray("frames").length();
        int fps =
            clip.has("sheet") ? clip.getJSONObject("sheet").getInt("fps") : clip.getInt("fps");
        long frame = (now - clipStart) * fps / 1000;
        if (oneShot && frame >= count) {
          play(role("idle"), false);
        } else if (lastFrame != (int) (frame % count)) decodeFrame((int) (frame % count));
      }
      if (!dragging && moving) {
        boolean gravity = context.getSharedPreferences("app", 0).getBoolean("physics", true);
        physics.gravity = gravity ? Ui.dp(context, 1400) : 0;
        if (wandering && now < wanderEnd && !profile.fixed)
          physics.vx = Math.copySign(Ui.dp(context, 26), physics.vx == 0 ? 1 : physics.vx);
        else wandering = false;
        physics.step(seconds);
        updateWindow();
        if (physics.resting && !wandering) {
          moving = false;
          persist();
        }
      }
      if (!dragging && !oneShot && now >= nextAction) {
        nextAction = now + 20000 + random.nextInt(20000);
        if (!profile.fixed && random.nextBoolean()) {
          moving = true;
          wandering = true;
          wanderEnd = now + 5000;
          physics.vx = Ui.dp(context, 26) * (random.nextBoolean() ? 1 : -1);
          play(role("move"), false);
        } else play(random.nextInt(pack.animations.length()), true);
      }
    } catch (Exception error) {
      say("该动作无法播放，请检查素材包");
      play(role("idle"), false);
    }
  }

  void collide(PetSpriteOverlay other) {
    if (dragging || other.dragging || (!moving && !other.moving)) return;
    PetPhysics.collide(physics, other.physics, (size() + other.size()) * .38);
  }

  private void updateWindow() {
    if (disposed) return;
    int x = (int) Math.round(physics.x), y = (int) Math.round(physics.y);
    layout.x = x;
    layout.y = y;
    try {
      manager.updateViewLayout(image, layout);
    } catch (RuntimeException error) {
      dispose();
    }
  }

  private void persist() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastPositionSave < 250) return;
    lastPositionSave = now;
    try {
      new PetLibrary(context).position(profile.id, (int) physics.x, (int) physics.y);
      profile.x = (int) physics.x;
      profile.y = (int) physics.y;
    } catch (Exception ignored) {
      /* Keep the live window usable if storage is temporarily unavailable. */
    }
  }

  void playName(String name) {
    for (int i = 0; i < pack.animations.length(); i++)
      if (pack.animations.optJSONObject(i).optString("name").equals(name)) {
        play(i, true);
        return;
      }
    say("该动作不存在");
  }

  private void stopDrawable() {
    if (drawable instanceof AnimatedImageDrawable)
      ((AnimatedImageDrawable) drawable).clearAnimationCallbacks();
    if (drawable instanceof Animatable) ((Animatable) drawable).stop();
  }

  private void play(int index, boolean once) {
    if (disposed || index < 0 || index >= pack.animations.length()) return;
    generation++;
    if (decoding != null) decoding.cancel(true);
    decoding = null;
    stopDrawable();
    clipIndex = index;
    oneShot = once;
    lastFrame = -1;
    clipStart = SystemClock.elapsedRealtime();
    try {
      JSONObject clip = clip();
      if (clip.has("frames") || clip.has("sheet")) {
        decodeFrame(0);
        return;
      }
      int token = generation;
      decoding =
          decoder.submit(
              () -> {
                try {
                  Drawable next =
                      ImageDecoder.decodeDrawable(
                          pack.imageSource(context, clip.getString("file")),
                          (imageDecoder, info, source) -> {
                            int width = info.getSize().getWidth(),
                                height = info.getSize().getHeight();
                            int max =
                                context.getSharedPreferences("app", 0).getBoolean("battery", false)
                                    ? 384
                                    : 512;
                            float ratio = Math.min(1f, max / (float) Math.max(width, height));
                            imageDecoder.setTargetSize(
                                Math.max(1, Math.round(width * ratio)),
                                Math.max(1, Math.round(height * ratio)));
                          });
                  handler.post(
                      () -> {
                        if (disposed || token != generation) {
                          if (next instanceof Animatable) ((Animatable) next).stop();
                          return;
                        }
                        decoding = null;
                        drawable = next;
                        image.setImageDrawable(next);
                        if (next instanceof AnimatedImageDrawable) {
                          AnimatedImageDrawable animation = (AnimatedImageDrawable) next;
                          animation.setRepeatCount(
                              once ? 0 : AnimatedImageDrawable.REPEAT_INFINITE);
                          if (once)
                            animation.registerAnimationCallback(
                                new Animatable2.AnimationCallback() {
                                  @Override
                                  public void onAnimationEnd(Drawable ignored) {
                                    if (!disposed && token == generation) play(role("idle"), false);
                                  }
                                });
                          if (!paused) animation.start();
                        } else if (once)
                          handler.postDelayed(
                              () -> {
                                if (!disposed && token == generation) play(role("idle"), false);
                              },
                              3000);
                      });
                } catch (Exception error) {
                  handler.post(
                      () -> {
                        if (!disposed && token == generation) {
                          decoding = null;
                          say("动作解码失败，请选择其他动作或检查图片");
                          oneShot = false;
                          nextAction = SystemClock.elapsedRealtime() + 20000;
                        }
                      });
                }
              });
    } catch (Exception error) {
      say("动作信息无效");
    }
  }

  private void decodeFrame(int frame) throws Exception {
    if (decoding != null && !decoding.isDone()) return;
    JSONObject current = clip();
    int token = generation;
    decoding =
        decoder.submit(
            () -> {
              try {
                String path =
                    current.has("sheet")
                        ? current.getJSONObject("sheet").getString("file")
                        : current.getJSONArray("frames").getString(frame);
                String key = profile.packId + "/" + clipIndex + "/" + path + "/" + frame;
                Bitmap bitmap = FRAMES.get(key);
                if (bitmap == null) {
                  try (InputStream input = pack.open(context, path)) {
                    if (current.has("sheet")) {
                      JSONObject sheet = current.getJSONObject("sheet");
                      int width = sheet.getInt("frameWidth"),
                          height = sheet.getInt("frameHeight"),
                          columns = sheet.getInt("columns");
                      BitmapFactory.Options options = new BitmapFactory.Options();
                      options.inSampleSize = 1;
                      while (Math.max(width, height) / options.inSampleSize > 512)
                        options.inSampleSize *= 2;
                      BitmapRegionDecoder region = BitmapRegionDecoder.newInstance(input, false);
                      if (region == null) throw new IOException("无法读取精灵图");
                      try {
                        int left = frame % columns * width, top = frame / columns * height;
                        bitmap =
                            region.decodeRegion(
                                new Rect(left, top, left + width, top + height), options);
                      } finally {
                        region.recycle();
                      }
                    } else {
                      ImageDecoder.Source source = pack.imageSource(context, path);
                      bitmap =
                          ImageDecoder.decodeBitmap(
                              source,
                              (imageDecoder, info, ignored) -> {
                                float scale =
                                    Math.min(
                                        1f,
                                        512f
                                            / Math.max(
                                                info.getSize().getWidth(),
                                                info.getSize().getHeight()));
                                imageDecoder.setTargetSize(
                                    Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                                    Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                              });
                    }
                  }
                  if (bitmap == null) throw new IOException("序列帧无法解码");
                  FRAMES.put(key, bitmap);
                }
                Bitmap result = bitmap;
                handler.post(
                    () -> {
                      if (!disposed && token == generation) {
                        decoding = null;
                        lastFrame = frame;
                        drawable = new BitmapDrawable(context.getResources(), result);
                        image.setImageDrawable(drawable);
                      }
                    });
              } catch (Exception error) {
                handler.post(
                    () -> {
                      if (!disposed && token == generation) {
                        decoding = null;
                        oneShot = false;
                        say("序列或精灵图解码失败");
                      }
                    });
              }
            });
  }

  void pause(boolean value) {
    paused = value;
    if (drawable instanceof Animatable) {
      if (value) ((Animatable) drawable).stop();
      else ((Animatable) drawable).start();
    }
    if (!value) clipStart = SystemClock.elapsedRealtime();
  }

  private void attachTouch() {
    image.setOnClickListener(view -> play(role("click"), true));
    image.setOnLongClickListener(
        view -> {
          showMenu();
          return true;
        });
    image.setOnTouchListener(
        new View.OnTouchListener() {
          float startX, startY;
          double originX, originY;
          boolean moved, longPressed;
          VelocityTracker velocity;
          final Runnable longPress =
              () -> {
                longPressed = true;
                image.performLongClick();
              };

          private void recycle() {
            if (velocity != null) velocity.recycle();
            velocity = null;
          }

          @Override
          public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                if (menu != null) menu.dismiss();
                recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                dragging = true;
                moving = false;
                wandering = false;
                startX = event.getRawX();
                startY = event.getRawY();
                originX = physics.x;
                originY = physics.y;
                moved = longPressed = false;
                physics.vx = physics.vy = 0;
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
              case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(event);
                float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                if (!moved
                    && Math.hypot(dx, dy) > ViewConfiguration.get(context).getScaledTouchSlop()) {
                  moved = true;
                  handler.removeCallbacks(longPress);
                  play(role("drag"), false);
                }
                if (moved) {
                  physics.x = originX + dx;
                  physics.y = originY + dy;
                  physics.clamp();
                  updateWindow();
                }
                return true;
              case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                dragging = false;
                if (moved) {
                  if (context.getSharedPreferences("app", 0).getBoolean("physics", true)
                      && velocity != null) {
                    velocity.addMovement(event);
                    velocity.computeCurrentVelocity(1000, Ui.dp(context, 2500));
                    physics.vx = velocity.getXVelocity();
                    physics.vy = velocity.getYVelocity();
                    moving = true;
                  }
                  play(role("idle"), false);
                  persist();
                } else if (!longPressed) view.performClick();
                recycle();
                nextAction = SystemClock.elapsedRealtime() + 20000;
                return true;
              case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                dragging = false;
                recycle();
                persist();
                return true;
              default:
                return false;
            }
          }
        });
  }

  private void open(String page, boolean chat) {
    Intent intent =
        new Intent(context, chat ? ChatActivity.class : MainActivity.class)
            .putExtra("profileId", profile.id)
            .putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      context.startActivity(intent);
    } catch (RuntimeException error) {
      Ui.message(context, "请从通知栏打开控制中心");
    }
  }

  private void showMenu() {
    if (disposed) return;
    try {
      LinearLayout root = Ui.column(context);
      root.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8));
      root.setBackgroundColor(Ui.dark(context) ? 0xff222638 : Color.WHITE);
      menu = new PopupWindow(root, Ui.dp(context, 230), -2, true);
      menu.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
      menu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
      menu.setOutsideTouchable(true);
      menu.setElevation(Ui.dp(context, 12));
      root.addView(Ui.text(context, profile.name, 18));
      Ui.button(
          root,
          "选择动作",
          () -> {
            menu.dismiss();
            open("actions", false);
          });
      Ui.button(
          root,
          "随便做个动作",
          () -> {
            menu.dismiss();
            play(random.nextInt(pack.animations.length()), true);
          });
      Ui.button(
          root,
          "和它对话",
          () -> {
            menu.dismiss();
            open("", true);
          });
      Ui.button(
          root,
          "回到原位",
          () -> {
            menu.dismiss();
            physics.x = 24;
            physics.y = 160;
            physics.vx = physics.vy = 0;
            moving = false;
            physics.clamp();
            updateWindow();
            persist();
          });
      Ui.button(
          root,
          "收起这只伙伴",
          () -> {
            menu.dismiss();
            try {
              new PetLibrary(context).visibility(profile.id, false);
              refresh.run();
            } catch (Exception error) {
              Ui.message(context, Ui.safeError(error));
            }
          });
      Point bounds = new Point();
      manager.getDefaultDisplay().getSize(bounds);
      menu.showAtLocation(
          image,
          Gravity.TOP | Gravity.START,
          Math.max(0, Math.min(layout.x, bounds.x - Ui.dp(context, 230))),
          Math.max(0, Math.min(layout.y, bounds.y - Ui.dp(context, 400))));
    } catch (RuntimeException error) {
      open("actions", false);
    }
  }

  @android.annotation.SuppressLint("RtlHardcoded")
  void say(String text) {
    if (disposed) return;
    removeBubble();
    bubble = new TextView(context);
    bubble.setText(text.length() > 140 ? text.substring(0, 140) + "…" : text);
    bubble.setTextSize(14);
    bubble.setTextColor(0xff24304d);
    bubble.setPadding(
        Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10));
    GradientDrawable background = new GradientDrawable();
    background.setColor(Color.WHITE);
    background.setCornerRadius(Ui.dp(context, 14));
    bubble.setBackground(background);
    bubble.setMaxLines(5);
    WindowManager.LayoutParams parameters =
        new WindowManager.LayoutParams(
            Ui.dp(context, 230),
            -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
    parameters.gravity = Gravity.TOP | Gravity.LEFT;
    parameters.alpha = .8f;
    Point bounds = new Point();
    manager.getDefaultDisplay().getSize(bounds);
    parameters.x = Math.max(0, Math.min(layout.x, bounds.x - parameters.width));
    parameters.y = Math.max(0, layout.y - Ui.dp(context, 130));
    try {
      manager.addView(bubble, parameters);
      handler.postDelayed(this::removeBubble, 10000);
    } catch (RuntimeException ignored) {
      bubble = null;
    }
  }

  private void removeBubble() {
    if (bubble != null && bubble.isAttachedToWindow()) {
      try {
        manager.removeView(bubble);
      } catch (RuntimeException ignored) {
      }
    }
    bubble = null;
  }

  void dispose() {
    if (disposed) return;
    persist();
    disposed = true;
    generation++;
    handler.removeCallbacksAndMessages(null);
    if (decoding != null) decoding.cancel(true);
    stopDrawable();
    if (menu != null) menu.dismiss();
    removeBubble();
    if (image.isAttachedToWindow())
      try {
        manager.removeView(image);
      } catch (RuntimeException ignored) {
      }
    image.setImageDrawable(null);
    drawable = null;
  }

  private static final class PetImage extends ImageView {
    PetImage(Context context) {
      super(context);
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
