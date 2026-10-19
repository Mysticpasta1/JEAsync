package dev.jeiasync.mixin;

import java.util.List;
import java.util.Set;

import dev.jeiasync.compat.ErrorUtilBridge;
import dev.jeiasync.compat.JeiRemovedTypeRewrite;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Installs the JEI 15.71 type rewrite before Mixin prepares other mods' mixin classes.
 */
public final class JeiAsyncMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		JeiRemovedTypeRewrite.install();
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
		JeiRemovedTypeRewrite.install();
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		ErrorUtilBridge.prepare(targetClass);
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
