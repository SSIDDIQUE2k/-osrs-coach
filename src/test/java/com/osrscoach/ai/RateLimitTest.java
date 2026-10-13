package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscoach.CoachConfig;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import com.osrscoach.research.WikiClient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitTest
{
	private static final Gson GSON = new Gson();
	private static final String GROQ_429 = "{\"error\":{\"message\":\"Rate limit reached for model `openai/gpt-oss-120b` on tokens per minute (TPM): Limit 8000, Used 6975, Requested 7514. Please try again in 48.6675s. Need more tokens?\",\"type\":\"tokens\"}}";
	private final List<Long> slept = new ArrayList<>();
	private HttpJson.Sleeper original;

	@BeforeEach
	void stubSleep()
	{
		original = HttpJson.sleeper;
		HttpJson.sleeper = slept::add;
	}

	@AfterEach
	void restore()
	{
		HttpJson.sleeper = original;
	}

	private static CoachRequest req(List<String> progress)
	{
		return CoachRequest.builder().systemPrompt("s").history(Collections.emptyList()).userMessage("q")
			.model("openai/gpt-oss-120b").maxTokens(300).progress(progress::add).build();
	}

	@Test
	void parsesGroqAndHeaderWaits()
	{
		assertEquals(48.6675, HttpJson.retryAfterSeconds(null, GROQ_429), 0.001);
		assertEquals(72.5, HttpJson.retryAfterSeconds(null, "please try again in 1m12.5s"), 0.001);
		assertEquals(7.0, HttpJson.retryAfterSeconds("7", GROQ_429), 0.001);
		assertEquals(-1, HttpJson.retryAfterSeconds(null, "{\"error\":\"slow down\"}"), 0.001);
	}

	@Test
	void waitsThenSucceeds() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(429, GROQ_429).respond(200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
		List<String> progress = new ArrayList<>();
		CoachReply r = new OpenAiCompatibleProvider(ai.client(), GSON, "gsk", "https://api.groq.com/openai/v1").respond(req(progress), new CoachTools(null));
		assertEquals("done", r.getText());
		assertEquals(Collections.singletonList(49_000L), slept);
		assertTrue(progress.get(0).contains("waiting 49s"), progress.toString());
	}

	@Test
	void givesUpWhenWaitTooLongOrRepeated()
	{
		FakeHttp longWait = new FakeHttp().respond(429, "{\"error\":{\"message\":\"Please try again in 5m0s.\"}}");
		CoachException e = assertThrows(CoachException.class,
			() -> new OpenAiCompatibleProvider(longWait.client(), GSON, "k", "https://x.test/v1").respond(req(new ArrayList<>()), new CoachTools(null)));
		assertEquals(CoachException.Kind.RATE_LIMITED, e.getKind());
		assertTrue(slept.isEmpty());

		FakeHttp always = new FakeHttp().respond(429, GROQ_429).respond(429, GROQ_429).respond(429, GROQ_429);
		assertThrows(CoachException.class,
			() -> new OpenAiCompatibleProvider(always.client(), GSON, "k", "https://x.test/v1").respond(req(new ArrayList<>()), new CoachTools(null)));
		assertEquals(HttpJson.MAX_RATE_RETRIES, slept.size());
	}

	@Test
	void groqIsCompactAutomatically()
	{
		CoachConfig groq = new CoachConfig()
		{
			@Override public Provider provider() { return Provider.OPENAI_COMPATIBLE; }
			@Override public String baseUrl() { return "https://api.groq.com/openai/v1"; }
		};
		assertTrue(CoachService.compact(groq));
		assertFalse(CoachService.compact(MockData.config(CoachConfig.Provider.ANTHROPIC, "k", true)));
	}

	@Test
	void compactToolsFetchSmallerPages()
	{
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 1000; i++)
		{
			big.append("requirement ");
		}
		FakeHttp wiki = new FakeHttp().respond(200, "{\"parse\":{\"title\":\"Dragon Slayer I\",\"wikitext\":\"" + big + "\"}}");
		CoachTools tools = new CoachTools(new WikiClient(wiki.client(), GSON, Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES)).compact();
		JsonObject a = new JsonObject();
		a.addProperty("title", "Dragon Slayer I");
		String out = tools.execute("wiki_page", a);
		assertTrue(out.contains("[...page truncated...]"));
		assertTrue(out.length() < 3600, "len " + out.length());
	}
}
