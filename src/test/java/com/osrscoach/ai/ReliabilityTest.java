package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.osrscoach.CoachConfig;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import com.osrscoach.research.WikiClient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReliabilityTest
{
	private static final Gson GSON = new Gson();
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

	@AfterEach
	void stop()
	{
		exec.shutdownNow();
	}

	private static CoachConfig cfg(boolean wiki)
	{
		return new CoachConfig()
		{
			@Override public Provider provider() { return Provider.OPENAI_COMPATIBLE; }
			@Override public String apiKey() { return "gsk"; }
			@Override public String model() { return "openai/gpt-oss-120b"; }
			@Override public String baseUrl() { return "https://api.groq.com/openai/v1"; }
			@Override public boolean wikiLookups() { return wiki; }
			@Override public boolean mapMarkers() { return false; }
			@Override public String backup1BaseUrl() { return "https://generativelanguage.googleapis.com/v1beta/openai/"; }
			@Override public String backup1ApiKey() { return "AIza"; }
			@Override public String backup1Model() { return "gemini-3.8-flash"; }
		};
	}

	private CoachService service(FakeHttp ai, FakeHttp wiki, boolean wikiOn)
	{
		WikiClient w = new WikiClient(wiki.client(), GSON, Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES);
		CoachService s = new CoachService(() -> CompletableFuture.completedFuture(MockData.beginner()), () -> cfg(wikiOn), () -> null,
			ai.client(), GSON, w, exec, Clock.fixed(MockData.NOW, ZoneOffset.UTC));
		s.retryUnit = TimeUnit.MILLISECONDS;
		return s;
	}

	@Test
	void everythingDownStillGivesWikiResults() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(402, "{\"error\":\"no credit\"}")
			.respond(429, "[{\"error\":{\"message\":\"You exceeded your current quota\"}}]");
		FakeHttp wiki = new FakeHttp().respond(200, "{\"query\":{\"search\":[{\"title\":\"Priest in Peril\",\"snippet\":\"Kill the <span>ghoul</span>...\"},{\"title\":\"Ghoul\",\"snippet\":\"Ghouls are found north of Canifis\"}]}}");
		CoachReply r = service(ai, wiki, true).ask("where do i go for the slayer quest", null).get(5, TimeUnit.SECONDS);
		assertFalse(r.isLive());
		assertEquals("Wiki results — no AI", r.getProviderLabel());
		assertTrue(r.getText().startsWith("**AI is unavailable right now**"), r.getText());
		assertTrue(r.getText().contains("[Priest in Peril](https://oldschool.runescape.wiki/w/Priest_in_Peril)"), r.getText());
		assertEquals(2, r.getLookedUp().size());
		assertEquals("slayer quest", wiki.requests.get(0).url().queryParameter("srsearch"));
		assertTrue(r.getWarnings().get(0).startsWith("No AI answer:"));
	}

	@Test
	void rateLimitedServiceIsSkippedNextTime() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(429, "[{\"error\":{\"message\":\"You exceeded your current quota\"}}]") // main: quota, no hint -> 15 min cooldown
			.respond(200, "{\"choices\":[{\"message\":{\"content\":\"backup 1\"}}]}")
			.respond(200, "{\"choices\":[{\"message\":{\"content\":\"backup again\"}}]}");
		CoachService s = service(ai, new FakeHttp(), false);
		assertEquals("backup 1", s.ask("q1", null).get(5, TimeUnit.SECONDS).getText());
		CoachReply second = s.ask("q2", null).get(5, TimeUnit.SECONDS);
		assertEquals("backup again", second.getText());
		assertEquals(3, ai.requests.size(), "main was not called again while cooling down");
		assertEquals("generativelanguage.googleapis.com", ai.requests.get(2).url().host());
		assertTrue(second.getWarnings().get(0).contains("cooling down"), second.getWarnings().toString());

		s.resetCooldowns();
		ai.respond(200, "{\"choices\":[{\"message\":{\"content\":\"main back\"}}]}");
		assertEquals("main back", s.ask("q3", null).get(5, TimeUnit.SECONDS).getText());
		assertEquals("api.groq.com", ai.requests.get(3).url().host());
	}

	@Test
	void wikiQueryDropsFillerWords()
	{
		assertEquals("slayer quest", CoachService.wikiQuery("Where do I go for the slayer quest?"));
		assertEquals("best way train fishing level 20", CoachService.wikiQuery("what is the best way to train fishing at level 20"));
		assertEquals("where", CoachService.wikiQuery("where"));
	}
}
