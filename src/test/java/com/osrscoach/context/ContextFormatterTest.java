package com.osrscoach.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.osrscoach.MockData;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ContextFormatterTest
{
	private static final UserProfile EMPTY = UserProfile.builder().membership("AUTO_DETECT").build();

	@Test
	void fullContextIncludesLevelsQuestsItems()
	{
		String s = ContextFormatter.format(MockData.beginner(), EMPTY, ContextToggles.builder().build(), MockData.NOW);
		assertTrue(s.contains("Attack 10 (1154 xp)"), s);
		assertTrue(s.contains("In progress (1): Sheep Shearer"), s);
		assertTrue(s.contains("Finished (1): Cook's Assistant"), s);
		assertTrue(s.contains("(2) is NOT STARTED"), s);
		assertTrue(s.contains("Bronze axe; Coins x25"), s);
		assertTrue(s.contains("Weapon: Bronze sword"), s);
		assertTrue(s.contains("Agility 1 (0 xp, members)"), s);
	}

	@Test
	void loggedOutIsUnknownNotZero()
	{
		String s = ContextFormatter.format(AccountSnapshot.loggedOut(MockData.NOW), EMPTY, ContextToggles.all(), MockData.NOW);
		assertTrue(s.contains("NOT LOGGED IN"));
		assertTrue(s.contains("do NOT treat skills as level 1"));
		assertTrue(s.contains("quest progress is unknown, not zero"));
		assertFalse(s.contains("Inventory is empty"), "missing inventory must not read as empty");
	}

	@Test
	void emptyInventoryIsDistinctFromUnloaded()
	{
		AccountSnapshot empty = MockData.beginner().toBuilder().inventory(Collections.emptyList()).build();
		assertTrue(ContextFormatter.format(empty, EMPTY, ContextToggles.builder().build(), MockData.NOW).contains("Inventory is empty."));
		AccountSnapshot unloaded = MockData.beginner().toBuilder().inventoryAvailability(Availability.NOT_LOADED).build();
		String s = ContextFormatter.format(unloaded, EMPTY, ContextToggles.builder().build(), MockData.NOW);
		assertTrue(s.contains("not loaded by the game client yet"));
		assertFalse(s.contains("Bronze axe"));
	}

	@Test
	void disabledCategoriesLeakNothing()
	{
		ContextToggles none = ContextToggles.builder().skills(false).quests(false).inventory(false).equipment(false)
			.location(false).accountType(false).bank(false).profile(false).build();
		UserProfile p = UserProfile.builder().membership("AUTO_DETECT").budget("SECRET_BUDGET").goals("SECRET_GOAL").build();
		String s = ContextFormatter.format(MockData.beginner(), p, none, MockData.NOW);
		for (String leak : new String[]{"Attack", "Sheep Shearer", "Bronze axe", "Bronze sword", "3222", "Normal", "SECRET_BUDGET", "SECRET_GOAL"})
		{
			assertFalse(s.contains(leak), "leaked " + leak + "\n" + s);
		}
		assertTrue(s.contains("not shared (disabled in settings)"));
	}

	@Test
	void budgetNeverInferred()
	{
		String s = ContextFormatter.format(MockData.beginner(), EMPTY, ContextToggles.builder().build(), MockData.NOW);
		assertTrue(s.contains("Budget: not given (do not infer it from inventory"));
	}

	@Test
	void bankIsLabelledWithAge()
	{
		AccountSnapshot b = MockData.beginner().toBuilder().bankAvailability(Availability.AVAILABLE)
			.bank(Collections.singletonList(new ItemStack(379, "Lobster", 40, null)))
			.bankCachedAt(MockData.NOW.minusSeconds(2 * 3600)).build();
		String s = ContextFormatter.format(b, EMPTY, ContextToggles.all(), MockData.NOW);
		assertTrue(s.contains("CACHED snapshot from 2 h ago"), s);
		assertTrue(s.contains("Lobster x40"));
		// default toggles keep bank off
		assertFalse(ContextFormatter.format(b, EMPTY, ContextToggles.builder().build(), MockData.NOW).contains("Lobster"));
	}

	@Test
	void bankNeverSeenSaysSo()
	{
		String s = ContextFormatter.format(MockData.beginner(), EMPTY, ContextToggles.all(), MockData.NOW);
		assertTrue(s.contains("not seen this session. Do not assume"));
	}

	@Test
	void membershipRules()
	{
		AccountSnapshot f2p = MockData.beginner();
		assertTrue(ContextFormatter.membership(f2p, EMPTY, Availability.AVAILABLE).startsWith("unknown"));
		AccountSnapshot mem = f2p.toBuilder().onMembersWorld(true).build();
		assertTrue(ContextFormatter.membership(mem, EMPTY, Availability.AVAILABLE).startsWith("member"));
		UserProfile set = UserProfile.builder().membership("FREE_TO_PLAY").build();
		assertEquals("free-to-play (set by player)", ContextFormatter.membership(mem, set, Availability.AVAILABLE));
	}

	@Test
	void selectedQuestShowsState()
	{
		UserProfile p = UserProfile.builder().membership("AUTO_DETECT").selectedQuest("Sheep Shearer").build();
		assertTrue(ContextFormatter.format(MockData.beginner(), p, ContextToggles.builder().build(), MockData.NOW)
			.contains("Player selected this quest for help: Sheep Shearer (state: IN_PROGRESS)"));
	}

	@Test
	void summary()
	{
		assertEquals("Cmb 9 · Total 60 · Quests 1/4 · Normal · read just now", ContextFormatter.summary(MockData.beginner(), MockData.NOW));
		assertTrue(ContextFormatter.summary(null, MockData.NOW).startsWith("Not logged in"));
	}
}
