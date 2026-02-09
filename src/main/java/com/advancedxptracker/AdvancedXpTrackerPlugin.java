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

import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
	private HiscoresPanel panel;
	private NavigationButton navButton;
	private ScheduledExecutorService executor;
	private String loggedInUsername = null;
	private int initializeTracker = 0;
	private long lastAccountHash = -1L;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Hiscores Tracker starting up");

		// Create executor for background tasks
		executor = Executors.newScheduledThreadPool(2);

		// Initialize data manager
		log.debug("Initializing Stats Data Manager");
		dataManager = new StatsDataManager(configManager, gson);
		log.debug("Data Manager initialized");

		// Create UI panel
		log.debug("Creating Hiscores Panel");
		panel = new HiscoresPanel(httpClient, dataManager, executor, spriteManager, gson);
		log.debug("Hiscores Panel created");

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

		executor.shutdown();
		clientToolbar.removeNavigation(navButton);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			// LOGGED_IN fires on region changes too, not just actual login.
			// Only capture stats if the account actually changed.
			long currentAccountHash = client.getAccountHash();
			if (currentAccountHash != lastAccountHash)
			{
				lastAccountHash = currentAccountHash;
				String username = client.getLocalPlayer().getName();
				loggedInUsername = username;
				log.debug("Player logged in: '{}'", username);
				captureClientStats();
			}
		}
		else if (state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			// Set initialization guard -- skip StatChanged events for 2 game ticks
			// to avoid processing the login sync burst
			initializeTracker = 2;
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			if (loggedInUsername != null)
			{
				log.debug("Player '{}' logged out", loggedInUsername);
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
			initializeTracker--;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		if (initializeTracker > 0)
		{
			// Skip stat changes during login synchronization
			return;
		}

		if (loggedInUsername != null && client.getGameState() == GameState.LOGGED_IN)
		{
			log.debug("Stat changed for '{}': {}", loggedInUsername, statChanged.getSkill());
			captureClientStats();
		}
	}

	private void captureClientStats()
	{
		if (loggedInUsername == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		log.debug("Capturing client stats for logged-in player: '{}'", loggedInUsername);

		try
		{
			PlayerStats stats = new PlayerStats(loggedInUsername, System.currentTimeMillis());

			// Capture all skill XP from the client
			for (Skill skill : Skill.values())
			{
				if (skill == Skill.OVERALL)
				{
					// Calculate total level
					int totalLevel = 0;
					long totalXp = 0;
					for (Skill s : Skill.values())
					{
						if (s != Skill.OVERALL)
						{
							totalLevel += client.getRealSkillLevel(s);
							totalXp += client.getSkillExperience(s);
						}
					}
					stats.setSkill("overall", -1, totalLevel, totalXp);
				}
				else
				{
					String skillName = skill.getName().toLowerCase();
					int level = client.getRealSkillLevel(skill);
					long xp = client.getSkillExperience(skill);
					stats.setSkill(skillName, -1, level, xp); // rank unknown from client
					log.debug("  {}: level={}, xp={}", skillName, level, xp);
				}
			}

			// Save the snapshot
			dataManager.saveSnapshot(stats);
			log.debug("Saved client snapshot for '{}'", loggedInUsername);

			// Notify panel to refresh if this player is selected
			panel.onClientStatsUpdated(loggedInUsername);
		}
		catch (Exception e)
		{
			log.error("Failed to capture client stats for '{}'", loggedInUsername, e);
		}
	}

	@Provides
	AdvancedXpTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdvancedXpTrackerConfig.class);
	}
}
