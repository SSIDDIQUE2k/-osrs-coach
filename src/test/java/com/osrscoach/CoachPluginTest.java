package com.osrscoach;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches RuneLite in developer mode with this plugin loaded:  ./gradlew run */
public class CoachPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CoachPlugin.class);
		RuneLite.main(args);
	}
}
