package com.osrscoach.ai;

import com.google.gson.Gson;
import com.osrscoach.CoachConfig;
import com.osrscoach.context.AccountSnapshot;
import com.osrscoach.context.Availability;
import com.osrscoach.context.ContextFormatter;
import com.osrscoach.context.ContextToggles;
import com.osrscoach.context.UserProfile;
import com.osrscoach.research.WebSearchClient;
import com.osrscoach.research.WikiClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * Orchestrates one question: read context (client thread) → build prompt → call provider with lookups (worker thread).
 * Owns the conversation history.
 */
@Slf4j
public class CoachService
{
	private final Supplier<CompletableFuture<AccountSnapshot>> snapshotSource;
	private final Supplier<CoachConfig> config;
	private final Supplier<String> selectedQuest;
	private final OkHttpClient http;
	private final Gson gson;
	private final WikiClient wiki;
	private final ScheduledExecutorService executor;
	static final int MAX_WAIT_SECONDS = 65;
	static final int MAX_RETRIES = 2;
	/** Unit for scheduled retry delays; tests shrink it. */
	TimeUnit retryUnit = TimeUnit.SECONDS;
	private final Clock clock;

	private final List<ChatTurn> history = new ArrayList<>();
	private volatile MapMarker marker;
	private volatile AccountSnapshot lastSnapshot;

	public CoachService(Supplier<CompletableFuture<AccountSnapshot>> snapshotSource, Supplier<CoachConfig> config,
		Supplier<String> selectedQuest, OkHttpClient http, Gson gson, WikiClient wiki, ScheduledExecutorService executor, Clock clock)
	{
		this.snapshotSource = snapshotSource;
		this.config = config;
		this.selectedQuest = selectedQuest;
		this.http = http;
		this.gson = gson;
		this.wiki = wiki;
		this.executor = executor;
		this.clock = clock;
	}

	public void setMarker(MapMarker marker)
	{
		this.marker = marker;
	}

	public CompletableFuture<CoachReply> ask(String question)
	{
		return ask(question, null);
	}

	/**
	 * @param progress optional status updates (called on the worker thread).
	 * Rate-limited or busy providers are retried by scheduling a new attempt on the executor after the
	 * server-suggested delay; no thread ever sleeps.
	 */
	public CompletableFuture<CoachReply> ask(String question, java.util.function.Consumer<String> progress)
	{
		CompletableFuture<CoachReply> result = new CompletableFuture<>();
		attempt(question, progress, 0, result);
		return result;
	}

	private void attempt(String question, java.util.function.Consumer<String> progress, int attemptNo, CompletableFuture<CoachReply> result)
	{
		try
		{
			executor.execute(() ->
			{
				try
				{
					if (attemptNo > 0 && progress != null)
					{
						progress.accept("Thinking…");
					}
					result.complete(askBlocking(question, progress));
				}
				catch (CoachException e)
				{
					double wait = e.getRetryAfterSeconds();
					if (e.isRetryable() && wait >= 0 && wait <= MAX_WAIT_SECONDS && attemptNo < MAX_RETRIES)
					{
						long secs = Math.max(1, (long) Math.ceil(wait));
						if (progress != null)
						{
							progress.accept(e.isServerBusy()
								? "AI service busy — retrying in " + secs + "s…"
								: "Free-tier limit reached — waiting " + secs + "s, then continuing…");
						}
						executor.schedule(() -> attempt(question, progress, attemptNo + 1, result), secs, retryUnit);
					}
					else
					{
						result.completeExceptionally(e);
					}
				}
				catch (RuntimeException e)
				{
					// Log only the type and stack (messages from HTTP libraries can echo secrets).
					log.warn("Coach request failed: {} at {}", e.getClass().getName(),
						e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "?");
					result.completeExceptionally(new CoachException(CoachException.Kind.BAD_CONFIG,
						"Something in your settings couldn't be used (" + e.getClass().getSimpleName()
							+ "). Re-paste your API keys and Base URLs, then try again.", true));
				}
			});
		}
		catch (java.util.concurrent.RejectedExecutionException e)
		{
			// Plugin is shutting down.
			result.completeExceptionally(e);
		}
	}

	/** Re-reads account context on a worker thread. */
	public CompletableFuture<AccountSnapshot> refresh()
	{
		return CompletableFuture.supplyAsync(this::readSnapshot, executor);
	}

	CoachReply askBlocking(String question) throws CoachException
	{
		return askBlocking(question, null);
	}

	/** One AI service to try, in order: main provider, then configured backups. */
	static final class Target
	{
		final String name;
		final AiProvider provider;
		final String model;
		final boolean compact;
		final boolean mock;

		Target(String name, AiProvider provider, String model, boolean compact, boolean mock)
		{
			this.name = name;
			this.provider = provider;
			this.model = model == null ? "" : model.trim();
			this.compact = compact;
			this.mock = mock;
		}
	}

	List<Target> targets(CoachConfig c, AccountSnapshot snap)
	{
		List<Target> out = new ArrayList<>();
		AiProvider main = provider(c, snap);
		boolean mock = c.provider() == CoachConfig.Provider.MOCK;
		out.add(new Target(mock ? "Mock mode" : "Main provider (" + hostOf(c.provider() == CoachConfig.Provider.ANTHROPIC ? "https://api.anthropic.com" : c.baseUrl()) + ")",
			main, c.model(), compact(c), mock));
		if (!mock)
		{
			addBackup(out, c, c.backup1BaseUrl(), c.backup1ApiKey(), c.backup1Model(), 1);
			addBackup(out, c, c.backup2BaseUrl(), c.backup2ApiKey(), c.backup2Model(), 2);
		}
		return out;
	}

	private void addBackup(List<Target> out, CoachConfig c, String url, String key, String model, int n)
	{
		if (url == null || url.trim().isEmpty() || model == null || model.trim().isEmpty())
		{
			return;
		}
		boolean compact = c.compactMode() || url.contains("groq.com");
		out.add(new Target("Backup " + n + " (" + hostOf(url) + ")", new OpenAiCompatibleProvider(aiHttp(), gson, key, url), model, compact, false));
	}

	static String hostOf(String url)
	{
		String s = url == null ? "" : url.trim().replaceFirst("^https?://", "");
		int i = s.indexOf('/');
		return i < 0 ? s : s.substring(0, i);
	}

	private CoachReply ask(Target t, CoachConfig c, String question, String context, java.util.function.Consumer<String> progress)
		throws CoachException
	{
		CoachTools tools = new CoachTools(c.wikiLookups() ? wiki : null, webSearch(c));
		if (c.mapMarkers() && marker != null)
		{
			tools.withMarker(marker);
		}
		if (t.compact)
		{
			tools.compact();
		}
		if (!t.mock && t.model.isEmpty())
		{
			throw new CoachException(CoachException.Kind.BAD_CONFIG, "Set a model name in the plugin settings.", false);
		}
		CoachRequest req = CoachRequest.builder()
			.systemPrompt(CoachPrompt.system(context, tools.wikiEnabled(), tools.webEnabled()))
			.history(trimmedHistory(t.compact ? Math.min(2, c.historyTurns()) : c.historyTurns()))
			.userMessage(question)
			.model(t.model)
			.maxTokens(c.maxTokens())
			.accountContext(context)
			.progress(progress)
			.compact(t.compact)
			.build();
		return t.provider.respond(req, tools);
	}

	/** True for settings or providers known to have tight free-tier rate limits. */
	static boolean compact(CoachConfig c)
	{
		return c.compactMode()
			|| (c.provider() == CoachConfig.Provider.OPENAI_COMPATIBLE && c.baseUrl() != null && c.baseUrl().contains("groq.com"));
	}

	CoachReply askBlocking(String question, java.util.function.Consumer<String> progress) throws CoachException
	{
		CoachConfig c = config.get();
		AccountSnapshot snap = readSnapshot();
		String context = contextText(snap, c);

		List<Target> targets = targets(c, snap);
		List<String> fallbackNotes = new ArrayList<>();
		CoachReply reply = null;
		for (int i = 0; i < targets.size(); i++)
		{
			Target t = targets.get(i);
			try
			{
				reply = ask(t, c, question, context, progress);
				break;
			}
			catch (CoachException e)
			{
				boolean last = i == targets.size() - 1;
				if (last)
				{
					throw e;
				}
				fallbackNotes.add(t.name + " failed (" + e.getKind().getTitle() + ") — answered by " + targets.get(i + 1).name + " instead.");
				if (progress != null)
				{
					progress.accept(t.name + ": " + e.getKind().getTitle() + " — trying " + targets.get(i + 1).name + "…");
				}
			}
		}
		if (!fallbackNotes.isEmpty())
		{
			List<String> warnings = new ArrayList<>(fallbackNotes);
			warnings.addAll(reply.getWarnings());
			reply = new CoachReply(reply.getText(), reply.getLookedUp(), warnings, reply.isLive(), reply.getProviderLabel(), reply.getMarks());
		}
		synchronized (history)
		{
			history.add(new ChatTurn(ChatTurn.Role.USER, question));
			history.add(new ChatTurn(ChatTurn.Role.ASSISTANT, reply.getText()));
		}
		return reply;
	}

	WebSearchClient webSearch(CoachConfig c)
	{
		switch (c.webSearch())
		{
			case SERPER_GOOGLE:
				return new WebSearchClient(http, gson, WebSearchClient.Backend.SERPER_GOOGLE, c.webSearchKey());
			case BRAVE:
				return new WebSearchClient(http, gson, WebSearchClient.Backend.BRAVE, c.webSearchKey());
			case OFF:
			default:
				return null;
		}
	}

	/** AI answers can take much longer than RuneLite's default HTTP timeouts allow. */
	OkHttpClient aiHttp()
	{
		return http.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.readTimeout(120, TimeUnit.SECONDS)
			.build();
	}

	AiProvider provider(CoachConfig c, AccountSnapshot snap)
	{
		switch (c.provider())
		{
			case ANTHROPIC:
				return new AnthropicProvider(aiHttp(), gson, c.apiKey());
			case OPENAI_COMPATIBLE:
				return new OpenAiCompatibleProvider(aiHttp(), gson, c.apiKey(), c.baseUrl());
			case MOCK:
			default:
				return new MockProvider(snap, c.sendQuests(), c.sendSkills());
		}
	}

	AccountSnapshot readSnapshot()
	{
		AccountSnapshot snap;
		try
		{
			snap = snapshotSource.get().get(3, TimeUnit.SECONDS);
		}
		catch (Exception e)
		{
			log.debug("Account context read failed", e);
			snap = AccountSnapshot.builder().capturedAt(clock.instant()).loggedIn(true)
				.skillsAvailability(Availability.ERROR).questsAvailability(Availability.ERROR)
				.inventoryAvailability(Availability.ERROR).equipmentAvailability(Availability.ERROR)
				.locationAvailability(Availability.ERROR).accountAvailability(Availability.ERROR)
				.bankAvailability(Availability.ERROR).build();
		}
		lastSnapshot = snap;
		return snap;
	}

	/** Exactly the context block that would be sent with a question right now. */
	public String contextText(AccountSnapshot snap, CoachConfig c)
	{
		return ContextFormatter.format(snap, profile(c), toggles(c), clock.instant());
	}

	UserProfile profile(CoachConfig c)
	{
		return UserProfile.builder()
			.membership(c.membership().name())
			.budget(c.budget())
			.goals(c.goals())
			.preferences(c.preferences())
			.selectedQuest(selectedQuest.get())
			.build();
	}

	static ContextToggles toggles(CoachConfig c)
	{
		return ContextToggles.builder()
			.skills(c.sendSkills()).quests(c.sendQuests()).inventory(c.sendInventory())
			.equipment(c.sendEquipment()).location(c.sendLocation()).accountType(c.sendAccountType())
			.bank(c.sendBank()).profile(c.sendProfile())
			.build();
	}

	private List<ChatTurn> trimmedHistory(int turns)
	{
		synchronized (history)
		{
			int keep = Math.max(0, turns) * 2;
			int from = Math.max(0, history.size() - keep);
			return new ArrayList<>(history.subList(from, history.size()));
		}
	}

	public void clearHistory()
	{
		synchronized (history)
		{
			history.clear();
		}
	}

	public AccountSnapshot lastSnapshot()
	{
		return lastSnapshot;
	}
}
