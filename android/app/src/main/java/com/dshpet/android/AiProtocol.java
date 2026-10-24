package com.dshpet.android;

import java.io.IOException;
import java.net.URI;
import org.json.*;

/** Data-only adapters for Chat Completions, Responses and Anthropic text messages. */
public final class AiProtocol {
  private AiProtocol() {}

  public static URI endpoint(AiSettings settings) throws Exception {
    String base = settings.baseUrl.replaceAll("/+$", "");
    AiSettings.validateBase(base);
    String suffix =
        settings.protocol.equals("responses")
            ? "/responses"
            : settings.protocol.equals("anthropic") ? "/messages" : "/chat/completions";
    if (settings.protocol.equals("anthropic")
        && !base.endsWith("/v1")
        && !base.endsWith("/messages")) base += "/v1";
    return URI.create(base.endsWith(suffix) ? base : base + suffix);
  }

  public static JSONObject request(AiSettings settings, String system, JSONArray messages)
      throws Exception {
    JSONObject body = new JSONObject().put("model", settings.model).put("stream", true);
    if (settings.protocol.equals("responses"))
      return body.put("instructions", system)
          .put("input", messages)
          .put("max_output_tokens", settings.maxTokens)
          .put("store", false);
    if (settings.protocol.equals("anthropic"))
      return body.put("system", system)
          .put("messages", messages)
          .put("max_tokens", settings.maxTokens);
    JSONArray conversation =
        new JSONArray().put(new JSONObject().put("role", "system").put("content", system));
    for (int i = 0; i < messages.length(); i++) conversation.put(messages.getJSONObject(i));
    return body.put("messages", conversation)
        .put(
            "api.openai.com".equalsIgnoreCase(endpoint(settings).getHost())
                ? "max_completion_tokens"
                : "max_tokens",
            settings.maxTokens);
  }

  public static String delta(String protocol, JSONObject event) throws Exception {
    if (event.has("error")
        || event.optString("type").equals("error")
        || event.optString("type").equals("response.failed"))
      throw new IOException("服务商返回生成错误，请检查模型权限或稍后重试");
    if (protocol.equals("responses"))
      return event.optString("type").equals("response.output_text.delta")
          ? event.optString("delta", "")
          : "";
    if (protocol.equals("anthropic")) {
      JSONObject delta = event.optJSONObject("delta");
      return event.optString("type").equals("content_block_delta")
              && delta != null
              && delta.optString("type").equals("text_delta")
          ? delta.optString("text", "")
          : "";
    }
    JSONArray choices = event.optJSONArray("choices");
    if (choices == null || choices.length() == 0) return "";
    JSONObject choice = choices.getJSONObject(0), delta = choice.optJSONObject("delta");
    if (delta == null) delta = choice.optJSONObject("message");
    if (delta != null && !delta.optString("refusal", "").isEmpty())
      throw new IOException("服务商未提供这条回复");
    return delta == null ? "" : delta.optString("content", "");
  }

  public static String full(String protocol, JSONObject response) throws Exception {
    if (!protocol.equals("responses") && !protocol.equals("anthropic"))
      return delta(protocol, response);
    StringBuilder text = new StringBuilder();
    JSONArray blocks =
        protocol.equals("responses")
            ? response.optJSONArray("output")
            : response.optJSONArray("content");
    if (blocks == null) return "";
    for (int i = 0; i < blocks.length(); i++) {
      JSONObject block = blocks.getJSONObject(i);
      if (protocol.equals("anthropic")) {
        if (block.optString("type").equals("text")) text.append(block.optString("text"));
      } else {
        JSONArray content = block.optJSONArray("content");
        if (content != null)
          for (int j = 0; j < content.length(); j++) {
            JSONObject part = content.getJSONObject(j);
            if (part.optString("type").equals("output_text")) text.append(part.optString("text"));
          }
      }
    }
    return text.toString();
  }

  public static boolean complete(String protocol, JSONObject event) {
    String type = event.optString("type");
    if (protocol.equals("responses"))
      return type.equals("response.completed") || type.equals("response.incomplete");
    if (protocol.equals("anthropic")) return type.equals("message_stop");
    JSONArray choices = event.optJSONArray("choices");
    return choices != null
        && choices.length() > 0
        && !choices.optJSONObject(0).isNull("finish_reason");
  }

  public static boolean truncated(String protocol, JSONObject event) {
    if (protocol.equals("responses"))
      return event.optString("type").equals("response.incomplete")
          || event.optString("status").equals("incomplete");
    if (protocol.equals("anthropic"))
      return event.optString("stop_reason").equals("max_tokens")
          || event.optJSONObject("delta") != null
              && event.optJSONObject("delta").optString("stop_reason").equals("max_tokens");
    JSONArray choices = event.optJSONArray("choices");
    return choices != null
        && choices.length() > 0
        && choices.optJSONObject(0).optString("finish_reason").equals("length");
  }
}
