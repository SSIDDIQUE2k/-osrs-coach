package com.osrscoach.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Shared POST-JSON helper. Never logs headers or bodies (they may hold the API key or account data). */
final class HttpJson
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int MAX_ERROR_DETAIL = 300;
	// Groq: "try again in 48.6s" / "1m12.5s"; Gemini: "Please retry in 41.5s" or "retryDelay": "41s".
	private static final Pattern TRY_AGAIN = Pattern.compile("(?i)(?:try again|retry) in\\s*(?:(\\d+)m)?\\s*([0-9.]+)\\s*s");
	private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"");

	/**
	 * Single request, no waiting. Rate limits (429) and transient server errors (5xx) come back as a retryable
	 * {@link CoachException} carrying the server's suggested delay; {@link CoachService} schedules any retry.
	 */
	static JsonObject post(OkHttpClient http, Gson gson, String url, Map<String, String> headers, JsonObject body, String providerName)
		throws CoachException
	{
		return postOnce(http, gson, url, headers, body, providerName);
	}

	static double retryAfterSeconds(String header, String body)
	{
		if (header != null)
		{
			try
			{
				return Double.parseDouble(header.trim());
			}
			catch (NumberFormatException ignored)
			{
				// fall through to body
			}
		}
		if (body != null)
		{
			Matcher m = TRY_AGAIN.matcher(body);
			if (m.find())
			{
				double mins = m.group(1) == null ? 0 : Double.parseDouble(m.group(1));
				return mins * 60 + Double.parseDouble(m.group(2));
			}
			Matcher d = RETRY_DELAY.matcher(body);
			if (d.find())
			{
				return Double.parseDouble(d.group(1));
			}
		}
		return -1;
	}

	private HttpJson()
	{
	}

	static JsonObject postOnce(OkHttpClient http, Gson gson, String url, Map<String, String> headers, JsonObject body, String providerName)
		throws CoachException
	{
		Request req;
		try
		{
			Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(body)));
			for (Map.Entry<String, String> h : headers.entrySet())
			{
				rb.header(h.getKey(), h.getValue());
			}
			req = rb.build();
		}
		catch (IllegalArgumentException e)
		{
			// Deliberately no cause/message: OkHttp's message echoes the header value, i.e. the API key.
			throw new CoachException(CoachException.Kind.BAD_CONFIG, "The API key or Base URL for " + providerName
				+ " contains a character that can't be sent. Delete it in settings and paste it again.", false);
		}
		try (Response res = http.newCall(req).execute())
		{
			ResponseBody rbody = res.body();
			String text = rbody == null ? "" : rbody.string();
			if (!res.isSuccessful())
			{
				CoachException ce = CoachException.fromStatus(res.code(), providerName, errorDetail(gson, text));
				if (res.code() == 429)
				{
					throw ce.withRetryAfter(retryAfterSeconds(res.header("Retry-After"), text), false);
				}
				if (res.code() == 500 || res.code() == 502 || res.code() == 503 || res.code() == 504 || res.code() == 529)
				{
					double hinted = retryAfterSeconds(res.header("Retry-After"), null);
					throw ce.withRetryAfter(hinted > 0 ? hinted : 3, true);
				}
				throw ce;
			}
			try
			{
				JsonElement el = gson.fromJson(text, JsonElement.class);
				if (el == null || !el.isJsonObject())
				{
					throw new CoachException(CoachException.Kind.INVALID_RESPONSE, providerName + " sent a response that isn't a JSON object.", true);
				}
				return el.getAsJsonObject();
			}
			catch (JsonSyntaxException e)
			{
				throw new CoachException(CoachException.Kind.INVALID_RESPONSE, providerName + " sent malformed JSON.", true, e);
			}
		}
		catch (InterruptedIOException e)
		{
			throw new CoachException(CoachException.Kind.NETWORK, providerName + " took too long to answer (timeout). Try again.", true, e);
		}
		catch (IOException e)
		{
			throw new CoachException(CoachException.Kind.NETWORK, "Couldn't reach " + providerName + " (" + e.getClass().getSimpleName() + "). Check your internet connection.", true, e);
		}
	}

	/** Extracts a short provider error message; never echoes request data. */
	static String errorDetail(Gson gson, String body)
	{
		try
		{
			JsonElement root = gson.fromJson(body, JsonElement.class);
			// Gemini's OpenAI-compatible endpoint wraps errors in a list: [{"error":{...}}]
			if (root != null && root.isJsonArray() && root.getAsJsonArray().size() > 0)
			{
				root = root.getAsJsonArray().get(0);
			}
			JsonObject o = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
			if (o != null && o.has("error"))
			{
				JsonElement e = o.get("error");
				String msg = e.isJsonObject() && e.getAsJsonObject().has("message") ? e.getAsJsonObject().get("message").getAsString() : e.toString();
				return msg.length() > MAX_ERROR_DETAIL ? msg.substring(0, MAX_ERROR_DETAIL) + "…" : msg;
			}
		}
		catch (RuntimeException ignored)
		{
			// fall through
		}
		return "";
	}
}
