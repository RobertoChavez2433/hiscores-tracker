package com.advancedxptracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Manages historical snapshots of player stats
 */
@Slf4j
public class StatsDataManager
{
	private static final String CONFIG_GROUP = "advancedxptracker";
	private static final String CONFIG_KEY_PREFIX = "player_";
	private static final String ACCOUNT_TYPE_KEY_PREFIX = "accounttype_";
	private static final Gson GSON = new Gson();
	private static final long MAX_SNAPSHOT_AGE = TimeUnit.DAYS.toMillis(365); // Keep 1 year

	private final ConfigManager configManager;

	public StatsDataManager(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * Save account type for a player
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
			log.info("No account type found for '{}', defaulting to NORMAL", username);
			return AccountType.NORMAL;
		}

		try
		{
			AccountType type = AccountType.valueOf(typeName);
			log.info("Loaded account type '{}' for player '{}'", type.getDisplayName(), username);
			return type;
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
		log.info("========================================");
		log.info("SAVING SNAPSHOT for username: '{}'", stats.getUsername());
		log.info("========================================");

		String key = CONFIG_KEY_PREFIX + stats.getUsername().toLowerCase();
		log.info("Config key will be: '{}'", key);

		List<PlayerStats> snapshots = loadSnapshots(stats.getUsername());
		log.info("Loaded {} existing snapshots", snapshots.size());

		// Add new snapshot
		snapshots.add(stats);
		log.info("Added new snapshot. Total snapshots now: {}", snapshots.size());

		// Sample some activity data from the new snapshot
		log.info("Sample activities from NEW snapshot:");
		stats.getActivities().entrySet().stream()
			.filter(e -> e.getValue().getScore() > 0)
			.limit(10)
			.forEach(e -> log.info("  '{}': score={}", e.getKey(), e.getValue().getScore()));

		// Remove old snapshots (older than 1 year)
		long cutoffTime = System.currentTimeMillis() - MAX_SNAPSHOT_AGE;
		snapshots.removeIf(s -> s.getTimestamp() < cutoffTime);
		log.info("After cleanup: {} snapshots remain", snapshots.size());

		// Save back to config
		String json = GSON.toJson(snapshots);
		log.info("JSON length to save: {} characters", json.length());
		configManager.setConfiguration(CONFIG_GROUP, key, json);

		log.info("✅ Saved snapshot for '{}' ({} total snapshots)", stats.getUsername(), snapshots.size());
		log.info("========================================");
	}

	/**
	 * Load all snapshots for a player
	 */
	public List<PlayerStats> loadSnapshots(String username)
	{
		String key = CONFIG_KEY_PREFIX + username.toLowerCase();
		String json = configManager.getConfiguration(CONFIG_GROUP, key);

		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}

		try
		{
			Type listType = new TypeToken<List<PlayerStats>>(){}.getType();
			List<PlayerStats> snapshots = GSON.fromJson(json, listType);
			return snapshots != null ? snapshots : new ArrayList<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load snapshots for {}", username, e);
			return new ArrayList<>();
		}
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
		log.info("========================================");
		log.info("REMOVING PLAYER: '{}'", username);
		log.info("========================================");

		String dataKey = CONFIG_KEY_PREFIX + username.toLowerCase();
		String typeKey = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();

		configManager.unsetConfiguration(CONFIG_GROUP, dataKey);
		configManager.unsetConfiguration(CONFIG_GROUP, typeKey);

		log.info("✅ Removed player '{}' and all associated data", username);
		log.info("========================================");
	}

	/**
	 * Get list of all tracked players
	 */
	public List<String> getTrackedPlayers()
	{
		List<String> players = new ArrayList<>();
		// Get all keys for this config group
		List<String> keys = configManager.getConfigurationKeys(CONFIG_GROUP);

		log.info("getConfigurationKeys returned: {}", keys);

		if (keys != null)
		{
			for (String fullKey : keys)
			{
				log.info("Checking key: {}", fullKey);
				// Keys come back as "configgroup.keyname", extract just the keyname part
				String[] parts = fullKey.split("\\.", 2);
				if (parts.length == 2)
				{
					String keyName = parts[1];
					log.info("KeyName extracted: {}", keyName);
					if (keyName.startsWith(CONFIG_KEY_PREFIX))
					{
						String usernameKey = keyName.substring(CONFIG_KEY_PREFIX.length());
						log.info("Username key extracted: {}", usernameKey);
						// Load the actual username from the first snapshot (preserves original casing)
						List<PlayerStats> snapshots = loadSnapshots(usernameKey);
						log.info("Loaded {} snapshots for key: {}", snapshots.size(), usernameKey);
						if (!snapshots.isEmpty())
						{
							String username = snapshots.get(0).getUsername();
							log.info("Adding player with original casing: {}", username);
							players.add(username);
						}
					}
				}
			}
		}

		log.info("Returning {} tracked players total", players.size());
		return players;
	}

	/**
	 * Delete all data for a player
	 */
	public void deletePlayer(String username)
	{
		String key = CONFIG_KEY_PREFIX + username.toLowerCase();
		configManager.unsetConfiguration(CONFIG_GROUP, key);
		log.info("Deleted all data for {}", username);
	}
}
