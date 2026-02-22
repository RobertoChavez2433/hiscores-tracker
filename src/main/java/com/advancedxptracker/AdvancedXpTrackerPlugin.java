package com.advancedxptracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hiscores Tracker Plugin
 * Tracks player stats from OSRS Hiscores with historical gains tracking
 */
@Slf4j
@PluginDescriptor(
	name = "Hiscores Tracker",
	description = "Track player stats and gains with complete hiscores display. Multiple accounts, historical data, and daily progress tracking.",
	tags = {"hiscores", "stats", "tracking", "gains", "xp", "experience", "skills", "bosses", "clues"}
)
public class AdvancedXpTrackerPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private AdvancedXpTrackerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Client client;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private Gson gson;

	private StatsDataManager dataManager;
	private HiscoresClient hiscoresClient;
	private HiscoresPanel panel;
	private NavigationButton navButton;
	private ScheduledExecutorService executor;
	private ScheduledExecutorService autoFetchExecutor;
	private AutoDailyFetchManager autoFetchManager;
	private volatile String loggedInUsername = null;
	private volatile int initializeTracker = 0;
	private long lastAccountHash = -1L;
	private volatile long lastStatChangeTime = 0;
	private volatile boolean statsDirty = false;

	private void verboseDebug(String format, Object... args)
	{
		if (config.verboseDebugLogging())
		{
			log.debug(format, args);
		}
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Hiscores Tracker starting up");

		// Create executor for background tasks (file I/O, gains calculations)
		executor = Executors.newScheduledThreadPool(1);

		// Create dedicated executor for auto-fetch HTTP work (isolated from file I/O executor)
		autoFetchExecutor = Executors.newSingleThreadScheduledExecutor();

		// Initialize data manager
		log.debug("Initializing Stats Data Manager");
		dataManager = new StatsDataManager(configManager, gson);

		// Create hiscores client
		hiscoresClient = new HiscoresClient(httpClient, gson);

		// Create UI panel
		log.debug("Creating Hiscores Panel");
		panel = new HiscoresPanel(hiscoresClient, dataManager, executor, spriteManager);
		log.debug("Hiscores Panel created");

		autoFetchManager = new AutoDailyFetchManager(
			hiscoresClient, dataManager, autoFetchExecutor,
			(username, stats) -> SwingUtilities.invokeLater(() -> panel.onAutoFetchCompleted(username, stats))
		);

		// Start async load; refresh player list on EDT once data is ready
		dataManager.initialize(executor, () -> panel.refreshPlayerList());
		log.debug("Data Manager initialization scheduled");

		// Create and add navigation button
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

		log.debug("Building navigation button");
		navButton = NavigationButton.builder()
			.tooltip("Hiscores Tracker")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.debug("Navigation button added to toolbar");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Hiscores Tracker shutting down");

		// Shutdown panel (cancel timers, clear caches)
		if (panel != null)
		{
			panel.shutDown();
		}

		if (autoFetchManager != null)
		{
			autoFetchManager.shutdown();
		}

		if (autoFetchExecutor != null)
		{
			autoFetchExecutor.shutdown();
			try
			{
				if (!autoFetchExecutor.awaitTermination(2, TimeUnit.SECONDS))
				{
					autoFetchExecutor.shutdownNow();
				}
			}
			catch (InterruptedException e)
			{
				autoFetchExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

		if (executor != null)
		{
			// Submit flush to executor queue — serializes after any in-flight snapshot tasks
			if (dataManager != null)
			{
				executor.submit(dataManager::flush);
			}
			executor.shutdown();
			try
			{
				if (!executor.awaitTermination(2, TimeUnit.SECONDS))
				{
					executor.shutdownNow();
				}
			}
			catch (InterruptedException e)
			{
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		// Reset state
		loggedInUsername = null;
		lastAccountHash = -1L;
		initializeTracker = 0;
		statsDirty = false;
		lastStatChangeTime = 0;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		verboseDebug("[GameState] {} (RuneLite/game lifecycle)", state.name());

		if (state == GameState.LOGGED_IN)
		{
			// LOGGED_IN fires on region changes too, not just actual login.
			// Only capture stats if the account actually changed.
			long currentAccountHash = client.getAccountHash();
			if (currentAccountHash != lastAccountHash)
			{
				lastAccountHash = currentAccountHash;
				if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
				{
					return;
				}
				String username = client.getLocalPlayer().getName();
				loggedInUsername = username;
				log.debug("Player logged in: '{}'", username);
				verboseDebug("[GameState] LOGGED_IN → capturing client stats (account changed)");
				captureClientStats();
				autoFetchManager.onPlayerLoggedIn(username);
			}
		}
		else if (state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			// Set initialization guard -- skip StatChanged events for 2 game ticks
			// to avoid processing the login sync burst
			initializeTracker = 2;
			statsDirty = false;
			verboseDebug("[GameState] {} → set init guard (2 ticks, skip StatChanged)", state.name());
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			if (loggedInUsername != null)
			{
				log.debug("Player '{}' logged out", loggedInUsername);
				verboseDebug("[GameState] LOGIN_SCREEN → cleared logged-in user");
				autoFetchManager.onPlayerLoggedOut();
				loggedInUsername = null;
				lastAccountHash = -1L;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (initializeTracker > 0)
		{
			int before = initializeTracker;
			initializeTracker--;
			if (before > 0)
			{
				verboseDebug("[InitGuard] ticks left={} (StatChanged will be {} accepted)", initializeTracker, initializeTracker == 0 ? "now" : "ignored until 0");
			}
			return;
		}

		// Debounce: capture stats after 5 seconds of no XP changes
		if (statsDirty && System.currentTimeMillis() - lastStatChangeTime >= 5000)
		{
			statsDirty = false;
			captureClientStats();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		if (initializeTracker > 0)
		{
			verboseDebug("[StatChanged] skipped (login sync guard, ticks left={}), skill={}", initializeTracker, statChanged.getSkill());
			return;
		}

		if (loggedInUsername != null && client.getGameState() == GameState.LOGGED_IN)
		{
			log.debug("Stat changed for '{}': {}", loggedInUsername, statChanged.getSkill());
			lastStatChangeTime = System.currentTimeMillis();
			statsDirty = true;
		}
	}

	private void captureClientStats()
	{
		if (loggedInUsername == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// Phase 1: Read client API (MUST be on game thread — fast, ~50 memory reads)
		final String username = loggedInUsername;
		final long timestamp = System.currentTimeMillis();
		final int[] levels = new int[Skill.values().length];
		final long[] xps = new long[Skill.values().length];

		int totalLevel = 0;
		long totalXp = 0;
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				int idx = skill.ordinal();
				levels[idx] = client.getRealSkillLevel(skill);
				xps[idx] = client.getSkillExperience(skill);
				totalLevel += levels[idx];
				totalXp += xps[idx];
			}
		}
		final int finalTotalLevel = totalLevel;
		final long finalTotalXp = totalXp;

		log.debug("Captured client stats for '{}', dispatching to executor", username);

		// Phase 2: Build snapshot + persist + notify (on background executor)
		executor.submit(() -> {
			try
			{
				PlayerStats stats = new PlayerStats(username, timestamp, "client");
				stats.setSkill("overall", -1, finalTotalLevel, finalTotalXp);

				for (Skill skill : Skill.values())
				{
					if (skill != Skill.OVERALL)
					{
						String skillName = skill.getName().toLowerCase();
						stats.setSkill(skillName, -1, levels[skill.ordinal()], xps[skill.ordinal()]);
					}
				}

				dataManager.saveSnapshot(stats, "client");
				log.debug("Saved client snapshot for '{}'", username);

				// Notify panel on EDT
				SwingUtilities.invokeLater(() -> panel.onClientStatsUpdated(username));
			}
			catch (Exception e)
			{
				log.error("Failed to process client stats for '{}'", username, e);
			}
		});
	}

	@Provides
	AdvancedXpTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdvancedXpTrackerConfig.class);
	}
}
