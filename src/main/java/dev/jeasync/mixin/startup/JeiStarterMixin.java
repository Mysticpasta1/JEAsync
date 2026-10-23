package dev.jeasync.mixin.startup;

import dev.jeasync.ClientRecipes;
import dev.jeasync.MixinApplied;
import mezz.jei.common.Internal;
import mezz.jei.library.startup.JeiStarter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.crafting.Recipe;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Keeps modded recipes in the client manager while JEI starts.
 * <p>
 * When no synced list has been stored yet, {@code JeiStarter.start} replaces the manager with
 * vanilla recipes. Avaritia and its addons then register that vanilla list. A synced list is put
 * back first. The vanilla replacement is skipped when the manager already has recipes, which is
 * the list a force-start would otherwise wipe.
 * <p>
 * The skip hooks {@code Internal.hasClientRecipes}. That name is the same in the published JEI
 * jar. {@code RecipeManager.replaceRecipes} is not: the jar calls it by its SRG name.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = JeiStarter.class, remap = false)
public abstract class JeiStarterMixin {
	@Inject(method = "start", at = @At("HEAD"), remap = false)
	private void jeasync$restoreSyncedRecipes(CallbackInfo ci) {
		MixinApplied.mark("startup.JeiStarterMixin");
		ClientRecipes.restoreIfDrifted();
	}

	@Redirect(
		method = "start",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/common/Internal;hasClientRecipes()Z"
		),
		remap = false
	)
	private boolean jeasync$keepExistingRecipes() {
		if (Internal.hasClientRecipes()) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return false;
		}
		// This read only checks that the list is non-empty. It is not a plugin taking the fallback.
		ClientRecipes.ignoreNextRecipeRead();
		try {
			Collection<Recipe<?>> current = level.getRecipeManager().getRecipes();
			if (current.isEmpty()) {
				return false;
			}
			LogManager.getLogger().info("jeasync: kept {} recipes already in the client manager", current.size());
			return true;
		} finally {
			ClientRecipes.endIgnoredRecipeRead();
		}
	}
}
