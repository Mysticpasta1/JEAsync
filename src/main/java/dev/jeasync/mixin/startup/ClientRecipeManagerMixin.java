package dev.jeasync.mixin.startup;

import dev.jeasync.ClientRecipeViews;
import dev.jeasync.ClientRecipes;
import dev.jeasync.JeiLoadProgress;
import dev.jeasync.RenderThreadGuard;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ConcurrentModificationException;

/**
 * Hands plugins a stable copy of the client recipe manager while JEI is registering.
 * <p>
 * Avaritia filters {@code byType().values()} for each crafting tier, and addons do the same for
 * the recipes they added. That map is live. A sync that replaces it, or another plugin walking it,
 * either throws or drops entries from the stream, and the plugin then keeps the short list.
 * <p>
 * A read taken while loading is remembered. When a sync replaces that map, startup can tell that
 * plugins already copied the old list. The keep-check that only asks whether the manager is empty
 * does not count.
 * <p>
 * The copies are dropped at every load boundary, not left on the manager. This mixin is applied to
 * the {@code RecipeManager} the level holds, which outlives the load, so a copy kept here also keeps
 * the whole recipe generation it was taken from alive for the session.
 * <p>
 * Targets the client recipe manager. Dedicated servers do not load this mixin.
 */
@Mixin(RecipeManager.class)
public abstract class ClientRecipeManagerMixin implements ClientRecipeViews {

	@Shadow
	private Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes;

	@Unique
	private Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> jeasync$byType;

	@Unique
	private Map<RecipeType<?>, Object> jeasync$byTypeSource;

	@Unique
	private Collection<Recipe<?>> jeasync$all;

	@Unique
	private Object jeasync$allSource;

	/**
	 * The map handed to a plugin during this load. Compared by identity with the live map.
	 */
	@Unique
	private volatile Object jeasync$servedMap;

	@Override
	public boolean jeasync$servedStale() {
		Object served = this.jeasync$servedMap;
		return served != null && served != this.recipes;
	}

	@Override
	public void jeasync$clearServed() {
		synchronized (this) {
			this.jeasync$servedMap = null;
			this.jeasync$all = null;
			this.jeasync$allSource = null;
			this.jeasync$byType = null;
			this.jeasync$byTypeSource = null;
		}
	}

	@Inject(method = "byType", at = @At("RETURN"), cancellable = true)
	private void jeasync$stableType(RecipeType<?> type, CallbackInfoReturnable<Map<ResourceLocation, Recipe<?>>> cir) {
		if (!jeasync$needed()) {
			this.jeasync$byType = null;
			this.jeasync$byTypeSource = null;
			this.jeasync$servedMap = null;
			return;
		}
		Object generation = this.recipes;
		Map<ResourceLocation, Recipe<?>> live = cir.getReturnValue();
		cir.setReturnValue(jeasync$copyType(type, live));
		jeasync$noteServed(generation);
	}

	@Inject(method = "getRecipes", at = @At("HEAD"), cancellable = true)
	private void jeasync$stableRecipes(CallbackInfoReturnable<Collection<Recipe<?>>> cir) {
		if (!jeasync$needed()) {
			this.jeasync$all = null;
			this.jeasync$allSource = null;
			this.jeasync$servedMap = null;
			return;
		}
		Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> source = this.recipes;
		synchronized (this) {
			if (this.jeasync$all == null || this.jeasync$allSource != source) {
				this.jeasync$all = jeasync$copyAll(source);
				this.jeasync$allSource = source;
			}
			jeasync$noteServed(source);
			cir.setReturnValue(this.jeasync$all);
		}
	}

	@Unique
	private void jeasync$noteServed(Object source) {
		if (source != null && !ClientRecipes.ignoringRecipeRead()) {
			this.jeasync$servedMap = source;
		}
	}

	@Unique
	private boolean jeasync$needed() {
		return JeiLoadProgress.isLoading() || RenderThreadGuard.isAsyncWorkerInPluginScope();
	}

	@Unique
	private Map<ResourceLocation, Recipe<?>> jeasync$copyType(RecipeType<?> type, Map<ResourceLocation, Recipe<?>> live) {
		synchronized (this) {
			if (this.jeasync$byType == null || this.jeasync$byTypeSource == null) {
				this.jeasync$byType = new HashMap<>();
				this.jeasync$byTypeSource = new HashMap<>();
			}
			Map<ResourceLocation, Recipe<?>> cached = this.jeasync$byType.get(type);
			if (cached != null && this.jeasync$byTypeSource.get(type) == live) {
				return cached;
			}
			Map<ResourceLocation, Recipe<?>> copy = jeasync$copyMap(live);
			this.jeasync$byType.put(type, copy);
			this.jeasync$byTypeSource.put(type, live);
			return copy;
		}
	}

	@Unique
	private static Map<ResourceLocation, Recipe<?>> jeasync$copyMap(Map<ResourceLocation, Recipe<?>> live) {
		if (live == null || live.isEmpty()) {
			return Map.of();
		}
		for (int attempt = 0; attempt < 8; attempt++) {
			try {
				return Map.copyOf(live);
			} catch (ConcurrentModificationException | NullPointerException | IllegalArgumentException ignored) {
				// The manager published a new map between passes, or a null slipped in. Try again.
			}
		}
		Map<ResourceLocation, Recipe<?>> copy = new HashMap<>();
		try {
			for (Map.Entry<ResourceLocation, Recipe<?>> entry : live.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					copy.put(entry.getKey(), entry.getValue());
				}
			}
		} catch (ConcurrentModificationException ignored) {
			// Keep the entries copied before the map moved.
		}
		return Map.copyOf(copy);
	}

	@Unique
	private static Collection<Recipe<?>> jeasync$copyAll(Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> source) {
		if (source == null || source.isEmpty()) {
			return List.of();
		}
		for (int attempt = 0; attempt < 8; attempt++) {
			try {
				List<Recipe<?>> copy = new ArrayList<>();
				for (Map<ResourceLocation, Recipe<?>> typed : source.values()) {
					for (Recipe<?> recipe : typed.values()) {
						if (recipe != null) {
							copy.add(recipe);
						}
					}
				}
				return List.copyOf(copy);
			} catch (ConcurrentModificationException ignored) {
				// Same as the per-type copy: the outer map was replaced mid-read.
			}
		}
		return List.of();
	}
}
