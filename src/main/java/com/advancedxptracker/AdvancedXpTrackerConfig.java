package com.advancedxptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Configuration for Hiscores Tracker
 */
@ConfigGroup("advancedxptracker")
public interface AdvancedXpTrackerConfig extends Config
{
	@ConfigSection(
		name = "Refresh Settings",
		description = "Configure how often data is refreshed",
		position = 1
	)
	String refreshSection = "refresh";

	@ConfigSection(
		name = "Display Settings",
		description = "Configure how information is displayed",
		position = 2
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Developer",
		description = "Options for testing and debugging (no effect in normal use)",
		position = 3
	)
	String developerSection = "developer";

	// ===== REFRESH SETTINGS =====

	@ConfigItem(
		keyName = "autoRefreshEnabled",
		name = "Enable Auto-Refresh",
		description = "Automatically refresh hiscores data periodically",
		position = 1,
		section = refreshSection,
		hidden = true
	)
	default boolean autoRefreshEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "refreshIntervalMinutes",
		name = "Refresh Interval (Minutes)",
		description = "How often to automatically refresh data (minimum 5 minutes)",
		position = 2,
		section = refreshSection,
		hidden = true
	)
	default int refreshIntervalMinutes()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "dataRetentionDays",
		name = "Data Retention (Days)",
		description = "How many days of history to keep (max 180 days)",
		position = 3,
		section = refreshSection
	)
	default int dataRetentionDays()
	{
		return 180; // 6 months
	}

	// ===== DISPLAY SETTINGS =====

	@ConfigItem(
		keyName = "defaultTimeframe",
		name = "Default Timeframe",
		description = "Default timeframe for gains display",
		position = 1,
		section = displaySection
	)
	default String defaultTimeframe()
	{
		return "Today";
	}

	// ===== DEVELOPER (testing / debugging) =====

	@ConfigItem(
		keyName = "lastSelectedPlayer",
		name = "",
		description = "",
		hidden = true
	)
	default String lastSelectedPlayer()
	{
		return "";
	}

	@ConfigItem(
		keyName = "verboseDebugLogging",
		name = "Verbose debug logging",
		description = "When enabled, log extra detail at DEBUG: every game state change, login sync guard, and snapshot source. Use with Run Task 'Run RuneLite with plugin' (--debug) and filter terminal for 'Hiscores' or 'advancedxptracker'.",
		position = 1,
		section = developerSection
	)
	default boolean verboseDebugLogging()
	{
		return false;
	}
}
