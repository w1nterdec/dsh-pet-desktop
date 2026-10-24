package com.dshpet.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** No provider SDK, inherited credentials, retries, redirects or background requests. */
final class AiClient {
  private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "pet-ai-timeout");
            thread.setDaemon(true);
            return thread;
          });

  interface ConnectionFactory {
    HttpURLConnection open(URI uri) throws Exception;
  }

  interface Partial {
    void accept(String text);
  }

  private final ConnectionFactory factory;
  private volatile HttpURLConnection active;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private volatile boolean timedOut;

  AiClient() {
    this(uri -> (HttpURLConnection) uri.toURL().openConnection());
  }

  AiClient(ConnectionFactory factory) {
    this.factory = factory;
  }

  void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    HttpURLConnection connection = active;
    if (connection != null) TIMEOUTS.execute(connection::disconnect);
  }

  String send(AiSettings settings, String system, JSONArray messages, Partial partial)
      throws Exception {
    settings.validate(true);
    if (cancelled.get()) throw new IOException("请求已取消");
    HttpURLConnection connection = factory.open(AiProtocol.endpoint(settings));
    active = connection;
    java.util.concurrent.ScheduledFuture<?> timeout =
        TIMEOUTS.schedule(
            () -> {
              timedOut = true;
              connection.disconnect();
            },
            settings.timeoutSeconds,
            java.util.concurrent.TimeUnit.SECONDS);
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(settings.timeoutSeconds * 1000);
      connection.setUseCaches(false);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "text/event-stream, application/json");
      if (settings.protocol.equals("anthropic")) {
        connection.setRequestProperty("x-api-key", settings.apiKey);
        connection.setRequestProperty("anthropic-version", "2023-06-01");
      } else connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey);
      byte[] bytes =
          AiProtocol.request(settings, system, messages)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream output = connection.getOutputStream()) {
        if (cancelled.get()) throw new IOException("请求已取消");
        output.write(bytes);
      }
      int status = connection.getResponseCode();
      if (status != 200)
        throw new IOException(
            status == 401 || status == 403
                ? "认证失败，请检查密钥和模型权限"
                : status == 429
                    ? "服务商限流或额度不足，请稍后重试"
                    : status >= 300 && status < 400
                        ? "接口返回重定向，请检查基础地址"
                        : "服务商请求失败（HTTP " + status + "），请检查接口与模型设置");
      long deadline = System.nanoTime() + settings.timeoutSeconds * 1000000000L;
      StringBuilder text = new StringBuilder();
      boolean completed = false, truncated = false;
      long lastUpdate = 0;
      try (InputStream raw = connection.getInputStream();
          InputStream bounded = new LimitedResponse(raw, 2 * 1024 * 1024)) {
        if (String.valueOf(connection.getContentType())
            .toLowerCase(java.util.Locale.ROOT)
            .contains("text/event-stream")) {
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8));
          String line;
          StringBuilder data = new StringBuilder();
          while ((line = reader.readLine()) != null) {
            check(deadline);
            if (line.isEmpty()) {
              if (data.length() > 0) {
                if (data.toString().trim().equals("[DONE]")) {
                  completed = true;
                  break;
                }
                JSONObject event = new JSONObject(data.toString());
                text.append(AiProtocol.delta(settings.protocol, event));
                completed |= AiProtocol.complete(settings.protocol, event);
                truncated |= AiProtocol.truncated(settings.protocol, event);
                data.setLength(0);
                checkText(text);
                if (System.nanoTime() - lastUpdate > 60000000L) {
                  partial.accept(text.toString());
                  lastUpdate = System.nanoTime();
                }
              }
            } else if (line.startsWith("data:")) {
              if (data.length() > 0) data.append('\n');
              data.append(line.substring(5).trim());
            }
          }
          if (data.toString().trim().equals("[DONE]")) completed = true;
          else if (data.length() > 0) {
            JSONObject event = new JSONObject(data.toString());
            text.append(AiProtocol.delta(settings.protocol, event));
            completed |= AiProtocol.complete(settings.protocol, event);
            truncated |= AiProtocol.truncated(settings.protocol, event);
            checkText(text);
          }
          if (!completed) throw new IOException("连接提前结束，未保存不完整的回复");
        } else {
          JSONObject response =
              new JSONObject(
                  new String(JsonFiles.read(bounded, 2 * 1024 * 1024), StandardCharsets.UTF_8));
          text.append(AiProtocol.full(settings.protocol, response));
          truncated = AiProtocol.truncated(settings.protocol, response);
        }
      }
      check(deadline);
      checkText(text);
      if (text.toString().trim().isEmpty()) throw new IOException("服务商未返回文本，请检查模型是否支持该协议或增加输出上限");
      if (truncated) text.append("\n\n（回复达到输出上限，可在 AI 设置中调整。）");
      partial.accept(text.toString());
      return text.toString();
    } catch (java.net.SocketTimeoutException error) {
      throw new IOException("请求超时，可缩短对话或调整超时后重试");
    } catch (java.net.UnknownHostException | javax.net.ssl.SSLException error) {
      throw new IOException("无法建立安全连接，请检查网络、域名和证书");
    } catch (IOException error) {
      if (timedOut) throw new IOException("请求超时");
      if (cancelled.get()) throw new IOException("请求已取消");
      throw error;
    } finally {
      timeout.cancel(false);
      active = null;
      connection.disconnect();
    }
  }

  private void check(long deadline) throws IOException {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
    if (System.nanoTime() > deadline) throw new IOException("请求超时");
  }

  private void checkText(StringBuilder text) throws IOException {
    if (text.length() > 16000) throw new IOException("回复过长，请降低输出上限");
  }

  private static final class LimitedResponse extends FilterInputStream {
    long remaining;

    LimitedResponse(InputStream input, long limit) {
      super(input);
      remaining = limit;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value >= 0 && --remaining < 0) throw new IOException("服务商响应超出限制");
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, length);
      if (count > 0 && (remaining -= count) < 0) throw new IOException("服务商响应超出限制");
      return count;
    }
  }
}
