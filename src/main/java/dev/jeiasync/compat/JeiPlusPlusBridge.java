package dev.jeiasync.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig;
import com.lingmu0.JeiPlusPlusMod.client.CreativeTabBar;
import com.lingmu0.JeiPlusPlusMod.client.IngredientListFeatureSource;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeOverlay;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeScreen;
import com.lingmu0.JeiPlusPlusMod.client.RecipeTreeSession;
import mezz.jei.common.input.IInputTarget;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.input.UserInput;
import mezz.jei.common.input.handlers.InputGroup;
import mezz.jei.common.input.interaction.IInputInteraction;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Connects JEI++ 1.0.7 GUI hooks to the input targets JEI 15.71 actually returns.
 * Forge builds those screens on a JEI Async thread. The wrappers only allocate handlers there.
 */
public final class JeiPlusPlusBridge {
	private static volatile Method creativeClick;
	private static volatile Method creativeScroll;
	private static volatile boolean creativeReady;

	private JeiPlusPlusBridge() {
	}

	public static IInputTarget wrapBookmark(Object button, IInputTarget original, Object overlay) {
		if (!(button instanceof LegacyToggleButton toggle) || original == null) {
			return original;
		}
		IInputTarget treeButton = toggle.createInputHandler();
		IInputTarget rightClick = new TreeRightClick(toggle);
		IInputTarget tree = new InputGroup("JeiPlusPlusRecipeTreeButton", rightClick, treeButton);
		IInputTarget both = new InputGroup("JeiPlusPlusRecipeTreeAndBookmarks", tree, original);
		return new ChoosingTarget(overlay, original, tree, both);
	}

	public static IInputTarget wrapGrid(Object grid, IInputTarget original) {
		if (original == null) {
			return null;
		}
		return new CreativeTabTarget(grid, original);
	}

	public static Object recipeTreePress(Object layouts, Object input) {
		if (!(layouts instanceof RecipeGuiLayouts recipeLayouts) || !(input instanceof UserInput user)) {
			return null;
		}
		if (!user.isMouseInput() || !user.isSimulate()) {
			return null;
		}
		if (!RecipeTreeOverlay.click(recipeLayouts, user.getMouseX(), user.getMouseY(), user.getKey().getValue())) {
			return null;
		}
		return Optional.of((IInputInteraction) ignored -> {
		});
	}

	private static boolean recipeTreeEnabled() {
		Object value = JeiPlusPlusConfig.RECIPE_TREE_ENABLED.get();
		return value instanceof Boolean enabled && enabled;
	}

	private static boolean hasValidScreen(Object overlay) {
		try {
			Field field = findField(overlay.getClass(), "guiPropertiesCache");
			if (field == null) {
				return false;
			}
			Object cache = field.get(overlay);
			if (cache == null) {
				return false;
			}
			Object valid = cache.getClass().getMethod("hasValidScreen").invoke(cache);
			return Boolean.TRUE.equals(valid);
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static Field findField(Class<?> type, String name) {
		Class<?> current = type;
		while (current != null && current != Object.class) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		return null;
	}

	private static void prepareCreativeTabs() {
		if (creativeReady) {
			return;
		}
		synchronized (JeiPlusPlusBridge.class) {
			if (creativeReady) {
				return;
			}
			try {
				for (Method method : CreativeTabBar.class.getDeclaredMethods()) {
					if ("handleClick".equals(method.getName()) && method.getParameterCount() == 3) {
						method.setAccessible(true);
						creativeClick = method;
					} else if ("handleScroll".equals(method.getName()) && method.getParameterCount() == 6) {
						method.setAccessible(true);
						creativeScroll = method;
					}
				}
			} catch (Throwable ignored) {
				creativeClick = null;
				creativeScroll = null;
			}
			creativeReady = true;
		}
	}

	private static IngredientListFeatureSource featureSource(Object grid) {
		try {
			Field field = findField(grid.getClass(), "ingredientSource");
			if (field == null) {
				return null;
			}
			Object value = field.get(grid);
			return value instanceof IngredientListFeatureSource source ? source : null;
		} catch (IllegalAccessException ignored) {
			return null;
		}
	}

	private static ImmutableRect2i gridArea(Object grid) {
		if (!(grid instanceof IngredientGridWithNavigation navigation)) {
			return ImmutableRect2i.EMPTY;
		}
		return CreativeTabBar.getArea(navigation.getBackgroundArea(), navigation.getBackButtonArea());
	}

	private static boolean creativeClick(IngredientListFeatureSource source, ImmutableRect2i area, UserInput input) {
		prepareCreativeTabs();
		Method method = creativeClick;
		if (method == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(method.invoke(null, source, area, input));
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static boolean creativeScroll(IngredientListFeatureSource source, ImmutableRect2i area, double mouseX, double mouseY, double scrollY) {
		prepareCreativeTabs();
		Method method = creativeScroll;
		if (method == null || scrollY == 0) {
			return false;
		}
		try {
			Object result = method.invoke(null, source, area, mouseX, mouseY, scrollY, Boolean.TRUE);
			return result instanceof Optional<?> handled && handled.isPresent();
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static final class ChoosingTarget implements IInputTarget {
		private final Object overlay;
		private final IInputTarget original;
		private final IInputTarget tree;
		private final IInputTarget both;

		private ChoosingTarget(Object overlay, IInputTarget original, IInputTarget tree, IInputTarget both) {
			this.overlay = overlay;
			this.original = original;
			this.tree = tree;
			this.both = both;
		}

		@Override
		public Optional<IInputInteraction> beginInput(Screen screen, UserInput input, IInternalKeyMappings keys) {
			return choose().beginInput(screen, input, keys);
		}

		@Override
		public boolean scroll(double mouseX, double mouseY, double scrollX, double scrollY) {
			return choose().scroll(mouseX, mouseY, scrollX, scrollY);
		}

		@Override
		public void resetInput() {
			choose().resetInput();
		}

		private IInputTarget choose() {
			if (!recipeTreeEnabled()) {
				return original;
			}
			if (Minecraft.getInstance().screen instanceof RecipeTreeScreen) {
				return tree;
			}
			return hasValidScreen(overlay) ? both : original;
		}
	}

	private static final class TreeRightClick implements IInputTarget {
		private final LegacyToggleButton button;

		private TreeRightClick(LegacyToggleButton button) {
			this.button = button;
		}

		@Override
		public Optional<IInputInteraction> beginInput(Screen screen, UserInput input, IInternalKeyMappings keys) {
			if (!input.isMouseInput() || input.getKey().getValue() != 1) {
				return Optional.empty();
			}
			if (!button.isMouseOver(input.getMouseX(), input.getMouseY())) {
				return Optional.empty();
			}
			if (input.isSimulate()) {
				return Optional.of(execute -> clear(screen, execute));
			}
			clear(screen, input);
			return Optional.of(execute -> {
			});
		}

		private static void clear(Screen screen, UserInput input) {
			if (input.isSimulate()) {
				return;
			}
			RecipeTreeSession.clear();
			if (screen instanceof RecipeTreeScreen treeScreen) {
				treeScreen.onClose();
			}
		}
	}

	private static final class CreativeTabTarget implements IInputTarget {
		private final Object grid;
		private final IInputTarget original;

		private CreativeTabTarget(Object grid, IInputTarget original) {
			this.grid = grid;
			this.original = original;
		}

		@Override
		public Optional<IInputInteraction> beginInput(Screen screen, UserInput input, IInternalKeyMappings keys) {
			IngredientListFeatureSource source = featureSource(grid);
			ImmutableRect2i area = gridArea(grid);
			if (source != null && creativeClick(source, area, input)) {
				return Optional.of(execute -> {
					if (input.isSimulate()) {
						creativeClick(source, area, execute);
					}
				});
			}
			return original.beginInput(screen, input, keys);
		}

		@Override
		public boolean scroll(double mouseX, double mouseY, double scrollX, double scrollY) {
			IngredientListFeatureSource source = featureSource(grid);
			if (source != null && creativeScroll(source, gridArea(grid), mouseX, mouseY, scrollY)) {
				return true;
			}
			return original.scroll(mouseX, mouseY, scrollX, scrollY);
		}

		@Override
		public void resetInput() {
			original.resetInput();
		}
	}
}
