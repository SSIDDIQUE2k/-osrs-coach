package com.osrscoach.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscoach.FakeHttp;
import com.osrscoach.ai.CoachTools;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class WebSearchClientTest
{
	private static final Gson GSON = new Gson();

	private static WebSearchClient client(FakeHttp f, WebSearchClient.Backend b, String key)
	{
		return new WebSearchClient(f.client(), GSON, b, key);
	}

	@Test
	void serperReturnsGoogleResultsAndSendsKeyHeader() throws Exception
	{
		FakeHttp f = new FakeHttp().respond(200, "{\"answerBox\":{\"snippet\":\"Varlamore part 3 released\"},"
			+ "\"organic\":[{\"title\":\"OSRS news\",\"link\":\"https://secure.runescape.com/m=news/x\",\"snippet\":\"Patch notes\"},"
			+ "{\"title\":\"bad\",\"link\":\"javascript:alert(1)\",\"snippet\":\"x\"}]}");
		LookupResult r = client(f, WebSearchClient.Backend.SERPER_GOOGLE, "serper-key").search("osrs update");
		assertEquals("serper-key", f.requests.get(0).header("X-API-KEY"));
		assertEquals("https://google.serper.dev/search", f.requests.get(0).url().toString());
		assertTrue(f.bodies.get(0).contains("\"q\":\"osrs update\""));
		assertTrue(r.getText().contains("Google answer box: Varlamore part 3 released"));
		assertEquals(1, r.getSources().size(), "non-http links are dropped");
	}

	@Test
	void braveParsesResults() throws Exception
	{
		FakeHttp f = new FakeHttp().respond(200, "{\"type\":\"search\",\"web\":{\"results\":[{\"title\":\"Reddit thread\",\"url\":\"https://www.reddit.com/r/2007scape/x\",\"description\":\"<strong>Best</strong> f2p money\"}]}}");
		LookupResult r = client(f, WebSearchClient.Backend.BRAVE, "brave-key").search("f2p money");
		assertEquals("brave-key", f.requests.get(0).header("X-Subscription-Token"));
		assertEquals("f2p money", f.requests.get(0).url().queryParameter("q"));
		assertTrue(r.getText().contains("Best f2p money"), r.getText());
		assertEquals("https://www.reddit.com/r/2007scape/x", r.getSources().get(0).getUrl());
	}

	@Test
	void braveEmptyResultsAreFine() throws Exception
	{
		LookupResult r = client(new FakeHttp().respond(200, "{\"type\":\"search\"}"), WebSearchClient.Backend.BRAVE, "k").search("zzzz");
		assertTrue(r.getText().contains("(no results)"));
	}

	@Test
	void failuresAreLookupExceptions()
	{
		assertThrows(LookupException.class, () -> client(new FakeHttp(), WebSearchClient.Backend.SERPER_GOOGLE, " ").search("x"));
		LookupException auth = assertThrows(LookupException.class, () -> client(new FakeHttp().respond(403, "{}"), WebSearchClient.Backend.SERPER_GOOGLE, "k").search("x"));
		assertTrue(auth.getMessage().contains("key rejected"));
		LookupException quota = assertThrows(LookupException.class, () -> client(new FakeHttp().respond(429, "{}"), WebSearchClient.Backend.BRAVE, "k").search("x"));
		assertTrue(quota.getMessage().contains("quota"));
		assertThrows(LookupException.class, () -> client(new FakeHttp().fail(new UnknownHostException()), WebSearchClient.Backend.BRAVE, "k").search("x"));
		assertThrows(LookupException.class, () -> client(new FakeHttp().respond(200, "{\"oops\":1}"), WebSearchClient.Backend.SERPER_GOOGLE, "k").search("x"));
	}

	@Test
	void htmlToTextDropsScriptsAndTags()
	{
		String t = WebSearchClient.htmlToText("<html><head><style>p{}</style><script>steal()</script></head><body><nav>menu</nav><h1>Guide</h1><p>Use a <b>bronze axe</b>&nbsp;first.</p></body></html>");
		assertFalse(t.contains("steal"));
		assertFalse(t.contains("menu"));
		assertTrue(t.contains("Guide"));
		assertTrue(t.contains("Use a bronze axe first."), t);
	}

	@Test
	void webPageOnlyOpensUrlsFromSearchResults()
	{
		FakeHttp f = new FakeHttp()
			.respond(200, "{\"organic\":[{\"title\":\"Guide\",\"link\":\"https://example.com/guide\",\"snippet\":\"s\"}]}")
			.respond(200, "<html><body><p>Woodcutting tips</p></body></html>");
		CoachTools tools = new CoachTools(null, client(f, WebSearchClient.Backend.SERPER_GOOGLE, "k"));

		JsonObject bad = new JsonObject();
		bad.addProperty("url", "http://192.168.1.1/admin");
		assertTrue(tools.execute("web_page", bad).startsWith("NOT_ALLOWED"));

		JsonObject q = new JsonObject();
		q.addProperty("query", "osrs woodcutting");
		String search = tools.execute("web_search", q);
		assertTrue(search.contains("general-web"), "web results are marked lower-trust");
		assertTrue(tools.sources().isEmpty(), "search hits are not listed as fetched pages");

		JsonObject ok = new JsonObject();
		ok.addProperty("url", "https://example.com/guide");
		String page = tools.execute("web_page", ok);
		assertTrue(page.contains("Woodcutting tips"));
		assertEquals("https://example.com/guide", tools.sources().get(0).getUrl());

		JsonObject wiki = new JsonObject();
		wiki.addProperty("query", "x");
		assertTrue(tools.execute("wiki_search", wiki).startsWith("LOOKUP_UNAVAILABLE"), "wiki off stays off");
		assertEquals(2, tools.specs().size());
	}
}
