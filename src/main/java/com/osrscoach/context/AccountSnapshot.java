package com.osrscoach.context;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * An immutable copy of what the plugin could read from the client at one moment.
 * Contains no RuneLite types so it can be built from mock data in tests.
 */
@Value
@Builder(toBuilder = true)
public class AccountSnapshot
{
	Instant capturedAt;
	boolean loggedIn;

	@Builder.Default
	Availability skillsAvailability = Availability.NOT_LOGGED_IN;
	@Builder.Default
	List<SkillInfo> skills = Collections.emptyList();
	int combatLevel;
	int totalLevel;

	@Builder.Default
	Availability questsAvailability = Availability.NOT_LOGGED_IN;
	/** Quest name -> NOT_STARTED / IN_PROGRESS / FINISHED. Insertion ordered. */
	@Builder.Default
	Map<String, String> quests = Collections.emptyMap();

	@Builder.Default
	Availability inventoryAvailability = Availability.NOT_LOGGED_IN;
	@Builder.Default
	List<ItemStack> inventory = Collections.emptyList();

	@Builder.Default
	Availability equipmentAvailability = Availability.NOT_LOGGED_IN;
	@Builder.Default
	List<ItemStack> equipment = Collections.emptyList();

	@Builder.Default
	Availability locationAvailability = Availability.NOT_LOGGED_IN;
	int x;
	int y;
	int plane;
	int regionId;

	@Builder.Default
	Availability accountAvailability = Availability.NOT_LOGGED_IN;
	/** e.g. "Normal", "Ironman", or "unknown (raw 9)". */
	String accountType;
	/** True when on a members world. False = F2P world (membership itself is then unknown). */
	boolean onMembersWorld;
	int world;
	/** Non-standard world modes such as SEASONAL or DEADMAN, empty for normal worlds. */
	@Builder.Default
	List<String> worldModes = Collections.emptyList();

	@Builder.Default
	Availability bankAvailability = Availability.NEVER_SEEN;
	@Builder.Default
	List<ItemStack> bank = Collections.emptyList();
	Instant bankCachedAt;

	public static AccountSnapshot loggedOut(Instant now)
	{
		return AccountSnapshot.builder().capturedAt(now).loggedIn(false).build();
	}
}
