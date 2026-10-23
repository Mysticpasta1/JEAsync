package dev.jeasync.mixin.load;

import com.google.common.base.Preconditions;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.handlers.IScreenHandler;
import mezz.jei.common.collect.ListMultiMap;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.gui.GuiContainerHandlers;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;

/**
 * Keeps GUI handler registration intact when every plugin registers at once.
 * <p>
 * {@code GuiContainerHandlers} stores handlers in an {@code ArrayList} and looks the screen class
 * up with a stream. Two plugins registering click areas walk that list while the other appends,
 * and the stream throws {@code ConcurrentModificationException}. The screen-handler map and the
 * global-handler list are plain collections too.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.load.registration.GuiHandlerRegistration.class, remap = false)
public abstract class GuiHandlerRegistrationMixin {
	@Shadow
	private GuiContainerHandlers guiContainerHandlers;

	@Shadow
	private List<IGlobalGuiHandler> globalGuiHandlers;

	@Shadow
	private Map<Class<?>, IScreenHandler<?>> guiScreenHandlers;

	@Shadow
	private ListMultiMap<Class<?>, IGhostIngredientHandler<?>> ghostIngredientHandlers;

	@Shadow
	@Final
	private static List<Class<? extends Screen>> ghostIngredientGuiBlacklist;

	/**
	 * @author Mysticpasta1
	 * @reason Register container handlers under one lock so the handler list is not walked while it grows.
	 */
	@Overwrite
	public <T extends AbstractContainerScreen<?>> void addGuiContainerHandler(Class<? extends T> guiClass, IGuiContainerHandler<T> guiHandler) {
		dev.jeasync.MixinApplied.mark("load.GuiHandlerRegistrationMixin");
		ErrorUtil.checkNotNull(guiClass, "guiClass");
		ErrorUtil.checkNotNull(guiHandler, "guiHandler");
		synchronized (this) {
			this.guiContainerHandlers.add(guiClass, guiHandler);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Register generic container handlers under the same lock as the other container handlers.
	 */
	@Overwrite
	@SuppressWarnings("unchecked")
	public <T extends AbstractContainerScreen<?>> void addGenericGuiContainerHandler(Class<? extends T> guiClass, IGuiContainerHandler<?> guiHandler) {
		ErrorUtil.checkNotNull(guiClass, "guiClass");
		ErrorUtil.checkNotNull(guiHandler, "guiHandler");
		synchronized (this) {
			this.guiContainerHandlers.add(guiClass, (IGuiContainerHandler<? super T>) guiHandler);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Append global handlers under the same lock so the list is not torn.
	 */
	@Overwrite
	public void addGlobalGuiHandler(IGlobalGuiHandler globalGuiHandler) {
		ErrorUtil.checkNotNull(globalGuiHandler, "globalGuiHandler");
		synchronized (this) {
			this.globalGuiHandlers.add(globalGuiHandler);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Put screen handlers under the same lock so two plugins cannot lose a map entry.
	 */
	@Overwrite
	public <T extends Screen> void addGuiScreenHandler(Class<T> guiClass, IScreenHandler<T> handler) {
		ErrorUtil.checkNotNull(guiClass, "guiClass");
		Preconditions.checkArgument(Screen.class.isAssignableFrom(guiClass), "guiClass must inherit from Screen");
		Preconditions.checkArgument(!Screen.class.equals(guiClass), "you cannot add a handler for Screen, only a subclass.");
		ErrorUtil.checkNotNull(handler, "guiScreenHandler");
		synchronized (this) {
			this.guiScreenHandlers.put(guiClass, handler);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Register ghost-ingredient handlers under the same lock as the other GUI handlers.
	 */
	@Overwrite
	public <T extends Screen> void addGhostIngredientHandler(Class<T> guiClass, IGhostIngredientHandler<T> handler) {
		ErrorUtil.checkNotNull(guiClass, "guiClass");
		Preconditions.checkArgument(Screen.class.isAssignableFrom(guiClass), "guiClass must inherit from Screen");
		Preconditions.checkArgument(!ghostIngredientGuiBlacklist.contains(guiClass), "you cannot add a ghost ingredient handler for the following Guis, it would interfere with using JEI: %s", ghostIngredientGuiBlacklist);
		ErrorUtil.checkNotNull(handler, "handler");
		synchronized (this) {
			this.ghostIngredientHandlers.put(guiClass, handler);
		}
	}
}
