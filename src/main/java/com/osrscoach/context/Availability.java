package com.osrscoach.context;

/**
 * Why a piece of context is or isn't present. Keeps "we couldn't read it" separate from
 * "it is genuinely zero / empty".
 */
public enum Availability
{
	AVAILABLE("available"),
	NOT_LOGGED_IN("unavailable: not logged in"),
	NOT_LOADED("unavailable: not loaded by the game client yet"),
	NEVER_SEEN("unavailable: not seen this session"),
	DISABLED_BY_USER("not shared (disabled in settings)"),
	ERROR("unavailable: read error");

	private final String label;

	Availability(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
