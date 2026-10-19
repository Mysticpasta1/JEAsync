package dev.jeiasync.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

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
 * Connects JEI++ GUI hooks to the input targets JEI returns.
 * <p>
 * JEI++ is not a compile dependency. Every call into it goes through reflection and does nothing
 * when that mod is absent. The wrappers only allocate handlers; they do not touch the render thread's
 * ingredient list.
 */
public final class JeiPlusPlusBridge {
	private static final String CREATIVE_TAB_BAR = "com.lingmu0.JeiPlusPlusMod.client.CreativeTabBar";
	private static final String FEATURE_SOURCE = "com.lingmu0.JeiPlusPlusMod.client.IngredientListFeatureSource";
	private static final String RECIPE_TREE_OVERLAY = "com.lingmu0.JeiPlusPlusMod.client.RecipeTreeOverlay";
	private static final String RECIPE_TREE_SCREEN = "com.lingmu0.JeiPlusPlusMod.client.RecipeTreeScreen";
	private static final String RECIPE_TREE_SESSION = "com.lingmu0.JeiPlusPlusMod.client.RecipeTreeSession";
	private static final String CONFIG = "com.lingmu0.JeiPlusPlusMod.JeiPlusPlusConfig";

	private static volatile Method creativeClick;
	private static volatile Method creativeScroll;
	private static volatile Method creativeArea;
	private static volatile Method recipeTreeClick;
	private static volatile Method sessionClear;
	private static volatile Class<?> featureSourceType;
	private static volatile Class<?> recipeTreeScreenType;
	private static volatile boolean creativeReady;
	private static volatile boolean reflectionReady;

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
		prepareReflection();
		Method click = recipeTreeClick;
		if (click == null) {
			return null;
		}
		try {
			Object handled = click.invoke(null, recipeLayouts, user.getMouseX(), user.getMouseY(), user.getKey().getValue());
			if (!Boolean.TRUE.equals(handled)) {
				return null;
			}
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
		return Optional.of((IInputInteraction) ignored -> {
		});
	}

	private static boolean recipeTreeEnabled() {
		prepareReflection();
		try {
			Class<?> config = Class.forName(CONFIG);
			Object holder = config.getField("RECIPE_TREE_ENABLED").get(null);
			Object value = holder.getClass().getMethod("get").invoke(holder);
			return Boolean.TRUE.equals(value);
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
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

	private static void prepareReflection() {
		if (reflectionReady) {
			return;
		}
		synchronized (JeiPlusPlusBridge.class) {
			if (reflectionReady) {
				return;
			}
			featureSourceType = load(FEATURE_SOURCE);
			recipeTreeScreenType = load(RECIPE_TREE_SCREEN);
			Class<?> overlay = load(RECIPE_TREE_OVERLAY);
			recipeTreeClick = findMethod(overlay, "click", 4);
			Class<?> session = load(RECIPE_TREE_SESSION);
			sessionClear = findMethod(session, "clear", 0);
			Class<?> bar = load(CREATIVE_TAB_BAR);
			creativeArea = findMethod(bar, "getArea", 2);
			reflectionReady = true;
		}
	}

	private static void prepareCreativeTabs() {
		if (creativeReady) {
			return;
		}
		synchronized (JeiPlusPlusBridge.class) {
			if (creativeReady) {
				return;
			}
			prepareReflection();
			try {
				Class<?> bar = Class.forName(CREATIVE_TAB_BAR);
				for (Method method : bar.getDeclaredMethods()) {
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

	private static Class<?> load(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException ignored) {
			return null;
		}
	}

	private static Method findMethod(Class<?> type, String name, int parameters) {
		if (type == null) {
			return null;
		}
		for (Method method : type.getDeclaredMethods()) {
			if (name.equals(method.getName()) && method.getParameterCount() == parameters) {
				method.setAccessible(true);
				return method;
			}
		}
		for (Method method : type.getMethods()) {
			if (name.equals(method.getName()) && method.getParameterCount() == parameters) {
				method.setAccessible(true);
				return method;
			}
		}
		return null;
	}

	private static Object featureSource(Object grid) {
		prepareReflection();
		Class<?> type = featureSourceType;
		if (type == null) {
			return null;
		}
		try {
			Field field = findField(grid.getClass(), "ingredientSource");
			if (field == null) {
				return null;
			}
			Object value = field.get(grid);
			return type.isInstance(value) ? value : null;
		} catch (IllegalAccessException ignored) {
			return null;
		}
	}

	private static ImmutableRect2i gridArea(Object grid) {
		if (!(grid instanceof IngredientGridWithNavigation navigation)) {
			return ImmutableRect2i.EMPTY;
		}
		prepareReflection();
		Method area = creativeArea;
		if (area == null) {
			return ImmutableRect2i.EMPTY;
		}
		try {
			Object result = area.invoke(null, navigation.getBackgroundArea(), navigation.getBackButtonArea());
			return result instanceof ImmutableRect2i rect ? rect : ImmutableRect2i.EMPTY;
		} catch (ReflectiveOperationException ignored) {
			return ImmutableRect2i.EMPTY;
		}
	}

	private static boolean creativeClick(Object source, ImmutableRect2i area, UserInput input) {
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

	private static boolean creativeScroll(Object source, ImmutableRect2i area, double mouseX, double mouseY, double scrollY) {
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

	private static void clearSession() {
		prepareReflection();
		Method clear = sessionClear;
		if (clear == null) {
			return;
		}
		try {
			clear.invoke(null);
		} catch (ReflectiveOperationException ignored) {
			// The session class is absent or renamed. The screen close below still runs.
		}
	}

	private static boolean isRecipeTreeScreen(Object screen) {
		prepareReflection();
		Class<?> type = recipeTreeScreenType;
		return type != null && type.isInstance(screen);
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
			if (isRecipeTreeScreen(Minecraft.getInstance().screen)) {
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
			clearSession();
			if (isRecipeTreeScreen(screen)) {
				screen.onClose();
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
			Object source = featureSource(grid);
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
			Object source = featureSource(grid);
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
