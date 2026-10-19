package dev.jeiasync.mixin.thread;

import dev.jeiasync.MixinApplied;
import net.minecraft.world.item.CreativeModeTab;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Serialises {@link CreativeModeTab#buildContents}.
 * <p>
 * JEI's vanilla plugin builds every creative tab while it registers ingredients, and other plugins
 * do the same from their own callbacks. The tab writes {@code displayItems} with no lock. Two
 * plugins building tabs at once lose items or throw {@code ConcurrentModificationException} from
 * inside a generator. One lock for every tab avoids that without depending on lock order between
 * tabs that call each other.
 */
@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabMixin {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Object JEI_ASYNC_BUILD_LOCK = new Object();
	private static final ThreadLocal<Boolean> JEI_ASYNC_BUILDING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	private static final Set<CreativeModeTab> LOGGED_BAD_STACKS = Collections.newSetFromMap(new IdentityHashMap<>());

	@Inject(method = "buildContents", at = @At("HEAD"), cancellable = true)
	private void jeiasync$serializeBuild(CreativeModeTab.ItemDisplayParameters parameters, CallbackInfo ci) {
		if (JEI_ASYNC_BUILDING.get()) {
			return;
		}
		MixinApplied.mark("thread.CreativeModeTabMixin");
		ci.cancel();
		synchronized (JEI_ASYNC_BUILD_LOCK) {
			JEI_ASYNC_BUILDING.set(Boolean.TRUE);
			try {
				((CreativeModeTab) (Object) this).buildContents(parameters);
			} catch (IllegalArgumentException ex) {
				if (ex.getMessage() == null || !ex.getMessage().contains("stack count must be 1")) {
					throw ex;
				}
				// Forge rejects the stack inside the tab generator. On the render thread that
				// exception escapes CreativeModeInventoryScreen and drops the client. JEI already
				// treats a throwing tab as "contents stop here" when it builds the ingredient list.
				CreativeModeTab tab = (CreativeModeTab) (Object) this;
				if (LOGGED_BAD_STACKS.add(tab)) {
					LOGGER.warn("JEI Async: creative tab \"{}\" accepted a stack whose count was not 1. Its contents stop there.", tab.getDisplayName().getString(), ex);
				}
			} finally {
				JEI_ASYNC_BUILDING.set(Boolean.FALSE);
			}
		}
	}
}
