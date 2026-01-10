package com.advancedxptracker;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the gains between two PlayerStats snapshots
 */
@Getter
public class PlayerGains
{
	private final PlayerStats current;
	private final PlayerStats previous;
	private final Map<String, SkillGain> skillGains;
	private final Map<String, Integer> activityGains;

	public PlayerGains(PlayerStats current, PlayerStats previous)
	{
		this.current = current;
		this.previous = previous;
		this.skillGains = new HashMap<>();
		this.activityGains = new HashMap<>();
	}

	public void addSkillGain(String skillName, long xpGain, int levelGain)
	{
		skillGains.put(skillName, new SkillGain(xpGain, levelGain));
	}

	public void addActivityGain(String activityName, int scoreGain)
	{
		activityGains.put(activityName, scoreGain);
	}

	public SkillGain getSkillGain(String skillName)
	{
		return skillGains.getOrDefault(skillName, new SkillGain(0, 0));
	}

	public int getActivityGain(String activityName)
	{
		return activityGains.getOrDefault(activityName, 0);
	}

	/**
	 * Data class for skill gains
	 */
	@Getter
	public static class SkillGain
	{
		private final long xpGain;
		private final int levelGain;

		public SkillGain(long xpGain, int levelGain)
		{
			this.xpGain = xpGain;
			this.levelGain = levelGain;
		}

		public boolean hasGain()
		{
			return xpGain > 0 || levelGain > 0;
		}
	}
}
