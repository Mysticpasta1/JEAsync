package dev.jeiasync.mixin.compat;

import dev.jeiasync.compat.JeiRemovedTypeRewrite;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the JEI++ compatibility mixins only when that mod is installed.
 * The probe is the mod class, not a mixin target: loading a target from here re-enters mixin application.
 */
public final class JeiPlusPlusMixinPlugin implements IMixinConfigPlugin {
	private static final String JEI_PLUS_PLUS = "com.lingmu0.JeiPlusPlusMod.JeiPlusPlus";

	private boolean resolved;
	private boolean present;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!resolved) {
			resolved = true;
			try {
				Class.forName(JEI_PLUS_PLUS, false, JeiPlusPlusMixinPlugin.class.getClassLoader());
				present = true;
			} catch (ClassNotFoundException notLoaded) {
				present = false;
			} catch (Throwable ignored) {
				present = true;
			}
		}
		return present;
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
		if (JeiRemovedTypeRewrite.isGameClass(targetClassName)) {
			JeiRemovedTypeRewrite.apply(targetClass);
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
