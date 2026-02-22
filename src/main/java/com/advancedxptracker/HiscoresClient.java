package com.advancedxptracker;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for fetching player data from OSRS Hiscores API
 */
@Slf4j
public class HiscoresClient
{
	private static final Map<String, String> NAME_TO_KEY_MAP = createNameToKeyMapping();

	private static final int MAX_RETRIES = 3;
	private static final long INITIAL_BACKOFF_MS = 2000;
	private static final long CACHE_TTL_MS = 60_000;

	private final OkHttpClient httpClient;
	private final Gson gson;

	private final Map<String, CachedResult> responseCache = new ConcurrentHashMap<>();

	private static class CachedResult
	{
		final PlayerStats stats;
		final long timestamp;

		CachedResult(PlayerStats stats)
		{
			this.stats = stats;
			this.timestamp = System.currentTimeMillis();
		}

		boolean isExpired()
		{
			return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
		}
	}

	public HiscoresClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Fetch hiscores data for a player
	 * @param username Player name
	 * @param accountType Account type (Normal, Ironman, etc.)
	 * @return PlayerStats object with all hiscores data
	 */
	public PlayerStats fetchPlayerStats(String username, AccountType accountType) throws IOException
	{
		log.debug("Fetching hiscores for '{}' ({})", username, accountType.getDisplayName());

		String cacheKey = username.toLowerCase() + ":" + accountType.name();
		CachedResult cached = responseCache.get(cacheKey);
		if (cached != null && !cached.isExpired())
		{
			log.debug("Returning cached hiscores for '{}'", username);
			return cached.stats;
		}

		String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
		String url = accountType.getApiUrl() + encodedUsername;
		log.debug("API URL: {}", url);

		Request request = new Request.Builder()
			.url(url)
			.build();

		int attempt = 0;
		while (true)
		{
			boolean shouldRetry = false;
			long backoff = 0;

			try (Response response = httpClient.newCall(request).execute())
			{
				if (response.code() == 429 && attempt < MAX_RETRIES)
				{
					backoff = INITIAL_BACKOFF_MS * (1L << attempt);
					log.debug("Rate limited, retrying in {}ms (attempt {})", backoff, attempt + 1);
					shouldRetry = true;
				}
				else if (!response.isSuccessful())
				{
					int code = response.code();
					if (code == 404)
					{
						throw new PlayerNotFoundException(username);
					}
					else if (code == 429)
					{
						throw new IOException("Hiscores API rate limit reached. Please wait a moment and try again.");
					}
					else
					{
						throw new IOException("Hiscores API error (HTTP " + code + "). The service may be temporarily unavailable.");
					}
				}
				else
				{
					ResponseBody body = response.body();
					if (body == null)
					{
						throw new IOException("Empty response from Hiscores API");
					}
					String jsonData = body.string();
					log.debug("API response received, body length: {} characters", jsonData.length());

					PlayerStats result = parseHiscoresJson(username, jsonData);
					responseCache.put(cacheKey, new CachedResult(result));
					log.debug("Finished fetching for '{}'", username);
					return result;
				}
			}
			// Response is now closed — safe to sleep
			if (shouldRetry)
			{
				try
				{
					Thread.sleep(backoff);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting to retry Hiscores API request", e);
				}
				attempt++;
			}
		}
	}

	/**
	 * Parse the hiscores JSON response into PlayerStats using name-based mapping
	 */
	private PlayerStats parseHiscoresJson(String username, String jsonData)
	{
		log.debug("Parsing hiscores JSON data for username: '{}'", username);

		HiscoresResponse response;
		try
		{
			response = gson.fromJson(jsonData, HiscoresResponse.class);
		}
		catch (Exception e)
		{
			log.error("Failed to parse JSON response", e);
			throw new RuntimeException("Failed to parse hiscores JSON", e);
		}

		if (response == null)
		{
			log.error("Parsed response is null");
			throw new RuntimeException("Hiscores response is null");
		}

		PlayerStats stats = new PlayerStats(username, System.currentTimeMillis(), "hiscores_api");

		// Parse skills from JSON
		if (response.skills != null)
		{
			log.debug("Parsing {} skills from JSON", response.skills.length);
			int skillsParsed = 0;
			for (HiscoresResponse.SkillEntry skill : response.skills)
			{
				String key = NAME_TO_KEY_MAP.get(skill.name);
				if (key != null)
				{
					stats.setSkill(key, skill.rank, skill.level, skill.xp);
					skillsParsed++;
					log.debug("  Skill '{}' → '{}': level={}, xp={}", skill.name, key, skill.level, skill.xp);
				}
				else
				{
					log.debug("Unknown skill in hiscore: {}", skill.name);
				}
			}
			log.debug("Parsed {} skills", skillsParsed);
		}

		// Parse activities and bosses from JSON using name-based mapping
		if (response.activities != null)
		{
			log.debug("Parsing {} activities/bosses from JSON", response.activities.length);
			int activitiesParsed = 0;
			int activitiesWithScores = 0;

			for (HiscoresResponse.ActivityEntry activity : response.activities)
			{
				String key = NAME_TO_KEY_MAP.get(activity.name);
				if (key != null)
				{
					stats.setActivity(key, activity.rank, (int) activity.score);
					activitiesParsed++;

					if (activity.score > 0)
					{
						activitiesWithScores++;
						log.debug("Activity '{}' -> '{}': rank={}, score={}", activity.name, key, activity.rank, activity.score);
					}
				}
				else
				{
					log.debug("Unknown activity in hiscore: {}", activity.name);
				}
			}

			log.debug("Parsed {} activities/bosses total, {} have non-zero scores", activitiesParsed, activitiesWithScores);
		}

		log.debug("Successfully parsed hiscores for '{}'", username);
		return stats;
	}

	/**
	 * Create mapping from OSRS API names to internal keys
	 * Names must match EXACTLY as returned by the JSON API (case-sensitive, including spaces and apostrophes)
	 */
	private static Map<String, String> createNameToKeyMapping()
	{
		Map<String, String> map = new HashMap<>();

		// Skills (25 total)
		map.put("Overall", "overall");
		map.put("Attack", "attack");
		map.put("Defence", "defence");
		map.put("Strength", "strength");
		map.put("Hitpoints", "hitpoints");
		map.put("Ranged", "ranged");
		map.put("Prayer", "prayer");
		map.put("Magic", "magic");
		map.put("Cooking", "cooking");
		map.put("Woodcutting", "woodcutting");
		map.put("Fletching", "fletching");
		map.put("Fishing", "fishing");
		map.put("Firemaking", "firemaking");
		map.put("Crafting", "crafting");
		map.put("Smithing", "smithing");
		map.put("Mining", "mining");
		map.put("Herblore", "herblore");
		map.put("Agility", "agility");
		map.put("Thieving", "thieving");
		map.put("Slayer", "slayer");
		map.put("Farming", "farming");
		map.put("Runecraft", "runecraft");
		map.put("Hunter", "hunter");
		map.put("Construction", "construction");
		map.put("Sailing", "sailing");

		// Activities (20 total - includes legacy entries that may not be active)
		map.put("Grid Points", "grid_points");
		map.put("League Points", "league_points");
		map.put("Deadman Points", "deadman_points");
		map.put("Bounty Hunter - Hunter", "bounty_hunter_hunter");
		map.put("Bounty Hunter - Rogue", "bounty_hunter_rogue");
		map.put("Bounty Hunter (Legacy) - Hunter", "bounty_hunter_legacy_hunter");
		map.put("Bounty Hunter (Legacy) - Rogue", "bounty_hunter_legacy_rogue");
		map.put("Clue Scrolls (all)", "clue_all");
		map.put("Clue Scrolls (beginner)", "clue_beginner");
		map.put("Clue Scrolls (easy)", "clue_easy");
		map.put("Clue Scrolls (medium)", "clue_medium");
		map.put("Clue Scrolls (hard)", "clue_hard");
		map.put("Clue Scrolls (elite)", "clue_elite");
		map.put("Clue Scrolls (master)", "clue_master");
		map.put("LMS - Rank", "lms");
		map.put("PvP Arena - Rank", "pvp_arena");
		map.put("Soul Wars Zeal", "soul_wars");
		map.put("Rifts closed", "rifts_closed");
		map.put("Colosseum Glory", "colosseum_glory");
		map.put("Collections Logged", "collections_logged");

		// Bosses (68 total) - EXACT names from OSRS JSON API
		map.put("Abyssal Sire", "abyssal_sire");
		map.put("Alchemical Hydra", "alchemical_hydra");
		map.put("Amoxliatl", "amoxliatl");
		map.put("Araxxor", "araxxor");
		map.put("Artio", "artio");
		map.put("Barrows Chests", "barrows");
		map.put("Bryophyta", "bryophyta");
		map.put("Callisto", "callisto");
		map.put("Calvar'ion", "calvarion");
		map.put("Cerberus", "cerberus");
		map.put("Chambers of Xeric", "chambers_of_xeric");
		map.put("Chambers of Xeric: Challenge Mode", "chambers_of_xeric_cm");
		map.put("Chaos Elemental", "chaos_elemental");
		map.put("Chaos Fanatic", "chaos_fanatic");
		map.put("Commander Zilyana", "commander_zilyana");
		map.put("Corporeal Beast", "corporeal_beast");
		map.put("Crazy Archaeologist", "crazy_archaeologist");
		map.put("Dagannoth Prime", "dagannoth_prime");
		map.put("Dagannoth Rex", "dagannoth_rex");
		map.put("Dagannoth Supreme", "dagannoth_supreme");
		map.put("Deranged Archaeologist", "deranged_archaeologist");
		map.put("Doom of Mokhaiotl", "doom_of_mokhaiotl");
		map.put("Duke Sucellus", "duke_sucellus");
		map.put("General Graardor", "general_graardor");
		map.put("Giant Mole", "giant_mole");
		map.put("Grotesque Guardians", "grotesque_guardians");
		map.put("Hespori", "hespori");
		map.put("Kalphite Queen", "kalphite_queen");
		map.put("King Black Dragon", "king_black_dragon");
		map.put("Kraken", "kraken");
		map.put("Kree'Arra", "kreearra");
		map.put("K'ril Tsutsaroth", "kril_tsutsaroth");
		map.put("Lunar Chests", "lunar_chests");
		map.put("Mimic", "mimic");
		map.put("Nex", "nex");
		map.put("Nightmare", "nightmare");
		map.put("Phosani's Nightmare", "phosanis_nightmare");
		map.put("Obor", "obor");
		map.put("Phantom Muspah", "phantom_muspah");
		map.put("Sarachnis", "sarachnis");
		map.put("Scorpia", "scorpia");
		map.put("Scurrius", "scurrius");
		map.put("Shellbane Gryphon", "shellbane_gryphon");
		map.put("Skotizo", "skotizo");
		map.put("Sol Heredit", "sol_heredit");
		map.put("Spindel", "spindel");
		map.put("Tempoross", "tempoross");
		map.put("The Gauntlet", "the_gauntlet");
		map.put("The Corrupted Gauntlet", "the_corrupted_gauntlet");
		map.put("The Hueycoatl", "the_hueycoatl");
		map.put("The Leviathan", "the_leviathan");
		map.put("The Royal Titans", "the_royal_titans");
		map.put("The Whisperer", "the_whisperer");
		map.put("Theatre of Blood", "theatre_of_blood");
		map.put("Theatre of Blood: Hard Mode", "theatre_of_blood_hm");
		map.put("Thermonuclear Smoke Devil", "thermonuclear_smoke_devil");
		map.put("Tombs of Amascut", "tombs_of_amascut");
		map.put("Tombs of Amascut: Expert Mode", "tombs_of_amascut_expert");
		map.put("TzKal-Zuk", "tzkal_zuk");
		map.put("TzTok-Jad", "tztok_jad");
		map.put("Vardorvis", "vardorvis");
		map.put("Venenatis", "venenatis");
		map.put("Vet'ion", "vetion");
		map.put("Vorkath", "vorkath");
		map.put("Wintertodt", "wintertodt");
		map.put("Yama", "yama");
		map.put("Zalcano", "zalcano");
		map.put("Zulrah", "zulrah");

		return Collections.unmodifiableMap(map);
	}
}
