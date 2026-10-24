package com.dshpet.android;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;

public final class AiSettingsActivity extends Activity {
  private AiRepository repository;
  private AiSettings saved;
  private EditText base, model, key, tokens, rounds, timeout;
  private Spinner protocol;
  private Switch enabled;

  @Override
  public void onCreate(Bundle state) {
    Ui.theme(this);
    super.onCreate(state);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    repository = new AiRepository(this);
    try {
      saved = repository.settings();
    } catch (Exception error) {
      Ui.message(this, Ui.safeError(error));
      finish();
      return;
    }
    LinearLayout root = Ui.screen(this);
    Ui.heading(root, "AI 连接设置", "使用你自己的服务商。AI 默认关闭，发送消息和测试连接可能产生服务商费用。");
    LinearLayout card = Ui.card(root);
    enabled = Ui.toggle(card, "启用 AI 对话", saved.enabled);
    card.addView(Ui.text(this, "接口协议", 13));
    protocol = new Spinner(this);
    protocol.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {
              "OpenAI 兼容 · Chat Completions", "OpenAI · Responses", "Anthropic · Messages"
            }));
    protocol.setSelection(
        saved.protocol.equals("responses") ? 1 : saved.protocol.equals("anthropic") ? 2 : 0);
    card.addView(protocol);
    base = Ui.field(card, "服务商基础地址（HTTPS）", saved.baseUrl, false);
    base.setId(R.id.ai_base);
    base.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    model = Ui.field(card, "模型 ID", saved.model, false);
    model.setId(R.id.ai_model);
    model.setHint("填写服务商提供的模型 ID");
    key = Ui.field(card, "API 密钥", "", false);
    key.setId(R.id.ai_key);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setHint(saved.hasKey() ? "已保存；留空保留，更换服务商时需重新填写" : "仅保存在本机加密存储中");
    key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    tokens = Ui.field(card, "单次输出上限（128～4096 tokens）", String.valueOf(saved.maxTokens), false);
    tokens.setInputType(InputType.TYPE_CLASS_NUMBER);
    rounds = Ui.field(card, "上下文记忆轮数（0～20）", String.valueOf(saved.memoryRounds), false);
    rounds.setInputType(InputType.TYPE_CLASS_NUMBER);
    timeout = Ui.field(card, "请求超时（15～120 秒）", String.valueOf(saved.timeoutSeconds), false);
    timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
    Ui.button(card, "保存 AI 设置", this::save).setId(R.id.ai_save);
    Ui.button(
        card,
        "删除本机密钥并关闭 AI",
        () ->
            Ui.confirm(
                this,
                "删除 API 密钥",
                "只删除本机连接密钥，不会删除聊天记忆。",
                () -> {
                  try {
                    AiCoordinator.cancelAll();
                    repository.clearKey();
                    Ui.message(this, "密钥已删除，AI 已关闭");
                    recreate();
                  } catch (Exception error) {
                    Ui.message(this, Ui.safeError(error));
                  }
                }));
    root.addView(
        Ui.text(this, "密钥由 Android Keystore 加密；聊天仅在你开启 AI 并发送消息时交给所填服务商。协议与模型支持范围由服务商决定。", 14));
    Ui.button(root, "返回", this::finish);
  }

  private void save() {
    try {
      String selected =
          protocol.getSelectedItemPosition() == 1
              ? "responses"
              : protocol.getSelectedItemPosition() == 2 ? "anthropic" : "compatible";
      JSONObject input =
          new JSONObject()
              .put("enabled", enabled.isChecked())
              .put("protocol", selected)
              .put("baseUrl", base.getText().toString().trim())
              .put("model", model.getText().toString().trim())
              .put("maxTokens", Integer.parseInt(tokens.getText().toString()))
              .put("memoryRounds", Integer.parseInt(rounds.getText().toString()))
              .put("timeoutSeconds", Integer.parseInt(timeout.getText().toString()));
      AiSettings next = AiSettings.merge(saved, input, key.getText().toString());
      AiCoordinator.cancelAll();
      repository.save(next);
      saved = next;
      key.setText("");
      key.setHint(next.hasKey() ? "已保存；留空保留" : "尚未保存密钥");
      Ui.message(this, "AI 设置已加密保存");
    } catch (NumberFormatException error) {
      Ui.message(this, "输出上限、轮数和超时须为整数");
    } catch (Exception error) {
      Ui.message(this, Ui.safeError(error));
    }
  }
}
