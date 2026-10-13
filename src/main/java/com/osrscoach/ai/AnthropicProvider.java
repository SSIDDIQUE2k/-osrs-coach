package com.osrscoach.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.OkHttpClient;

/** Claude Messages API with client-side tools (our wiki/price lookups). */
public class AnthropicProvider implements AiProvider
{
	static final String DEFAULT_URL = "https://api.anthropic.com/v1/messages";
	static final int MAX_ROUNDS = 5;

	private final OkHttpClient http;
	private final Gson gson;
	private final String apiKey;
	private final String url;

	public AnthropicProvider(OkHttpClient http, Gson gson, String apiKey)
	{
		this(http, gson, apiKey, DEFAULT_URL);
	}

	AnthropicProvider(OkHttpClient http, Gson gson, String apiKey, String url)
	{
		this.http = http;
		this.gson = gson;
		this.apiKey = apiKey;
		this.url = url;
	}

	@Override
	public String label()
	{
		return "Claude (Anthropic API)";
	}

	@Override
	public CoachReply respond(CoachRequest req, CoachTools tools) throws CoachException
	{
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			throw new CoachException(CoachException.Kind.NO_CREDENTIALS, "Add your Anthropic API key in the plugin settings, or switch Provider to MOCK.", false);
		}
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("x-api-key", apiKey.trim());
		headers.put("anthropic-version", "2023-06-01");

		JsonArray messages = new JsonArray();
		for (ChatTurn t : req.getHistory())
		{
			messages.add(textMessage(t.getRole() == ChatTurn.Role.USER ? "user" : "assistant", t.getText()));
		}
		messages.add(textMessage("user", req.getUserMessage()));

		JsonArray toolDefs = new JsonArray();
		for (CoachTools.Spec s : tools.specs())
		{
			JsonObject d = new JsonObject();
			d.addProperty("name", s.name);
			d.addProperty("description", s.description);
			d.add("input_schema", s.schema);
			toolDefs.add(d);
		}

		int maxRounds = req.isCompact() ? 3 : MAX_ROUNDS;
		for (int round = 0; round <= maxRounds; round++)
		{
			JsonObject body = new JsonObject();
			body.addProperty("model", req.getModel());
			body.addProperty("max_tokens", req.getMaxTokens());
			body.addProperty("system", req.getSystemPrompt());
			body.add("messages", messages);
			if (toolDefs.size() > 0)
			{
				body.add("tools", toolDefs);
				if (round == maxRounds)
				{
					JsonObject none = new JsonObject();
					none.addProperty("type", "none");
					body.add("tool_choice", none);
				}
			}

			JsonObject res = HttpJson.post(http, gson, url, headers, body, "Anthropic");
			JsonArray content = res.has("content") && res.get("content").isJsonArray() ? res.getAsJsonArray("content") : null;
			if (content == null)
			{
				throw new CoachException(CoachException.Kind.INVALID_RESPONSE, "Anthropic response had no content.", true);
			}
			String stop = res.has("stop_reason") && !res.get("stop_reason").isJsonNull() ? res.get("stop_reason").getAsString() : "";

			if ("tool_use".equals(stop) && round < maxRounds)
			{
				JsonObject assistant = new JsonObject();
				assistant.addProperty("role", "assistant");
				assistant.add("content", content);
				messages.add(assistant);

				JsonArray results = new JsonArray();
				for (JsonElement el : content)
				{
					JsonObject block = el.getAsJsonObject();
					if (!"tool_use".equals(str(block, "type")))
					{
						continue;
					}
					JsonObject input = block.has("input") && block.get("input").isJsonObject() ? block.getAsJsonObject("input") : new JsonObject();
					JsonObject r = new JsonObject();
					r.addProperty("type", "tool_result");
					r.addProperty("tool_use_id", str(block, "id"));
					r.addProperty("content", tools.execute(str(block, "name"), input));
					results.add(r);
				}
				if (results.size() == 0)
				{
					throw new CoachException(CoachException.Kind.INVALID_RESPONSE, "Anthropic asked to use a tool but sent no tool call.", true);
				}
				JsonObject user = new JsonObject();
				user.addProperty("role", "user");
				user.add("content", results);
				messages.add(user);
				continue;
			}

			StringBuilder text = new StringBuilder();
			for (JsonElement el : content)
			{
				JsonObject block = el.isJsonObject() ? el.getAsJsonObject() : null;
				if (block != null && "text".equals(str(block, "type")) && block.has("text"))
				{
					text.append(block.get("text").getAsString());
				}
			}
			String out = text.toString().trim();
			if (out.isEmpty())
			{
				throw new CoachException(CoachException.Kind.INVALID_RESPONSE,
					"max_tokens".equals(stop) ? "The answer was cut off before any text. Raise 'Max answer length'." : "Anthropic returned an empty answer.", true);
			}
			if ("max_tokens".equals(stop))
			{
				out += "\n\n_(Answer cut off — raise 'Max answer length' in settings for longer replies.)_";
			}
			return new CoachReply(out, tools.sources(), tools.failures(), true, label() + " · " + req.getModel(), tools.marks());
		}
		throw new CoachException(CoachException.Kind.INVALID_RESPONSE, "The AI kept requesting lookups without answering.", true);
	}

	private static JsonObject textMessage(String role, String text)
	{
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", text);
		return m;
	}

	private static String str(JsonObject o, String k)
	{
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}
}
