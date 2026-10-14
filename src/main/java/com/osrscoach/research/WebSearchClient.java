package com.osrscoach.research;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * General web search for anything the OSRS Wiki doesn't cover (news, Reddit threads, YouTube guides, Jagex posts).
 * Backends: Serper (Google results) or Brave Search. Blocking: call off the client and Swing threads.
 */
public class WebSearchClient
{
	public enum Backend
	{
		SERPER_GOOGLE("Google (via Serper)", "google.serper.dev"),
		BRAVE("Brave Search", "api.search.brave.com");

		public final String label;
		public final String host;

		Backend(String label, String host)
		{
			this.label = label;
			this.host = host;
		}
	}

	static final String SERPER_URL = "https://google.serper.dev/search";
	static final String BRAVE_URL = "https://api.search.brave.com/res/v1/web/search";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int RESULTS = 6;
	public static final int DEFAULT_PAGE_CHARS = 7000;
	private static final long MAX_PAGE_BYTES = 2_000_000;
	private static final Pattern SCRIPTS = Pattern.compile("(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>");
	private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
	private static final Pattern SPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
	private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

	private final OkHttpClient http;
	private final Gson gson;
	private final Backend backend;
	private final String apiKey;
	private final String serperUrl;
	private final String braveUrl;

	public WebSearchClient(OkHttpClient http, Gson gson, Backend backend, String apiKey)
	{
		this(http, gson, backend, apiKey, SERPER_URL, BRAVE_URL);
	}

	WebSearchClient(OkHttpClient http, Gson gson, Backend backend, String apiKey, String serperUrl, String braveUrl)
	{
		this.http = http;
		this.gson = gson;
		this.backend = backend;
		this.apiKey = com.osrscoach.ai.Secrets.cleanKey(apiKey);
		this.serperUrl = serperUrl;
		this.braveUrl = braveUrl;
	}

	public Backend backend()
	{
		return backend;
	}

	public LookupResult search(String query) throws LookupException
	{
		if (apiKey.isEmpty())
		{
			throw new LookupException("Web search is on but no search API key is set (" + backend.label + ")");
		}
		List<Source> sources = new ArrayList<>();
		StringBuilder sb = new StringBuilder("Web results from ").append(backend.label).append(" for \"").append(query).append("\":\n");
		if (backend == Backend.SERPER_GOOGLE)
		{
			JsonObject body = new JsonObject();
			body.addProperty("q", query);
			body.addProperty("num", RESULTS);
			Request req = new Request.Builder().url(serperUrl)
				.header("X-API-KEY", apiKey)
				.post(RequestBody.create(JSON, gson.toJson(body))).build();
			JsonObject root = json(req, backend.host);
			JsonObject box = obj(root, "answerBox");
			if (box != null)
			{
				String a = firstNonEmpty(str(box, "answer"), str(box, "snippet"));
				if (!a.isEmpty())
				{
					sb.append("Google answer box: ").append(a).append(box.has("link") ? " <" + str(box, "link") + ">" : "").append('\n');
				}
			}
			JsonArray organic = root.has("organic") && root.get("organic").isJsonArray() ? root.getAsJsonArray("organic") : null;
			if (organic == null)
			{
				throw new LookupException("Unexpected response format from " + backend.host);
			}
			for (JsonElement e : organic)
			{
				add(sb, sources, e.getAsJsonObject(), "title", "link", "snippet");
			}
		}
		else
		{
			HttpUrl url = HttpUrl.parse(braveUrl).newBuilder()
				.addQueryParameter("q", query)
				.addQueryParameter("count", String.valueOf(RESULTS))
				.build();
			Request req = new Request.Builder().url(url)
				.header("X-Subscription-Token", apiKey)
				.header("Accept", "application/json").build();
			JsonObject root = json(req, backend.host);
			JsonObject web = obj(root, "web");
			JsonArray results = web != null && web.has("results") && web.get("results").isJsonArray() ? web.getAsJsonArray("results") : null;
			if (results == null)
			{
				if (root.has("type") && "search".equals(str(root, "type")))
				{
					results = new JsonArray();
				}
				else
				{
					throw new LookupException("Unexpected response format from " + backend.host);
				}
			}
			for (JsonElement e : results)
			{
				add(sb, sources, e.getAsJsonObject(), "title", "url", "description");
			}
		}
		if (sources.isEmpty())
		{
			sb.append("(no results)\n");
		}
		return new LookupResult(sb.toString(), sources);
	}

	/** Reads one result page as plain text. Only http(s); size-capped; HTML stripped. */
	public LookupResult fetch(String url) throws LookupException
	{
		return fetch(url, DEFAULT_PAGE_CHARS);
	}

	public LookupResult fetch(String url, int maxChars) throws LookupException
	{
		HttpUrl u = HttpUrl.parse(url);
		if (u == null)
		{
			throw new LookupException("Not a valid web address");
		}
		Request req = new Request.Builder().url(u)
			.header("User-Agent", WikiClient.USER_AGENT)
			.header("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5").build();
		try (Response res = http.newCall(req).execute())
		{
			ResponseBody body = res.body();
			if (!res.isSuccessful() || body == null)
			{
				throw new LookupException("Page fetch failed: HTTP " + res.code() + " from " + u.host());
			}
			MediaType type = body.contentType();
			if (type != null && !"text".equals(type.type()) && !type.subtype().contains("html") && !type.subtype().contains("json"))
			{
				throw new LookupException("That page isn't text (" + type + ")");
			}
			okio.BufferedSource src = body.source();
			src.request(MAX_PAGE_BYTES);
			okio.Buffer buf = src.getBuffer();
			String raw = buf.readUtf8(Math.min(buf.size(), MAX_PAGE_BYTES));
			String text = htmlToText(raw);
			boolean cut = text.length() > maxChars;
			if (cut)
			{
				text = text.substring(0, maxChars);
			}
			String title = u.host();
			return new LookupResult("Page: " + url + "\n" + text + (cut ? "\n[...page truncated...]" : ""),
				java.util.Collections.singletonList(new Source(title, url, null)));
		}
		catch (IOException e)
		{
			throw new LookupException("Couldn't reach " + u.host() + " (" + e.getClass().getSimpleName() + ")", e);
		}
	}

	static String htmlToText(String html)
	{
		String s = SCRIPTS.matcher(html).replaceAll(" ");
		s = s.replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n");
		s = TAGS.matcher(s).replaceAll(" ");
		s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">");
		s = SPACE.matcher(s).replaceAll(" ");
		s = BLANK_LINES.matcher(s).replaceAll("\n");
		return s.trim();
	}

	private JsonObject json(Request req, String host) throws LookupException
	{
		try (Response res = http.newCall(req).execute())
		{
			ResponseBody body = res.body();
			if (res.code() == 401 || res.code() == 403)
			{
				throw new LookupException("Search key rejected by " + host + " (HTTP " + res.code() + ")");
			}
			if (res.code() == 429 || res.code() == 402)
			{
				throw new LookupException("Search quota used up or rate limited at " + host + " (HTTP " + res.code() + ")");
			}
			if (!res.isSuccessful() || body == null)
			{
				throw new LookupException("Search failed: HTTP " + res.code() + " from " + host);
			}
			JsonElement el = gson.fromJson(body.charStream(), JsonElement.class);
			if (el == null || !el.isJsonObject())
			{
				throw new LookupException("Unexpected response format from " + host);
			}
			return el.getAsJsonObject();
		}
		catch (IOException e)
		{
			throw new LookupException("Couldn't reach " + host + " (" + e.getClass().getSimpleName() + ")", e);
		}
		catch (RuntimeException e)
		{
			throw new LookupException("Unreadable response from " + host, e);
		}
	}

	private static void add(StringBuilder sb, List<Source> sources, JsonObject o, String titleKey, String urlKey, String snippetKey)
	{
		String title = str(o, titleKey);
		String link = str(o, urlKey);
		if (link.isEmpty() || !(link.startsWith("https://") || link.startsWith("http://")))
		{
			return;
		}
		String snippet = TAGS.matcher(str(o, snippetKey)).replaceAll("");
		sb.append("- ").append(title).append(" <").append(link).append(">: ").append(snippet).append('\n');
		sources.add(new Source(title.isEmpty() ? link : title, link, null));
	}

	private static JsonObject obj(JsonObject o, String k)
	{
		return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
	}

	private static String str(JsonObject o, String k)
	{
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	private static String firstNonEmpty(String a, String b)
	{
		return a.isEmpty() ? b : a;
	}
}
