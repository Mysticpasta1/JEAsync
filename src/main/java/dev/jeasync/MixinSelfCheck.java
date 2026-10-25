package dev.jeasync;

import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Forces this mod's mixin targets to be class-loaded so that a broken mixin surfaces immediately
 * and with a clear message, rather than as a confusing failure later in startup.
 * <p>
 * A dedicated server never loads JEI's client-side classes, so most of this mod's targets would
 * otherwise go completely unexercised on a server and a mistake in them would only appear in a
 * real client. Forcing the load is cheap and makes the failure legible.
 * <p>
 * Only targets that are safe to load on a dedicated server are listed here. Anything touching
 * {@code net.minecraft.client} is skipped, because those classes cannot resolve in a server JVM
 * and their absence is not a mixin failure.
 */
final class MixinSelfCheck {
	private static final Logger LOGGER = LogManager.getLogger();

	private static final List<String> SERVER_SAFE_TARGETS = List.of(
		"mezz.jei.common.collect.Table",
		"mezz.jei.common.collect.MultiMap",
		"mezz.jei.common.collect.SetMultiMap",
		"mezz.jei.common.collect.ListMultiMap",
		"mezz.jei.library.recipes.collect.RecipeTypeData",
		// Package-private, and named by the internal mixin config's shadow fields, so a rename
		// there would not be caught by anything else.
		"mezz.jei.library.recipes.collect.IngredientUidIndex",
		"mezz.jei.library.recipes.collect.IngredientToRecipesMap",
		"mezz.jei.library.recipes.collect.RecipeIngredientTable",
		"mezz.jei.library.recipes.collect.RecipeMap",
		"mezz.jei.library.recipes.RecipeManagerInternal",
		"mezz.jei.library.recipes.RecipeManager",
		"mezz.jei.library.recipes.ExtendableRecipeCategoryHelper",
		"mezz.jei.library.load.registration.RecipeCategoryRegistration",
		// Pulled in by RecipeManagerInternal, and patched indirectly via its collections.
		"mezz.jei.library.recipes.PluginManager",
		"mezz.jei.library.ingredients.subtypes.SubtypeInterpreters",
		"mezz.jei.library.ingredients.RegisteredIngredients",
		"mezz.jei.library.ingredients.IngredientVisibility",
		"mezz.jei.library.ingredients.IngredientBlacklistInternal",
		"net.minecraftforge.eventbus.EventSubclassTransformer",
		"net.minecraft.world.item.CreativeModeTab",
		"mezz.jei.forge.platform.ModHelper",
		"mezz.jei.common.ingredients.itemStacks.TypedItemStack",
		// The config adapter's nested file record, named by its binary name because it is nested.
		// Common code, so this loads on a server as well as a client.
		"mezz.jei.common.config.ConfigManagerAdapter$ConfigFile",
		// Not a JEI class: this is the client event loop the guard mixin patches, so a rename
		// there would silently disable the guard rather than fail anything else.
		"net.minecraft.util.thread.BlockableEventLoop"
	);

	/**
	 * Targets that only exist on a client. A dedicated server cannot load these, so they are
	 * reported as skipped rather than counted as failures; verifying them needs a real client
	 * run, which is why they are listed explicitly rather than omitted silently.
	 */
	private static final List<String> CLIENT_ONLY_TARGETS = List.of(
		"mezz.jei.library.load.PluginCaller",
		// IngredientManager exposes createClickableIngredient(Rect2i, ...), so it cannot
		// resolve in a server JVM at all.
		"mezz.jei.library.ingredients.IngredientManager",
		"mezz.jei.library.ingredients.IngredientSet",
		// Search backend; only ever constructed on the client.
		"mezz.jei.gui.search.ElementSearch",
		"mezz.jei.gui.ingredients.IngredientFilter",
		"mezz.jei.common.util.RegistryUtil",
		"net.minecraft.client.Minecraft",
		"mezz.jei.forge.startup.EventRegistration",
		"mezz.jei.forge.startup.StartEventObserver",
		"mezz.jei.library.startup.JeiStarter",
		"net.minecraft.world.item.crafting.RecipeManager",
		"mezz.jei.gui.events.GuiEventHandler",
		"mezz.jei.gui.input.GuiTextFieldFilter"
	);

	private MixinSelfCheck() {
	}

	static void run() {
		boolean client = isClient();

		List<String> targets = new java.util.ArrayList<>(SERVER_SAFE_TARGETS);
		if (client) {
			// On a client the remaining targets resolve, so check them too rather than
			// reporting them as untested.
			targets.addAll(CLIENT_ONLY_TARGETS);
		}

		int failures = forceLoad(targets);
		if (failures == 0) {
			LOGGER.info("Mixin self-check: {} target classes loaded{}", targets.size(),
				client ? "" : (" (" + CLIENT_ONLY_TARGETS.size() + " client-only skipped)"));
		} else {
			LOGGER.error("Mixin self-check: {} of {} target classes failed to load", failures, targets.size());
		}

		// The load above proves nothing about patching. Only the mixins themselves can report
		// that they applied, because they say so from inside the code they inject.
		int applied = dev.jeasync.MixinApplied.appliedCount();
		if (applied == 0) {
			LOGGER.warn("No mixin has reported as applied. That is expected on a dedicated server, where JEI never calls the patched plugin dispatch, and for any mixin whose target method has not been reached yet; it is only meaningful on a client. If nothing applies on a client, the MixinConfigs manifest attribute is missing, so the config is never registered at all.");
		} else {
			LOGGER.info("{} mixin(s) confirmed applied: {}", applied,
				dev.jeasync.MixinApplied.applied());
		}
	}

	private static int forceLoad(List<String> classNames) {
		int failures = 0;
		for (String className : classNames) {
			try {
				Class.forName(className, true, MixinSelfCheck.class.getClassLoader());
			} catch (Throwable t) {
				failures++;
				LOGGER.error("Mixin target {} failed to load. A mixin for it is likely invalid.", className, t);
			}
		}
		return failures;
	}

	/**
	 * A dedicated server has no {@code net.minecraft.client}, so the client-only targets cannot
	 * resolve there. Asking Forge which side we are on rather than probing for the class matters:
	 * touching a client class on a dedicated server trips Forge's dist cleaner and logs an ERROR
	 * that looks like a real fault.
	 */
	private static boolean isClient() {
		return !FMLEnvironment.dist.isDedicatedServer();
	}
}