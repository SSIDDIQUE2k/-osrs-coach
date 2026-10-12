package com.osrscoach.context;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a snapshot into the exact text sent to the AI provider. The same text is shown
 * to the player in the "What gets sent?" preview, so the two can never drift apart.
 */
public final class ContextFormatter
{
	private static final int MAX_BANK_LINES = 150;

	private ContextFormatter()
	{
	}

	public static String format(AccountSnapshot s, UserProfile p, ContextToggles t, Instant now)
	{
		StringBuilder b = new StringBuilder();
		b.append("Snapshot taken: ").append(age(s.getCapturedAt(), now)).append('\n');
		if (!s.isLoggedIn())
		{
			b.append("Game state: NOT LOGGED IN. No live account data is available; do not assume levels, quests or items.\n");
		}

		// ---- account type / world ----
		b.append("\n[Account]\n");
		Availability acc = t.isAccountType() ? s.getAccountAvailability() : Availability.DISABLED_BY_USER;
		if (acc == Availability.AVAILABLE)
		{
			b.append("Account type: ").append(nz(s.getAccountType(), "unknown")).append('\n');
			b.append("World: ").append(s.getWorld()).append(s.isOnMembersWorld() ? " (members world)" : " (free-to-play world)");
			if (!s.getWorldModes().isEmpty())
			{
				b.append(", special modes: ").append(String.join(", ", s.getWorldModes()));
			}
			b.append('\n');
		}
		else
		{
			b.append("Account type/world: ").append(acc.label()).append('\n');
		}
		b.append("Membership: ").append(membership(s, p, acc)).append('\n');

		// ---- skills ----
		b.append("\n[Skills]\n");
		Availability sk = t.isSkills() ? s.getSkillsAvailability() : Availability.DISABLED_BY_USER;
		if (sk == Availability.AVAILABLE)
		{
			b.append("Combat level ").append(s.getCombatLevel()).append(", total level ").append(s.getTotalLevel()).append('\n');
			List<String> parts = new ArrayList<>();
			for (SkillInfo si : s.getSkills())
			{
				parts.add(si.getName() + " " + si.getLevel() + " (" + si.getXp() + " xp" + (si.isMembersOnly() ? ", members" : "") + ")");
			}
			b.append(String.join("; ", parts)).append('\n');
		}
		else
		{
			b.append(sk.label()).append(" — do NOT treat skills as level 1.\n");
		}

		// ---- quests ----
		b.append("\n[Quests]\n");
		Availability q = t.isQuests() ? s.getQuestsAvailability() : Availability.DISABLED_BY_USER;
		if (q == Availability.AVAILABLE)
		{
			List<String> done = new ArrayList<>();
			List<String> started = new ArrayList<>();
			int notStarted = 0;
			for (Map.Entry<String, String> e : s.getQuests().entrySet())
			{
				switch (e.getValue())
				{
					case "FINISHED":
						done.add(e.getKey());
						break;
					case "IN_PROGRESS":
						started.add(e.getKey());
						break;
					default:
						notStarted++;
				}
			}
			b.append("In progress (").append(started.size()).append("): ").append(listOrNone(started)).append('\n');
			b.append("Finished (").append(done.size()).append("): ").append(listOrNone(done)).append('\n');
			b.append("Every other quest/miniquest in the game's list (").append(notStarted).append(") is NOT STARTED.\n");
		}
		else
		{
			b.append(q.label()).append(" — quest progress is unknown, not zero.\n");
		}
		if (t.isProfile() && p != null && notBlank(p.getSelectedQuest()))
		{
			b.append("Player selected this quest for help: ").append(p.getSelectedQuest());
			String state = q == Availability.AVAILABLE ? s.getQuests().get(p.getSelectedQuest()) : null;
			b.append(state != null ? " (state: " + state + ")" : " (state unknown)").append('\n');
		}

		// ---- inventory / equipment ----
		b.append("\n[Inventory]\n");
		appendItems(b, t.isInventory() ? s.getInventoryAvailability() : Availability.DISABLED_BY_USER, s.getInventory(), "Inventory is empty.");
		b.append("\n[Equipment]\n");
		appendItems(b, t.isEquipment() ? s.getEquipmentAvailability() : Availability.DISABLED_BY_USER, s.getEquipment(), "Nothing equipped.");

		// ---- location ----
		b.append("\n[Location]\n");
		Availability loc = t.isLocation() ? s.getLocationAvailability() : Availability.DISABLED_BY_USER;
		if (loc == Availability.AVAILABLE)
		{
			b.append("Tile x=").append(s.getX()).append(" y=").append(s.getY()).append(" plane=").append(s.getPlane())
				.append(" region=").append(s.getRegionId())
				.append(". Only name the area if you are confident; otherwise ask the player where they are.\n");
		}
		else
		{
			b.append(loc.label()).append('\n');
		}

		// ---- bank ----
		b.append("\n[Bank]\n");
		Availability bank = t.isBank() ? s.getBankAvailability() : Availability.DISABLED_BY_USER;
		if (bank == Availability.AVAILABLE)
		{
			b.append("CACHED snapshot from ").append(age(s.getBankCachedAt(), now))
				.append(". It may be out of date. ").append(s.getBank().size()).append(" item types.\n");
			int n = 0;
			for (ItemStack it : s.getBank())
			{
				if (n++ >= MAX_BANK_LINES)
				{
					b.append("... (").append(s.getBank().size() - MAX_BANK_LINES).append(" more not sent)\n");
					break;
				}
				b.append(it.getName()).append(" x").append(it.getQuantity()).append('\n');
			}
		}
		else
		{
			b.append(bank.label()).append(". Do not assume what is or isn't in the bank.\n");
		}

		// ---- profile ----
		b.append("\n[Player-entered info]\n");
		if (t.isProfile() && p != null)
		{
			b.append("Budget: ").append(notBlank(p.getBudget()) ? p.getBudget() : "not given (do not infer it from inventory; ask if it matters)").append('\n');
			b.append("Goals: ").append(notBlank(p.getGoals()) ? p.getGoals() : "not given").append('\n');
			b.append("Preferences: ").append(notBlank(p.getPreferences()) ? p.getPreferences() : "not given").append('\n');
		}
		else
		{
			b.append(Availability.DISABLED_BY_USER.label()).append('\n');
		}
		return b.toString();
	}

	/** One-line summary for the panel header. */
	public static String summary(AccountSnapshot s, Instant now)
	{
		if (s == null || !s.isLoggedIn())
		{
			return "Not logged in — advice will be general.";
		}
		StringBuilder b = new StringBuilder();
		if (s.getSkillsAvailability() == Availability.AVAILABLE)
		{
			b.append("Cmb ").append(s.getCombatLevel()).append(" · Total ").append(s.getTotalLevel());
		}
		else
		{
			b.append("Skills: ").append(s.getSkillsAvailability().label());
		}
		if (s.getQuestsAvailability() == Availability.AVAILABLE)
		{
			long done = s.getQuests().values().stream().filter("FINISHED"::equals).count();
			b.append(" · Quests ").append(done).append('/').append(s.getQuests().size());
		}
		if (s.getAccountAvailability() == Availability.AVAILABLE && s.getAccountType() != null)
		{
			b.append(" · ").append(s.getAccountType());
		}
		b.append(" · read ").append(age(s.getCapturedAt(), now));
		return b.toString();
	}

	static String membership(AccountSnapshot s, UserProfile p, Availability acc)
	{
		String override = p == null ? null : p.getMembership();
		if ("MEMBER".equals(override))
		{
			return "member (set by player)";
		}
		if ("FREE_TO_PLAY".equals(override))
		{
			return "free-to-play (set by player)";
		}
		if (acc == Availability.AVAILABLE && s.isOnMembersWorld())
		{
			return "member (currently on a members world)";
		}
		if (acc == Availability.AVAILABLE)
		{
			return "unknown — on a free-to-play world, which members can also use. Ask if it matters.";
		}
		return "unknown";
	}

	private static void appendItems(StringBuilder b, Availability a, List<ItemStack> items, String emptyText)
	{
		if (a != Availability.AVAILABLE)
		{
			b.append(a.label()).append('\n');
			return;
		}
		if (items.isEmpty())
		{
			b.append(emptyText).append('\n');
			return;
		}
		List<String> parts = new ArrayList<>();
		for (ItemStack it : items)
		{
			String s = (it.getSlot() != null ? it.getSlot() + ": " : "") + it.getName();
			if (it.getQuantity() > 1)
			{
				s += " x" + it.getQuantity();
			}
			parts.add(s);
		}
		b.append(String.join("; ", parts)).append('\n');
	}

	public static String age(Instant then, Instant now)
	{
		if (then == null)
		{
			return "unknown time";
		}
		long secs = Math.max(0, Duration.between(then, now).getSeconds());
		if (secs < 60)
		{
			return "just now";
		}
		if (secs < 3600)
		{
			return (secs / 60) + " min ago";
		}
		if (secs < 86400)
		{
			return (secs / 3600) + " h ago";
		}
		return (secs / 86400) + " days ago";
	}

	private static String listOrNone(List<String> l)
	{
		return l.isEmpty() ? "none" : String.join(", ", l);
	}

	private static boolean notBlank(String s)
	{
		return s != null && !s.trim().isEmpty();
	}

	private static String nz(String s, String d)
	{
		return s == null ? d : s;
	}
}
