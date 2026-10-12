package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import com.osrscoach.research.WikiClient;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ProvidersTest
{
	private static final Gson GSON = new Gson();
	private static final String KEY = "sk-ant-TEST-SECRET-123";

	private static CoachRequest req()
	{
		return CoachRequest.builder().systemPrompt("sys").history(Collections.singletonList(new ChatTurn(ChatTurn.Role.USER, "earlier")))
			.userMessage("What next?").model("claude-sonnet-5-5").maxTokens(500).accountContext("ctx").build();
	}

	private static CoachTools tools(FakeHttp f)
	{
		return new CoachTools(new WikiClient(f.client(), GSON, Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES));
	}

	// ---------------- Anthropic ----------------

	@Test
	void anthropicToolLoopFetchesWikiAndReturnsSources() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(200, "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"text\",\"text\":\"Let me check.\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"wiki_search\",\"input\":{\"query\":\"Sheep Shearer\"}}]}")
			.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"**Next step:** Finish Sheep Shearer.\"}]}");
		FakeHttp wiki = new FakeHttp().respond(200, "{\"query\":{\"search\":[{\"title\":\"Sheep Shearer\",\"snippet\":\"wool\"}]}}");

		CoachReply r = new AnthropicProvider(ai.client(), GSON, KEY).respond(req(), tools(wiki));

		assertEquals("**Next step:** Finish Sheep Shearer.", r.getText());
		assertTrue(r.isLive());
		assertEquals("https://oldschool.runescape.wiki/w/Sheep_Shearer", r.getLookedUp().get(0).getUrl());
		assertEquals(KEY, ai.requests.get(0).header("x-api-key"));
		assertEquals("2023-06-01", ai.requests.get(0).header("anthropic-version"));
		JsonObject second = GSON.fromJson(ai.bodies.get(1), JsonObject.class);
		String secondMsgs = second.get("messages").toString();
		assertTrue(secondMsgs.contains("tool_result") && secondMsgs.contains("tu_1"), secondMsgs);
		assertTrue(secondMsgs.contains("reference_material"), "tool output must be fenced as data");
		assertTrue(second.has("tools"));
	}

	@Test
	void anthropicMissingKey()
	{
		CoachException e = assertThrows(CoachException.class, () -> new AnthropicProvider(new FakeHttp().client(), GSON, " ").respond(req(), new CoachTools(null)));
		assertEquals(CoachException.Kind.NO_CREDENTIALS, e.getKind());
		assertFalse(e.isRetryable());
	}

	@Test
	void anthropicHttpErrorsAreClassifiedAndNeverEchoTheKey()
	{
		Object[][] cases = {
			{401, CoachException.Kind.AUTH, false},
			{429, CoachException.Kind.RATE_LIMITED, true},
			{529, CoachException.Kind.SERVER, true},
			{400, CoachException.Kind.BAD_CONFIG, false},
		};
		for (Object[] c : cases)
		{
			FakeHttp ai = new FakeHttp().respond((Integer) c[0], "{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"boom\"}}");
			CoachException e = assertThrows(CoachException.class, () -> new AnthropicProvider(ai.client(), GSON, KEY).respond(req(), new CoachTools(null)));
			assertEquals(c[1], e.getKind(), "HTTP " + c[0]);
			assertEquals(c[2], e.isRetryable(), "HTTP " + c[0]);
			assertTrue(e.getMessage().contains("boom"));
			assertFalse(e.getMessage().contains(KEY));
		}
	}

	@Test
	void anthropicInvalidResponses()
	{
		String[] bad = {
			"not json at all",
			"[1,2,3]",
			"{\"id\":\"x\"}",
			"{\"stop_reason\":\"end_turn\",\"content\":[]}",
			"{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"text\",\"text\":\"hm\"}]}",
		};
		for (String b : bad)
		{
			FakeHttp ai = new FakeHttp().respond(200, b);
			CoachException e = assertThrows(CoachException.class, () -> new AnthropicProvider(ai.client(), GSON, KEY).respond(req(), new CoachTools(null)), b);
			assertEquals(CoachException.Kind.INVALID_RESPONSE, e.getKind(), b);
			assertTrue(e.isRetryable());
		}
	}

	@Test
	void anthropicNetworkErrors()
	{
		CoachException timeout = assertThrows(CoachException.class, () -> new AnthropicProvider(new FakeHttp().fail(new SocketTimeoutException()).client(), GSON, KEY).respond(req(), new CoachTools(null)));
		assertEquals(CoachException.Kind.NETWORK, timeout.getKind());
		assertTrue(timeout.getMessage().contains("timeout"));
		CoachException dns = assertThrows(CoachException.class, () -> new AnthropicProvider(new FakeHttp().fail(new UnknownHostException("api")).client(), GSON, KEY).respond(req(), new CoachTools(null)));
		assertEquals(CoachException.Kind.NETWORK, dns.getKind());
	}

	@Test
	void anthropicWikiDownStillAnswersWithWarning() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(200, "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"wiki_page\",\"input\":{\"title\":\"Dragon Slayer I\"}}]}")
			.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"I couldn't verify the requirements.\"}]}");
		FakeHttp wiki = new FakeHttp().fail(new UnknownHostException("oldschool.runescape.wiki"));
		CoachReply r = new AnthropicProvider(ai.client(), GSON, KEY).respond(req(), tools(wiki));
		assertEquals(1, r.getWarnings().size());
		assertTrue(r.getWarnings().get(0).contains("Couldn't reach"));
		assertTrue(ai.bodies.get(1).contains("LOOKUP_FAILED"));
		assertTrue(r.getLookedUp().isEmpty());
	}

	@Test
	void anthropicNoToolsWhenLookupsDisabled() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
		new AnthropicProvider(ai.client(), GSON, KEY).respond(req(), new CoachTools(null));
		assertFalse(GSON.fromJson(ai.bodies.get(0), JsonObject.class).has("tools"));
	}

	@Test
	void anthropicStopsRunawayToolLoop() throws Exception
	{
		FakeHttp ai = new FakeHttp();
		String tool = "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"wiki_search\",\"input\":{\"query\":\"q\"}}]}";
		for (int i = 0; i < AnthropicProvider.MAX_ROUNDS; i++)
		{
			ai.respond(200, tool);
		}
		ai.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"final\"}]}");
		FakeHttp wiki = new FakeHttp();
		for (int i = 0; i < 10; i++)
		{
			wiki.respond(200, "{\"query\":{\"search\":[]}}");
		}
		CoachReply r = new AnthropicProvider(ai.client(), GSON, KEY).respond(req(), tools(wiki));
		assertEquals("final", r.getText());
		JsonObject last = GSON.fromJson(ai.bodies.get(ai.bodies.size() - 1), JsonObject.class);
		assertEquals("none", last.getAsJsonObject("tool_choice").get("type").getAsString());
	}

	// ---------------- OpenAI-compatible ----------------

	@Test
	void openAiToolCallFlow() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(200, "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"ge_price\",\"arguments\":\"{\\\"item_name\\\":\\\"Lobster\\\"}\"}}]}}]}")
			.respond(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Lobsters cost about 150 gp.\"}}]}");
		FakeHttp wiki = new FakeHttp()
			.respond(200, "[{\"id\":379,\"name\":\"Lobster\"}]")
			.respond(200, "{\"data\":{\"379\":{\"high\":152,\"highTime\":1791835000,\"low\":148,\"lowTime\":1791834000}}}");
		CoachReply r = new OpenAiCompatibleProvider(ai.client(), GSON, "sk-test", "https://api.openai.com/v1/").respond(req(), tools(wiki));
		assertEquals("Lobsters cost about 150 gp.", r.getText());
		assertEquals("https://api.openai.com/v1/chat/completions", ai.requests.get(0).url().toString());
		assertEquals("Bearer sk-test", ai.requests.get(0).header("Authorization"));
		assertTrue(ai.bodies.get(1).contains("\"tool_call_id\":\"c1\""));
		assertTrue(r.getLookedUp().get(0).getNote().startsWith("checked"));
	}

	@Test
	void openAiLocalServerNeedsNoKey() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
		CoachReply r = new OpenAiCompatibleProvider(ai.client(), GSON, "", "http://localhost:11434/v1").respond(req(), new CoachTools(null));
		assertEquals("hi", r.getText());
		assertEquals(null, ai.requests.get(0).header("Authorization"));
	}

	@Test
	void openAiBadConfigAndInvalidResponses()
	{
		assertEquals(CoachException.Kind.BAD_CONFIG, assertThrows(CoachException.class,
			() -> new OpenAiCompatibleProvider(new FakeHttp().client(), GSON, "k", "api.openai.com").respond(req(), new CoachTools(null))).getKind());
		assertEquals(CoachException.Kind.NO_CREDENTIALS, assertThrows(CoachException.class,
			() -> new OpenAiCompatibleProvider(new FakeHttp().client(), GSON, "", "https://openrouter.ai/api/v1").respond(req(), new CoachTools(null))).getKind());
		for (String b : new String[]{"{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", "{}"})
		{
			FakeHttp ai = new FakeHttp().respond(200, b);
			assertEquals(CoachException.Kind.INVALID_RESPONSE, assertThrows(CoachException.class,
				() -> new OpenAiCompatibleProvider(ai.client(), GSON, "k", "https://x.test/v1").respond(req(), new CoachTools(null)), b).getKind());
		}
	}

	@Test
	void openAiMalformedToolArgumentsAreReportedToModel() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(200, "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"wiki_search\",\"arguments\":\"{not json\"}}]}}]}")
			.respond(200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
		CoachReply r = new OpenAiCompatibleProvider(ai.client(), GSON, "k", "https://x.test/v1").respond(req(), tools(new FakeHttp()));
		assertEquals("done", r.getText());
		assertTrue(ai.bodies.get(1).contains("BAD_ARGUMENTS"));
	}
}
