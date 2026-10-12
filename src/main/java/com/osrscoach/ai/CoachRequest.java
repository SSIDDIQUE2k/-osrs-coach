package com.osrscoach.ai;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CoachRequest
{
	String systemPrompt;
	/** Prior turns, oldest first, text only. */
	List<ChatTurn> history;
	String userMessage;
	String model;
	int maxTokens;
	/** Plain account-context text (also embedded in systemPrompt); used by mock mode. */
	String accountContext;
}
