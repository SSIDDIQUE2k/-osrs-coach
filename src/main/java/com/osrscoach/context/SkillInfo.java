package com.osrscoach.context;

import lombok.Value;

@Value
public class SkillInfo
{
	String name;
	int level;
	int xp;
	boolean membersOnly;
}
