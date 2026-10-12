package com.osrscoach.context;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.inject.Singleton;
import lombok.Value;

/**
 * Holds the last bank contents the client showed us (only when the player opens the bank).
 * Kept in memory for this session only; never written to disk.
 */
@Singleton
public class BankCache
{
	@Value
	public static class Entry
	{
		List<ItemStack> items;
		Instant cachedAt;
	}

	private volatile Entry entry;

	public void update(List<ItemStack> items, Instant at)
	{
		entry = new Entry(Collections.unmodifiableList(items), at);
	}

	public Entry get()
	{
		return entry;
	}

	public void clear()
	{
		entry = null;
	}
}
