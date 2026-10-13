package com.osrscoach;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrscoach.ai.CoachService;
import com.osrscoach.ai.MapMarker;
import com.osrscoach.context.AccountSnapshot;
import com.osrscoach.context.Availability;
import com.osrscoach.context.BankCache;
import com.osrscoach.context.ContextCollector;
import com.osrscoach.research.WikiClient;
import com.osrscoach.ui.CoachPanel;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "OSRS Coach",
	description = "Beginner AI coach: reads your account, suggests one next step, cites the OSRS Wiki",
	tags = {"ai", "coach", "beginner", "guide", "quest", "help"}
)
public class CoachPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private ConfigManager configManager;
	@Inject
	private CoachConfig config;
	@Inject
	private OkHttpClient okHttpClient;
	@Inject
	private Gson gson;
	@Inject
	private ContextCollector collector;
	@Inject
	private BankCache bankCache;
	@Inject
	private ClientThread clientThread;
	@Inject
	private WorldMapPointManager worldMapPointManager;
	@Inject
	private EventBus eventBus;

	private volatile WorldMapPoint markerPoint;
	private volatile WorldPoint markerTarget;
	private BufferedImage markerIcon;

	private ScheduledExecutorService executor;
	private CoachService service;
	private CoachPanel panel;
	private NavigationButton navButton;
	private volatile String selectedQuest;
	private final Set<String> finishedSeen = new HashSet<>();
	private final Set<String> notified = new HashSet<>();
	private boolean baselineTaken;

	@Provides
	CoachConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CoachConfig.class);
	}

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "osrs-coach");
			t.setDaemon(true);
			return t;
		});
		WikiClient wiki = new WikiClient(okHttpClient, gson);
		service = new CoachService(collector::collect, () -> config, () -> selectedQuest, okHttpClient, gson, wiki, executor, Clock.systemUTC());

		List<String> quests = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			quests.add(q.getName());
		}
		quests.sort(String::compareToIgnoreCase);
		List<String> skills = new ArrayList<>();
		for (Skill s : Skill.values())
		{
			if (!"Overall".equals(s.getName()))
			{
				skills.add(s.getName());
			}
		}

		markerIcon = ImageUtil.loadImageResource(getClass(), "marker.png");
		service.setMarker(new MapMarker()
		{
			@Override
			public void mark(String label, int x, int y, int plane)
			{
				clientThread.invoke(() -> placeMarker(label, new WorldPoint(x, y, plane)));
			}

			@Override
			public void clear()
			{
				clientThread.invoke(CoachPlugin.this::clearMarker);
			}
		});

		panel = new CoachPanel(service, () -> config, configManager, quests, skills, q -> selectedQuest = q,
			() -> clientThread.invoke(this::clearMarker));
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder().tooltip("OSRS Coach").icon(icon).priority(1).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		if (config.openOnStartup())
		{
			javax.swing.SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
		}

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			refreshAndDetect();
		}
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(this::clearMarker);
		clientToolbar.removeNavigation(navButton);
		executor.shutdownNow();
		bankCache.clear();
		finishedSeen.clear();
		notified.clear();
		baselineTaken = false;
		panel = null;
		service = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			refreshAndDetect();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Could be a different account next: drop per-account caches.
			bankCache.clear();
			finishedSeen.clear();
			baselineTaken = false;
			if (panel != null)
			{
				panel.showContext(AccountSnapshot.loggedOut(java.time.Instant.now()));
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		// Fires on the client thread when the bank is open; we only cache what the client already shows the player.
		// Nothing is cached unless the player opted in to sharing bank context.
		if (config.sendBank() && e.getContainerId() == InventoryID.BANK && e.getItemContainer() != null)
		{
			bankCache.update(collector.toStacks(e.getItemContainer().getItems(), false), java.time.Instant.now());
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		WorldPoint target = markerTarget;
		if (target == null || client.getLocalPlayer() == null)
		{
			return;
		}
		WorldPoint me = client.getLocalPlayer().getWorldLocation();
		if (me != null && me.getPlane() == target.getPlane() && me.distanceTo2D(target) <= 3)
		{
			clearMarker();
			if (panel != null)
			{
				panel.showArrived();
			}
		}
	}

	/** Client thread. Draws only: world map pin, hint arrow, optional Shortest Path route. */
	private void placeMarker(String label, WorldPoint wp)
	{
		clearMarker();
		WorldMapPoint p = WorldMapPoint.builder()
			.worldPoint(wp)
			.image(markerIcon)
			.name("Coach: " + label)
			.tooltip("Coach: " + label)
			.snapToEdge(true)
			.jumpOnClick(true)
			.build();
		worldMapPointManager.add(p);
		markerPoint = p;
		markerTarget = wp;
		client.setHintArrow(wp);
		Map<String, Object> data = new java.util.HashMap<>();
		if (client.getLocalPlayer() != null)
		{
			data.put("start", client.getLocalPlayer().getWorldLocation());
		}
		data.put("target", wp);
		eventBus.post(new PluginMessage("shortestpath", "path", data));
		if (panel != null)
		{
			panel.showMarker(label);
		}
	}

	/** Client thread. Removes only what the coach placed. */
	void clearMarker()
	{
		WorldMapPoint p = markerPoint;
		WorldPoint target = markerTarget;
		if (p != null)
		{
			worldMapPointManager.remove(p);
		}
		if (target != null)
		{
			if (client.hasHintArrow() && target.equals(client.getHintArrowPoint()))
			{
				client.clearHintArrow();
			}
			eventBus.post(new PluginMessage("shortestpath", "clear"));
		}
		markerPoint = null;
		markerTarget = null;
		if (panel != null)
		{
			panel.showMarker(null);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (!config.proactive() || e.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		String m = e.getMessage().toLowerCase();
		if (m.contains("completed a quest") || m.contains("quest complete"))
		{
			// The message text is only a hint; the quest-state diff decides.
			refreshAndDetect();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (CoachConfig.GROUP.equals(e.getGroup()) && panel != null)
		{
			panel.updateProviderLabel();
		}
	}

	private void refreshAndDetect()
	{
		if (service == null)
		{
			return;
		}
		service.refresh().thenAccept(snap ->
		{
			if (panel == null)
			{
				return;
			}
			panel.showContext(snap);
			detectFinishedQuests(snap);
		});
	}

	/** Runs on the coach worker thread. Shows at most one quiet banner per finished quest per session. */
	private synchronized void detectFinishedQuests(AccountSnapshot snap)
	{
		if (!snap.isLoggedIn() || snap.getQuestsAvailability() != Availability.AVAILABLE)
		{
			return;
		}
		Set<String> nowFinished = new HashSet<>();
		for (Map.Entry<String, String> q : snap.getQuests().entrySet())
		{
			if ("FINISHED".equals(q.getValue()))
			{
				nowFinished.add(q.getKey());
			}
		}
		if (baselineTaken && config.proactive())
		{
			for (String q : nowFinished)
			{
				if (!finishedSeen.contains(q) && notified.add(q))
				{
					panel.showQuestCompleted(q);
					break;
				}
			}
		}
		finishedSeen.clear();
		finishedSeen.addAll(nowFinished);
		baselineTaken = true;
	}
}
