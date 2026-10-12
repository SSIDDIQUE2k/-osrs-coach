package com.osrscoach.ai;

public interface AiProvider
{
	/** Blocking. Call off the client and Swing threads. */
	CoachReply respond(CoachRequest request, CoachTools tools) throws CoachException;

	String label();
}
