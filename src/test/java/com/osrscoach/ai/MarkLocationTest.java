package com.osrscoach.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscoach.FakeHttp;
import com.osrscoach.MockData;
import com.osrscoach.research.WikiClient;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkLocationTest
{
	private final List<String> placed = new ArrayList<>();
	private final MapMarker fake = new MapMarker()
	{
		@Override
		public void mark(String label, int x, int y, int plane)
		{
			placed.add(label + "@" + x + "," + y + "," + plane);
		}

		@Override
		public void clear()
		{
			placed.clear();
		}
	};

	private CoachTools tools(FakeHttp wiki)
	{
		return new CoachTools(new WikiClient(wiki.client(), new Gson(), Clock.fixed(MockData.NOW, ZoneOffset.UTC), WikiClient.WIKI, WikiClient.PRICES)).withMarker(fake);
	}

	private static JsonObject mark(String label, int x, int y)
	{
		JsonObject o = new JsonObject();
		o.addProperty("label", label);
		o.addProperty("x", x);
		o.addProperty("y", y);
		return o;
	}

	private static JsonObject page(String title)
	{
		JsonObject o = new JsonObject();
		o.addProperty("title", title);
		return o;
	}

	@Test
	void guessedCoordinatesAreRejected()
	{
		CoachTools t = tools(new FakeHttp());
		assertTrue(t.execute("mark_location", mark("Ghouls", 3423, 3461)).startsWith("COORDINATES_UNVERIFIED"));
		assertTrue(placed.isEmpty());
	}

	@Test
	void coordinatesFromFetchedWikiPageAreMarked()
	{
		FakeHttp wiki = new FakeHttp().respond(200, "{\"parse\":{\"title\":\"Ghoul\",\"wikitext\":\"{{Map|x:3423,y:3461|x:3426,y:3465}}\"}}");
		CoachTools t = tools(wiki);
		t.execute("wiki_page", page("Ghoul"));
		String out = t.execute("mark_location", mark("Ghouls north of Canifis", 3423, 3461));
		assertTrue(out.startsWith("MARKED"), out);
		assertEquals("Ghouls north of Canifis@3423,3461,0", placed.get(0));
		assertEquals(1, t.marks().size());
	}

	@Test
	void startmapFormatAccepted()
	{
		FakeHttp wiki = new FakeHttp().respond(200, "{\"parse\":{\"title\":\"Priest in Peril\",\"wikitext\":\"|startmap = 3221,3473\"}}");
		CoachTools t = tools(wiki);
		t.execute("wiki_page", page("Priest in Peril"));
		assertTrue(t.execute("mark_location", mark("Drezel", 3221, 3473)).startsWith("MARKED"));
		assertTrue(t.execute("mark_location", mark("Nearby guess", 3222, 3473)).startsWith("COORDINATES_UNVERIFIED"));
	}

	@Test
	void outOfWorldAndBadArgsRejected()
	{
		CoachTools t = tools(new FakeHttp());
		assertTrue(t.execute("mark_location", mark("Nowhere", 99999, 5)).startsWith("COORDINATES_REJECTED"));
		JsonObject bad = new JsonObject();
		bad.addProperty("label", "x");
		assertTrue(t.execute("mark_location", bad).startsWith("BAD_ARGUMENTS"));
	}

	@Test
	void toolOnlyOfferedWithWikiAndMarker()
	{
		assertTrue(tools(new FakeHttp()).specs().stream().anyMatch(s -> s.name.equals("mark_location")));
		assertFalse(new CoachTools(null).withMarker(fake).specs().stream().anyMatch(s -> s.name.equals("mark_location")));
		assertTrue(new CoachTools(null).withMarker(fake).execute("mark_location", mark("a", 3200, 3200)).startsWith("MARKER_UNAVAILABLE"));
	}

	@Test
	void seenInTextPatterns()
	{
		assertTrue(CoachTools.seenInFetchedText("x:3209,y:3214|r=3", 3209, 3214));
		assertTrue(CoachTools.seenInFetchedText("{{map|3410,3494|zoom=3}}", 3410, 3494));
		assertFalse(CoachTools.seenInFetchedText("13410,3494", 3410, 3494));
		assertFalse(CoachTools.seenInFetchedText("3410,34945", 3410, 3494));
	}
}
