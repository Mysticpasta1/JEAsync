package dev.jeiasync.mixin.startup;

import dev.jeiasync.ClientRecipeViews;
import dev.jeiasync.ClientRecipes;
import dev.jeiasync.JeiLoadProgress;
import dev.jeiasync.RenderThreadGuard;
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
 * Targets the client recipe manager. Dedicated servers do not load this mixin.
 */
@Mixin(RecipeManager.class)
public abstract class ClientRecipeManagerMixin implements ClientRecipeViews {

	@Shadow
	private Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes;

	@Unique
	private Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> jeiasync$byType;

	@Unique
	private Map<RecipeType<?>, Object> jeiasync$byTypeSource;

	@Unique
	private Collection<Recipe<?>> jeiasync$all;

	@Unique
	private Object jeiasync$allSource;

	/**
	 * The map handed to a plugin during this load. Compared by identity with the live map.
	 */
	@Unique
	private volatile Object jeiasync$servedMap;

	@Override
	public boolean jeiasync$servedStale() {
		Object served = this.jeiasync$servedMap;
		return served != null && served != this.recipes;
	}

	@Override
	public void jeiasync$clearServed() {
		this.jeiasync$servedMap = null;
	}

	@Inject(method = "byType", at = @At("RETURN"), cancellable = true)
	private void jeiasync$stableType(RecipeType<?> type, CallbackInfoReturnable<Map<ResourceLocation, Recipe<?>>> cir) {
		if (!jeiasync$needed()) {
			this.jeiasync$byType = null;
			this.jeiasync$byTypeSource = null;
			this.jeiasync$servedMap = null;
			return;
		}
		Object generation = this.recipes;
		Map<ResourceLocation, Recipe<?>> live = cir.getReturnValue();
		cir.setReturnValue(jeiasync$copyType(type, live));
		jeiasync$noteServed(generation);
	}

	@Inject(method = "getRecipes", at = @At("HEAD"), cancellable = true)
	private void jeiasync$stableRecipes(CallbackInfoReturnable<Collection<Recipe<?>>> cir) {
		if (!jeiasync$needed()) {
			this.jeiasync$all = null;
			this.jeiasync$allSource = null;
			this.jeiasync$servedMap = null;
			return;
		}
		Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> source = this.recipes;
		synchronized (this) {
			if (this.jeiasync$all == null || this.jeiasync$allSource != source) {
				this.jeiasync$all = jeiasync$copyAll(source);
				this.jeiasync$allSource = source;
			}
			jeiasync$noteServed(source);
			cir.setReturnValue(this.jeiasync$all);
		}
	}

	@Unique
	private void jeiasync$noteServed(Object source) {
		if (source != null && !ClientRecipes.ignoringRecipeRead()) {
			this.jeiasync$servedMap = source;
		}
	}

	@Unique
	private boolean jeiasync$needed() {
		return JeiLoadProgress.isLoading() || RenderThreadGuard.isAsyncWorkerInPluginScope();
	}

	@Unique
	private Map<ResourceLocation, Recipe<?>> jeiasync$copyType(RecipeType<?> type, Map<ResourceLocation, Recipe<?>> live) {
		synchronized (this) {
			if (this.jeiasync$byType == null || this.jeiasync$byTypeSource == null) {
				this.jeiasync$byType = new HashMap<>();
				this.jeiasync$byTypeSource = new HashMap<>();
			}
			Map<ResourceLocation, Recipe<?>> cached = this.jeiasync$byType.get(type);
			if (cached != null && this.jeiasync$byTypeSource.get(type) == live) {
				return cached;
			}
			Map<ResourceLocation, Recipe<?>> copy = jeiasync$copyMap(live);
			this.jeiasync$byType.put(type, copy);
			this.jeiasync$byTypeSource.put(type, live);
			return copy;
		}
	}

	@Unique
	private static Map<ResourceLocation, Recipe<?>> jeiasync$copyMap(Map<ResourceLocation, Recipe<?>> live) {
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
	private static Collection<Recipe<?>> jeiasync$copyAll(Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> source) {
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
