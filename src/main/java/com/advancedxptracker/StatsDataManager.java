package com.advancedxptracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Manages historical snapshots of player stats using a separate JSON file
 * Account types are still stored in ConfigManager for user preferences
 */
@Slf4j
public class StatsDataManager
{
	private static final String CONFIG_GROUP = "advancedxptracker";
	private static final String ACCOUNT_TYPE_KEY_PREFIX = "accounttype_";
	private static final long MAX_SNAPSHOT_AGE = TimeUnit.DAYS.toMillis(180); // Keep 180 days
	private static final String DATA_FILE_NAME = "hiscores-tracker-data.json";

	private final ConfigManager configManager;
	private final Gson gson;
	private final File dataFile;
	private Map<String, List<PlayerStats>> allPlayerData;

	public StatsDataManager(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson.newBuilder().setPrettyPrinting().create();

		// Use RuneLite's official directory constant
		this.dataFile = new File(RuneLite.RUNELITE_DIR, DATA_FILE_NAME);

		// Load all data from file
		loadAllData();

		// Clean up old snapshots on startup
		cleanupOldSnapshots();

		log.info("StatsDataManager initialized with data file: {}", dataFile.getAbsolutePath());
	}

	/**
	 * Load all player data from JSON file
	 */
	private void loadAllData()
	{
		if (!dataFile.exists())
		{
			log.info("Data file doesn't exist yet, starting fresh");
			allPlayerData = new HashMap<>();
			return;
		}

		try (Reader reader = new FileReader(dataFile))
		{
			Type type = new TypeToken<Map<String, List<PlayerStats>>>(){}.getType();
			allPlayerData = gson.fromJson(reader, type);

			if (allPlayerData == null)
			{
				allPlayerData = new HashMap<>();
			}

			log.info("Loaded data for {} players from file", allPlayerData.size());
		}
		catch (Exception e)
		{
			log.error("Failed to load data file, starting fresh", e);
			allPlayerData = new HashMap<>();
		}
	}

	/**
	 * Save all player data to JSON file
	 */
	private void saveAllData()
	{
		try
		{
			// Create parent directory if it doesn't exist
			if (!dataFile.getParentFile().exists())
			{
				dataFile.getParentFile().mkdirs();
			}

			try (Writer writer = new FileWriter(dataFile))
			{
				gson.toJson(allPlayerData, writer);
			}

			log.info("Saved data for {} players to file", allPlayerData.size());
		}
		catch (Exception e)
		{
			log.error("Failed to save data file", e);
		}
	}

	/**
	 * Clean up snapshots older than 180 days for all players
	 */
	private void cleanupOldSnapshots()
	{
		long cutoffTime = System.currentTimeMillis() - MAX_SNAPSHOT_AGE;
		int totalRemoved = 0;

		for (Map.Entry<String, List<PlayerStats>> entry : allPlayerData.entrySet())
		{
			List<PlayerStats> snapshots = entry.getValue();
			int beforeSize = snapshots.size();
			snapshots.removeIf(s -> s.getTimestamp() < cutoffTime);
			int removed = beforeSize - snapshots.size();

			if (removed > 0)
			{
				totalRemoved += removed;
				log.info("Removed {} old snapshots for player '{}'", removed, entry.getKey());
			}
		}

		if (totalRemoved > 0)
		{
			saveAllData();
			log.info("Cleanup complete: removed {} total old snapshots", totalRemoved);
		}
	}

	/**
	 * Save account type for a player (stored in ConfigManager)
	 */
	public void saveAccountType(String username, AccountType accountType)
	{
		String key = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();
		configManager.setConfiguration(CONFIG_GROUP, key, accountType.name());
		log.info("Saved account type '{}' for player '{}'", accountType.getDisplayName(), username);
	}

	/**
	 * Load account type for a player (defaults to NORMAL if not set)
	 */
	public AccountType loadAccountType(String username)
	{
		String key = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();
		String typeName = configManager.getConfiguration(CONFIG_GROUP, key);

		if (typeName == null || typeName.isEmpty())
		{
			return AccountType.NORMAL;
		}

		try
		{
			return AccountType.valueOf(typeName);
		}
		catch (IllegalArgumentException e)
		{
			log.warn("Invalid account type '{}' for player '{}', defaulting to NORMAL", typeName, username);
			return AccountType.NORMAL;
		}
	}

	/**
	 * Save a player stats snapshot
	 */
	public void saveSnapshot(PlayerStats stats)
	{
		log.info("Saving snapshot for username: '{}'", stats.getUsername());

		String key = stats.getUsername().toLowerCase();
		List<PlayerStats> snapshots = allPlayerData.computeIfAbsent(key, k -> new ArrayList<>());

		// Add new snapshot
		snapshots.add(stats);
		log.info("Added new snapshot. Total snapshots: {}", snapshots.size());

		// Remove old snapshots (older than 180 days)
		long cutoffTime = System.currentTimeMillis() - MAX_SNAPSHOT_AGE;
		int beforeSize = snapshots.size();
		snapshots.removeIf(s -> s.getTimestamp() < cutoffTime);
		int removed = beforeSize - snapshots.size();

		if (removed > 0)
		{
			log.info("Removed {} old snapshots", removed);
		}

		// Save to file
		saveAllData();
		log.info("✅ Saved snapshot for '{}' ({} total snapshots)", stats.getUsername(), snapshots.size());
	}

	/**
	 * Load all snapshots for a player
	 */
	public List<PlayerStats> loadSnapshots(String username)
	{
		String key = username.toLowerCase();
		List<PlayerStats> snapshots = allPlayerData.get(key);
		return snapshots != null ? new ArrayList<>(snapshots) : new ArrayList<>();
	}

	/**
	 * Get the most recent snapshot for a player
	 */
	public PlayerStats getLatestSnapshot(String username)
	{
		List<PlayerStats> snapshots = loadSnapshots(username);
		if (snapshots.isEmpty())
		{
			return null;
		}

		return snapshots.get(snapshots.size() - 1);
	}

	/**
	 * Get a snapshot from X days ago (closest match)
	 */
	public PlayerStats getSnapshotFromDaysAgo(String username, int days)
	{
		List<PlayerStats> snapshots = loadSnapshots(username);
		if (snapshots.isEmpty())
		{
			return null;
		}

		long targetTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);

		// Find closest snapshot to target time
		PlayerStats closest = null;
		long smallestDiff = Long.MAX_VALUE;

		for (PlayerStats snapshot : snapshots)
		{
			long diff = Math.abs(snapshot.getTimestamp() - targetTime);
			if (diff < smallestDiff)
			{
				smallestDiff = diff;
				closest = snapshot;
			}
		}

		return closest;
	}

	/**
	 * Remove a player and all their data
	 */
	public void removePlayer(String username)
	{
		log.info("Removing player: '{}'", username);

		String key = username.toLowerCase();
		String typeKey = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();

		// Remove from data file
		allPlayerData.remove(key);
		saveAllData();

		// Remove account type from config
		configManager.unsetConfiguration(CONFIG_GROUP, typeKey);

		log.info("✅ Removed player '{}' and all associated data", username);
	}

	/**
	 * Get list of all tracked players
	 */
	public List<String> getTrackedPlayers()
	{
		List<String> players = new ArrayList<>();

		for (Map.Entry<String, List<PlayerStats>> entry : allPlayerData.entrySet())
		{
			List<PlayerStats> snapshots = entry.getValue();
			if (!snapshots.isEmpty())
			{
				// Use original casing from first snapshot
				String username = snapshots.get(0).getUsername();
				players.add(username);
			}
		}

		log.info("Returning {} tracked players", players.size());
		return players;
	}

	/**
	 * Delete all data for a player
	 */
	public void deletePlayer(String username)
	{
		removePlayer(username);
	}
}
