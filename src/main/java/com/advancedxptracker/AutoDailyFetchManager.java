package com.advancedxptracker;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ensures every tracked player has at least one Hiscores API snapshot per calendar day.
 * Runs fetch cycles on login and hourly, using the shared executor for all work.
 */
@Slf4j
public class AutoDailyFetchManager
{
	@FunctionalInterface
	public interface AutoFetchListener
	{
		void onPlayerFetched(String username, PlayerStats stats);
	}

	private static final long INTER_PLAYER_DELAY_SECONDS = 10;
	private static final long HOURLY_PERIOD_HOURS = 1;

	private final HiscoresClient hiscoresClient;
	private final StatsDataManager dataManager;
	private final ScheduledExecutorService executor;
	private final AutoFetchListener listener;

	/** Guards hourly timer; only one should run at a time. */
	private ScheduledFuture<?> hourlyTimer;

	/** The currently-scheduled next step of an in-flight fetch cycle. */
	private ScheduledFuture<?> pendingFetchStep;

	/** Set to true when a fetch cycle should be aborted (logout or shutdown). */
	private volatile boolean stopped = false;

	public AutoDailyFetchManager(
		HiscoresClient hiscoresClient,
		StatsDataManager dataManager,
		ScheduledExecutorService executor,
		AutoFetchListener listener)
	{
		this.hiscoresClient = hiscoresClient;
		this.dataManager = dataManager;
		this.executor = executor;
		this.listener = listener;
	}

	/**
	 * Called when a player logs in. Cancels any running fetch cycle, schedules an
	 * immediate one with the logged-in player prioritised, and starts the hourly
	 * timer if it is not already running.
	 */
	public synchronized void onPlayerLoggedIn(String username)
	{
		log.debug("Auto-daily fetch: player logged in ({})", username);

		stopped = false;
		cancelPendingFetchStep();
		scheduleImmediateFetchCycle(username);

		if (hourlyTimer == null || hourlyTimer.isCancelled() || hourlyTimer.isDone())
		{
			try
			{
				hourlyTimer = executor.scheduleAtFixedRate(
					() -> triggerFetchCycle(null),
					HOURLY_PERIOD_HOURS,
					HOURLY_PERIOD_HOURS,
					TimeUnit.HOURS);
				log.debug("Auto-daily fetch: hourly timer started");
			}
			catch (java.util.concurrent.RejectedExecutionException e)
			{
				log.debug("Auto-daily fetch: executor shut down, cannot start hourly timer");
			}
		}
	}

	/**
	 * Called when the player reaches the login screen. Cancels the hourly timer
	 * and any in-flight fetch step.
	 */
	public synchronized void onPlayerLoggedOut()
	{
		log.debug("Auto-daily fetch: player logged out");
		stopped = true;
		cancelHourlyTimer();
		cancelPendingFetchStep();
	}

	/**
	 * Cancels all timers immediately. Called from Plugin.shutDown().
	 */
	public synchronized void shutdown()
	{
		log.debug("Auto-daily fetch: shutting down");
		stopped = true;
		cancelHourlyTimer();
		cancelPendingFetchStep();
	}

	// -----------------------------------------------------------------
	// Internal helpers
	// -----------------------------------------------------------------

	private void scheduleImmediateFetchCycle(String priorityUsername)
	{
		try
		{
			pendingFetchStep = executor.schedule(
				() -> triggerFetchCycle(priorityUsername),
				0,
				TimeUnit.SECONDS);
		}
		catch (java.util.concurrent.RejectedExecutionException e)
		{
			log.debug("Auto-daily fetch: executor shut down, cannot schedule immediate fetch cycle");
		}
	}

	private void cancelHourlyTimer()
	{
		if (hourlyTimer != null)
		{
			hourlyTimer.cancel(false);
			hourlyTimer = null;
			log.debug("Auto-daily fetch: hourly timer cancelled");
		}
	}

	private void cancelPendingFetchStep()
	{
		if (pendingFetchStep != null)
		{
			pendingFetchStep.cancel(false);
			pendingFetchStep = null;
			log.debug("Auto-daily fetch: pending fetch step cancelled");
		}
	}

	// -----------------------------------------------------------------
	// Fetch cycle
	// -----------------------------------------------------------------

	/**
	 * Build the list of stale players and begin fetching them sequentially.
	 *
	 * @param priorityUsername player to place first in the queue, or null for alphabetical order only
	 */
	void triggerFetchCycle(String priorityUsername)
	{
		if (!dataManager.isInitialized())
		{
			log.debug("Auto-daily fetch: data manager not yet initialized, skipping cycle");
			return;
		}

		List<String> tracked = dataManager.getTrackedPlayers();
		if (tracked.isEmpty())
		{
			log.debug("Auto-daily fetch: no tracked players, skipping cycle");
			return;
		}

		LocalDate today = LocalDate.now();
		List<String> stale = new ArrayList<>();

		for (String player : tracked)
		{
			if (isStale(player, today))
			{
				stale.add(player);
			}
		}

		if (stale.isEmpty())
		{
			log.debug("Auto-daily fetch: all {} tracked players already have a snapshot for today", tracked.size());
			return;
		}

		// Sort: priority player first, then alphabetical
		final String priority = priorityUsername != null ? priorityUsername.toLowerCase() : null;
		stale.sort(Comparator.<String, Integer>comparing(
				p -> priority != null && p.equalsIgnoreCase(priority) ? 0 : 1)
			.thenComparing(String.CASE_INSENSITIVE_ORDER));

		log.debug("Auto-daily fetch: {} of {} tracked players are stale, starting fetch cycle",
			stale.size(), tracked.size());

		fetchNext(stale, 0, stale.size());
	}

	/**
	 * Returns true when the player has no Hiscores API snapshot dated today.
	 */
	private boolean isStale(String username, LocalDate today)
	{
		PlayerStats latest = dataManager.getLatestHiscoresSnapshot(username);
		if (latest == null)
		{
			return true;
		}
		LocalDate snapshotDate = Instant.ofEpochMilli(latest.getTimestamp())
			.atZone(ZoneId.systemDefault())
			.toLocalDate();
		return !snapshotDate.equals(today);
	}

	/**
	 * Fetch the player at {@code index} in {@code queue}, then schedule the next
	 * fetch after a 10-second delay (or log the summary when the queue is exhausted).
	 */
	private void fetchNext(List<String> queue, int index, int totalStale)
	{
		if (index >= queue.size())
		{
			log.info("Auto-fetch complete: updated {} of {} tracked players",
				totalStale, dataManager.getTrackedPlayers().size());
			return;
		}

		String username = queue.get(index);
		log.debug("Auto-daily fetch: fetching '{}' ({}/{})", username, index + 1, queue.size());

		try
		{
			AccountType accountType = dataManager.loadAccountType(username);
			PlayerStats stats = hiscoresClient.fetchPlayerStats(username, accountType);
			dataManager.saveSnapshot(stats, "hiscores_api");
			listener.onPlayerFetched(username, stats);
			log.debug("Auto-daily fetch: successfully fetched '{}'", username);
		}
		catch (PlayerNotFoundException e)
		{
			log.warn("Auto-daily fetch: player '{}' not found (404), skipping", username);
		}
		catch (IOException e)
		{
			log.warn("Auto-daily fetch: failed to fetch '{}': {}", username, e.getMessage());
		}

		// Schedule next fetch step — the guard at the top of fetchNext handles
		// the completion summary when index + 1 reaches queue.size().
		long delay = (index + 1 < queue.size()) ? INTER_PLAYER_DELAY_SECONDS : 0;
		synchronized (this)
		{
			if (stopped) return;
			try
			{
				pendingFetchStep = executor.schedule(
					() -> fetchNext(queue, index + 1, totalStale),
					delay,
					TimeUnit.SECONDS);
			}
			catch (java.util.concurrent.RejectedExecutionException e)
			{
				log.debug("Auto-daily fetch: executor shut down, cannot schedule next fetch step");
			}
		}
	}
}
