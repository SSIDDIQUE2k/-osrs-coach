package com.osrscoach;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(CoachConfig.GROUP)
public interface CoachConfig extends Config
{
	String GROUP = "osrscoach";

	enum Provider
	{
		MOCK,
		ANTHROPIC,
		OPENAI_COMPATIBLE
	}

	enum WebSearch
	{
		OFF,
		SERPER_GOOGLE,
		BRAVE
	}

	enum Membership
	{
		AUTO_DETECT,
		FREE_TO_PLAY,
		MEMBER
	}

	@ConfigSection(name = "AI provider", description = "Which AI service answers your questions", position = 0)
	String providerSection = "provider";

	@ConfigSection(name = "Context sent to AI", description = "Choose which account details may be sent with a question", position = 1)
	String contextSection = "context";

	@ConfigSection(name = "About me", description = "Things the game can't tell the coach", position = 2)
	String aboutSection = "about";

	@ConfigSection(name = "Behaviour", description = "Lookups, history and suggestions", position = 3)
	String behaviourSection = "behaviour";

	// ---------------- provider ----------------

	@ConfigItem(keyName = "provider", name = "Provider",
		description = "MOCK = offline rule-based placeholder (no AI). ANTHROPIC = Claude API. OPENAI_COMPATIBLE = OpenAI, OpenRouter, Ollama, LM Studio, etc.",
		section = providerSection, position = 0)
	default Provider provider()
	{
		return Provider.MOCK;
	}

	@ConfigItem(keyName = "apiKey", name = "API key",
		description = "Stored locally in your RuneLite settings. Never logged. Leave blank for local servers like Ollama.",
		secret = true, section = providerSection, position = 1)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(keyName = "model", name = "Model",
		description = "e.g. claude-sonnet-5-5 or claude-haiku-5-5 (Anthropic), gpt-4.1-mini (OpenAI), llama3.1 (Ollama). Must support tool calling for wiki lookups.",
		section = providerSection, position = 2)
	default String model()
	{
		return "claude-sonnet-5-5";
	}

	@ConfigItem(keyName = "baseUrl", name = "Base URL (OpenAI-compatible)",
		description = "Only for OPENAI_COMPATIBLE. e.g. https://api.openai.com/v1 or http://localhost:11434/v1",
		section = providerSection, position = 3)
	default String baseUrl()
	{
		return "https://api.openai.com/v1";
	}

	@ConfigItem(keyName = "maxTokens", name = "Max answer length (tokens)",
		description = "Upper bound on each answer. Lower = cheaper.", section = providerSection, position = 4)
	@Range(min = 200, max = 4000)
	default int maxTokens()
	{
		return 1200;
	}

	// ---------------- context toggles ----------------

	@ConfigItem(keyName = "sendSkills", name = "Skills & XP", description = "Send skill levels and experience", section = contextSection, position = 0)
	default boolean sendSkills()
	{
		return true;
	}

	@ConfigItem(keyName = "sendQuests", name = "Quest progress", description = "Send quest states", section = contextSection, position = 1)
	default boolean sendQuests()
	{
		return true;
	}

	@ConfigItem(keyName = "sendInventory", name = "Inventory", description = "Send carried items", section = contextSection, position = 2)
	default boolean sendInventory()
	{
		return true;
	}

	@ConfigItem(keyName = "sendEquipment", name = "Equipment", description = "Send worn items", section = contextSection, position = 3)
	default boolean sendEquipment()
	{
		return true;
	}

	@ConfigItem(keyName = "sendLocation", name = "Location", description = "Send your tile coordinates", section = contextSection, position = 4)
	default boolean sendLocation()
	{
		return true;
	}

	@ConfigItem(keyName = "sendAccountType", name = "Account type & world", description = "Send ironman mode and members/F2P world", section = contextSection, position = 5)
	default boolean sendAccountType()
	{
		return true;
	}

	@ConfigItem(keyName = "sendBank", name = "Bank (cached)",
		description = "Send the bank snapshot cached the last time you opened your bank this session. Off by default.",
		section = contextSection, position = 6)
	default boolean sendBank()
	{
		return false;
	}

	@ConfigItem(keyName = "sendProfile", name = "My goals, budget & notes", description = "Send what you typed in 'About me'", section = contextSection, position = 7)
	default boolean sendProfile()
	{
		return true;
	}

	// ---------------- about me ----------------

	@ConfigItem(keyName = "membership", name = "Membership", description = "Override if auto-detect is wrong", section = aboutSection, position = 0)
	default Membership membership()
	{
		return Membership.AUTO_DETECT;
	}

	@ConfigItem(keyName = "budget", name = "Budget", description = "Free text, e.g. '500k gp'. The coach never guesses this from your inventory.", section = aboutSection, position = 1)
	default String budget()
	{
		return "";
	}

	@ConfigItem(keyName = "goals", name = "Goals", description = "e.g. 'Get a fire cape eventually; enjoy questing'", section = aboutSection, position = 2)
	default String goals()
	{
		return "";
	}

	@ConfigItem(keyName = "preferences", name = "Preferences", description = "e.g. 'I like skilling more than combat; 1 hour a day'", section = aboutSection, position = 3)
	default String preferences()
	{
		return "";
	}

	// ---------------- behaviour ----------------

	@ConfigItem(keyName = "wikiLookups", name = "Allow wiki & price lookups",
		description = "Lets the coach search the OSRS Wiki and read real-time prices from prices.runescape.wiki",
		section = behaviourSection, position = 0)
	default boolean wikiLookups()
	{
		return true;
	}

	@ConfigItem(keyName = "webSearch", name = "General web search",
		description = "Lets the coach search the whole web for things the wiki lacks (updates, Reddit, guides). "
			+ "SERPER_GOOGLE = real Google results (serper.dev). BRAVE = Brave Search API. Needs its own key below.",
		section = behaviourSection, position = 1)
	default WebSearch webSearch()
	{
		return WebSearch.OFF;
	}

	@ConfigItem(keyName = "webSearchKey", name = "Web search API key",
		description = "Key from serper.dev or api-dashboard.search.brave.com. Stored locally, never logged.",
		secret = true, section = behaviourSection, position = 2)
	default String webSearchKey()
	{
		return "";
	}

	@ConfigItem(keyName = "historyTurns", name = "Remembered turns", description = "How many previous Q&A pairs are re-sent for context", section = behaviourSection, position = 3)
	@Range(min = 0, max = 20)
	default int historyTurns()
	{
		return 6;
	}

	@ConfigItem(keyName = "proactive", name = "Quest-complete suggestions",
		description = "When you finish a quest, show ONE quiet prompt in the panel. No popups or notifications.",
		section = behaviourSection, position = 4)
	default boolean proactive()
	{
		return false;
	}
}
