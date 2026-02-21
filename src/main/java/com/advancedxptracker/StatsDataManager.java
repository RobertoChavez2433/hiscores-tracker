package com.advancedxptracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.swing.SwingUtilities;
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
	private static final String DATA_FILE_NAME = "hiscores-tracker-data.json";
	private static final int MAX_SNAPSHOTS_PER_PLAYER = 1024;
	private static final long FULL_RESOLUTION_MS = TimeUnit.HOURS.toMillis(24);
	private static final long HOURLY_RESOLUTION_MS = TimeUnit.DAYS.toMillis(7);
	private static final int RETENTION_DAYS = 180;
	private static final long MAX_SNAPSHOT_AGE = TimeUnit.DAYS.toMillis(RETENTION_DAYS);

	private final ConfigManager configManager;
	private final Gson gson;
	private final File dataFile;
	private Map<String, List<PlayerStats>> allPlayerData = new HashMap<>();
	private final Object dataLock = new Object();
	private final AtomicBoolean isDirty = new AtomicBoolean(false);
	private volatile boolean initialized = false;

	public StatsDataManager(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson.newBuilder().setPrettyPrinting().create();

		// Use RuneLite's official directory constant
		this.dataFile = new File(RuneLite.RUNELITE_DIR, DATA_FILE_NAME);

		log.debug("StatsDataManager created, data file: {}", dataFile.getAbsolutePath());
	}

	public boolean isInitialized()
	{
		return initialized;
	}

	/**
	 * Schedule periodic flush and load data asynchronously.
	 * Calls onReady on the EDT once data is loaded and cleanup is complete.
	 */
	public void initialize(ScheduledExecutorService executor, Runnable onReady)
	{
		// Schedule periodic flush
		executor.scheduleAtFixedRate(() -> {
			if (isDirty.compareAndSet(true, false))
			{
				saveAllData();
			}
		}, 30, 30, TimeUnit.SECONDS);

		// Load data asynchronously
		executor.submit(() -> {
			loadAllData();
			cleanupOldSnapshots();
			initialized = true;
			if (onReady != null)
			{
				SwingUtilities.invokeLater(onReady);
			}
		});
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
			synchronized (dataLock)
			{
				allPlayerData = new HashMap<>();
			}
			return;
		}

		try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8))
		{
			Type type = new TypeToken<Map<String, List<PlayerStats>>>(){}.getType();
			Map<String, List<PlayerStats>> loaded = gson.fromJson(reader, type);

			int loadedCount;
			synchronized (dataLock)
			{
				allPlayerData = loaded != null ? loaded : new HashMap<>();
				loadedCount = allPlayerData.size();
			}

			log.debug("Loaded data for {} players from file", loadedCount);
		}
		catch (Exception e)
		{
			log.error("Failed to load data file, starting fresh", e);

			// Backup corrupt file for potential manual recovery
			try
			{
				File backupFile = new File(dataFile.getParentFile(), DATA_FILE_NAME + ".corrupt");
				Files.copy(dataFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				log.warn("Corrupt data file backed up to: {}", backupFile.getAbsolutePath());
			}
			catch (IOException copyEx)
			{
				log.error("Failed to backup corrupt data file", copyEx);
			}

			synchronized (dataLock)
			{
				allPlayerData = new HashMap<>();
			}
		}
	}

	/**
	 * Save all player data to JSON file.
	 * Serializes under lock, then writes outside lock so slow I/O doesn't hold it.
	 */
	private void saveAllData()
	{
		String json;
		int playerCount;
		synchronized (dataLock)
		{
			json = gson.toJson(allPlayerData);
			playerCount = allPlayerData.size();
		}

		try
		{
			// Create parent directory if it doesn't exist
			if (!dataFile.getParentFile().exists())
			{
				dataFile.getParentFile().mkdirs();
			}

			File tempFile = new File(dataFile.getParentFile(), DATA_FILE_NAME + ".tmp");
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8))
			{
				writer.write(json);
			}
			try
			{
				Files.move(tempFile.toPath(), dataFile.toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				// Fallback for filesystems that don't support atomic move
				Files.move(tempFile.toPath(), dataFile.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			}

			log.debug("Saved data for {} players to file", playerCount);
		}
		catch (Exception e)
		{
			log.error("Failed to save data file", e);
			isDirty.set(true); // Retry on next flush cycle
		}
	}

	/**
	 * Clean up and compact snapshots for all players using tiered compaction.
	 */
	private void cleanupOldSnapshots()
	{
		int totalRemoved;

		synchronized (dataLock)
		{
			totalRemoved = 0;

			for (Map.Entry<String, List<PlayerStats>> entry : allPlayerData.entrySet())
			{
				List<PlayerStats> snapshots = entry.getValue();
				int beforeSize = snapshots.size();
				List<PlayerStats> compacted = compactSnapshots(snapshots);
				snapshots.clear();
				snapshots.addAll(compacted);
				int removed = beforeSize - snapshots.size();

				if (removed > 0)
				{
					totalRemoved += removed;
					log.debug("Compacted {} snapshots for player '{}'", removed, entry.getKey());
				}
			}
		}

		if (totalRemoved > 0)
		{
			saveAllData();
			log.debug("Cleanup complete: compacted {} total snapshots", totalRemoved);
		}
	}

	/**
	 * Compact a list of snapshots using tiered retention:
	 * - age < 24h: keep all
	 * - 24h <= age < 7d: keep 1 per calendar hour (closest to top of hour)
	 * - 7d <= age < 180d: keep 1 per calendar day (closest to midnight)
	 * - age >= 180d: discard
	 *
	 * Input list may be in any order; output is sorted oldest-first.
	 */
	private List<PlayerStats> compactSnapshots(List<PlayerStats> snapshots)
	{
		long now = System.currentTimeMillis();
		long recentCutoff = now - FULL_RESOLUTION_MS;
		long hourlyCutoff = now - HOURLY_RESOLUTION_MS;
		long ancientCutoff = now - MAX_SNAPSHOT_AGE;

		List<PlayerStats> recent = new ArrayList<>();
		// key: truncated LocalDateTime (hour or day), value: best snapshot for that bucket
		Map<LocalDateTime, PlayerStats> mediumBuckets = new LinkedHashMap<>();
		Map<LocalDateTime, PlayerStats> oldBuckets = new LinkedHashMap<>();

		ZoneId zone = ZoneId.systemDefault();

		for (PlayerStats snapshot : snapshots)
		{
			long ts = snapshot.getTimestamp();

			if (ts < ancientCutoff)
			{
				// Discard: older than 180 days
				continue;
			}
			else if (ts >= recentCutoff)
			{
				// age < 24h: keep all
				recent.add(snapshot);
			}
			else if (ts >= hourlyCutoff)
			{
				// 24h <= age < 7d: keep 1 per calendar hour (closest to top of hour)
				LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone);
				LocalDateTime bucket = ldt.truncatedTo(ChronoUnit.HOURS);
				long remainder = ts % TimeUnit.HOURS.toMillis(1);
				PlayerStats existing = mediumBuckets.get(bucket);
				if (existing == null)
				{
					mediumBuckets.put(bucket, snapshot);
				}
				else
				{
					long existingRemainder = existing.getTimestamp() % TimeUnit.HOURS.toMillis(1);
					if (remainder < existingRemainder)
					{
						mediumBuckets.put(bucket, snapshot);
					}
				}
			}
			else
			{
				// 7d <= age < 180d: keep 1 per calendar day (closest to midnight / start of day)
				LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone);
				LocalDateTime bucket = ldt.truncatedTo(ChronoUnit.DAYS);
				long remainder = ts % TimeUnit.DAYS.toMillis(1);
				PlayerStats existing = oldBuckets.get(bucket);
				if (existing == null)
				{
					oldBuckets.put(bucket, snapshot);
				}
				else
				{
					long existingRemainder = existing.getTimestamp() % TimeUnit.DAYS.toMillis(1);
					if (remainder < existingRemainder)
					{
						oldBuckets.put(bucket, snapshot);
					}
				}
			}
		}

		List<PlayerStats> result = new ArrayList<>(oldBuckets.values());
		result.addAll(mediumBuckets.values());
		result.addAll(recent);
		result.sort(Comparator.comparingLong(PlayerStats::getTimestamp));
		return result;
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
		int finalSnapshotCount;

		synchronized (dataLock)
		{
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

			// Compact snapshots using tiered retention policy
			List<PlayerStats> compacted = compactSnapshots(snapshots);
			snapshots.clear();
			snapshots.addAll(compacted);

			// Dual-gate safety net: hard cap at 1024 snapshots
			while (snapshots.size() > MAX_SNAPSHOTS_PER_PLAYER)
			{
				snapshots.remove(0);
			}

			finalSnapshotCount = snapshots.size();
		}

		// Mark for periodic flush (every 30 seconds)
		isDirty.set(true);
		log.debug("Saved snapshot for '{}' (source: {}, {} total snapshots)", stats.getUsername(), source, finalSnapshotCount);
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
		synchronized (dataLock)
		{
			List<PlayerStats> snapshots = allPlayerData.get(key);
			return snapshots != null ? new ArrayList<>(snapshots) : new ArrayList<>();
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
		log.debug("Removing player: '{}'", username);

		String key = username.toLowerCase();
		String typeKey = ACCOUNT_TYPE_KEY_PREFIX + username.toLowerCase();

		// Remove from data map under lock, then let periodic flush handle persistence
		synchronized (dataLock)
		{
			allPlayerData.remove(key);
		}
		isDirty.set(true);

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

		synchronized (dataLock)
		{
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
		}

		log.debug("Returning {} tracked players", players.size());
		return players;
	}

}
