package com.osrscoach.context;

import lombok.Builder;
import lombok.Value;

/** Things the player typed in, because the game can't reliably tell us. */
@Value
@Builder
public class UserProfile
{
	String membership; // AUTO_DETECT / FREE_TO_PLAY / MEMBER
	String budget;
	String goals;
	String preferences;
	/** Quest the player picked in the panel's "Quest help" selector, may be null. */
	String selectedQuest;
}
