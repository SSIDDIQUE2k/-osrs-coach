package com.osrscoach.ai;

/** Draws a destination for the player to walk to themselves. Visual only: never moves the player. */
public interface MapMarker
{
	void mark(String label, int x, int y, int plane);

	void clear();
}
