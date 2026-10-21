package dev.jeiasync.mixin.nbt;

import dev.jeiasync.MixinApplied;
import dev.jeiasync.nbt.CompactTagMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Map;

/**
 * Replaces the fastutil map a compound was given with a short entry list of the same entries.
 * <p>
 * The pack builds each compound on a default-capacity open hash map. Copying those into a
 * {@link java.util.HashMap} removed the empty table slots and left a node object per key, which
 * was still most of the retained heap. A tag with at most eight keys keeps the pairs in arrays.
 * An empty compound keeps no map of its own: every empty tag points at one shared empty map, and
 * the first write gives that tag its own list. One shared mutable tag would let a write on one
 * stack show up on every other stack, so the compound object stays when something still holds it.
 * <p>
 * Mysticpasta1 — the heap dump showed those maps hanging off {@code CompoundTag.tags}.
 */
@Mixin(CompoundTag.class)
public class CompoundTagMixin {
	@Shadow
	@Final
	@Mutable
	private Map<String, Tag> tags;

	@Unique
	private static volatile boolean jeiasync$marked;

	@Invoker("<init>")
	private static CompoundTag jeiasync$newTag(Map<String, Tag> entries) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy built a fresh hash map and then the constructor copied it again. Write the copied entries straight into the short list.
	 */
	@Inject(method = "copy", at = @At("HEAD"), cancellable = true)
	private void jeiasync$copyCompact(CallbackInfoReturnable<CompoundTag> cir) {
		Map<String, Tag> current = this.tags;
		if (current == null) {
			return;
		}
		if (current == jeiasync$sharedEmpty() || current.isEmpty()) {
			cir.setReturnValue(jeiasync$newTag(jeiasync$sharedEmpty()));
			return;
		}
		cir.setReturnValue(jeiasync$newTag(CompactTagMap.deepCopy(current)));
	}

	/**
	 * @author Mysticpasta1
	 * @reason Compound tags were stored in open hash maps, and the plain-map copy still kept a node per key. Keep a short entry list, and keep one shared map for every empty tag.
	 */
	@Inject(method = "<init>(Ljava/util/Map;)V", at = @At("RETURN"))
	private void jeiasync$dropOpenHashMap(Map<String, Tag> ignored, CallbackInfo ci) {
		Map<String, Tag> current = this.tags;
		if (current == null || current == jeiasync$sharedEmpty()) {
			return;
		}
		if (current.isEmpty()) {
			this.tags = jeiasync$sharedEmpty();
			jeiasync$mark();
			return;
		}
		if (!CompactTagMap.isOpenHashMap(current)) {
			return;
		}
		this.tags = CompactTagMap.copyOf(current);
		jeiasync$mark();
	}

	/**
	 * @author Mysticpasta1
	 * @reason An empty tag shares one map. Give this tag its own map before a write so the shared map stays empty.
	 */
	@Inject(method = "put", at = @At("HEAD"))
	private void jeiasync$openForPut(String key, Tag value, CallbackInfoReturnable<Tag> cir) {
		jeiasync$openForWrite();
	}

	/**
	 * @author Mysticpasta1
	 * @reason An empty tag shares one map. Give this tag its own map before a write so the shared map stays empty.
	 */
	@Inject(
		method = {
			"putByte",
			"putShort",
			"putInt",
			"putLong",
			"putUUID",
			"putFloat",
			"putDouble",
			"putString",
			"putByteArray(Ljava/lang/String;[B)V",
			"putByteArray(Ljava/lang/String;Ljava/util/List;)V",
			"putIntArray(Ljava/lang/String;[I)V",
			"putIntArray(Ljava/lang/String;Ljava/util/List;)V",
			"putLongArray(Ljava/lang/String;[J)V",
			"putLongArray(Ljava/lang/String;Ljava/util/List;)V",
			"putBoolean"
		},
		at = @At("HEAD")
	)
	private void jeiasync$openForPrimitive(CallbackInfo ci) {
		jeiasync$openForWrite();
	}

	/**
	 * @author Mysticpasta1
	 * @reason An empty tag shares one map. Give this tag its own map before a merge so the shared map stays empty.
	 */
	@Inject(method = "merge", at = @At("HEAD"))
	private void jeiasync$openForMerge(CompoundTag other, CallbackInfoReturnable<CompoundTag> cir) {
		jeiasync$openForWrite();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Removing a key from an empty shared map would throw. There is nothing to remove.
	 */
	@Inject(method = "remove", at = @At("HEAD"), cancellable = true)
	private void jeiasync$skipRemoveOnShared(String key, CallbackInfo ci) {
		if (this.tags == jeiasync$sharedEmpty()) {
			ci.cancel();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The last key left this tag. Drop its map and use the shared empty one.
	 */
	@Inject(method = "remove", at = @At("RETURN"))
	private void jeiasync$releaseMapAfterRemove(String key, CallbackInfo ci) {
		Map<String, Tag> current = this.tags;
		if (current != jeiasync$sharedEmpty() && current.isEmpty()) {
			this.tags = jeiasync$sharedEmpty();
		}
	}

	@Unique
	private void jeiasync$openForWrite() {
		if (this.tags == jeiasync$sharedEmpty()) {
			this.tags = new CompactTagMap();
		}
	}

	@Unique
	@SuppressWarnings("unchecked")
	private static Map<String, Tag> jeiasync$sharedEmpty() {
		return (Map<String, Tag>) (Map<?, ?>) Collections.emptyMap();
	}

	@Unique
	private static void jeiasync$mark() {
		if (jeiasync$marked) {
			return;
		}
		jeiasync$marked = true;
		MixinApplied.mark("nbt.CompoundTagMixin");
		LogManager.getLogger(CompoundTagMixin.class).info(
			"JEI Async: compound tags keep a short entry list, and empty tags share one map and are left off recipe items"
		);
	}
}
