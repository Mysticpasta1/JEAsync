package dev.jeasync.mixin.compat;

import mezz.jei.common.Internal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * JEI++ 1.0.7 calls {@code Internal.getJeiClientConfigs()}, which JEI 15.71 removed.
 * Targets upstream JEI++ 1.0.7 against JEI 15.71.0.224.
 */
@Mixin(value = com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites.class, remap = false)
public abstract class RecipeTreeFavoritesMixin {
	@Shadow
	private static int laidOutBookmarkColumns() {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason JEI 15.71 renamed getJeiClientConfigs to getClientConfigs, so bookmark columns are read from that config and still fall back to 9.
	 */
	@Overwrite
	private static int bookmarkColumns() {
		int laidOut = laidOutBookmarkColumns();
		if (laidOut > 0) {
			return laidOut;
		}
		int fallback = 9;
		try {
			Object grid = Internal.getClientConfigs().getBookmarkListConfig();
			Object value;
			try {
				value = grid.getClass().getMethod("getMaxColumns").invoke(grid);
			} catch (ReflectiveOperationException ignored) {
				value = grid.getClass().getMethod("maxColumns").invoke(grid);
				try {
					value = value.getClass().getMethod("getValue").invoke(value);
				} catch (ReflectiveOperationException ignoredInner) {
					// The config holder itself is not a column count. The fallback below covers that.
				}
			}
			if (value instanceof Number number) {
				return Math.max(1, number.intValue());
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Configs are not created yet, or this JEI build names the column accessor differently.
		}
		return fallback;
	}
}
