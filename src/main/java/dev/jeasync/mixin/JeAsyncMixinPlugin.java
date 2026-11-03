package dev.jeasync.mixin;

import java.util.List;
import java.util.Set;

import dev.jeasync.compat.ErrorUtilBridge;
import dev.jeasync.compat.JeiRemovedTypeRewrite;
import dev.jeasync.compat.SearchFieldBridge;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Installs the JEI 15.71 type rewrite before Mixin prepares other mods' mixin classes.
 */
public final class JeAsyncMixinPlugin implements IMixinConfigPlugin {
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
		// This mod pokes inside JEI's GUI and the client's level fields. On a dedicated server
		// those targets do not exist in the shape this mod was compiled against, and every
		// injection there is dead weight, so each mixin opts out and the mod stays inert.
		return !FMLEnvironment.dist.isDedicatedServer();
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
		SearchFieldBridge.prepare(targetClass);
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
