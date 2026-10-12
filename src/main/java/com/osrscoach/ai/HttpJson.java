package com.osrscoach.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
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

	private HttpJson()
	{
	}

	static JsonObject post(OkHttpClient http, Gson gson, String url, Map<String, String> headers, JsonObject body, String providerName)
		throws CoachException
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
				throw CoachException.fromStatus(res.code(), providerName, errorDetail(gson, text));
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
