package com.osrscoach.research;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Read-only access to the OSRS Wiki (MediaWiki API) and the wiki's real-time price API.
 * All methods block and MUST be called off the client and Swing threads.
 *
 * The wiki asks API users to send a descriptive User-Agent:
 * https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices#Please_set_a_descriptive_User-Agent!
 */
public class WikiClient
{
	public static final String WIKI = "https://oldschool.runescape.wiki";
	public static final String PRICES = "https://prices.runescape.wiki/api/v1/osrs";
	static final String USER_AGENT = "osrs-coach RuneLite plugin (beginner coach; github.com/runelite/plugin-hub)";
	public static final int DEFAULT_PAGE_CHARS = 9000;
	private static final long MAPPING_TTL_MS = 6L * 3600 * 1000;
	private static final Pattern TAGS = Pattern.compile("<[^>]+>");
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final OkHttpClient http;
	private final Gson gson;
	private final Clock clock;
	private final String wikiBase;
	private final String pricesBase;

	private Map<String, int[]> nameToId; // lowercased name -> {id}
	private long mappingLoadedAt;

	public WikiClient(OkHttpClient http, Gson gson)
	{
		this(http, gson, Clock.systemUTC(), WIKI, PRICES);
	}

	public WikiClient(OkHttpClient http, Gson gson, Clock clock, String wikiBase, String pricesBase)
	{
		this.http = http;
		this.gson = gson;
		this.clock = clock;
		this.wikiBase = wikiBase;
		this.pricesBase = pricesBase;
	}

	public LookupResult search(String query) throws LookupException
	{
		HttpUrl url = HttpUrl.parse(wikiBase + "/api.php").newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("list", "search")
			.addQueryParameter("srsearch", query)
			.addQueryParameter("srlimit", "5")
			.addQueryParameter("format", "json")
			.build();
		JsonObject root = getJson(url).getAsJsonObject();
		JsonArray hits = path(root, "query", "search");
		if (hits == null)
		{
			throw new LookupException("Wiki search returned an unexpected format");
		}
		StringBuilder sb = new StringBuilder();
		List<Source> sources = new ArrayList<>();
		for (JsonElement e : hits)
		{
			JsonObject h = e.getAsJsonObject();
			String title = h.get("title").getAsString();
			String snippet = h.has("snippet") ? unescape(TAGS.matcher(h.get("snippet").getAsString()).replaceAll("")) : "";
			String link = pageUrl(title);
			sb.append("- ").append(title).append(" <").append(link).append(">: ").append(snippet).append('\n');
			sources.add(new Source(title, link, null));
		}
		if (sources.isEmpty())
		{
			return new LookupResult("No OSRS Wiki pages matched \"" + query + "\".", Collections.emptyList());
		}
		return new LookupResult(sb.toString(), sources);
	}

	public LookupResult page(String title) throws LookupException
	{
		return page(title, DEFAULT_PAGE_CHARS);
	}

	public LookupResult page(String title, int maxChars) throws LookupException
	{
		HttpUrl url = HttpUrl.parse(wikiBase + "/api.php").newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("page", title)
			.addQueryParameter("prop", "wikitext")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.build();
		JsonObject root = getJson(url).getAsJsonObject();
		if (root.has("error"))
		{
			String info = root.getAsJsonObject("error").has("info") ? root.getAsJsonObject("error").get("info").getAsString() : "unknown";
			return new LookupResult("Wiki page \"" + title + "\" not found (" + info + "). Try wiki_search first.", Collections.emptyList());
		}
		JsonObject parse = root.getAsJsonObject("parse");
		if (parse == null || !parse.has("wikitext"))
		{
			throw new LookupException("Wiki page response had no content");
		}
		String realTitle = parse.get("title").getAsString();
		String text = parse.get("wikitext").getAsString();
		boolean truncated = text.length() > maxChars;
		if (truncated)
		{
			text = text.substring(0, maxChars);
		}
		String link = pageUrl(realTitle);
		return new LookupResult("Page: " + realTitle + " <" + link + ">\n" + text + (truncated ? "\n[...page truncated...]" : ""),
			Collections.singletonList(new Source(realTitle, link, null)));
	}

	public LookupResult price(String itemName) throws LookupException
	{
		Integer id = findItemId(itemName);
		if (id == null)
		{
			return new LookupResult("No tradeable item named \"" + itemName + "\" in the price list. Check the exact in-game name.", Collections.emptyList());
		}
		HttpUrl url = HttpUrl.parse(pricesBase + "/latest").newBuilder().addQueryParameter("id", String.valueOf(id)).build();
		JsonObject root = getJson(url).getAsJsonObject();
		JsonObject data = root.getAsJsonObject("data");
		JsonObject p = data == null ? null : data.getAsJsonObject(String.valueOf(id));
		Instant checked = clock.instant();
		String link = "https://prices.runescape.wiki/osrs/item/" + id;
		if (p == null)
		{
			return new LookupResult("No recent trades recorded for " + itemName + " (id " + id + ").",
				Collections.singletonList(new Source(itemName + " price", link, "checked " + HHMM.format(checked))));
		}
		StringBuilder sb = new StringBuilder();
		sb.append(itemName).append(" (id ").append(id).append(") Grand Exchange real-time price, checked ").append(HHMM.format(checked)).append(":\n");
		appendPrice(sb, "Instant-buy (high)", p, "high", "highTime");
		appendPrice(sb, "Instant-sell (low)", p, "low", "lowTime");
		sb.append("Source: ").append(link).append('\n');
		return new LookupResult(sb.toString(), Collections.singletonList(new Source(itemName + " price", link, "checked " + HHMM.format(checked))));
	}

	private void appendPrice(StringBuilder sb, String label, JsonObject p, String key, String timeKey)
	{
		sb.append(label).append(": ");
		if (p.has(key) && !p.get(key).isJsonNull())
		{
			sb.append(String.format(Locale.ROOT, "%,d gp", p.get(key).getAsLong()));
			if (p.has(timeKey) && !p.get(timeKey).isJsonNull())
			{
				sb.append(" (last trade ").append(HHMM.format(Instant.ofEpochSecond(p.get(timeKey).getAsLong()))).append(')');
			}
		}
		else
		{
			sb.append("no data");
		}
		sb.append('\n');
	}

	synchronized Integer findItemId(String name) throws LookupException
	{
		long now = clock.millis();
		if (nameToId == null || now - mappingLoadedAt > MAPPING_TTL_MS)
		{
			JsonElement el = getJson(HttpUrl.parse(pricesBase + "/mapping"));
			if (!el.isJsonArray())
			{
				throw new LookupException("Price mapping had an unexpected format");
			}
			Map<String, int[]> m = new HashMap<>();
			for (JsonElement e : el.getAsJsonArray())
			{
				JsonObject o = e.getAsJsonObject();
				m.put(o.get("name").getAsString().toLowerCase(Locale.ROOT), new int[]{o.get("id").getAsInt()});
			}
			nameToId = m;
			mappingLoadedAt = now;
		}
		int[] id = nameToId.get(name.trim().toLowerCase(Locale.ROOT));
		return id == null ? null : id[0];
	}

	public String pageUrl(String title)
	{
		try
		{
			return wikiBase + "/w/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/").replace("%3A", ":").replace("%27", "'");
		}
		catch (java.io.UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private JsonElement getJson(HttpUrl url) throws LookupException
	{
		Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
		try (Response res = http.newCall(req).execute())
		{
			ResponseBody body = res.body();
			if (!res.isSuccessful() || body == null)
			{
				throw new LookupException("Lookup failed: HTTP " + res.code() + " from " + url.host());
			}
			JsonElement el = gson.fromJson(body.charStream(), JsonElement.class);
			if (el == null)
			{
				throw new LookupException("Empty response from " + url.host());
			}
			return el;
		}
		catch (IOException e)
		{
			throw new LookupException("Couldn't reach " + url.host() + " (" + e.getClass().getSimpleName() + ")", e);
		}
		catch (RuntimeException e)
		{
			throw new LookupException("Unreadable response from " + url.host(), e);
		}
	}

	private static JsonArray path(JsonObject o, String a, String b)
	{
		if (o == null || !o.has(a) || !o.get(a).isJsonObject())
		{
			return null;
		}
		JsonObject x = o.getAsJsonObject(a);
		return x.has(b) && x.get(b).isJsonArray() ? x.getAsJsonArray(b) : null;
	}

	private static String unescape(String s)
	{
		return s.replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
	}

}
