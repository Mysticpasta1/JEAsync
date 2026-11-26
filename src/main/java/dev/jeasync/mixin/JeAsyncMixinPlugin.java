package dev.jeasync.mixin;

import java.util.List;
import java.util.Set;

import dev.jeasync.compat.LocalSlotFixer;
import net.neoforged.fml.loading.FMLEnvironment;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the local-slot fixup before Mixin merges each target.
 * <p>
 * JEI's published builds use an optimizer that reuses argument slots for primitives inside render,
 * input and plugin methods. A mixin callback that reloads those slots then fails verification.
 * {@link LocalSlotFixer} rewrites the affected method bodies before Mixin applies its injectors.
 */
public final class JeAsyncMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// This mod pokes inside JEI's GUI and the client's level fields. On a dedicated server
		// those targets do not exist in the shape this mod was compiled against, and every
		// injection there is dead weight, so each mixin opts out and the mod stays inert.
		return !FMLEnvironment.dist.isDedicatedServer();
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		LocalSlotFixer.prepare(targetClass);
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
