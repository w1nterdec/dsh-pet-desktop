package com.dshpet.android;

import static org.junit.Assert.*;

import java.io.IOException;
import org.json.*;
import org.junit.Test;

public final class AiProtocolTest {
  private AiSettings settings(String protocol, String base) throws Exception {
    AiSettings settings = new AiSettings();
    settings.protocol = protocol;
    settings.baseUrl = base;
    settings.model = "user-model";
    settings.apiKey = "TEST-KEY-SENTINEL";
    settings.enabled = true;
    return settings;
  }

  @Test
  public void protocolsUseTheirOwnEndpointAndTokenFieldsWithoutSerializingCredentials()
      throws Exception {
    JSONArray messages =
        new JSONArray().put(new JSONObject().put("role", "user").put("content", "你好"));
    AiSettings official = settings("compatible", "https://api.openai.com/v1/");
    JSONObject body = AiProtocol.request(official, "persona", messages);
    assertEquals(
        "https://api.openai.com/v1/chat/completions", AiProtocol.endpoint(official).toString());
    assertTrue(body.has("max_completion_tokens"));
    assertFalse(body.has("max_tokens"));
    AiSettings compatible = settings("compatible", "https://provider.test/v1/chat/completions");
    assertEquals(compatible.baseUrl, AiProtocol.endpoint(compatible).toString());
    assertTrue(AiProtocol.request(compatible, "persona", messages).has("max_tokens"));
    AiSettings responses = settings("responses", "https://api.openai.com/v1");
    JSONObject response = AiProtocol.request(responses, "persona", messages);
    assertEquals("https://api.openai.com/v1/responses", AiProtocol.endpoint(responses).toString());
    assertFalse(response.getBoolean("store"));
    assertTrue(response.has("max_output_tokens"));
    AiSettings anthropic = settings("anthropic", "https://api.anthropic.com");
    assertEquals(
        "https://api.anthropic.com/v1/messages", AiProtocol.endpoint(anthropic).toString());
    assertEquals("persona", AiProtocol.request(anthropic, "persona", messages).getString("system"));
    assertFalse(body.toString().contains("TEST-KEY-SENTINEL"));
  }

  @Test
  public void settingsRejectUnsafeOriginsAndDoNotCarryAKeyToAnotherProvider() throws Exception {
    for (String base :
        new String[] {
          "http://provider.test",
          "https://user:secret@provider.test",
          "https://provider.test?api_key=secret",
          "https://provider.test#secret",
          "https://provider.test/a/../b"
        }) assertThrows(base, IOException.class, () -> AiSettings.validateBase(base));
    AiSettings before = settings("compatible", "https://one.test/v1");
    JSONObject same = before.json().put("apiKey", "").put("baseUrl", "https://one.test/proxy");
    assertEquals("TEST-KEY-SENTINEL", AiSettings.merge(before, same, "").apiKey);
    assertThrows(
        IOException.class,
        () -> AiSettings.merge(before, same.put("baseUrl", "https://two.test/v1"), ""));
    assertEquals("NEW-KEY", AiSettings.merge(before, same, "NEW-KEY").apiKey);
  }

  @Test
  public void onlyTextDeltasAreDisplayedAndTruncationIsRecognized() throws Exception {
    assertEquals(
        "hello",
        AiProtocol.delta(
            "responses", new JSONObject("{type:'response.output_text.delta',delta:'hello'}")));
    assertEquals(
        "",
        AiProtocol.delta(
            "responses", new JSONObject("{type:'response.reasoning_text.delta',delta:'private'}")));
    assertEquals(
        "hi",
        AiProtocol.delta(
            "anthropic",
            new JSONObject("{type:'content_block_delta',delta:{type:'text_delta',text:'hi'}}")));
    assertEquals(
        "",
        AiProtocol.delta(
            "anthropic",
            new JSONObject(
                "{type:'content_block_delta',delta:{type:'thinking_delta',thinking:'private'}}")));
    assertTrue(
        AiProtocol.truncated("compatible", new JSONObject("{choices:[{finish_reason:'length'}]}")));
    assertTrue(AiProtocol.complete("anthropic", new JSONObject("{type:'message_stop'}")));
    assertThrows(
        IOException.class,
        () -> AiProtocol.delta("compatible", new JSONObject("{error:{message:'SERVER-SECRET'}}")));
  }
}
