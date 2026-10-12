package com.osrscoach;

import com.osrscoach.context.AccountSnapshot;
import com.osrscoach.context.Availability;
import com.osrscoach.context.ItemStack;
import com.osrscoach.context.SkillInfo;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A fresh-ish F2P account for tests: no live game client needed. */
public final class MockData
{
	public static final Instant NOW = Instant.parse("2026-10-12T20:00:00Z");

	private MockData()
	{
	}

	public static AccountSnapshot beginner()
	{
		Map<String, String> quests = new LinkedHashMap<>();
		quests.put("Cook's Assistant", "FINISHED");
		quests.put("Sheep Shearer", "IN_PROGRESS");
		quests.put("Dragon Slayer I", "NOT_STARTED");
		quests.put("Rune Mysteries", "NOT_STARTED");
		return AccountSnapshot.builder()
			.capturedAt(NOW.minusSeconds(5)).loggedIn(true)
			.skillsAvailability(Availability.AVAILABLE)
			.skills(Arrays.asList(
				new SkillInfo("Attack", 10, 1154, false),
				new SkillInfo("Strength", 12, 1584, false),
				new SkillInfo("Fishing", 1, 0, false),
				new SkillInfo("Agility", 1, 0, true)))
			.combatLevel(9).totalLevel(60)
			.questsAvailability(Availability.AVAILABLE).quests(quests)
			.inventoryAvailability(Availability.AVAILABLE)
			.inventory(Arrays.asList(new ItemStack(1351, "Bronze axe", 1, null), new ItemStack(995, "Coins", 25, null)))
			.equipmentAvailability(Availability.AVAILABLE)
			.equipment(Collections.singletonList(new ItemStack(1277, "Bronze sword", 1, "Weapon")))
			.locationAvailability(Availability.AVAILABLE).x(3222).y(3218).plane(0).regionId(12850)
			.accountAvailability(Availability.AVAILABLE).accountType("Normal").onMembersWorld(false).world(301)
			.build();
	}

	public static CoachConfig config(CoachConfig.Provider p, String key, boolean wiki)
	{
		return new CoachConfig()
		{
			@Override
			public Provider provider()
			{
				return p;
			}

			@Override
			public String apiKey()
			{
				return key;
			}

			@Override
			public boolean wikiLookups()
			{
				return wiki;
			}

			@Override
			public String budget()
			{
				return "200k gp";
			}
		};
	}
}
