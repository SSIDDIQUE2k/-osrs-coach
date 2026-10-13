package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.osrscoach.CoachConfig;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import com.osrscoach.research.WikiClient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BackupProviderTest
{
	private static final Gson GSON = new Gson();
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();

	@AfterEach
	void stop()
	{
		exec.shutdownNow();
	}

	private static CoachConfig cfg(boolean withBackup)
	{
		return new CoachConfig()
		{
			@Override public Provider provider() { return Provider.OPENAI_COMPATIBLE; }
			@Override public String apiKey() { return "hf"; }
			@Override public String model() { return "openai/gpt-oss-120b"; }
			@Override public String baseUrl() { return "https://router.huggingface.co/v1"; }
			@Override public boolean wikiLookups() { return false; }
			@Override public String backup1BaseUrl() { return withBackup ? "https://generativelanguage.googleapis.com/v1beta/openai/" : ""; }
			@Override public String backup1ApiKey() { return "AIza"; }
			@Override public String backup1Model() { return "gemini-3.8-flash"; }
		};
	}

	private CoachService service(FakeHttp ai, boolean backup)
	{
		WikiClient w = new WikiClient(new FakeHttp().client(), GSON, Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES);
		CoachService s = new CoachService(() -> CompletableFuture.completedFuture(MockData.beginner()), () -> cfg(backup), () -> null,
			ai.client(), GSON, w, exec, Clock.fixed(MockData.NOW, ZoneOffset.UTC));
		s.retryUnit = TimeUnit.MILLISECONDS;
		return s;
	}

	@Test
	void outOfCreditFallsBackToBackup() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(402, "{\"error\":\"You have no remaining credits.\"}")
			.respond(200, "{\"choices\":[{\"message\":{\"content\":\"from gemini\"}}]}");
		List<String> progress = new CopyOnWriteArrayList<>();
		CoachReply r = service(ai, true).ask("q", progress::add).get(5, TimeUnit.SECONDS);
		assertEquals("from gemini", r.getText());
		assertTrue(ai.requests.get(0).url().host().equals("router.huggingface.co"));
		assertTrue(ai.requests.get(1).url().toString().startsWith("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"));
		assertEquals("Bearer AIza", ai.requests.get(1).header("Authorization"));
		assertTrue(ai.bodies.get(1).contains("gemini-3.8-flash"));
		assertTrue(r.getWarnings().get(0).contains("answered by Backup 1 (generativelanguage.googleapis.com)"), r.getWarnings().toString());
		assertTrue(progress.get(0).contains("trying Backup 1"), progress.toString());
	}

	@Test
	void allFailingSurfacesLastError()
	{
		FakeHttp ai = new FakeHttp()
			.respond(402, "{\"error\":\"no credit\"}")
			.respond(401, "{\"error\":{\"message\":\"bad key\"}}");
		ExecutionException e = assertThrows(ExecutionException.class, () -> service(ai, true).ask("q", null).get(5, TimeUnit.SECONDS));
		assertEquals(CoachException.Kind.AUTH, ((CoachException) e.getCause()).getKind());
	}

	@Test
	void noBackupConfiguredMeansSingleAttempt()
	{
		FakeHttp ai = new FakeHttp().respond(402, "{\"error\":\"no credit\"}");
		assertThrows(ExecutionException.class, () -> service(ai, false).ask("q", null).get(5, TimeUnit.SECONDS));
		assertEquals(1, ai.requests.size());
	}

	@Test
	void hostOf()
	{
		assertEquals("api.groq.com", CoachService.hostOf("https://api.groq.com/openai/v1"));
		assertEquals("localhost:1234", CoachService.hostOf("http://localhost:1234/v1"));
	}
}
