package com.osrscoach.context;

import lombok.Value;

@Value
public class ItemStack
{
	int id;
	String name;
	long quantity;
	/** Equipment slot name, or null for inventory/bank items. */
	String slot;
}
