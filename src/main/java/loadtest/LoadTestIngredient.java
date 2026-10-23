package loadtest;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A synthetic ingredient type, used to make JEI's search index build a measurable size.
 * <p>
 * The index cost scales with (ingredients x prefixes). JEI's built-in item type is bounded by
 * however many items the test pack happens to contain, which is not a dial that can be turned;
 * registering a synthetic type makes the cost a function of a system property instead.
 * <p>
 * Registered through {@code registerIngredients}, which is the phase JEI logs, so the effect is
 * visible in the same per-phase timings the registration measurements use.
 */
public final class LoadTestIngredient {
	private LoadTestIngredient() {
	}

	public static final IIngredientType<String> TYPE = new IIngredientType<>() {
		@Override
		public String getUid() {
			return "jeasync_loadtest";
		}

		@Override
		public Class<? extends String> getIngredientClass() {
			return String.class;
		}
	};

	public static final IIngredientHelper<String> HELPER = new IIngredientHelper<>() {
		@Override
		public IIngredientType<String> getIngredientType() {
			return TYPE;
		}

		@Override
		public String getDisplayName(String ingredient) {
			return "Synthetic Load Test Ingredient " + ingredient;
		}

		@Override
		public String getUniqueId(String ingredient, UidContext context) {
			return "jeasync_loadtest:" + ingredient;
		}

		@Override
		public ResourceLocation getResourceLocation(String ingredient) {
			return new ResourceLocation("jeasync_loadtest", "synthetic");
		}

		@Override
		public String getErrorInfo(String ingredient) {
			return "";
		}

		@Override
		public String copyIngredient(String ingredient) {
			return ingredient;
		}
	};

	public static final IIngredientRenderer<String> RENDERER = new IIngredientRenderer<>() {
		@Override
		public void render(GuiGraphics guiGraphics, String ingredient) {
			// Never drawn: the load test does not open the ingredient list.
		}

		@Override
		public List<Component> getTooltip(String ingredient, TooltipFlag tooltipFlag) {
			return List.of();
		}
	};

	/**
	 * Distinct strings, because the ingredient set keys on the ingredient itself and identical
	 * values would collapse and leave the index artificially small.
	 */
	public static Collection<String> create(int count, int startId) {
		List<String> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			list.add("synthetic_" + (startId + i));
		}
		return list;
	}
}