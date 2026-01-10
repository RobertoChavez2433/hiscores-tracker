package com.advancedxptracker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
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

	private StatsDataManager dataManager;
	private HiscoresPanel panel;
	private NavigationButton navButton;
	private ScheduledExecutorService executor;
	private String loggedInUsername = null;

	@Override
	protected void startUp() throws Exception
	{
		log.info("==========================================");
		log.info("Hiscores Tracker STARTING UP!");
		log.info("==========================================");

		// Create executor for background tasks
		executor = Executors.newScheduledThreadPool(2);

		// Initialize data manager
		log.info("Initializing Stats Data Manager...");
		dataManager = new StatsDataManager(configManager);
		log.info("Data Manager initialized successfully");

		// Create UI panel
		log.info("Creating Hiscores Panel...");
		panel = new HiscoresPanel(httpClient, dataManager, executor, spriteManager);
		log.info("Hiscores Panel created successfully");

		// Create and add navigation button
		BufferedImage icon = null;
		try
		{
			log.info("Attempting to load icon from /icon.png...");
			icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
			if (icon != null)
			{
				log.info("✅ Icon loaded successfully! Size: " + icon.getWidth() + "x" + icon.getHeight());
			}
			else
			{
				log.warn("⚠️ Icon loaded but is null!");
			}
		}
		catch (Exception e)
		{
			log.error("❌ Could not load icon!", e);
		}

		log.info("Building navigation button...");
		navButton = NavigationButton.builder()
			.tooltip("Hiscores Tracker")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		log.info("Navigation button built successfully");

		log.info("Adding navigation button to toolbar...");
		clientToolbar.addNavigation(navButton);
		log.info("✅ Navigation button added to toolbar!");

		log.info("==========================================");
		log.info("Hiscores Tracker STARTUP COMPLETE!");
		log.info("==========================================");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Hiscores Tracker shutting down!");

		// Shutdown executor
		if (executor != null)
		{
			executor.shutdown();
		}

		// Remove UI
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		log.info("Hiscores Tracker stopped!");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Player just logged in
			String username = client.getLocalPlayer().getName();
			loggedInUsername = username;
			log.info("========================================");
			log.info("PLAYER LOGGED IN: '{}'", username);
			log.info("========================================");

			// Capture initial stats snapshot
			captureClientStats();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			if (loggedInUsername != null)
			{
				log.info("Player '{}' logged out", loggedInUsername);
				loggedInUsername = null;
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		// When player gains XP, capture a snapshot
		if (loggedInUsername != null && client.getGameState() == GameState.LOGGED_IN)
		{
			log.debug("Stat changed for '{}': {}", loggedInUsername, statChanged.getSkill());
			// Capture snapshot after XP gain (debounced to avoid spam)
			captureClientStats();
		}
	}

	private void captureClientStats()
	{
		if (loggedInUsername == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		log.info("Capturing client stats for logged-in player: '{}'", loggedInUsername);

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
			log.info("✅ Saved client snapshot for '{}'", loggedInUsername);

			// Notify panel to refresh if this player is selected
			if (panel != null)
			{
				panel.onClientStatsUpdated(loggedInUsername);
			}
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
