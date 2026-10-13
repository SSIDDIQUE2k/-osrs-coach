package com.osrscoach.ai;

import com.google.gson.JsonObject;
import com.osrscoach.context.AccountSnapshot;
import com.osrscoach.context.Availability;
import com.osrscoach.context.SkillInfo;
import java.util.Map;

/**
 * Offline placeholder used when no AI provider is configured. It does NOT understand questions;
 * it applies one fixed rule to the account context so the whole pipeline can be tried without a key.
 * Every reply is marked non-live and starts with a banner.
 */
public class MockProvider implements AiProvider
{
	public static final String BANNER = "⚠ **MOCK MODE — this is not AI output.** A fixed rule produced this from your account data; it did not read your question. Set a Provider and API key in settings for real answers.";

	private final AccountSnapshot snapshot;
	private final boolean questsShared;
	private final boolean skillsShared;

	public MockProvider(AccountSnapshot snapshot, boolean questsShared, boolean skillsShared)
	{
		this.snapshot = snapshot;
		this.questsShared = questsShared;
		this.skillsShared = skillsShared;
	}

	@Override
	public String label()
	{
		return "Mock mode (no AI)";
	}

	@Override
	public CoachReply respond(CoachRequest req, CoachTools tools)
	{
		String step;
		String why;
		String lookup;
		if (snapshot == null || !snapshot.isLoggedIn())
		{
			step = "Log in, then press **Refresh** so the coach can read your account.";
			why = "Without live data, any advice would be a guess.";
			lookup = null;
		}
		else
		{
			String inProgress = questsShared ? firstInProgress(snapshot) : null;
			SkillInfo lowest = skillsShared ? lowestFreeSkill(snapshot) : null;
			if (inProgress != null)
			{
				step = "Continue **" + inProgress + "** (it's already in progress).";
				why = "Finishing started quests gives quest points and rewards without new requirements to chase.";
				lookup = inProgress;
			}
			else if (lowest != null)
			{
				step = "Train **" + lowest.getName() + "** (your lowest free-to-play skill, level " + lowest.getLevel() + ").";
				why = "Raising your lowest skill opens more quests and training options.";
				lookup = lowest.getName();
			}
			else
			{
				step = "Share skills or quests in settings so the mock rule has something to work with.";
				why = "Both are unavailable or switched off.";
				lookup = null;
			}
		}

		StringBuilder sb = new StringBuilder(BANNER).append("\n\n");
		sb.append("**Next step:** ").append(step).append('\n');
		sb.append("**Why:** ").append(why).append('\n');
		sb.append("**How to start:** Open the OSRS Wiki page below; Quest Helper can guide quest steps.\n");
		if (lookup != null && tools.enabled())
		{
			JsonObject args = new JsonObject();
			args.addProperty("query", lookup);
			String res = tools.execute("wiki_search", args);
			if (res.startsWith("LOOKUP_FAILED") || res.startsWith("LOOKUP_UNAVAILABLE"))
			{
				sb.append("\n_Wiki lookup failed, so nothing here was verified._\n");
			}
		}
		else if (lookup != null)
		{
			sb.append("\n_Wiki lookups are off, so nothing here was verified._\n");
		}
		sb.append("**Sources:** ").append(tools.sources().isEmpty() ? "none (mock mode)" : "see the verified links below — fetched live from the wiki") .append('\n');
		return new CoachReply(sb.toString(), tools.sources(), tools.failures(), false, label(), tools.marks());
	}

	static String firstInProgress(AccountSnapshot s)
	{
		if (s.getQuestsAvailability() != Availability.AVAILABLE)
		{
			return null;
		}
		for (Map.Entry<String, String> e : s.getQuests().entrySet())
		{
			if ("IN_PROGRESS".equals(e.getValue()))
			{
				return e.getKey();
			}
		}
		return null;
	}

	static SkillInfo lowestFreeSkill(AccountSnapshot s)
	{
		if (s.getSkillsAvailability() != Availability.AVAILABLE)
		{
			return null;
		}
		SkillInfo best = null;
		for (SkillInfo si : s.getSkills())
		{
			if (!si.isMembersOnly() && (best == null || si.getXp() < best.getXp()))
			{
				best = si;
			}
		}
		return best;
	}
}
