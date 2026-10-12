package com.osrscoach.ai;

import lombok.Value;

@Value
public class ChatTurn
{
	public enum Role
	{
		USER,
		ASSISTANT
	}

	Role role;
	String text;
}
