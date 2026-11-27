package dev.jeasync.mixin.compat;

import java.util.List;
import java.util.Set;

import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Astral Sorcery tooltip guard only when Astral Sorcery is installed. Probing the mod
 * list avoids loading the target class from inside the mixin plugin, which would re-enter mixin
 * application.
 */
public final class AstralSorceryCompatMixinPlugin implements IMixinConfigPlugin {
	private static final String ASTRAL_SORCERY = "astralsorcery";

	private Boolean present;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (present == null) {
			try {
				present = ModList.get() != null && ModList.get().isLoaded(ASTRAL_SORCERY);
			} catch (Throwable ignored) {
				present = false;
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
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
