package com.advancedxptracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hiscores-style panel showing player stats with gains
 */
@Slf4j
public class HiscoresPanel extends PluginPanel
{
	// Sprite IDs from RuneLite API - VERIFIED by sprite export on 2026-01-11
	// These match the official RuneLite hiscores plugin sprite IDs
	private static final int SKILL_SAILING = 228;  // Anchor icon - VERIFIED
	private static final int HISCORE_DOOM_OF_MOKHAIOTL = 6347;  // Red demon mask - VERIFIED
	private static final int HISCORE_SHELLBANE_GRYPHON = 6349;  // Orange/tan creature - VERIFIED

	private static final String CONFIG_GROUP = "advancedxptracker";
	private static final String CONFIG_KEY_LAST_PLAYER = "lastSelectedPlayer";

	private final HiscoresClient hiscoresClient;
	private final StatsDataManager dataManager;
	private final ScheduledExecutorService executor;
	private final ScheduledExecutorService httpExecutor;
	private final SpriteManager spriteManager;
	private final ConfigManager configManager;

	// UI Components
	private JComboBox<String> playerSelector;
	private JComboBox<String> timeframeSelector;
	private final JPanel contentPanel;
	private JButton refreshButton;
	private JButton addPlayerButton;
	private JButton removePlayerButton;
	private JButton prevDayButton;
	private JButton nextDayButton;
	private JLabel dayLabel;

	// Current state
	private volatile PlayerStats currentStats;
	private PlayerGains currentGains;
	private int currentDayOffset = 0; // 0 = today, 1 = yesterday, etc.

	// Row maps (preserve insertion order for iteration)
	private final Map<String, StatRow> skillRows = new LinkedHashMap<>();
	private final Map<String, StatRow> clueRows = new LinkedHashMap<>();
	private final Map<String, StatRow> activityRows = new LinkedHashMap<>();
	private final Map<String, BossRow> bossRows = new LinkedHashMap<>();

	// Sprite cache
	private final Map<Integer, ImageIcon> spriteCache = new HashMap<>();

	// Status label
	private enum StatusType { LOADING, ERROR, HIDDEN }
	private final JLabel statusLabel = new JLabel();

	// Auto-refresh timer
	private ScheduledFuture<?> autoRefreshFuture;

	// Stale callback protection — incremented on each new request
	private final AtomicInteger requestGeneration = new AtomicInteger(0);

	// Suppresses ActionListener during combo box rebuilds (addItem fires listener on empty box)
	private boolean suppressPlayerSelection = false;

	// -------------------------------------------------------------------------
	// Inner classes
	// -------------------------------------------------------------------------

	private class StatRow
	{
		final JPanel container;
		final JLabel nameLabel;
		final JLabel iconLabel;
		final JLabel rankLabel;
		final JLabel valueLabel;
		final JLabel gainLabel;

		StatRow(String displayName, int spriteId)
		{
			container = new JPanel();
			container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
			container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			container.setBorder(new EmptyBorder(3, 5, 3, 5));
			container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

			// First row: name, icon, rank
			JPanel firstRow = new JPanel();
			firstRow.setLayout(new BoxLayout(firstRow, BoxLayout.X_AXIS));
			firstRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			firstRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

			nameLabel = new JLabel(displayName);
			nameLabel.setForeground(Color.WHITE);
			nameLabel.setFont(new Font("Serif", Font.PLAIN, 13));
			firstRow.add(nameLabel);
			firstRow.add(Box.createHorizontalGlue());

			iconLabel = new JLabel();
			iconLabel.setPreferredSize(new Dimension(20, 20));
			iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
			firstRow.add(iconLabel);

			loadSprite(spriteId, iconLabel);

			firstRow.add(Box.createHorizontalGlue());

			rankLabel = new JLabel("Rank: --");
			rankLabel.setForeground(Color.GRAY);
			rankLabel.setFont(new Font("Serif", Font.PLAIN, 12));
			firstRow.add(rankLabel);

			// Second row: value + gain
			JPanel secondRow = new JPanel(new BorderLayout());
			secondRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			secondRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

			valueLabel = new JLabel("--");
			valueLabel.setForeground(Color.GRAY);
			valueLabel.setFont(new Font("Serif", Font.PLAIN, 12));
			secondRow.add(valueLabel, BorderLayout.WEST);

			gainLabel = new JLabel("");
			gainLabel.setForeground(Color.GREEN);
			gainLabel.setFont(new Font("Serif", Font.PLAIN, 12));
			gainLabel.setVisible(false);
			secondRow.add(gainLabel, BorderLayout.EAST);

			container.add(firstRow);
			container.add(secondRow);
		}

		void update(String nameText, String valueText, String rankText, String gainText, String tooltip)
		{
			nameLabel.setText(nameText);
			valueLabel.setText(valueText);
			rankLabel.setText(rankText);

			if (gainText != null && !gainText.isEmpty())
			{
				gainLabel.setText(gainText);
				gainLabel.setVisible(true);
			}
			else
			{
				gainLabel.setVisible(false);
			}

			container.setToolTipText(tooltip);
			nameLabel.setToolTipText(tooltip);
			iconLabel.setToolTipText(tooltip);
			rankLabel.setToolTipText(tooltip);
			valueLabel.setToolTipText(tooltip);
			gainLabel.setToolTipText(tooltip);
		}

		void reset(String defaultName)
		{
			update(defaultName, "--", "Rank: --", null,
				"<html><b>" + defaultName + "</b><br>Rank: Unranked</html>");
		}
	}

	private class BossRow
	{
		final String displayName;
		final JPanel container;
		final JLabel iconLabel;
		final JLabel kcLabel;
		final JLabel gainLabel;

		BossRow(String displayName, int spriteId)
		{
			this.displayName = displayName;
			container = new JPanel();
			container.setLayout(new BoxLayout(container, BoxLayout.X_AXIS));
			container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			container.setBorder(new EmptyBorder(2, 3, 2, 3));

			iconLabel = new JLabel();
			iconLabel.setPreferredSize(new Dimension(20, 20));
			container.add(iconLabel);

			loadSprite(spriteId, iconLabel);

			container.add(Box.createHorizontalStrut(5));

			kcLabel = new JLabel("--");
			kcLabel.setForeground(Color.WHITE);
			kcLabel.setFont(new Font("Serif", Font.PLAIN, 12));
			container.add(kcLabel);

			gainLabel = new JLabel("");
			gainLabel.setForeground(Color.GREEN);
			gainLabel.setFont(new Font("Serif", Font.PLAIN, 11));
			gainLabel.setVisible(false);
			container.add(gainLabel);
		}

		void update(int score, int rank, int gain, String tooltip)
		{
			kcLabel.setText(score > 0 ? String.format("%,d", score) : "--");

			if (gain != 0 && score > 0)
			{
				gainLabel.setText(" (+" + String.format("%,d", gain) + ")");
				gainLabel.setVisible(true);
			}
			else
			{
				gainLabel.setVisible(false);
			}

			container.setToolTipText(tooltip);
			iconLabel.setToolTipText(tooltip);
			kcLabel.setToolTipText(tooltip);
			gainLabel.setToolTipText(tooltip);
		}

		void reset(String tooltip)
		{
			update(0, -1, 0, tooltip);
		}
	}

	// -------------------------------------------------------------------------
	// Sprite cache
	// -------------------------------------------------------------------------

	private void loadSprite(int spriteId, JLabel target)
	{
		if (spriteId == -1)
		{
			return;
		}

		ImageIcon cached = spriteCache.get(spriteId);
		if (cached != null)
		{
			target.setIcon(cached);
			return;
		}

		spriteManager.getSpriteAsync(spriteId, 0, (sprite) ->
			SwingUtilities.invokeLater(() -> {
				BufferedImage scaled = ImageUtil.resizeImage(
					ImageUtil.resizeCanvas(sprite, 25, 25), 20, 20);
				ImageIcon icon = new ImageIcon(scaled);
				spriteCache.put(spriteId, icon);
				target.setIcon(icon);
			}));
	}

	// -------------------------------------------------------------------------
	// Constructor
	// -------------------------------------------------------------------------

	public HiscoresPanel(HiscoresClient hiscoresClient, StatsDataManager dataManager, ScheduledExecutorService executor, ScheduledExecutorService httpExecutor, SpriteManager spriteManager, ConfigManager configManager)
	{
		super(false);
		this.hiscoresClient = hiscoresClient;
		this.dataManager = dataManager;
		this.executor = executor;
		this.httpExecutor = httpExecutor;
		this.spriteManager = spriteManager;
		this.configManager = configManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Initialize UI components first
		playerSelector = new JComboBox<>();
		playerSelector.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				label.setHorizontalAlignment(SwingConstants.CENTER);
				return label;
			}
		});

		timeframeSelector = new JComboBox<>(new String[]{"Today", "Week", "Month", "Year"});
		timeframeSelector.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				label.setHorizontalAlignment(SwingConstants.CENTER);
				return label;
			}
		});

		refreshButton = new JButton("↻");
		addPlayerButton = new JButton("+");
		removePlayerButton = new JButton("-");
		prevDayButton = new JButton("◀");
		nextDayButton = new JButton("▶");
		dayLabel = new JLabel("Today", SwingConstants.CENTER);

		// Top control panel
		JPanel topPanel = createTopPanel();
		add(topPanel, BorderLayout.NORTH);

		// Main content area
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(contentPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scrollPane, BorderLayout.CENTER);

		// Build all row components once
		buildAllRows();

		// Load tracked players
		refreshPlayerList();

		// Add action listener AFTER initial setup to avoid triggering during initialization
		playerSelector.addActionListener(e -> {
			if (suppressPlayerSelection)
			{
				return;
			}
			log.debug("Player selector changed to: {}", playerSelector.getSelectedItem());
			loadPlayerData();
		});

		setupListeners();
	}

	/**
	 * Build all row components once and add them to contentPanel.
	 * Rows are built with default "--" text and updated in-place thereafter.
	 */
	private void buildAllRows()
	{
		// ---- Skills ----
		String[] skillNames = {
			"Overall", "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic",
			"Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting",
			"Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming",
			"Runecraft", "Hunter", "Construction", "Sailing"
		};

		JPanel skillsSection = new JPanel();
		skillsSection.setLayout(new BoxLayout(skillsSection, BoxLayout.Y_AXIS));
		skillsSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (String name : skillNames)
		{
			String key = name.toLowerCase();
			StatRow row = new StatRow(name, getSkillSpriteId(name));
			row.reset(name + " lvl 1");
			skillRows.put(key, row);
			skillsSection.add(row.container);
		}

		addSectionHeader("Skills");
		contentPanel.add(skillsSection);
		contentPanel.add(Box.createVerticalStrut(20));

		// ---- Clues ----
		String[][] clueTypes = {
			{"All Clues", "clue_all"},
			{"Beginner Clues", "clue_beginner"},
			{"Easy Clues", "clue_easy"},
			{"Medium Clues", "clue_medium"},
			{"Hard Clues", "clue_hard"},
			{"Elite Clues", "clue_elite"},
			{"Master Clues", "clue_master"}
		};

		JPanel cluesSection = new JPanel();
		cluesSection.setLayout(new BoxLayout(cluesSection, BoxLayout.Y_AXIS));
		cluesSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (String[] clueType : clueTypes)
		{
			StatRow row = new StatRow(clueType[0], getActivitySpriteId(clueType[1]));
			row.update(clueType[0], "Completed: --", "Rank: --", null,
				"<html><b>" + clueType[0] + "</b><br>Rank: Unranked</html>");
			clueRows.put(clueType[1], row);
			cluesSection.add(row.container);
		}

		addSectionHeader("Clue Scrolls Completed");
		contentPanel.add(cluesSection);
		contentPanel.add(Box.createVerticalStrut(20));

		// ---- Activities ----
		String[][] activityTypes = {
			{"League Points", "league_points"},
			{"Bounty Hunter - Hunter", "bounty_hunter_hunter"},
			{"Bounty Hunter - Rogue", "bounty_hunter_rogue"},
			{"LMS", "lms"},
			{"PvP Arena", "pvp_arena"},
			{"Soul Wars", "soul_wars"},
			{"Rifts Closed", "rifts_closed"},
			{"Colosseum Glory", "colosseum_glory"},
			{"Collections Logged", "collections_logged"}
		};

		JPanel activitiesSection = new JPanel();
		activitiesSection.setLayout(new BoxLayout(activitiesSection, BoxLayout.Y_AXIS));
		activitiesSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (String[] activityType : activityTypes)
		{
			StatRow row = new StatRow(activityType[0], getActivitySpriteId(activityType[1]));
			row.update(activityType[0], "Score: --", "Rank: --", null,
				"<html><b>" + activityType[0] + "</b><br>Rank: Unranked</html>");
			activityRows.put(activityType[1], row);
			activitiesSection.add(row.container);
		}

		addSectionHeader("Activities");
		contentPanel.add(activitiesSection);
		contentPanel.add(Box.createVerticalStrut(20));

		// ---- Bosses ----
		String[] bossNames = {
			"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio", "Barrows",
			"Bryophyta", "Callisto", "Calvarion", "Cerberus", "Chambers of Xeric", "Chambers of Xeric CM",
			"Chaos Elemental", "Chaos Fanatic", "Commander Zilyana", "Corporeal Beast",
			"Crazy Archaeologist", "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme",
			"Deranged Archaeologist", "Doom of Mokhaiotl", "Duke Sucellus", "General Graardor", "Giant Mole",
			"Grotesque Guardians", "Hespori", "Kalphite Queen", "King Black Dragon",
			"Kraken", "Kree'arra", "K'ril Tsutsaroth", "Lunar Chests", "Mimic", "Nex", "Nightmare",
			"Phosani's Nightmare", "Obor", "Phantom Muspah", "Sarachnis", "Scorpia", "Scurrius",
			"Shellbane Gryphon", "Skotizo", "Sol Heredit", "Spindel", "Tempoross",
			"The Gauntlet", "The Corrupted Gauntlet", "The Hueycoatl", "The Leviathan",
			"The Royal Titans", "The Whisperer", "Theatre of Blood", "Theatre of Blood HM",
			"Thermonuclear Smoke Devil", "Tombs of Amascut", "Tombs of Amascut Expert",
			"TzKal-Zuk", "TzTok-Jad", "Vardorvis", "Venenatis", "Vet'ion", "Vorkath",
			"Wintertodt", "Yama", "Zalcano", "Zulrah"
		};

		JPanel bossesSection = new JPanel(new GridBagLayout());
		bossesSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;
		c.insets = new Insets(2, 2, 2, 2);

		int column = 0;
		for (String bossName : bossNames)
		{
			String key = bossName.toLowerCase().replace(" ", "_").replace("'", "").replace("-", "_");
			BossRow row = new BossRow(bossName, getActivitySpriteId(key));
			String tooltip = "<html><b>" + bossName + "</b><br>Rank: Unranked<br>KC: 0</html>";
			row.reset(tooltip);
			bossRows.put(key, row);
			c.gridx = column;
			bossesSection.add(row.container, c);
			column++;
			if (column >= 3)
			{
				column = 0;
				c.gridy++;
			}
		}

		addSectionHeader("Boss Kill Count");
		contentPanel.add(bossesSection);
		contentPanel.add(Box.createVerticalStrut(20));
	}

	private void addSectionHeader(String title)
	{
		JLabel headerLabel = new JLabel(title, SwingConstants.CENTER);
		headerLabel.setForeground(Color.YELLOW);
		headerLabel.setFont(new Font("Serif", Font.BOLD, 15));
		headerLabel.setBorder(new EmptyBorder(5, 5, 3, 5));
		headerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		contentPanel.add(headerLabel);
	}

	// -------------------------------------------------------------------------
	// Top panel
	// -------------------------------------------------------------------------

	private JPanel createTopPanel()
	{
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Player selection row
		JPanel playerRow = new JPanel(new BorderLayout(5, 0));
		playerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel playerLabel = new JLabel("Player:");
		playerLabel.setForeground(Color.WHITE);
		playerRow.add(playerLabel, BorderLayout.WEST);

		// Note: Action listener added after initial setup to avoid triggering during initialization
		playerRow.add(playerSelector, BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 2, 0));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		addPlayerButton.setToolTipText("Add player");
		addPlayerButton.addActionListener(e -> addPlayer());
		buttonPanel.add(addPlayerButton);

		removePlayerButton.setToolTipText("Remove player");
		removePlayerButton.addActionListener(e -> removePlayer());
		buttonPanel.add(removePlayerButton);

		playerRow.add(buttonPanel, BorderLayout.EAST);

		topPanel.add(playerRow);
		topPanel.add(Box.createVerticalStrut(5));

		// Timeframe and refresh row
		JPanel timeframeRow = new JPanel(new BorderLayout(5, 0));
		timeframeRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel timeframeLabel = new JLabel("Gains:");
		timeframeLabel.setForeground(Color.WHITE);
		timeframeRow.add(timeframeLabel, BorderLayout.WEST);

		timeframeRow.add(timeframeSelector, BorderLayout.CENTER);

		refreshButton.setToolTipText("Refresh data");
		refreshButton.addActionListener(e -> refreshPlayerData());
		timeframeRow.add(refreshButton, BorderLayout.EAST);

		topPanel.add(timeframeRow);
		topPanel.add(Box.createVerticalStrut(5));

		// Daily navigation row (only shown when "Today" is selected)
		JPanel dailyNavRow = new JPanel(new BorderLayout(5, 0));
		dailyNavRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dailyNavRow.setName("dailyNavRow");

		prevDayButton.setToolTipText("Previous day");
		prevDayButton.addActionListener(e -> {
			currentDayOffset++;
			refreshDailyView();
		});
		dailyNavRow.add(prevDayButton, BorderLayout.WEST);

		dayLabel.setForeground(Color.WHITE);
		dailyNavRow.add(dayLabel, BorderLayout.CENTER);

		nextDayButton.setToolTipText("Next day");
		nextDayButton.addActionListener(e -> {
			if (currentDayOffset > 0)
			{
				currentDayOffset--;
				refreshDailyView();
			}
		});
		dailyNavRow.add(nextDayButton, BorderLayout.EAST);

		topPanel.add(dailyNavRow);

		// Hide daily navigation by default
		dailyNavRow.setVisible(false);

		// Status label (inline error/loading messages — hidden by default)
		statusLabel.setVisible(false);
		statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
		statusLabel.setBorder(new EmptyBorder(4, 5, 4, 5));
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
		topPanel.add(Box.createVerticalStrut(3));
		topPanel.add(statusLabel);

		timeframeSelector.addActionListener(e -> {
			String selected = (String) timeframeSelector.getSelectedItem();
			log.debug("Timeframe changed to: {}", selected);
			dailyNavRow.setVisible("Today".equals(selected));
			if ("Today".equals(selected))
			{
				currentDayOffset = 0;
				refreshDailyView();
			}
			else
			{
				refreshGains();
			}
		});

		// Sync initial visibility with default timeframe
		dailyNavRow.setVisible("Today".equals(timeframeSelector.getSelectedItem()));

		return topPanel;
	}

	// -------------------------------------------------------------------------
	// Lifecycle / listeners
	// -------------------------------------------------------------------------

	private void setupListeners()
	{
		// Auto-refresh every 5 minutes — bounce to EDT so loadPlayerData reads Swing state safely
		autoRefreshFuture = executor.scheduleAtFixedRate(
			() -> SwingUtilities.invokeLater(this::refreshPlayerData),
			5, 5, TimeUnit.MINUTES);
	}

	public void shutDown()
	{
		// Cancel auto-refresh timer
		if (autoRefreshFuture != null)
		{
			autoRefreshFuture.cancel(false);
			autoRefreshFuture = null;
		}

		// Clear sprite cache
		spriteCache.clear();

		// Clear row references
		skillRows.clear();
		clueRows.clear();
		activityRows.clear();
		bossRows.clear();

		// Clear state
		currentStats = null;
		currentGains = null;
	}

	// -------------------------------------------------------------------------
	// Player management
	// -------------------------------------------------------------------------

	public void refreshPlayerList()
	{
		if (!dataManager.isInitialized())
		{
			playerSelector.removeAllItems();
			return;
		}
		log.debug("Refreshing player list");

		// Suppress listener during combo box rebuild (addItem on empty box auto-selects and fires)
		suppressPlayerSelection = true;
		try
		{
			playerSelector.removeAllItems();
			List<String> players = dataManager.getTrackedPlayers();
			log.debug("Found {} tracked players", players.size());
			for (String player : players)
			{
				log.debug("Adding player to selector: {}", player);
				playerSelector.addItem(player);
			}

			// ConfigManager is thread-safe; EDT access is fine (RuneLite convention)
			String lastPlayer = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_LAST_PLAYER);
			if (lastPlayer != null && !lastPlayer.isEmpty())
			{
				boolean found = false;
				for (int i = 0; i < playerSelector.getItemCount(); i++)
				{
					if (lastPlayer.equals(playerSelector.getItemAt(i)))
					{
						playerSelector.setSelectedIndex(i);
						found = true;
						break;
					}
				}
				if (!found)
				{
					log.debug("Last selected player '{}' no longer tracked, clearing config", lastPlayer);
					configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_LAST_PLAYER);
					playerSelector.setSelectedIndex(-1);
				}
			}
			else
			{
				playerSelector.setSelectedIndex(-1);
			}
		}
		finally
		{
			suppressPlayerSelection = false;
		}

		// Fire listener manually if a player was restored, so loadPlayerData() runs
		if (playerSelector.getSelectedIndex() != -1)
		{
			loadPlayerData();
		}

		log.debug("Player list refresh complete, selector has {} items", playerSelector.getItemCount());
	}

	private void addPlayer()
	{
		String username = JOptionPane.showInputDialog(this, "Enter player name:", "Add Player", JOptionPane.PLAIN_MESSAGE);
		if (username == null || username.trim().isEmpty())
		{
			return;
		}
		final String finalUsername = username.trim();

		AccountType[] accountTypes = AccountType.values();
		AccountType selectedType = (AccountType) JOptionPane.showInputDialog(
			this,
			"Select account type for " + finalUsername + ":",
			"Account Type",
			JOptionPane.QUESTION_MESSAGE,
			null,
			accountTypes,
			AccountType.NORMAL
		);

		if (selectedType == null)
		{
			return;
		}

		final AccountType finalAccountType = selectedType;
		log.debug("Adding player '{}' with account type '{}'", finalUsername, finalAccountType.getDisplayName());

		final int gen = requestGeneration.incrementAndGet();

		// Fetch initial data in background — rows stay visible showing old/blank data until update arrives
		httpExecutor.submit(() -> {
			try
			{
				log.debug("Fetching hiscores for new player: {} ({})", finalUsername, finalAccountType.getDisplayName());
				PlayerStats stats = hiscoresClient.fetchPlayerStats(finalUsername, finalAccountType);
				dataManager.saveSnapshot(stats, "hiscores_api");
				dataManager.saveAccountType(finalUsername, finalAccountType);
				log.debug("Successfully saved snapshot for: {}", finalUsername);

				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get())
					{
						log.debug("Discarding stale response (gen {} vs current {})", gen, requestGeneration.get());
						return;
					}
					log.debug("UI callback executing for player: {}", finalUsername);
					refreshPlayerList();
					playerSelector.setSelectedItem(finalUsername);
					log.debug("Selected item is now: {}", playerSelector.getSelectedItem());
					// loadPlayerData() is triggered by playerSelector's action listener
				});
			}
			catch (PlayerNotFoundException e)
			{
				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get()) return;
					setStatus(e.getMessage(), StatusType.ERROR);
					displayBlankStats();
				});
			}
			catch (Exception e)
			{
				log.error("Failed to fetch player: {}", finalUsername, e);
				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get()) return;
					setStatus("Failed to add player: " + e.getMessage(), StatusType.ERROR);
					displayBlankStats();
				});
			}
		});
	}

	private void removePlayer()
	{
		String selectedPlayer = (String) playerSelector.getSelectedItem();
		if (selectedPlayer == null)
		{
			JOptionPane.showMessageDialog(this,
				"No player selected to remove.",
				"No Selection",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		int confirm = JOptionPane.showConfirmDialog(this,
			"Remove player '" + selectedPlayer + "' and all their data?",
			"Confirm Removal",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (confirm == JOptionPane.YES_OPTION)
		{
			log.debug("Removing player: {}", selectedPlayer);
			dataManager.removePlayer(selectedPlayer);
			executor.submit(() -> dataManager.flush());
			refreshPlayerList();

			currentStats = null;
			currentGains = null;
			displayBlankStats();
		}
	}

	// -------------------------------------------------------------------------
	// Data loading
	// -------------------------------------------------------------------------

	private void loadPlayerData()
	{
		if (!dataManager.isInitialized())
		{
			return;
		}
		String selectedPlayer = (String) playerSelector.getSelectedItem();
		if (selectedPlayer == null || selectedPlayer.trim().isEmpty())
		{
			currentStats = null;
			currentGains = null;
			displayBlankStats();
			return;
		}

		// Persist last selected player for session restore
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_LAST_PLAYER, selectedPlayer);

		final int gen = requestGeneration.incrementAndGet();
		setStatus("Fetching data...", StatusType.LOADING);

		// Capture Swing state on EDT before submitting to background thread
		final String timeframe = (String) timeframeSelector.getSelectedItem();
		final int days = getTimeframeDays(timeframe);

		// HTTP fetch on httpExecutor (isolated from file I/O flush timer on executor).
		// Data operations (saveSnapshot, getSnapshotFromDaysAgo) are fast and lock-protected
		// via dataLock — acceptable to run here rather than bouncing to executor.
		httpExecutor.submit(() -> {
			try
			{
				log.debug("Loading player data for: {}", selectedPlayer);

				AccountType accountType = dataManager.loadAccountType(selectedPlayer);
				log.debug("Using account type '{}' for player '{}'", accountType.getDisplayName(), selectedPlayer);

				PlayerStats stats = hiscoresClient.fetchPlayerStats(selectedPlayer, accountType);
				dataManager.saveSnapshot(stats, "hiscores_api");
				log.debug("Successfully fetched and saved data for: {}", selectedPlayer);

				PlayerStats olderStats = dataManager.getSnapshotFromDaysAgo(stats.getUsername(), days);
				final PlayerStats finalStats = stats;
				final PlayerGains finalGains = stats.calculateGains(olderStats);
				log.debug("Calculated gains for timeframe: {}", timeframe);

				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get())
					{
						log.debug("Discarding stale response (gen {} vs current {})", gen, requestGeneration.get());
						return;
					}
					setStatus(null, StatusType.HIDDEN);
					currentStats = finalStats;
					currentGains = finalGains;
					displayStats();
				});
			}
			catch (PlayerNotFoundException e)
			{
				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get()) return;
					setStatus(e.getMessage(), StatusType.ERROR);
				});
			}
			catch (Exception e)
			{
				log.error("Failed to load player data: {}", selectedPlayer, e);
				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get()) return;
					setStatus("Failed to fetch data: " + e.getMessage(), StatusType.ERROR);
				});
			}
		});
	}

	private void refreshPlayerData()
	{
		if (currentStats != null)
		{
			loadPlayerData();
		}
	}

	private void refreshGains()
	{
		if (currentStats == null)
		{
			return;
		}

		final String username = currentStats.getUsername();
		// Capture Swing state on EDT before submitting to background thread
		final String timeframe = (String) timeframeSelector.getSelectedItem();
		final int days = getTimeframeDays(timeframe);
		final PlayerStats snap = currentStats;
		final int gen = requestGeneration.incrementAndGet();
		executor.submit(() -> {
			PlayerStats older = dataManager.getSnapshotFromDaysAgo(username, days);
			PlayerGains gains = snap.calculateGains(older);
			SwingUtilities.invokeLater(() -> {
				if (gen != requestGeneration.get()) return;
				currentGains = gains;
				displayStats();
			});
		});
	}

	private void refreshDailyView()
	{
		if (currentStats == null)
		{
			return;
		}

		if (currentDayOffset == 0)
		{
			dayLabel.setText("Today");
		}
		else if (currentDayOffset == 1)
		{
			dayLabel.setText("Yesterday");
		}
		else
		{
			dayLabel.setText(currentDayOffset + " days ago");
		}

		final String username = currentStats.getUsername();
		final int dayOffset = currentDayOffset;
		final PlayerStats snap = currentStats;
		final int gen = requestGeneration.incrementAndGet();
		executor.submit(() -> {
			// dayOffset=0 targets today's midnight; dayOffset+1 targets yesterday's midnight
			PlayerStats dayStats = dataManager.getSnapshotFromDaysAgo(username, dayOffset);
			PlayerStats nextDayStats = dataManager.getSnapshotFromDaysAgo(username, dayOffset + 1);

			final PlayerGains gains;
			final boolean noBaseline;
			if (dayOffset == 0 && dayStats != null)
			{
				// Today: gains = current stats vs start-of-today baseline
				gains = snap.calculateGains(dayStats);
				noBaseline = false;
			}
			else if (dayStats != null && nextDayStats != null)
			{
				// Historical days: gains between day start and previous day start
				gains = dayStats.calculateGains(nextDayStats);
				noBaseline = false;
			}
			else if (dayStats != null)
			{
				// No prior-day baseline — show zeros with explanation
				gains = dayStats.calculateGains(dayStats);
				noBaseline = true;
			}
			else
			{
				// No snapshot data at all for this day
				PlayerStats emptyStats = new PlayerStats(snap.getUsername(), System.currentTimeMillis());
				gains = emptyStats.calculateGains(emptyStats);
				noBaseline = true;
			}

			SwingUtilities.invokeLater(() -> {
				if (gen != requestGeneration.get()) return;
				nextDayButton.setEnabled(dayOffset > 0);
				if (noBaseline)
				{
					setStatus("No baseline data for this day", StatusType.LOADING);
				}
				else
				{
					setStatus(null, StatusType.HIDDEN);
				}
				currentGains = gains;
				displayStats();
			});
		});
	}

	private int getTimeframeDays(String timeframe)
	{
		switch (timeframe)
		{
			case "Today": return 0;
			case "Week": return 7;
			case "Month": return 30;
			case "Year": return 365;
			default: return 7;
		}
	}

	// -------------------------------------------------------------------------
	// In-place display updates
	// -------------------------------------------------------------------------

	private void displayStats()
	{
		if (currentStats == null)
		{
			displayBlankStats();
			return;
		}

		// Update skill rows
		for (Map.Entry<String, StatRow> entry : skillRows.entrySet())
		{
			String key = entry.getKey();
			StatRow row = entry.getValue();
			PlayerStats.SkillData skill = currentStats.getSkills().get(key);

			if (skill != null)
			{
				long gain = currentGains != null ? currentGains.getSkillGain(key).getXpGain() : 0;
				String nameText = capitalize(key) + " lvl " + skill.getLevel();
				String valueText = String.format("%,d XP", skill.getXp());
				String rankText = skill.getRank() > 0
					? "Rank " + String.format("%,d", skill.getRank())
					: "Unranked";
				String gainText = gain != 0 ? "+" + String.format("%,d", gain) + " XP" : null;
				String tooltip = buildSkillTooltip(capitalize(key), skill, gain);
				row.update(nameText, valueText, rankText, gainText, tooltip);
			}
			else
			{
				row.reset(capitalize(key) + " lvl 1");
			}
		}

		// Update clue rows
		updateActivityStatRows(clueRows, "Completed: ", "Clues Completed");

		// Update activity rows
		updateActivityStatRows(activityRows, "Score: ", "Score");

		// Update boss rows
		for (Map.Entry<String, BossRow> entry : bossRows.entrySet())
		{
			String key = entry.getKey();
			BossRow row = entry.getValue();
			PlayerStats.ActivityData activity = currentStats.getActivities().get(key);

			int score = (activity != null) ? activity.getScore() : 0;
			int rank = (activity != null) ? activity.getRank() : -1;
			int gain = (currentGains != null) ? currentGains.getActivityGain(key) : 0;

			String tooltip = buildBossTooltip(row.displayName, score, rank, gain);
			row.update(score, rank, gain, tooltip);
		}

		contentPanel.revalidate();
		contentPanel.repaint();
	}

	private void updateActivityStatRows(Map<String, StatRow> rows, String valuePrefix, String tooltipLabel)
	{
		for (Map.Entry<String, StatRow> entry : rows.entrySet())
		{
			String key = entry.getKey();
			StatRow row = entry.getValue();
			PlayerStats.ActivityData activity = currentStats.getActivities().get(key);
			int score = (activity != null) ? activity.getScore() : 0;
			int rank = (activity != null) ? activity.getRank() : -1;
			int gain = (currentGains != null) ? currentGains.getActivityGain(key) : 0;
			String displayName = row.nameLabel.getText();
			String rankText = "Rank " + (rank > 0 ? String.format("%,d", rank) : "--");
			String valueText = valuePrefix + (score > 0 ? String.format("%,d", score) : "--");
			String gainText = (gain != 0 && score > 0) ? "+" + String.format("%,d", gain) : null;
			String tooltip = buildActivityTooltip(displayName, tooltipLabel, score, rank, gain);
			row.update(displayName, valueText, rankText, gainText, tooltip);
		}
	}

	private void displayBlankStats()
	{
		log.debug("displayBlankStats()");

		for (Map.Entry<String, StatRow> entry : skillRows.entrySet())
		{
			entry.getValue().reset(capitalize(entry.getKey()) + " lvl 1");
		}
		for (Map.Entry<String, StatRow> entry : clueRows.entrySet())
		{
			String displayName = entry.getValue().nameLabel.getText();
			entry.getValue().update(displayName, "Completed: --", "Rank: --", null,
				"<html><b>" + displayName + "</b><br>Rank: Unranked</html>");
		}
		for (Map.Entry<String, StatRow> entry : activityRows.entrySet())
		{
			String displayName = entry.getValue().nameLabel.getText();
			entry.getValue().update(displayName, "Score: --", "Rank: --", null,
				"<html><b>" + displayName + "</b><br>Rank: Unranked</html>");
		}
		for (Map.Entry<String, BossRow> entry : bossRows.entrySet())
		{
			BossRow row = entry.getValue();
			String tooltip = "<html><b>" + row.displayName + "</b><br>Rank: Unranked<br>KC: 0</html>";
			row.reset(tooltip);
		}

		contentPanel.revalidate();
		contentPanel.repaint();
	}

	// -------------------------------------------------------------------------
	// Tooltip builders
	// -------------------------------------------------------------------------

	private String buildSkillTooltip(String skillName, PlayerStats.SkillData skill, long gain)
	{
		StringBuilder tooltip = new StringBuilder("<html>");
		tooltip.append("<b>").append(skillName).append("</b><br>");
		tooltip.append("Rank: ").append(skill.getRank() > 0 ? String.format("%,d", skill.getRank()) : "Unranked").append("<br>");
		tooltip.append("Level: ").append(skill.getLevel()).append("<br>");
		tooltip.append("Experience: ").append(String.format("%,d", skill.getXp())).append("<br>");
		if (skill.getLevel() < 99)
		{
			long xpToNext = getXpForLevel(skill.getLevel() + 1) - skill.getXp();
			tooltip.append("XP to level ").append(skill.getLevel() + 1).append(": ")
				.append(String.format("%,d", xpToNext)).append("<br>");
		}
		if (gain != 0)
		{
			String timeframe = (String) timeframeSelector.getSelectedItem();
			String gainLabel = timeframe.equals("Today") ? "Today's gain:" : timeframe + "'s gain:";
			tooltip.append("<br><b style='color:green'>").append(gainLabel).append("</b> +")
				.append(String.format("%,d", gain));
		}
		tooltip.append("</html>");
		return tooltip.toString();
	}

	private String buildActivityTooltip(String displayName, String valueLabel, int score, int rank, int gain)
	{
		StringBuilder tooltip = new StringBuilder("<html>");
		tooltip.append("<b>").append(displayName).append("</b><br>");
		tooltip.append("Rank: ").append(rank > 0 ? String.format("%,d", rank) : "Unranked").append("<br>");
		tooltip.append(valueLabel).append(": ").append(score > 0 ? String.format("%,d", score) : "Unranked").append("<br>");
		if (gain != 0 && score > 0)
		{
			String timeframe = (String) timeframeSelector.getSelectedItem();
			String gainLabelText = timeframe.equals("Today") ? "Today's gain:" : timeframe + "'s gain:";
			tooltip.append("<br><b style='color:green'>").append(gainLabelText).append("</b> +")
				.append(String.format("%,d", gain));
		}
		tooltip.append("</html>");
		return tooltip.toString();
	}

	private String buildBossTooltip(String displayName, int score, int rank, int gain)
	{
		StringBuilder tooltip = new StringBuilder("<html>");
		tooltip.append("<b>").append(displayName).append("</b><br>");
		tooltip.append("Rank: ").append(rank > 0 ? String.format("%,d", rank) : "Unranked").append("<br>");
		tooltip.append("KC: ").append(score > 0 ? String.format("%,d", score) : "Unranked").append("<br>");
		if (gain != 0 && score > 0)
		{
			String timeframe = (String) timeframeSelector.getSelectedItem();
			String gainLabelText = timeframe.equals("Today") ? "Today's gain:" : timeframe + "'s gain:";
			tooltip.append("<br><b style='color:green'>").append(gainLabelText).append("</b> +")
				.append(String.format("%,d", gain));
		}
		tooltip.append("</html>");
		return tooltip.toString();
	}

	private void setStatus(String message, StatusType type)
	{
		switch (type)
		{
			case LOADING:
				statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				statusLabel.setText(message);
				statusLabel.setVisible(true);
				break;
			case ERROR:
				statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
				statusLabel.setText(message);
				statusLabel.setVisible(true);
				break;
			case HIDDEN:
				statusLabel.setVisible(false);
				break;
		}
		statusLabel.revalidate();
	}


	// -------------------------------------------------------------------------
	// Sprite ID lookups
	// -------------------------------------------------------------------------

	private static final Map<String, Integer> SKILL_SPRITE_IDS = createSkillSpriteIdMap();

	private static Map<String, Integer> createSkillSpriteIdMap()
	{
		Map<String, Integer> map = new HashMap<>();
		map.put("overall", SpriteID.SKILL_TOTAL);
		map.put("attack", SpriteID.SKILL_ATTACK);
		map.put("defence", SpriteID.SKILL_DEFENCE);
		map.put("strength", SpriteID.SKILL_STRENGTH);
		map.put("hitpoints", SpriteID.SKILL_HITPOINTS);
		map.put("ranged", SpriteID.SKILL_RANGED);
		map.put("prayer", SpriteID.SKILL_PRAYER);
		map.put("magic", SpriteID.SKILL_MAGIC);
		map.put("cooking", SpriteID.SKILL_COOKING);
		map.put("woodcutting", SpriteID.SKILL_WOODCUTTING);
		map.put("fletching", SpriteID.SKILL_FLETCHING);
		map.put("fishing", SpriteID.SKILL_FISHING);
		map.put("firemaking", SpriteID.SKILL_FIREMAKING);
		map.put("crafting", SpriteID.SKILL_CRAFTING);
		map.put("smithing", SpriteID.SKILL_SMITHING);
		map.put("mining", SpriteID.SKILL_MINING);
		map.put("herblore", SpriteID.SKILL_HERBLORE);
		map.put("agility", SpriteID.SKILL_AGILITY);
		map.put("thieving", SpriteID.SKILL_THIEVING);
		map.put("slayer", SpriteID.SKILL_SLAYER);
		map.put("farming", SpriteID.SKILL_FARMING);
		map.put("runecraft", SpriteID.SKILL_RUNECRAFT);
		map.put("hunter", SpriteID.SKILL_HUNTER);
		map.put("construction", SpriteID.SKILL_CONSTRUCTION);
		map.put("sailing", SKILL_SAILING);
		return Collections.unmodifiableMap(map);
	}

	/**
	 * Get the sprite ID for a skill icon
	 */
	private static int getSkillSpriteId(String skillName)
	{
		return SKILL_SPRITE_IDS.getOrDefault(skillName.toLowerCase(), -1);
	}

	/**
	 * Get the sprite ID for an activity/boss/clue icon
	 */
	private int getActivitySpriteId(String key)
	{
		switch (key)
		{
			// Clue Scrolls - all use the same icon
			case "clue_all":
			case "clue_beginner":
			case "clue_easy":
			case "clue_medium":
			case "clue_hard":
			case "clue_elite":
			case "clue_master":
				return SpriteID.HISCORE_CLUE_SCROLL_ALL;

			// Activities
			case "league_points": return SpriteID.HISCORE_LEAGUE_POINTS;
			case "bounty_hunter_hunter": return SpriteID.HISCORE_BOUNTY_HUNTER_HUNTER;
			case "bounty_hunter_rogue": return SpriteID.HISCORE_BOUNTY_HUNTER_ROGUE;
			case "lms": return SpriteID.HISCORE_LAST_MAN_STANDING;
			case "pvp_arena": return SpriteID.HISCORE_PVP_ARENA_RANK;
			case "soul_wars": return SpriteID.HISCORE_SOUL_WARS_ZEAL;
			case "rifts_closed": return SpriteID.HISCORE_RIFTS_CLOSED;
			case "colosseum_glory": return SpriteID.HISCORE_COLOSSEUM_GLORY;
			case "collections_logged": return SpriteID.HISCORE_COLLECTIONS_LOGGED;

			// Bosses
			case "abyssal_sire": return SpriteID.HISCORE_ABYSSAL_SIRE;
			case "alchemical_hydra": return SpriteID.HISCORE_ALCHEMICAL_HYDRA;
			case "amoxliatl": return SpriteID.HISCORE_AMOXLIATL;
			case "araxxor": return SpriteID.HISCORE_ARAXXOR;
			case "artio": return SpriteID.HISCORE_ARTIO_CALLISTO;
			case "barrows": return SpriteID.HISCORE_BARROWS_CHESTS;
			case "bryophyta": return SpriteID.HISCORE_BRYOPHYTA;
			case "callisto": return SpriteID.HISCORE_ARTIO_CALLISTO;
			case "calvarion": return SpriteID.HISCORE_CALVARION_VETION;
			case "cerberus": return SpriteID.HISCORE_CERBERUS;
			case "chambers_of_xeric": return SpriteID.HISCORE_CHAMBERS_OF_XERIC;
			case "chambers_of_xeric_cm": return SpriteID.HISCORE_CHAMBERS_OF_XERIC_CHALLENGE_MODE;
			case "chaos_elemental": return SpriteID.HISCORE_CHAOS_ELEMENTAL;
			case "chaos_fanatic": return SpriteID.HISCORE_CHAOS_FANATIC;
			case "commander_zilyana": return SpriteID.HISCORE_COMMANDER_ZILYANA;
			case "corporeal_beast": return SpriteID.HISCORE_CORPOREAL_BEAST;
			case "crazy_archaeologist": return SpriteID.HISCORE_CRAZY_ARCHAEOLOGIST;
			case "dagannoth_prime": return SpriteID.HISCORE_DAGANNOTH_PRIME;
			case "dagannoth_rex": return SpriteID.HISCORE_DAGANNOTH_REX;
			case "dagannoth_supreme": return SpriteID.HISCORE_DAGANNOTH_SUPREME;
			case "deranged_archaeologist": return SpriteID.HISCORE_DERANGED_ARCHAEOLOGIST;
			case "doom_of_mokhaiotl":
				// Doom of Mokhaiotl (Sailing boss, Nov 2025)
				return HISCORE_DOOM_OF_MOKHAIOTL;
			case "duke_sucellus": return SpriteID.HISCORE_DUKE_SUCELLUS;
			case "general_graardor": return SpriteID.HISCORE_GENERAL_GRAARDOR;
			case "giant_mole": return SpriteID.HISCORE_GIANT_MOLE;
			case "grotesque_guardians": return SpriteID.HISCORE_GROTESQUE_GUARDIANS;
			case "hespori": return SpriteID.HISCORE_HESPORI;
			case "kalphite_queen": return SpriteID.HISCORE_KALPHITE_QUEEN;
			case "king_black_dragon": return SpriteID.HISCORE_KING_BLACK_DRAGON;
			case "kraken": return SpriteID.HISCORE_KRAKEN;
			case "kreearra": return SpriteID.HISCORE_KREEARRA;
			case "kril_tsutsaroth": return SpriteID.HISCORE_KRIL_TSUTSAROTH;
			case "lunar_chests": return SpriteID.HISCORE_LUNAR_CHESTS;
			case "mimic": return SpriteID.HISCORE_MIMIC;
			case "nex": return SpriteID.HISCORE_NEX;
			case "nightmare": return SpriteID.HISCORE_NIGHTMARE;
			case "phosanis_nightmare": return SpriteID.HISCORE_NIGHTMARE;
			case "obor": return SpriteID.HISCORE_OBOR;
			case "phantom_muspah": return SpriteID.HISCORE_PHANTOM_MUSPAH;
			case "sarachnis": return SpriteID.HISCORE_SARACHNIS;
			case "scorpia": return SpriteID.HISCORE_SCORPIA;
			case "scurrius": return SpriteID.HISCORE_SCURRIUS;
			case "shellbane_gryphon":
				// Shellbane Gryphon (Sailing boss, Nov 2025)
				return HISCORE_SHELLBANE_GRYPHON;
			case "skotizo": return SpriteID.HISCORE_SKOTIZO;
			case "sol_heredit": return SpriteID.HISCORE_SOL_HEREDIT;
			case "spindel": return SpriteID.HISCORE_SPINDEL_VENENATIS;
			case "tempoross": return SpriteID.HISCORE_TEMPOROSS;
			case "the_gauntlet": return SpriteID.HISCORE_THE_GAUNTLET;
			case "the_corrupted_gauntlet": return SpriteID.HISCORE_THE_CORRUPTED_GAUNTLET;
			case "the_hueycoatl": return SpriteID.HISCORE_THE_HUEYCOATL;
			case "the_leviathan": return SpriteID.HISCORE_THE_LEVIATHAN;
			case "the_royal_titans": return SpriteID.HISCORE_ROYAL_TITANS;
			case "the_whisperer": return SpriteID.HISCORE_THE_WHISPERER;
			case "theatre_of_blood": return SpriteID.HISCORE_THEATRE_OF_BLOOD;
			case "theatre_of_blood_hm": return SpriteID.HISCORE_THEATRE_OF_BLOOD;
			case "thermonuclear_smoke_devil": return SpriteID.HISCORE_THERMONUCLEAR_SMOKE_DEVIL;
			case "tombs_of_amascut": return SpriteID.HISCORE_TOMBS_OF_AMASCUT;
			case "tombs_of_amascut_expert": return SpriteID.HISCORE_TOMBS_OF_AMASCUT_EXPERT;
			case "tzkal_zuk": return SpriteID.HISCORE_TZKAL_ZUK;
			case "tztok_jad": return SpriteID.HISCORE_TZTOK_JAD;
			case "vardorvis": return SpriteID.HISCORE_VARDORVIS;
			case "venenatis": return SpriteID.HISCORE_SPINDEL_VENENATIS;
			case "vetion": return SpriteID.HISCORE_CALVARION_VETION;
			case "vorkath": return SpriteID.HISCORE_VORKATH;
			case "wintertodt": return SpriteID.HISCORE_WINTERTODT;
			case "yama": return SpriteID.HISCORE_YAMA;
			case "zalcano": return SpriteID.HISCORE_ZALCANO;
			case "zulrah": return SpriteID.HISCORE_ZULRAH;
			default: return -1;
		}
	}

	// -------------------------------------------------------------------------
	// Utilities
	// -------------------------------------------------------------------------

	private String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return s;
		}
		return s.substring(0, 1).toUpperCase() + s.substring(1);
	}

	private static final long[] XP_TABLE = computeXpTable();

	private static long[] computeXpTable()
	{
		long[] table = new long[100]; // index 0 unused, 1-99
		table[1] = 0;
		for (int level = 2; level <= 99; level++)
		{
			double points = 0;
			for (int i = 1; i < level; i++)
			{
				points += Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
			}
			table[level] = (long) Math.floor(points / 4.0);
		}
		return table;
	}

	private static long getXpForLevel(int level)
	{
		if (level < 1 || level > 99) return 0;
		return XP_TABLE[level];
	}

	/**
	 * Called when auto-daily fetch completes for a player.
	 * Already on EDT (caller wraps in SwingUtilities.invokeLater).
	 */
	public void onAutoFetchCompleted(String username, PlayerStats stats)
	{
		String selectedPlayer = (String) playerSelector.getSelectedItem();
		if (username.equalsIgnoreCase(selectedPlayer))
		{
			final int gen = requestGeneration.incrementAndGet();
			final String timeframe = (String) timeframeSelector.getSelectedItem();
			final int days = getTimeframeDays(timeframe);
			executor.submit(() -> {
				PlayerStats older = dataManager.getSnapshotFromDaysAgo(username, days);
				PlayerGains gains = stats.calculateGains(older);
				SwingUtilities.invokeLater(() -> {
					if (gen != requestGeneration.get()) return;
					currentStats = stats;
					currentGains = gains;
					displayStats();
				});
			});
		}
	}

	/**
	 * Called when the logged-in player's stats are updated from the game client
	 */
	public void onClientStatsUpdated(String username)
	{
		log.debug("Client stats updated for: '{}'", username);

		String selectedPlayer = (String) playerSelector.getSelectedItem();
		if (selectedPlayer != null && selectedPlayer.equalsIgnoreCase(username))
		{
			log.debug("Refreshing display for currently selected player '{}'", username);
			// Capture Swing state on EDT before submitting to executor
			final String timeframe = (String) timeframeSelector.getSelectedItem();
			final int days = getTimeframeDays(timeframe);
			final int gen = requestGeneration.incrementAndGet();
			executor.submit(() -> {
				List<PlayerStats> snapshots = dataManager.loadSnapshots(username);
				if (!snapshots.isEmpty())
				{
					PlayerStats latest = snapshots.get(snapshots.size() - 1);
					PlayerStats olderStats = dataManager.getSnapshotFromDaysAgo(username, days);
					PlayerGains gains = latest.calculateGains(olderStats);
					SwingUtilities.invokeLater(() -> {
						if (gen != requestGeneration.get()) return;
						currentStats = latest;
						currentGains = gains;
						displayStats();
					});
				}
			});
		}
	}
}
