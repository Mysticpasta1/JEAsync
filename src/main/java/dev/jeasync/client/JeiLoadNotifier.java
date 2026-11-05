package dev.jeasync.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Tells the player that JEI has finished loading.
 * <p>
 * The sound is the experience-orb pickup used by the Ding mod. Startup publishes after login, so
 * this runs on the client thread with a player in a world; if there is no player there is nothing
 * to notify.
 */
public final class JeiLoadNotifier {
	private JeiLoadNotifier() {
	}

	public static void announceLoaded(long elapsedNanos) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		player.displayClientMessage(
			Component.literal("§a[JEAsync] JEI finished loading.\n§7§oTook " + formatDuration(elapsedNanos)), false);
		player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
	}

	private static String formatDuration(long elapsedNanos) {
		long totalSeconds = Math.max(0L, elapsedNanos) / 1_000_000_000L;
		long hours = totalSeconds / 3600L;
		long minutes = totalSeconds % 3600L / 60L;
		long seconds = totalSeconds % 60L;
		if (hours > 0) {
			return hours + "h " + minutes + "m " + seconds + "s";
		}
		if (minutes > 0) {
			return minutes + "m " + seconds + "s";
		}
		return seconds + "s";
	}
}
