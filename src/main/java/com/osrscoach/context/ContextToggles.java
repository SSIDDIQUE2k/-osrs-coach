package com.osrscoach.context;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ContextToggles
{
	@Builder.Default boolean skills = true;
	@Builder.Default boolean quests = true;
	@Builder.Default boolean inventory = true;
	@Builder.Default boolean equipment = true;
	@Builder.Default boolean location = true;
	@Builder.Default boolean accountType = true;
	@Builder.Default boolean bank = false;
	@Builder.Default boolean profile = true;

	public static ContextToggles all()
	{
		return ContextToggles.builder().bank(true).build();
	}
}
