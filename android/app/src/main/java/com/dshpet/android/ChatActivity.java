package com.dshpet.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import org.json.*;

public final class ChatActivity extends Activity {
  private String profileId;
  private LinearLayout history;
  private EditText input;
  private TextView pending, status;
  private Button send, cancel;
  private AiCoordinator.Task task;
  private boolean foreground;

  @Override
  public void onCreate(Bundle state) {
    Ui.theme(this);
    super.onCreate(state);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    profileId = getIntent().getStringExtra("profileId");
    PetProfile profile;
    try {
      profile = new PetLibrary(this).find(profileId);
    } catch (Exception error) {
      Ui.message(this, Ui.safeError(error));
      finish();
      return;
    }
    LinearLayout root = Ui.screen(this);
    Ui.heading(root, "和" + profile.name + "聊聊", "每只伙伴保留独立的本机加密记忆。自动问候需在伙伴设置中另行开启。");
    Ui.button(root, "AI 连接设置", () -> startActivity(new Intent(this, AiSettingsActivity.class)));
    history = Ui.card(root);
    history.setId(R.id.chat_history);
    input = Ui.field(root, "想对它说什么", state == null ? "" : state.getString("draft", ""), true);
    input.setId(R.id.chat_input);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(6000)});
    status = Ui.text(this, "准备就绪", 14);
    status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status);
    send = Ui.button(root, "发送", () -> send(false));
    send.setId(R.id.chat_send);
    cancel =
        Ui.button(
            root,
            "停止生成",
            () -> {
              if (task != null) task.cancel();
              status.setText("正在取消…");
            });
    cancel.setId(R.id.chat_stop);
    cancel.setEnabled(false);
    Ui.button(root, "让它说一句（会调用模型）", () -> send(true));
    Ui.button(
        root,
        "测试连接（会调用模型）",
        () -> {
          if (input.getText().toString().isEmpty()) input.setText("请只回复：连接成功");
          send(false);
        });
    Ui.button(
        root,
        "清空这只伙伴的聊天记忆",
        () ->
            Ui.confirm(
                this,
                "清空聊天记忆",
                "将取消当前请求并删除这只伙伴的本机历史。",
                () -> {
                  try {
                    AiCoordinator.clear(this, profileId);
                    render();
                  } catch (Exception error) {
                    Ui.message(this, Ui.safeError(error));
                  }
                }));
    Ui.button(root, "返回伙伴管理", this::finish);
    render();
  }

  private void render() {
    history.removeAllViews();
    pending = null;
    try {
      JSONArray rows = new AiRepository(this).history(profileId);
      if (rows.length() == 0) history.addView(Ui.text(this, "还没有对话。发送第一条消息前，请先配置服务商并启用 AI。", 15));
      for (int i = Math.max(0, rows.length() - 20); i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        TextView text =
            Ui.text(
                this,
                (row.getString("role").equals("user") ? "你\n" : "伙伴\n") + row.getString("content"),
                16);
        text.setTextIsSelectable(true);
        history.addView(text);
      }
    } catch (Exception error) {
      history.addView(Ui.text(this, Ui.safeError(error), 15));
    }
  }

  private void send(boolean whisper) {
    if (task != null) return;
    String text = whisper ? "用一句话和我打个招呼吧。" : input.getText().toString().trim();
    try {
      task =
          AiCoordinator.send(
              this,
              profileId,
              text,
              whisper,
              new AiCoordinator.Listener() {
                @Override
                public void partial(String value) {
                  if (foreground) {
                    if (pending == null) {
                      pending = Ui.text(ChatActivity.this, "", 16);
                      history.addView(pending);
                    }
                    pending.setText(value);
                  }
                }

                @Override
                public void complete(String value) {
                  if (!isDestroyed()) {
                    task = null;
                    send.setEnabled(true);
                    cancel.setEnabled(false);
                    status.setText("回复已保存");
                    render();
                    input.setText("");
                    PetOverlayService.bubble(ChatActivity.this, profileId, value);
                  }
                }

                @Override
                public void failed(String message) {
                  if (!isDestroyed()) {
                    task = null;
                    send.setEnabled(true);
                    cancel.setEnabled(false);
                    status.setText(message);
                    render();
                  }
                }
              });
      send.setEnabled(false);
      cancel.setEnabled(true);
      status.setText("正在回复…");
    } catch (Exception error) {
      status.setText(Ui.safeError(error));
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (history != null && task == null) render();
  }

  @Override
  protected void onStop() {
    foreground = false;
    if (task != null) task.cancel();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (input != null) state.putString("draft", input.getText().toString());
  }

  @Override
  protected void onDestroy() {
    if (task != null) task.cancel();
    super.onDestroy();
  }
}
