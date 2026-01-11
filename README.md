# Hiscores Tracker

A comprehensive RuneLite plugin that tracks OSRS player stats and gains over time with a complete hiscores-style display.

## Features

### Core Functionality
- **Hiscores-Style Display** - Clean, familiar layout matching the official OSRS hiscores
- **Complete Coverage** - Track all 25 skills (including Sailing), 66+ bosses, clues, and activities
- **Multiple Accounts** - Track unlimited players simultaneously with easy account switching
- **Account Type Support** - Full support for Normal, Ironman, Hardcore Ironman, and Ultimate Ironman accounts

### Time-Based Tracking
- **Flexible Timeframes** - View gains for Today, Week, Month, or Year
- **Historical Snapshots** - Stores up to 180 days of daily snapshots
- **Daily Navigation** - Browse through past days to see historical gains
- **Auto-Refresh** - Automatic updates every 5 minutes (configurable)

### Display Features
- **Skill Icons** - All skills display with proper sprite icons including Sailing
- **Boss Icons** - All bosses including new Sailing-related bosses (Doom of Mokhaiotl, Shellbane Gryphon)
- **Color-Coded Gains** - Positive gains shown in green for easy visualization
- **Rank Tracking** - See your rank changes alongside XP/KC gains

## Installation

### Via RuneLite Plugin Hub (Coming Soon)
1. Open RuneLite
2. Click the Plugin Hub button
3. Search for "Hiscores Tracker"
4. Click Install

### Manual Installation (Current)
1. Download the latest `.jar` from the [Releases](https://github.com/RobertoChavez2433/hiscores-tracker/releases) page
2. Place it in `~/.runelite/sideloaded-plugins/` (create the folder if it doesn't exist)
3. Restart RuneLite
4. Enable the plugin in the Plugin Configuration panel

## Usage

1. **Add a Player**: Type a username in the search box and press Enter
2. **Select Timeframe**: Click Today/Week/Month/Year to change the view
3. **Navigate Days**: Use the date selector to view historical data
4. **Switch Accounts**: Use the dropdown to switch between tracked players
5. **Set Account Type**: Right-click an account in the dropdown to set its type (Normal/Ironman/etc.)

## Data Storage

- **Configuration**: Stored via RuneLite's ConfigManager
- **Historical Data**: Stored in `~/.runelite/hiscores-tracker-data.json`
- **Data Retention**: Automatically cleans snapshots older than 180 days
- **Automatic Cleanup**: Runs on plugin startup to maintain reasonable file size

## Technical Details

### Sprite IDs
The plugin uses verified sprite IDs for all skills and bosses:
- **Sailing Skill**: Sprite ID 228 (Anchor icon)
- **Doom of Mokhaiotl**: Sprite ID 6347 (Red demon mask)
- **Shellbane Gryphon**: Sprite ID 6349 (Orange/tan creature)

### API Compatibility
- Built against RuneLite API version 1.12.11+
- Compatible with OSRS updates through January 2026
- Supports all current hiscores endpoints (Normal, Ironman, Hardcore, Ultimate, Fresh Start Worlds)

## Development

### Building from Source
```bash
./gradlew build
```

The compiled JAR will be in `build/libs/advanced-xp-tracker-1.0-SNAPSHOT.jar`

### Testing
See `testing-tools/README.md` for development and testing utilities.

## Known Limitations

- Sailing skill and related bosses display correctly but rely on hardcoded sprite IDs until RuneLite API is updated
- Data is stored locally only (no cloud sync between devices)
- Requires periodic hiscores lookups (respects OSRS API rate limits)

## Contributing

Found a bug or have a feature request? Please open an issue on GitHub:
https://github.com/RobertoChavez2433/hiscores-tracker/issues

Pull requests are welcome! Please ensure your code:
- Follows the existing code style
- Includes appropriate comments
- Has been tested locally

## License

This plugin follows RuneLite's BSD 2-Clause License.

## Credits

- Created by RobertoChavez2433
- Built with the RuneLite Client API
- Sprite verification assisted by Claude Code
