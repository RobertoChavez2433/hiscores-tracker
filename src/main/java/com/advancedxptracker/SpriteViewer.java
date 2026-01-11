package com.advancedxptracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.SpriteManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Utility to extract and save sprite images for verification
 */
@Slf4j
public class SpriteViewer
{
	private final SpriteManager spriteManager;

	public SpriteViewer(SpriteManager spriteManager)
	{
		this.spriteManager = spriteManager;
	}

	/**
	 * Extract a sprite and save it as PNG for verification
	 */
	public void saveSpriteImage(int spriteId, String filename)
	{
		log.info("Attempting to load sprite ID: {}", spriteId);

		spriteManager.getSpriteAsync(spriteId, 0, (sprite) ->
		{
			if (sprite != null)
			{
				try
				{
					File outputDir = new File(System.getProperty("user.home"), "Documents/Runescape/Plugins/sprite-verification");
					outputDir.mkdirs();

					File outputFile = new File(outputDir, filename);
					ImageIO.write(sprite, "PNG", outputFile);

					log.info("Sprite {} saved to: {}", spriteId, outputFile.getAbsolutePath());
					log.info("Sprite dimensions: {}x{}", sprite.getWidth(), sprite.getHeight());
				}
				catch (IOException e)
				{
					log.error("Failed to save sprite {}: {}", spriteId, e.getMessage());
				}
			}
			else
			{
				log.warn("Sprite {} returned null", spriteId);
			}
		});
	}

	/**
	 * Save all three sprites we're trying to identify
	 */
	public void saveTestSprites(int sailingId, int doomId, int shellbaneId)
	{
		log.info("=== SPRITE VERIFICATION START ===");
		log.info("Testing sprite IDs:");
		log.info("  Sailing: {}", sailingId);
		log.info("  Doom of Mokhaiotl: {}", doomId);
		log.info("  Shellbane Gryphon: {}", shellbaneId);

		saveSpriteImage(sailingId, String.format("sailing_%d.png", sailingId));
		saveSpriteImage(doomId, String.format("doom_%d.png", doomId));
		saveSpriteImage(shellbaneId, String.format("shellbane_%d.png", shellbaneId));

		log.info("=== SPRITE VERIFICATION END ===");
	}

	/**
	 * Test a range of sprite IDs to find the correct ones
	 */
	public void testSpriteRange(int startId, int endId, String prefix)
	{
		log.info("Testing sprite range {}-{} with prefix '{}'", startId, endId, prefix);

		for (int i = startId; i <= endId; i++)
		{
			final int spriteId = i;
			saveSpriteImage(spriteId, String.format("%s_%d.png", prefix, spriteId));
		}
	}
}
