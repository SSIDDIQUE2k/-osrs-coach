package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscoach.CoachConfig;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import com.osrscoach.context.AccountSnapshot;
import com.osrscoach.context.Availability;
import com.osrscoach.research.WikiClient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoachServiceTest
{
	private final ExecutorService exec = Executors.newSingleThreadExecutor();
	private static final Gson GSON = new Gson();

	@AfterEach
	void stop()
	{
		exec.shutdownNow();
	}

	private CoachService service(CoachConfig cfg, Supplier<CompletableFuture<AccountSnapshot>> snap, FakeHttp ai, FakeHttp wiki)
	{
		WikiClient w = new WikiClient(wiki.client(), GSON, Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES);
		return new CoachService(snap, () -> cfg, () -> null, ai.client(), GSON, w, exec, Clock.fixed(MockData.NOW, ZoneOffset.UTC));
	}

	@Test
	void mockModeIsLabelledAndUsesAccountData() throws Exception
	{
		FakeHttp wiki = new FakeHttp().respond(200, "{\"query\":{\"search\":[{\"title\":\"Sheep Shearer\",\"snippet\":\"\"}]}}");
		CoachService s = service(MockData.config(CoachConfig.Provider.MOCK, "", true), () -> CompletableFuture.completedFuture(MockData.beginner()), new FakeHttp(), wiki);
		CoachReply r = s.ask("anything").get();
		assertFalse(r.isLive());
		assertTrue(r.getText().startsWith(MockProvider.BANNER));
		assertTrue(r.getText().contains("Continue **Sheep Shearer**"));
		assertEquals(1, r.getLookedUp().size(), "mock still demonstrates a real wiki lookup");
	}

	@Test
	void mockModeWithSearchUnavailable() throws Exception
	{
		CoachService s = service(MockData.config(CoachConfig.Provider.MOCK, "", true), () -> CompletableFuture.completedFuture(MockData.beginner()),
			new FakeHttp(), new FakeHttp().fail(new java.net.UnknownHostException("wiki")));
		CoachReply r = s.ask("x").get();
		assertTrue(r.getText().contains("Wiki lookup failed"));
		assertEquals(1, r.getWarnings().size());
	}

	@Test
	void mockModeLoggedOut() throws Exception
	{
		CoachService s = service(MockData.config(CoachConfig.Provider.MOCK, "", false), () -> CompletableFuture.completedFuture(AccountSnapshot.loggedOut(MockData.NOW)), new FakeHttp(), new FakeHttp());
		assertTrue(s.ask("x").get().getText().contains("Log in"));
	}

	@Test
	void contextReadFailureIsMarkedUnavailableNotZero() throws Exception
	{
		FakeHttp ai = new FakeHttp().respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
		CompletableFuture<AccountSnapshot> broken = new CompletableFuture<>();
		broken.completeExceptionally(new IllegalStateException("client thread died"));
		CoachService s = service(MockData.config(CoachConfig.Provider.ANTHROPIC, "k", false), () -> broken, ai, new FakeHttp());
		s.ask("x").get();
		assertEquals(Availability.ERROR, s.lastSnapshot().getSkillsAvailability());
		String system = GSON.fromJson(ai.bodies.get(0), JsonObject.class).get("system").getAsString();
		assertTrue(system.contains("unavailable: read error — do NOT treat skills as level 1"), system);
		assertTrue(system.contains("Lookup tools: UNAVAILABLE"));
	}

	@Test
	void liveRequestCarriesCoreInstructionContextAndHistory() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"first\"}]}")
			.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"second\"}]}");
		CoachService s = service(MockData.config(CoachConfig.Provider.ANTHROPIC, "k", true), () -> CompletableFuture.completedFuture(MockData.beginner()), ai, new FakeHttp());
		s.ask("q1").get();
		s.ask("q2").get();
		JsonObject body = GSON.fromJson(ai.bodies.get(1), JsonObject.class);
		String system = body.get("system").getAsString();
		assertTrue(system.startsWith(CoachPrompt.CORE));
		assertTrue(system.contains("Budget: 200k gp"));
		assertTrue(system.contains("Sheep Shearer"));
		assertEquals(3, body.getAsJsonArray("messages").size(), "q1, first, q2");
		s.clearHistory();
	}

	@Test
	void errorsSurfaceAsCoachException()
	{
		CoachService s = service(MockData.config(CoachConfig.Provider.ANTHROPIC, "", true), () -> CompletableFuture.completedFuture(MockData.beginner()), new FakeHttp(), new FakeHttp());
		ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, () -> s.ask("x").get());
		assertTrue(e.getCause() instanceof CoachException);
		assertEquals(CoachException.Kind.NO_CREDENTIALS, ((CoachException) e.getCause()).getKind());
	}

	@Test
	void webSearchToolsOfferedOnlyWhenConfigured() throws Exception
	{
		FakeHttp ai = new FakeHttp()
			.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}")
			.respond(200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"b\"}]}");
		CoachConfig off = MockData.config(CoachConfig.Provider.ANTHROPIC, "k", true);
		CoachConfig on = new CoachConfig()
		{
			@Override public Provider provider() { return Provider.ANTHROPIC; }
			@Override public String apiKey() { return "k"; }
			@Override public WebSearch webSearch() { return WebSearch.SERPER_GOOGLE; }
			@Override public String webSearchKey() { return "s"; }
		};
		service(off, () -> CompletableFuture.completedFuture(MockData.beginner()), ai, new FakeHttp()).ask("q").get();
		service(on, () -> CompletableFuture.completedFuture(MockData.beginner()), ai, new FakeHttp()).ask("q").get();
		assertFalse(ai.bodies.get(0).contains("web_search"));
		assertTrue(ai.bodies.get(0).contains("General web search is OFF"));
		assertTrue(ai.bodies.get(1).contains("\"name\":\"web_search\""));
		assertTrue(ai.bodies.get(1).contains("Source priority: OSRS Wiki"));
	}
}
