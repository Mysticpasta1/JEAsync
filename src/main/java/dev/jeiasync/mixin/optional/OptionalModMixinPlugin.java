package dev.jeiasync.mixin.optional;

import java.util.List;
import java.util.Set;

import dev.jeiasync.compat.MekanismRecipeTypeLock;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Locks Mekanism's recipe-type registry while that class is being mixed in.
 * The constructor and {@code findType} have to share one lock, which an inject cannot wrap.
 */
public final class OptionalModMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
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
		if ("mekanism.client.jei.MekanismJEIRecipeType".equals(targetClassName)) {
			MekanismRecipeTypeLock.apply(targetClass);
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
