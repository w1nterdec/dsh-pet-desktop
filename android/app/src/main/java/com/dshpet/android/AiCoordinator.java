package com.dshpet.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.*;

/** A single paid request at a time; explicit cancellation and success-only history commits. */
final class AiCoordinator {
  interface Listener {
    void partial(String text);

    void complete(String text);

    void failed(String message);
  }

  static final Object LOCK = new Object();
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static Task active;

  static final class Task {
    final String profile;
    final AiClient client = new AiClient();
    volatile boolean cancelled;

    Task(String profile) {
      this.profile = profile;
    }

    void cancel() {
      synchronized (LOCK) {
        cancelled = true;
        client.cancel();
      }
    }
  }

  static boolean busy() {
    synchronized (LOCK) {
      return active != null;
    }
  }

  static void cancelAll() {
    synchronized (LOCK) {
      if (active != null) active.cancel();
    }
  }

  static void clear(Context context, String profile) throws Exception {
    synchronized (LOCK) {
      if (active != null && active.profile.equals(profile)) active.cancel();
      new AiRepository(context).clear(profile);
    }
  }

  static Task send(
      Context context, String profileId, String message, boolean whisper, Listener listener)
      throws Exception {
    if (message.trim().isEmpty() || message.length() > 6000)
      throw new IOException("消息须为 1～6000 字符");
    Context application = context.getApplicationContext();
    Task task = new Task(profileId);
    synchronized (LOCK) {
      if (active != null) throw new IOException("AI 正在回复，请等待或取消当前请求");
      active = task;
    }
    WORKER.execute(
        () -> {
          try {
            AiRepository repository = new AiRepository(application);
            AiSettings settings = repository.settings();
            if (!settings.enabled) throw new IOException("AI 尚未启用，请先保存连接设置并显式开启");
            PetProfile profile = new PetLibrary(application).find(profileId);
            JSONArray messages = repository.context(profileId, settings.memoryRounds);
            messages.put(new JSONObject().put("role", "user").put("content", message));
            String system =
                profile.persona + "\n你的名字是“" + profile.name + "”。请使用用户使用的语言自然回复，不要声称你执行了手机操作。";
            if (whisper) system += "\n现在只说一句不超过30字的轻松日常问候。";
            String reply =
                task.client.send(
                    settings,
                    system,
                    messages,
                    text ->
                        MAIN.post(
                            () -> {
                              if (!task.cancelled) listener.partial(text);
                            }));
            synchronized (LOCK) {
              if (task.cancelled) throw new IOException("请求已取消");
              repository.append(profileId, message, reply);
            }
            MAIN.post(
                () -> {
                  if (!task.cancelled) listener.complete(reply);
                });
          } catch (Exception error) {
            String safe = task.cancelled ? "请求已取消" : Ui.safeError(error);
            MAIN.post(() -> listener.failed(safe));
          } finally {
            synchronized (LOCK) {
              if (active == task) active = null;
            }
          }
        });
    return task;
  }
}
