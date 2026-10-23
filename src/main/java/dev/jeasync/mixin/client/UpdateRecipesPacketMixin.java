package dev.jeasync.mixin.client;

import dev.jeasync.client.RecipeSyncProgress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the rest of a server recipe packet when one copied Mekanism recipe fails to decode.
 * <p>
 * Mekanism, and addons that copy its serializers, validate a recipe in its constructor after
 * {@code fromNetwork} has already read every byte of that recipe, so returning a placeholder
 * recipe there keeps the buffer aligned for the next one. A serializer that aborts in the middle
 * of a record — Mantle's {@code ItemStackLoadable} rejecting an empty result field before the
 * record's trailing floats are read — leaves the buffer with a few unread bytes, and the next
 * recipe then reads garbage. For those, the drop point is not knowable from the stack trace.
 * <p>
 * Targets the client decode path.
 */
@Mixin(ClientboundUpdateRecipesPacket.class)
public abstract class UpdateRecipesPacketMixin {
	private static final Logger LOGGER = LogManager.getLogger();

	/** How far past the failure the true recipe boundary can sit. The observed Mantle loss is 8 bytes. */
	private static final int RESYNC_WINDOW = 128;

	@Shadow
	@Final
	@Mutable
	private List<Recipe<?>> recipes;

	/**
	 * @author Mysticpasta1
	 * @reason A failed recipe is only dropped once the next recipe's headers read cleanly from
	 * the buffer, which is proof the copy landed on the real boundary for the remainder of the
	 * packet. With no provable boundary, the failure rethrows and the whole packet takes
	 * vanilla's failure path instead of silently desynchronizing every recipe after it.
	 */
	@Redirect(
		method = "fromNetwork",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/crafting/RecipeSerializer;fromNetwork(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/world/item/crafting/Recipe;"
		)
	)
	private static Recipe<?> jeasync$skipEmptyOutput(RecipeSerializer<?> serializer, ResourceLocation id, FriendlyByteBuf buf) {
		int start = buf.readerIndex();
		try {
			Recipe<?> recipe = serializer.fromNetwork(id, buf);
			RecipeSyncProgress.stamp();
			return recipe;
		} catch (Exception e) {
			int boundary = jeasync$findRecipeBoundary(buf, start);
			if (boundary < 0) {
				buf.readerIndex(start);
				throw e;
			}
			LOGGER.warn("jeasync: skipped recipe {} in the server packet ({}), resynced to the next recipe +{} bytes",
				id, e.getMessage(), boundary - start);
			buf.readerIndex(boundary);
			RecipeSyncProgress.stamp();
			return null;
		}
	}

	@Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
	private void jeasync$dropSkipped(FriendlyByteBuf buf, CallbackInfo ci) {
		if (this.recipes == null || this.recipes.isEmpty()) {
			return;
		}
		int dropped = 0;
		for (Recipe<?> recipe : this.recipes) {
			if (recipe == null) {
				dropped++;
			}
		}
		if (dropped == 0) {
			return;
		}
		List<Recipe<?>> kept = new ArrayList<>(this.recipes.size() - dropped);
		for (Recipe<?> recipe : this.recipes) {
			if (recipe != null) {
				kept.add(recipe);
			}
		}
		this.recipes = List.copyOf(kept);
		LOGGER.warn("jeasync: server recipe packet dropped {} recipes and kept {}", dropped, kept.size());
	}

	/**
	 * Finds the start of the next recipe by reading forward from the failure: a candidate is the
	 * real boundary when a registered serializer id plus a distinct recipe id decode there.
	 * Mysticpasta1 — the failing recipe always ends before a fresh header pair, and a random
	 * byte offset rarely parses as two valid resource locations with a registered serializer.
	 */
	private static int jeasync$findRecipeBoundary(FriendlyByteBuf buf, int start) {
		int probeFrom = buf.readerIndex();
		int last = Math.min(start + RESYNC_WINDOW, buf.writerIndex() - 2);
		for (int candidate = probeFrom; candidate <= last; candidate++) {
			buf.readerIndex(candidate);
			try {
				ResourceLocation type = buf.readResourceLocation();
				ResourceLocation nextId = buf.readResourceLocation();
				if (BuiltInRegistries.RECIPE_SERIALIZER.get(type) != null && !nextId.equals(type)) {
					return candidate;
				}
			} catch (Exception ignored) {
			}
		}
		buf.readerIndex(probeFrom);
		return -1;
	}
}
