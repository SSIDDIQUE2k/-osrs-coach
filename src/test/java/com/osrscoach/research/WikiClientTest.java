package com.osrscoach.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WikiClientTest
{
	private static WikiClient wiki(FakeHttp f)
	{
		return new WikiClient(f.client(), new Gson(), Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES);
	}

	@Test
	void searchParsesResultsAndSetsUserAgent() throws Exception
	{
		FakeHttp f = new FakeHttp().respond(200, "{\"query\":{\"search\":[{\"title\":\"Cook's Assistant\",\"snippet\":\"<span class=\\\"searchmatch\\\">Cook</span>&#039;s first quest\"}]}}");
		LookupResult r = wiki(f).search("cook");
		assertEquals(1, r.getSources().size());
		assertEquals("https://oldschool.runescape.wiki/w/Cook's_Assistant", r.getSources().get(0).getUrl());
		assertTrue(r.getText().contains("Cook's first quest"), r.getText());
		assertTrue(f.requests.get(0).header("User-Agent").contains("osrs-coach"));
	}

	@Test
	void pageTruncatesAndCites() throws Exception
	{
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 2000; i++)
		{
			big.append("requirement ");
		}
		FakeHttp f = new FakeHttp().respond(200, "{\"parse\":{\"title\":\"Dragon Slayer I\",\"wikitext\":\"" + big + "\"}}");
		LookupResult r = wiki(f).page("Dragon Slayer");
		assertTrue(r.getText().contains("[...page truncated...]"));
		assertEquals("Dragon Slayer I", r.getSources().get(0).getTitle());
	}

	@Test
	void missingPageIsNotAnError() throws Exception
	{
		FakeHttp f = new FakeHttp().respond(200, "{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\"}}");
		LookupResult r = wiki(f).page("Nope");
		assertTrue(r.getText().contains("not found"));
		assertTrue(r.getSources().isEmpty());
	}

	@Test
	void priceShowsCheckedTime() throws Exception
	{
		FakeHttp f = new FakeHttp()
			.respond(200, "[{\"id\":379,\"name\":\"Lobster\"}]")
			.respond(200, "{\"data\":{\"379\":{\"high\":152,\"highTime\":1791835000,\"low\":148,\"lowTime\":1791834000}}}");
		LookupResult r = wiki(f).price("lobster");
		assertTrue(r.getText().contains("152 gp"), r.getText());
		assertTrue(r.getText().contains("checked 2026-10-12 20:00 UTC"), r.getText());
		assertEquals("checked 2026-10-12 20:00 UTC", r.getSources().get(0).getNote());
	}

	@Test
	void unknownItemNoGuess() throws Exception
	{
		FakeHttp f = new FakeHttp().respond(200, "[{\"id\":379,\"name\":\"Lobster\"}]");
		WikiClient w = wiki(f);
		assertTrue(w.price("Golden lobster").getText().startsWith("No tradeable item"));
		assertNull(w.findItemId("nothing"));
	}

	@Test
	void networkAndServerFailuresBecomeLookupExceptions()
	{
		assertThrows(LookupException.class, () -> wiki(new FakeHttp().fail(new SocketTimeoutException())).search("x"));
		assertThrows(LookupException.class, () -> wiki(new FakeHttp().respond(503, "down")).search("x"));
		assertThrows(LookupException.class, () -> wiki(new FakeHttp().respond(200, "<html>not json")).search("x"));
		assertThrows(LookupException.class, () -> wiki(new FakeHttp().respond(200, "{\"unexpected\":1}")).search("x"));
	}
}
