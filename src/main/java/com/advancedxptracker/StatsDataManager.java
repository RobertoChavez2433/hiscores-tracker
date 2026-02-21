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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
	private final AtomicBoolean isDirty = new AtomicBoolean(false);

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

		log.debug("StatsDataManager initialized with data file: {}", dataFile.getAbsolutePath());
	}

	/**
	 * Start periodic flush of dirty data to disk.
	 */
	public void initialize(ScheduledExecutorService executor)
	{
		executor.scheduleAtFixedRate(() -> {
			if (isDirty.compareAndSet(true, false))
			{
				saveAllData();
			}
		}, 30, 30, TimeUnit.SECONDS);
	}

	/**
	 * Immediately flush pending data to disk if dirty.
	 */
	public void flush()
	{
		if (isDirty.compareAndSet(true, false))
		{
			saveAllData();
		}
	}

	/**
	 * Load all player data from JSON file
	 */
	private void loadAllData()
	{
		if (!dataFile.exists())
		{
			log.debug("Data file doesn't exist yet, starting fresh");
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

			log.debug("Loaded data for {} players from file", allPlayerData.size());
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

			log.debug("Saved data for {} players to file", allPlayerData.size());
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
				log.debug("Removed {} old snapshots for player '{}'", removed, entry.getKey());
			}
		}

		if (totalRemoved > 0)
		{
			saveAllData();
			log.debug("Cleanup complete: removed {} total old snapshots", totalRemoved);
		}
	}

	/**
	 * Save account type for a player (stored in ConfigManager)
	 */
	public void saveAccountType(String username, AccountType accountType)
	{
		String key = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();
		configManager.setConfiguration(CONFIG_GROUP, key, accountType.name());
		log.debug("Saved account type '{}' for player '{}'", accountType.getDisplayName(), username);
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
	 * Save a player stats snapshot (source used only for debug logging).
	 */
	public void saveSnapshot(PlayerStats stats, String source)
	{
		log.debug("Saving snapshot for username: '{}' (source: {})", stats.getUsername(), source);

		String key = stats.getUsername().toLowerCase();
		List<PlayerStats> snapshots = allPlayerData.computeIfAbsent(key, k -> new ArrayList<>());

		// Coalesce: skip if XP unchanged from latest snapshot
		if (!snapshots.isEmpty())
		{
			PlayerStats latest = snapshots.get(snapshots.size() - 1);
			long currentXp = stats.getTotalXp();
			if (currentXp != 0 && currentXp == latest.getTotalXp())
			{
				log.debug("Skipping duplicate snapshot for '{}' (XP unchanged)", stats.getUsername());
				return;
			}
		}

		// Add new snapshot
		snapshots.add(stats);
		log.debug("Added new snapshot, total snapshots: {}", snapshots.size());

		// Remove old snapshots (older than 180 days)
		long cutoffTime = System.currentTimeMillis() - MAX_SNAPSHOT_AGE;
		int beforeSize = snapshots.size();
		snapshots.removeIf(s -> s.getTimestamp() < cutoffTime);
		int removed = beforeSize - snapshots.size();

		if (removed > 0)
		{
			log.debug("Removed {} old snapshots", removed);
		}

		// Mark for periodic flush (every 30 seconds)
		isDirty.set(true);
		log.debug("Saved snapshot for '{}' (source: {}, {} total snapshots)", stats.getUsername(), source, snapshots.size());
	}

	/**
	 * Save a player stats snapshot (no source tag; for callers that do not pass source).
	 */
	public void saveSnapshot(PlayerStats stats)
	{
		saveSnapshot(stats, "unknown");
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
		log.debug("Removing player: '{}'", username);

		String key = username.toLowerCase();
		String typeKey = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();

		// Remove from data file
		allPlayerData.remove(key);
		saveAllData();

		// Remove account type from config
		configManager.unsetConfiguration(CONFIG_GROUP, typeKey);

		log.debug("Removed player '{}' and all associated data", username);
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

		log.debug("Returning {} tracked players", players.size());
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
