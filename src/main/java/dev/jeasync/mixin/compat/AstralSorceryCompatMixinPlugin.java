package dev.jeasync.mixin.compat;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Astral Sorcery tooltip guard only to Astral Sorcery classes.
 * <p>
 * This deliberately does not consult {@code ModList}. Mixin asks this question while the target
 * class is being defined, which can be before the mod list is safe to query; caching a false
 * answer there would disable the guard for the whole session even when Astral Sorcery is present.
 * A target class with this prefix only exists when the mod is installed, so the prefix is the
 * reliable test.
 */
public final class AstralSorceryCompatMixinPlugin implements IMixinConfigPlugin {
	private static final String ASTRAL_SORCERY_PACKAGE = "hellfirepvp.astralsorcery.";

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return targetClassName.startsWith(ASTRAL_SORCERY_PACKAGE);
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
