package com.advancedxptracker;

/**
 * Represents the different OSRS account types with their hiscores API endpoints
 */
public enum AccountType
{
	NORMAL("Normal", "https://services.runescape.com/m=hiscore_oldschool/index_lite.json?player=", "normal_icon.png"),
	IRONMAN("Ironman", "https://services.runescape.com/m=hiscore_oldschool_ironman/index_lite.json?player=", "ironman_icon.png"),
	HARDCORE_IRONMAN("Hardcore Ironman", "https://services.runescape.com/m=hiscore_oldschool_hardcore_ironman/index_lite.json?player=", "hardcore_ironman_icon.png"),
	ULTIMATE_IRONMAN("Ultimate Ironman", "https://services.runescape.com/m=hiscore_oldschool_ultimate/index_lite.json?player=", "ultimate_ironman_icon.png");

	private final String displayName;
	private final String apiUrl;
	private final String iconFileName;

	AccountType(String displayName, String apiUrl, String iconFileName)
	{
		this.displayName = displayName;
		this.apiUrl = apiUrl;
		this.iconFileName = iconFileName;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public String getApiUrl()
	{
		return apiUrl;
	}

	public String getIconFileName()
	{
		return iconFileName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
