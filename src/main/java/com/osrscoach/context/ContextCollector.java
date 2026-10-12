package com.osrscoach.context;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Reads supported game state. Every read happens on the client thread; callers get a future
 * they can wait on from any other thread. Read-only: this class never sends input to the game.
 */
@Slf4j
@Singleton
public class ContextCollector
{
	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final BankCache bankCache;
	private final Clock clock;

	@Inject
	ContextCollector(Client client, ClientThread clientThread, ItemManager itemManager, BankCache bankCache)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.bankCache = bankCache;
		this.clock = Clock.systemUTC();
	}

	public CompletableFuture<AccountSnapshot> collect()
	{
		CompletableFuture<AccountSnapshot> f = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				f.complete(readOnClientThread());
			}
			catch (Exception e)
			{
				log.warn("Context read failed", e);
				f.completeExceptionally(e);
			}
		});
		return f;
	}

	/** Must be called on the client thread. */
	AccountSnapshot readOnClientThread()
	{
		java.time.Instant now = clock.instant();
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return AccountSnapshot.loggedOut(now);
		}
		AccountSnapshot.AccountSnapshotBuilder b = AccountSnapshot.builder().capturedAt(now).loggedIn(true);

		// Skills
		try
		{
			List<SkillInfo> skills = new ArrayList<>();
			for (Skill s : Skill.values())
			{
				if (isOverall(s))
				{
					continue;
				}
				skills.add(new SkillInfo(s.getName(), client.getRealSkillLevel(s), client.getSkillExperience(s), s.isMembers()));
			}
			b.skills(skills).totalLevel(client.getTotalLevel()).combatLevel(client.getLocalPlayer().getCombatLevel())
				.skillsAvailability(Availability.AVAILABLE);
		}
		catch (Exception e)
		{
			log.debug("skills unavailable", e);
			b.skillsAvailability(Availability.ERROR);
		}

		// Quests (Quest#getState runs a client script, so it must be on the client thread)
		try
		{
			Map<String, String> quests = new LinkedHashMap<>();
			for (Quest q : Quest.values())
			{
				QuestState st = q.getState(client);
				quests.put(q.getName(), st == null ? "NOT_STARTED" : st.name());
			}
			b.quests(quests).questsAvailability(Availability.AVAILABLE);
		}
		catch (Exception e)
		{
			log.debug("quests unavailable", e);
			b.questsAvailability(Availability.ERROR);
		}

		// Inventory & equipment
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			b.inventoryAvailability(Availability.NOT_LOADED);
		}
		else
		{
			b.inventory(toStacks(inv.getItems(), false)).inventoryAvailability(Availability.AVAILABLE);
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			b.equipmentAvailability(Availability.NOT_LOADED);
		}
		else
		{
			b.equipment(toStacks(worn.getItems(), true)).equipmentAvailability(Availability.AVAILABLE);
		}

		// Location
		Player me = client.getLocalPlayer();
		WorldPoint wp = me.getWorldLocation();
		if (wp == null)
		{
			b.locationAvailability(Availability.NOT_LOADED);
		}
		else
		{
			b.x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).locationAvailability(Availability.AVAILABLE);
		}

		// Account type & world
		try
		{
			EnumSet<WorldType> types = client.getWorldType();
			List<String> modes = new ArrayList<>();
			for (WorldType wt : types)
			{
				if (wt != WorldType.MEMBERS)
				{
					modes.add(wt.name());
				}
			}
			b.accountType(accountTypeName(client.getVarbitValue(VarbitID.IRONMAN)))
				.onMembersWorld(types.contains(WorldType.MEMBERS))
				.world(client.getWorld())
				.worldModes(modes)
				.accountAvailability(Availability.AVAILABLE);
		}
		catch (Exception e)
		{
			log.debug("account info unavailable", e);
			b.accountAvailability(Availability.ERROR);
		}

		// Bank: only what we cached when the player opened it this session
		BankCache.Entry bank = bankCache.get();
		if (bank == null)
		{
			b.bankAvailability(Availability.NEVER_SEEN);
		}
		else
		{
			b.bank(bank.getItems()).bankCachedAt(bank.getCachedAt()).bankAvailability(Availability.AVAILABLE);
		}
		return b.build();
	}

	/** Must be called on the client thread. */
	public List<ItemStack> toStacks(Item[] items, boolean equipment)
	{
		if (items == null)
		{
			return Collections.emptyList();
		}
		Map<Integer, String> slotNames = new LinkedHashMap<>();
		if (equipment)
		{
			for (EquipmentInventorySlot s : EquipmentInventorySlot.values())
			{
				slotNames.put(s.getSlotIdx(), pretty(s.name()));
			}
		}
		// Merge identical inventory items (e.g. 14 separate lobsters) to keep the prompt small.
		Map<Integer, long[]> merged = new LinkedHashMap<>();
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i < items.length; i++)
		{
			Item it = items[i];
			if (it == null || it.getId() <= 0 || it.getQuantity() <= 0)
			{
				continue;
			}
			if (equipment)
			{
				out.add(new ItemStack(it.getId(), name(it.getId()), it.getQuantity(), slotNames.getOrDefault(i, "Slot " + i)));
			}
			else
			{
				merged.computeIfAbsent(it.getId(), k -> new long[1])[0] += it.getQuantity();
			}
		}
		for (Map.Entry<Integer, long[]> e : merged.entrySet())
		{
			out.add(new ItemStack(e.getKey(), name(e.getKey()), e.getValue()[0], null));
		}
		return out;
	}

	private String name(int id)
	{
		try
		{
			return itemManager.getItemComposition(itemManager.canonicalize(id)).getName();
		}
		catch (Exception e)
		{
			return "item #" + id;
		}
	}

	@SuppressWarnings("deprecation")
	private static boolean isOverall(Skill s)
	{
		return s == Skill.OVERALL;
	}

	static String accountTypeName(int raw)
	{
		switch (raw)
		{
			case 0:
				return "Normal";
			case 1:
				return "Ironman";
			case 2:
				return "Ultimate Ironman";
			case 3:
				return "Hardcore Ironman";
			case 4:
				return "Group Ironman";
			case 5:
				return "Hardcore Group Ironman";
			case 6:
				return "Unranked Group Ironman";
			default:
				return "unknown (raw " + raw + ")";
		}
	}

	private static String pretty(String enumName)
	{
		String s = enumName.toLowerCase().replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
