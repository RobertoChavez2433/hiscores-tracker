package com.advancedxptracker;

/**
 * Response model for OSRS Hiscores JSON API
 */
class HiscoresResponse
{
	static class SkillEntry
	{
		int id;
		String name;
		int rank;
		int level;
		long xp;
	}

	static class ActivityEntry
	{
		int id;
		String name;
		int rank;
		long score;
	}

	String name;
	SkillEntry[] skills;
	ActivityEntry[] activities;
}
