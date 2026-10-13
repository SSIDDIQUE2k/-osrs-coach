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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RateLimitTest
{
	private static final Gson GSON = new Gson();
	private static final String GROQ_429 = "{\"error\":{\"message\":\"Rate limit reached for model `openai/gpt-oss-120b` on tokens per minute (TPM): Limit 8000, Used 6975, Requested 7514. Please try again in 48.6675s. Need more tokens?\",\"type\":\"tokens\"}}";
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

	@AfterEach
	void stop()
	{
		exec.shutdownNow();
	}

	private static CoachConfig groq()
	{
		return new CoachConfig()
		{
			@Override public Provider provider() { return Provider.OPENAI_COMPATIBLE; }
			@Override public String apiKey() { return "gsk"; }
			@Override public String model() { return "openai/gpt-oss-120b"; }
			@Override public String baseUrl() { return "https://api.groq.com/openai/v1"; }
			@Override public boolean wikiLookups() { return false; }
		};
	}

	private CoachService service(FakeHttp ai)
	{
		WikiClient w = new WikiClient(new FakeHttp().client(), GSON, Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES);
		CoachService s = new CoachService(() -> CompletableFuture.completedFuture(MockData.beginner()), RateLimitTest::groq, () -> null,
			ai.client(), GSON, w, exec, Clock.fixed(MockData.NOW, ZoneOffset.UTC));
		s.retryUnit = TimeUnit.MILLISECONDS;
		return s;
	}

	@Test
	void parsesGroqGeminiAndHeaderWaits()
	{
		assertEquals(48.6675, HttpJson.retryAfterSeconds(null, GROQ_429), 0.001);
		assertEquals(72.5, HttpJson.retryAfterSeconds(null, "please try again in 1m12.5s"), 0.001);
		assertEquals(7.0, HttpJson.retryAfterSeconds("7", GROQ_429), 0.001);
		assertEquals(-1, HttpJson.retryAfterSeconds(null, "{\"error\":\"slow down\"}"), 0.001);
		assertEquals(41.5, HttpJson.retryAfterSeconds(null, "You exceeded your current quota. Please retry in 41.5s."), 0.001);
		assertEquals(41.0, HttpJson.retryAfterSeconds(null, "{\"error\":{\"details\":[{\"retryDelay\": \"41s\"}]}}"), 0.001);
	}

	@Test
	void rateLimitIsRetriedBySchedulingNotSleeping() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(429, GROQ_429).respond(200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
		List<String> progress = new CopyOnWriteArrayList<>();
		CoachReply r = service(ai).ask("q", progress::add).get(5, TimeUnit.SECONDS);
		assertEquals("done", r.getText());
		assertTrue(progress.get(0).contains("waiting 49s"), progress.toString());
		assertEquals("Thinking…", progress.get(1));
	}

	@Test
	void serverBusyIsRetried() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(503, "{\"error\":{\"message\":\"overloaded\"}}")
			.respond(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
		List<String> progress = new CopyOnWriteArrayList<>();
		assertEquals("ok", service(ai).ask("q", progress::add).get(5, TimeUnit.SECONDS).getText());
		assertTrue(progress.get(0).startsWith("AI service busy"), progress.toString());
	}

	@Test
	void givesUpWhenWaitTooLongOrRepeated() throws Exception
	{
		FakeHttp longWait = new FakeHttp().respond(429, "{\"error\":{\"message\":\"Please try again in 5m0s.\"}}");
		ExecutionException e1 = assertThrows(ExecutionException.class, () -> service(longWait).ask("q", null).get(5, TimeUnit.SECONDS));
		assertEquals(CoachException.Kind.RATE_LIMITED, ((CoachException) e1.getCause()).getKind());
		assertEquals(1, longWait.requests.size());

		FakeHttp always = new FakeHttp().respond(429, GROQ_429).respond(429, GROQ_429).respond(429, GROQ_429).respond(429, GROQ_429);
		assertThrows(ExecutionException.class, () -> service(always).ask("q", null).get(5, TimeUnit.SECONDS));
		assertEquals(CoachService.MAX_RETRIES + 1, always.requests.size());
	}

	@Test
	void groqIsCompactAutomatically()
	{
		assertTrue(CoachService.compact(groq()));
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
