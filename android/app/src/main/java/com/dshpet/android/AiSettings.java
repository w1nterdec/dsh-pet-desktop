package com.dshpet.android;

import java.io.IOException;
import java.net.URI;
import org.json.JSONObject;

/** Provider settings have no toString override: secret values must not enter logs. */
public final class AiSettings {
  public boolean enabled;
  public String protocol = "compatible", baseUrl = "https://api.openai.com/v1", model = "";
  String apiKey = "";
  public int maxTokens = 512, memoryRounds = 5, timeoutSeconds = 60;

  public static AiSettings from(JSONObject json) throws Exception {
    AiSettings settings = new AiSettings();
    settings.enabled = json.optBoolean("enabled", false);
    settings.protocol = json.optString("protocol", "compatible");
    settings.baseUrl = json.optString("baseUrl", settings.baseUrl);
    settings.model = json.optString("model", "");
    settings.apiKey = json.optString("apiKey", "");
    settings.maxTokens = json.optInt("maxTokens", 512);
    settings.memoryRounds = json.optInt("memoryRounds", 5);
    settings.timeoutSeconds = json.optInt("timeoutSeconds", 60);
    settings.validate(settings.enabled);
    return settings;
  }

  static URI validateBase(String value) throws IOException {
    try {
      URI uri = new URI(value);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getHost().isEmpty()
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() < -1
          || uri.getPort() > 65535
          || value.length() > 2048
          || value.matches(".*[\\p{Cc}\\s].*"))
        throw new IOException("服务商地址必须是没有凭据、查询参数或片段的 HTTPS 地址");
      for (String part : uri.getPath().split("/"))
        if (part.equals(".") || part.equals("..")) throw new IOException("服务商路径无效");
      return uri;
    } catch (java.net.URISyntaxException error) {
      throw new IOException("服务商地址无效");
    }
  }

  public void validate(boolean requireKey) throws IOException {
    if (!protocol.equals("compatible")
        && !protocol.equals("responses")
        && !protocol.equals("anthropic")) throw new IOException("请选择有效的接口协议");
    validateBase(baseUrl);
    if (model.length() > 200
        || java.util.regex.Pattern.compile("[\\p{Cc}]").matcher(model).find()
        || apiKey.length() > 8192
        || java.util.regex.Pattern.compile("[\\p{Cc}]").matcher(apiKey).find())
      throw new IOException("模型或密钥格式无效");
    if (maxTokens < 128
        || maxTokens > 4096
        || memoryRounds < 0
        || memoryRounds > 20
        || timeoutSeconds < 15
        || timeoutSeconds > 120) throw new IOException("输出限制、记忆轮数或超时超出范围");
    if (requireKey && (model.trim().isEmpty() || apiKey.trim().isEmpty()))
      throw new IOException("请先填写模型 ID 和 API 密钥");
  }

  public static AiSettings merge(AiSettings previous, JSONObject input, String newKey)
      throws Exception {
    AiSettings next = from(new JSONObject(input.toString()).put("enabled", false));
    boolean sameOrigin =
        validateBase(previous.baseUrl).resolve("/").equals(validateBase(next.baseUrl).resolve("/"));
    next.apiKey = newKey.trim().isEmpty() ? (sameOrigin ? previous.apiKey : "") : newKey.trim();
    next.enabled = input.optBoolean("enabled");
    next.validate(next.enabled);
    return next;
  }

  JSONObject json() throws Exception {
    return new JSONObject()
        .put("enabled", enabled)
        .put("protocol", protocol)
        .put("baseUrl", baseUrl)
        .put("model", model)
        .put("apiKey", apiKey)
        .put("maxTokens", maxTokens)
        .put("memoryRounds", memoryRounds)
        .put("timeoutSeconds", timeoutSeconds);
  }

  public boolean hasKey() {
    return !apiKey.isEmpty();
  }
}
