package com.osrscoach.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.function.Consumer;
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
	static final int MAX_WAIT_SECONDS = 65;
	static final int MAX_RATE_RETRIES = 2;
	private static final Pattern TRY_AGAIN = Pattern.compile("(?i)try again in\\s*(?:(\\d+)m)?\\s*([0-9.]+)\\s*s");
	/** Swappable for tests. */
	static Sleeper sleeper = ms -> Thread.sleep(ms);

	interface Sleeper
	{
		void sleep(long ms) throws InterruptedException;
	}

	static JsonObject post(OkHttpClient http, Gson gson, String url, Map<String, String> headers, JsonObject body, String providerName)
		throws CoachException
	{
		return post(http, gson, url, headers, body, providerName, null);
	}

	/**
	 * Like {@link #postOnce}, but when the provider says "rate limited, try again in N seconds" (N up to about a minute),
	 * waits and retries instead of failing. Free tiers (e.g. Groq) need this for multi-step answers.
	 */
	static JsonObject post(OkHttpClient http, Gson gson, String url, Map<String, String> headers, JsonObject body, String providerName,
		Consumer<String> progress) throws CoachException
	{
		for (int attempt = 0; ; attempt++)
		{
			try
			{
				return postOnce(http, gson, url, headers, body, providerName);
			}
			catch (RateLimited rl)
			{
				if (attempt >= MAX_RATE_RETRIES || rl.waitSeconds < 0 || rl.waitSeconds > MAX_WAIT_SECONDS)
				{
					throw rl.error;
				}
				long secs = Math.max(1, (long) Math.ceil(rl.waitSeconds));
				if (progress != null)
				{
					progress.accept("Free-tier limit reached — waiting " + secs + "s, then continuing…");
				}
				try
				{
					sleeper.sleep(secs * 1000L);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw rl.error;
				}
				if (progress != null)
				{
					progress.accept("Thinking…");
				}
			}
		}
	}

	/** Internal: a 429 with an optional server-suggested wait. */
	static final class RateLimited extends Exception
	{
		final double waitSeconds;
		final CoachException error;

		RateLimited(double waitSeconds, CoachException error)
		{
			super(null, null, false, false);
			this.waitSeconds = waitSeconds;
			this.error = error;
		}
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
		}
		return -1;
	}

	private HttpJson()
	{
	}

	static JsonObject postOnce(OkHttpClient http, Gson gson, String url, Map<String, String> headers, JsonObject body, String providerName)
		throws CoachException, RateLimited
	{
		Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(body)));
		for (Map.Entry<String, String> h : headers.entrySet())
		{
			rb.header(h.getKey(), h.getValue());
		}
		Request req;
		try
		{
			req = rb.build();
		}
		catch (IllegalArgumentException e)
		{
			throw new CoachException(CoachException.Kind.BAD_CONFIG, "The provider URL or a header is invalid. Check the Base URL setting.", false, e);
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
					throw new RateLimited(retryAfterSeconds(res.header("Retry-After"), text), ce);
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
			JsonObject o = gson.fromJson(body, JsonObject.class);
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
