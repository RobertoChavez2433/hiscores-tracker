package com.advancedxptracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AdvancedXpTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AdvancedXpTrackerPlugin.class);
		RuneLite.main(args);
	}
}
