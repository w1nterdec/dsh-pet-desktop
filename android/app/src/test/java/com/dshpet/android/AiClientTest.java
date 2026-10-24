package com.dshpet.android;

import static org.junit.Assert.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;

/**
 * Real local HTTP fixture behind injected transport; production configuration remains HTTPS-only.
 */
public final class AiClientTest {
  private AiSettings settings(String protocol) {
    AiSettings value = new AiSettings();
    value.enabled = true;
    value.model = "fixture-model";
    value.protocol = protocol;
    value.baseUrl = "https://provider.test/v1";
    value.apiKey = "TEST-KEY-SENTINEL";
    return value;
  }

  private String run(
      int status,
      String contentType,
      String content,
      String protocol,
      AtomicReference<String> auth,
      List<String> partial)
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      MockResponse response =
          new MockResponse()
              .setResponseCode(status)
              .setHeader("Content-Type", contentType)
              .setBody(content);
      if (content.contains("你好")) response.throttleBody(1, 1, TimeUnit.MILLISECONDS);
      server.enqueue(response);
      server.start();
      AiClient client =
          new AiClient(
              uri -> {
                assertEquals("https", uri.getScheme());
                return (HttpURLConnection) server.url("/").url().openConnection();
              });
      String result =
          client.send(
              settings(protocol),
              "persona",
              new JSONArray().put(new JSONObject().put("role", "user").put("content", "test")),
              partial::add);
      RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
      assertNotNull(request);
      assertFalse(request.getBody().readUtf8().contains("TEST-KEY-SENTINEL"));
      auth.set(request.getHeader(protocol.equals("anthropic") ? "x-api-key" : "Authorization"));
      return result;
    }
  }

  @Test
  public void fragmentedUtf8StreamingIsAccumulatedOnceAndUsesOnlyExplicitAuthentication()
      throws Exception {
    AtomicReference<String> auth = new AtomicReference<>();
    List<String> partial = new ArrayList<>();
    String content =
        "data: "
            + new JSONObject()
                .put(
                    "choices",
                    new JSONArray()
                        .put(new JSONObject().put("delta", new JSONObject().put("content", "你好"))))
            + "\n\ndata: [DONE]\n\n";
    assertEquals("你好", run(200, "text/event-stream", content, "compatible", auth, partial));
    assertEquals("你好", partial.get(partial.size() - 1));
    assertEquals("Bearer TEST-KEY-SENTINEL", auth.get());
  }

  @Test
  public void redirectsAndProviderErrorsNeverExposeTheRemoteBodyOrKey() throws Exception {
    for (int status : new int[] {302, 401, 429, 500}) {
      Exception error =
          assertThrows(
              Exception.class,
              () ->
                  run(
                      status,
                      "application/json",
                      "{error:'SERVER-SECRET-KEY'}",
                      "compatible",
                      new AtomicReference<>(),
                      new ArrayList<>()));
      assertFalse(error.getMessage().contains("SERVER-SECRET"));
      assertFalse(error.getMessage().contains("TEST-KEY"));
    }
  }

  @Test
  public void earlyEofAndExcessiveRepliesAreFailuresNotSuccessfulHistory() {
    assertThrows(
        Exception.class,
        () ->
            run(
                200,
                "text/event-stream",
                "data: {choices:[{delta:{content:'partial'}}]}\n\n",
                "compatible",
                new AtomicReference<>(),
                new ArrayList<>()));
    assertThrows(
        Exception.class,
        () ->
            run(
                200,
                "application/json",
                new JSONObject()
                    .put(
                        "choices",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put(
                                        "message",
                                        new JSONObject().put("content", "x".repeat(17000)))))
                    .toString(),
                "compatible",
                new AtomicReference<>(),
                new ArrayList<>()));
  }

  @Test
  public void responsesAndAnthropicStreamsUseTheirOwnTextAndStopEvents() throws Exception {
    assertEquals(
        "ok",
        run(
            200,
            "text/event-stream",
            "data: {type:'response.output_text.delta',delta:'ok'}\n\n"
                + "data: {type:'response.completed'}\n\n",
            "responses",
            new AtomicReference<>(),
            new ArrayList<>()));
    AtomicReference<String> key = new AtomicReference<>();
    assertEquals(
        "ok",
        run(
            200,
            "text/event-stream",
            "data: {type:'content_block_delta',delta:{type:'text_delta',text:'ok'}}\n\n"
                + "data: {type:'message_stop'}\n\n",
            "anthropic",
            key,
            new ArrayList<>()));
    assertEquals("TEST-KEY-SENTINEL", key.get());
  }

  @Test
  public void cancellationTerminatesAnActiveRead() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody("data: [DONE]\n\n")
              .setBodyDelay(2, TimeUnit.SECONDS));
      server.start();
      AiClient client =
          new AiClient(uri -> (HttpURLConnection) server.url("/").url().openConnection());
      Future<String> result =
          worker.submit(
              () ->
                  client.send(
                      settings("compatible"),
                      "persona",
                      new JSONArray()
                          .put(new JSONObject().put("role", "user").put("content", "test")),
                      ignored -> {}));
      assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
      client.cancel();
      assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
    } finally {
      worker.shutdownNow();
    }
  }
}
