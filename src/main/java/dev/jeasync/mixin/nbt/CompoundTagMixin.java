package dev.jeasync.mixin.nbt;

import dev.jeasync.MixinApplied;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
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
 * Every empty compound points at one shared map instead of carrying its own fastutil table, and
 * the first write gives that tag a fresh map of its own.
 * <p>
 * The short-entry-list storage ({@code CompactTagMap}) that used to wrap the small maps is gone
 * at Mysticpasta1's request: it measured as a net RAM loss on the pack and the only part worth
 * keeping was the empty-map share.
 * <p>
 * One shared mutable tag would let a write on one stack show up on every other stack, so the
 * compound object stays when something still holds it.
 */
@Mixin(CompoundTag.class)
public class CompoundTagMixin {

	@Shadow
	@Final
	@Mutable
	private Map<String, Tag> tags;

	@Unique
	private static volatile boolean jeasync$marked;

	@Invoker("<init>")
	private static CompoundTag jeasync$newTag(Map<String, Tag> entries) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason An empty tag's {@code copy} was building a fresh map for nothing. The shared empty one carries over and plain tags copy through vanilla.
	 */
	@Inject(method = "copy", at = @At("HEAD"), cancellable = true)
	private void jeasync$copyCompact(CallbackInfoReturnable<CompoundTag> cir) {
		Map<String, Tag> current = this.tags;
		if (current == null) {
			return;
		}
		if (current == jeasync$sharedEmpty() || current.isEmpty()) {
			cir.setReturnValue(jeasync$newTag(jeasync$sharedEmpty()));
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Every empty tag shares one map for as long as it stays empty.
	 */
	@Inject(method = "<init>(Ljava/util/Map;)V", at = @At("RETURN"))
	private void jeasync$dropOpenHashMap(Map<String, Tag> ignored, CallbackInfo ci) {
		Map<String, Tag> current = this.tags;
		if (current == null || current == jeasync$sharedEmpty()) {
			return;
		}
		if (current.isEmpty()) {
			this.tags = jeasync$sharedEmpty();
			jeasync$mark();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason An empty tag shares one map. Give this tag its own map before a write so the shared map stays empty.
	 */
	@Inject(method = "put", at = @At("HEAD"))
	private void jeasync$openForPut(String key, Tag value, CallbackInfoReturnable<Tag> cir) {
		jeasync$openForWrite();
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
	private void jeasync$openForPrimitive(CallbackInfo ci) {
		jeasync$openForWrite();
	}

	/**
	 * @author Mysticpasta1
	 * @reason An empty tag shares one map. Give this tag its own map before a merge so the shared map stays empty.
	 */
	@Inject(method = "merge", at = @At("HEAD"))
	private void jeasync$openForMerge(CompoundTag other, CallbackInfoReturnable<CompoundTag> cir) {
		jeasync$openForWrite();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Removing a key from an empty shared map would throw. There is nothing to remove.
	 */
	@Inject(method = "remove", at = @At("HEAD"), cancellable = true)
	private void jeasync$skipRemoveOnShared(String key, CallbackInfo ci) {
		if (this.tags == jeasync$sharedEmpty()) {
			ci.cancel();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The last key left this tag. Drop its map and use the shared empty one.
	 */
	@Inject(method = "remove", at = @At("RETURN"))
	private void jeasync$releaseMapAfterRemove(String key, CallbackInfo ci) {
		Map<String, Tag> current = this.tags;
		if (current != jeasync$sharedEmpty() && current.isEmpty()) {
			this.tags = jeasync$sharedEmpty();
		}
	}

	@Unique
	private void jeasync$openForWrite() {
		if (this.tags == jeasync$sharedEmpty()) {
			this.tags = new Object2ObjectOpenHashMap<>();
		}
	}

	@Unique
	@SuppressWarnings("unchecked")
	private static Map<String, Tag> jeasync$sharedEmpty() {
		return (Map<String, Tag>) (Map<?, ?>) Collections.emptyMap();
	}

	@Unique
	private static void jeasync$mark() {
		if (jeasync$marked) {
			return;
		}
		jeasync$marked = true;
		MixinApplied.mark("nbt.CompoundTagMixin");
		LogManager.getLogger(CompoundTagMixin.class).info(
			"jeasync: empty compound tags share one map, and those empty tags are left off recipe items"
		);
	}
}
