package com.advancedxptracker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a player's complete hiscores data at a point in time
 */
@Slf4j
@Getter
public class PlayerStats
{
	private final String username;
	private final long timestamp;
	private final Map<String, SkillData> skills;
	private final Map<String, ActivityData> activities;

	public PlayerStats(String username, long timestamp)
	{
		this.username = username;
		this.timestamp = timestamp;
		this.skills = new HashMap<>();
		this.activities = new HashMap<>();
	}

	public void setSkill(String name, int rank, int level, long xp)
	{
		skills.put(name, new SkillData(rank, level, xp));
	}

	public void setActivity(String name, int rank, int score)
	{
		activities.put(name, new ActivityData(rank, score));
	}

	/**
	 * Calculate gains compared to an older snapshot
	 */
	public PlayerGains calculateGains(PlayerStats older)
	{
		if (older == null)
		{
			return new PlayerGains(this, null);
		}

		PlayerGains gains = new PlayerGains(this, older);

		// Calculate skill XP gains
		for (Map.Entry<String, SkillData> entry : skills.entrySet())
		{
			String skillName = entry.getKey();
			SkillData current = entry.getValue();
			SkillData old = older.skills.get(skillName);

			if (old != null)
			{
				long xpGain = current.xp - old.xp;
				int levelGain = current.level - old.level;
				gains.addSkillGain(skillName, xpGain, levelGain);
			}
		}

		// Calculate activity score gains
		for (Map.Entry<String, ActivityData> entry : activities.entrySet())
		{
			String activityName = entry.getKey();
			ActivityData current = entry.getValue();
			ActivityData old = older.activities.get(activityName);

			if (old != null)
			{
				int scoreGain = current.score - old.score;
				gains.addActivityGain(activityName, scoreGain);
			}
		}

		return gains;
	}

	/**
	 * Data class for skill information
	 */
	@Getter
	public static class SkillData
	{
		private final int rank;
		private final int level;
		private final long xp;

		public SkillData(int rank, int level, long xp)
		{
			this.rank = rank;
			this.level = level;
			this.xp = xp;
		}
	}

	/**
	 * Data class for activity/boss information
	 */
	@Getter
	public static class ActivityData
	{
		private final int rank;
		private final int score;

		public ActivityData(int rank, int score)
		{
			this.rank = rank;
			this.score = score;
		}
	}
}
