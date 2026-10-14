package com.osrscoach.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.OkHttpClient;

/**
 * Chat Completions API as implemented by OpenAI, OpenRouter, Ollama, LM Studio and others.
 * Uses function calling for lookups; models without tool support should get lookups disabled.
 */
public class OpenAiCompatibleProvider implements AiProvider
{
	static final int MAX_ROUNDS = 5;

	private final OkHttpClient http;
	private final Gson gson;
	private final String apiKey;
	private final String baseUrl;

	public OpenAiCompatibleProvider(OkHttpClient http, Gson gson, String apiKey, String baseUrl)
	{
		this.http = http;
		this.gson = gson;
		this.apiKey = apiKey;
		this.baseUrl = Secrets.cleanUrl(baseUrl);
	}

	@Override
	public String label()
	{
		return "OpenAI-compatible (" + host() + ")";
	}

	private String host()
	{
		String s = baseUrl.replaceFirst("^https?://", "");
		int i = s.indexOf('/');
		return i < 0 ? s : s.substring(0, i);
	}

	@Override
	public CoachReply respond(CoachRequest req, CoachTools tools) throws CoachException
	{
		if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))
		{
			throw new CoachException(CoachException.Kind.BAD_CONFIG, "Set 'Base URL' to something like https://api.openai.com/v1 or http://localhost:11434/v1", false);
		}
		boolean local = baseUrl.contains("://localhost") || baseUrl.contains("://127.0.0.1");
		String key = Secrets.cleanKey(apiKey);
		if (key.isEmpty() && !local)
		{
			throw new CoachException(CoachException.Kind.NO_CREDENTIALS, "Add an API key for " + host() + " in the plugin settings, or switch Provider to MOCK.", false);
		}
		Map<String, String> headers = new LinkedHashMap<>();
		if (!key.isEmpty())
		{
			headers.put("Authorization", "Bearer " + key);
		}

		JsonArray messages = new JsonArray();
		messages.add(msg("system", req.getSystemPrompt()));
		for (ChatTurn t : req.getHistory())
		{
			messages.add(msg(t.getRole() == ChatTurn.Role.USER ? "user" : "assistant", t.getText()));
		}
		messages.add(msg("user", req.getUserMessage()));

		JsonArray toolDefs = new JsonArray();
		for (CoachTools.Spec s : tools.specs())
		{
			JsonObject fn = new JsonObject();
			fn.addProperty("name", s.name);
			fn.addProperty("description", s.description);
			fn.add("parameters", s.schema);
			JsonObject d = new JsonObject();
			d.addProperty("type", "function");
			d.add("function", fn);
			toolDefs.add(d);
		}

		int maxRounds = req.isCompact() ? 3 : MAX_ROUNDS;
		boolean useCompletionTokens = host().equals("api.openai.com");
		boolean switchedTokenParam = false;
		// One extra round: after the lookup budget is spent the model gets "answer now" tool results,
		// because some providers (e.g. Groq) reject tool_choice=none when the model still calls a tool.
		for (int round = 0; round <= maxRounds + 1; round++)
		{
			JsonObject body = new JsonObject();
			body.addProperty("model", req.getModel());
			// OpenAI's newer models reject max_tokens and require max_completion_tokens; most other servers want max_tokens.
			body.addProperty(useCompletionTokens ? "max_completion_tokens" : "max_tokens", req.getMaxTokens());
			body.add("messages", messages);
			if (toolDefs.size() > 0)
			{
				body.add("tools", toolDefs);
			}
			JsonObject res;
			try
			{
				res = HttpJson.post(http, gson, baseUrl + "/chat/completions", headers, body, host());
			}
			catch (CoachException e)
			{
				// Self-heal if the server names the other token parameter in its 400 error.
				String m = e.getMessage() == null ? "" : e.getMessage();
				if (!switchedTokenParam && e.getKind() == CoachException.Kind.BAD_CONFIG && m.contains(useCompletionTokens ? "max_tokens" : "max_completion_tokens"))
				{
					switchedTokenParam = true;
					useCompletionTokens = !useCompletionTokens;
					round--;
					continue;
				}
				throw e;
			}
			JsonObject message;
			String finish;
			try
			{
				JsonObject choice = res.getAsJsonArray("choices").get(0).getAsJsonObject();
				message = choice.getAsJsonObject("message");
				finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? choice.get("finish_reason").getAsString() : "";
				if (message == null)
				{
					throw new NullPointerException();
				}
			}
			catch (RuntimeException e)
			{
				throw new CoachException(CoachException.Kind.INVALID_RESPONSE, host() + " response had no choices/message.", true, e);
			}

			JsonArray calls = message.has("tool_calls") && message.get("tool_calls").isJsonArray() ? message.getAsJsonArray("tool_calls") : null;
			if (calls != null && calls.size() > 0 && round <= maxRounds)
			{
				messages.add(message);
				for (JsonElement el : calls)
				{
					JsonObject call = el.getAsJsonObject();
					JsonObject fn = call.getAsJsonObject("function");
					String name = fn != null && fn.has("name") ? fn.get("name").getAsString() : "";
					JsonObject args;
					try
					{
						String raw = fn != null && fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
						args = gson.fromJson(raw, JsonObject.class);
					}
					catch (JsonSyntaxException | IllegalStateException e)
					{
						args = null;
					}
					JsonObject r = new JsonObject();
					r.addProperty("role", "tool");
					r.addProperty("tool_call_id", call.has("id") ? call.get("id").getAsString() : "");
					String result;
					if (round == maxRounds)
					{
						result = "LOOKUP_LIMIT: no more lookups are allowed for this answer. Answer now with what you have, and say what is unverified.";
					}
					else
					{
						result = args == null ? "BAD_ARGUMENTS: arguments were not valid JSON" : tools.execute(name, args);
					}
					r.addProperty("content", result);
					messages.add(r);
				}
				continue;
			}

			String out = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString().trim() : "";
			if (out.isEmpty())
			{
				throw new CoachException(CoachException.Kind.INVALID_RESPONSE, host() + " returned an empty answer.", true);
			}
			if ("length".equals(finish))
			{
				out += "\n\n_(Answer cut off — raise 'Max answer length' in settings for longer replies.)_";
			}
			return new CoachReply(out, tools.sources(), tools.failures(), true, label() + " · " + req.getModel(), tools.marks());
		}
		throw new CoachException(CoachException.Kind.INVALID_RESPONSE, "The AI kept requesting lookups without answering.", true);
	}

	private static JsonObject msg(String role, String content)
	{
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}
}
