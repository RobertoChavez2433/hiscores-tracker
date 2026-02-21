package com.advancedxptracker;

import java.io.IOException;

public class PlayerNotFoundException extends IOException
{
	public PlayerNotFoundException(String username)
	{
		super("Player '" + username + "' not found. Check the username and account type.");
	}
}
