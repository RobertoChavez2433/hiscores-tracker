# Hiscores Tracker

A RuneLite plugin that tracks OSRS player stats and gains over time with a complete hiscores-style display.

## Features

- **Hiscores-Style Display** - Clean, familiar layout with skill icons and organized sections
- **Multiple Accounts** - Track unlimited players simultaneously
- **Complete Coverage** - All 24 skills, 66 bosses, clues, and activities
- **Flexible Timeframes** - View gains for Today, Week, Month, or Year
- **Historical Tracking** - Stores up to 1 year of daily snapshots
- **Auto-Refresh** - Automatic updates every 5 minutes
- **Daily Navigation** - Browse through past days to see historical gains
- **Account Type Support** - Track Normal, Ironman, Hardcore Ironman, Ultimate Ironman, Group Ironman accounts

## Quick Start

1. Install RuneLite from https://runelite.net
2. Open RuneLite and log in
3. Click the wrench icon (Configuration)
4. Search for "Hiscores Tracker" in the Plugin Hub
5. Click "Install"
6. Look for the plugin icon in the right sidebar
7. Click **+** to add a player
8. Select account type and enter player name
9. View stats and gains!

## Configuration

Go to **RuneLite Settings > Hiscores Tracker**:

### Refresh Settings
- **Enable Auto-Refresh** - Automatically update data (default: ON)
- **Refresh Interval** - How often to refresh in minutes (default: 5)
- **Data Retention** - Days of history to keep (default: 365)

### Display Settings
- **Default Timeframe** - Starting timeframe (default: "Today")
- **Show Ranks** - Display hiscores rank (default: ON)
- **Compact Mode** - More compact layout (default: OFF)

## Development

### Project Structure
```
src/main/java/com/advancedxptracker/
├── AdvancedXpTrackerPlugin.java   # Main plugin class
├── AdvancedXpTrackerConfig.java   # Configuration interface
├── HiscoresPanel.java             # Main UI panel
├── HiscoresClient.java            # API client for fetching data
├── PlayerStats.java               # Data model for player snapshots
├── PlayerGains.java               # Calculates gains between snapshots
└── StatsDataManager.java          # Manages historical data storage
```

### Key Components

**HiscoresClient** - Fetches player data from:
```
https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player={name}
```

**StatsDataManager** - Stores snapshots in RuneLite's ConfigManager as JSON

**HiscoresPanel** - UI with:
- Player selector dropdown
- Timeframe selector (Today/Week/Month/Year)
- Refresh button
- Search field
- Scrollable stats display

## Troubleshooting

### Plugin Not Showing in Plugin Hub
- Make sure you have the latest RuneLite version
- Restart RuneLite
- Check Plugin Hub tab in the configuration panel

### Stuck on "Loading..."
1. **Restart RuneLite completely**
2. Verify player name is spelled correctly
3. Ensure player has hiscores data (not a brand new account)
4. Check internet connection
5. Wait a moment - initial fetch can take a few seconds

### "Failed to fetch player data"
- Verify username spelling (check official hiscores)
- Player must have logged in recently to have hiscores data
- May be rate limited - wait 30 seconds and try again
- Check if OSRS hiscores website is accessible

## Development

### Building from Source
```bash
# Clone the repository
git clone https://github.com/RobertoChavez2433/hiscores-tracker.git
cd hiscores-tracker

# Build the plugin
./gradlew clean build

# JAR will be in: build/libs/
```

### Installing Custom Build
1. Build the plugin
2. Copy JAR from `build/libs/` to `%USERPROFILE%\.runelite\sideloaded-plugins\`
3. Launch RuneLite with `--developer-mode`

## Technical Details

- **Language**: Java 11
- **Build Tool**: Gradle 8.5
- **Dependencies**: RuneLite Client API, OkHttp, GSON, Lombok
- **Threading**: Background ExecutorService for API calls (prevents UI blocking)
- **Storage**: RuneLite ConfigManager (JSON serialization)
- **UI**: Swing components with RuneLite's ColorScheme

## Contributing

Found a bug or have a feature request? Please open an issue on GitHub:
https://github.com/RobertoChavez2433/hiscores-tracker/issues

## License

This plugin follows RuneLite's license and guidelines.

## Display Layout

### Skills Section
- Two-row layout per skill
- First row: Skill name + level, centered icon, rank
- Second row: Total XP, XP gains (green)
- Hover tooltip shows: Rank, Level, XP, XP to next level, and timeframe gains

### Clue Scrolls Completed
- Two-row layout per clue type
- First row: Clue type name, centered icon, rank
- Second row: Completed count, gain (green)
- Shows All, Beginner, Easy, Medium, Hard, Elite, and Master clues

### Activities
- Two-row layout per activity
- First row: Activity name, centered icon, rank
- Second row: Score, gain (green)
- Includes League Points, LMS, PvP Arena, Soul Wars, Rifts Closed, Colosseum Glory, etc.

### Boss Kill Count
- Compact 3-column grid layout to save space
- Shows boss icon + KC number + gain in parentheses
- Hover tooltip shows: Boss name, Rank, KC, and gains
- All 66 bosses included

## License

This plugin follows RuneLite's BSD 2-Clause License.

## Author

Created by RobertoChavez2433
