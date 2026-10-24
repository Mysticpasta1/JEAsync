package dev.jeasync;

import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config. Plugin registration always runs off the render thread.
 * <p>
 * A {@code -Djeasync.*} property overrides the file. See {@link AsyncConfig}.
 */
public final class JeAsyncConfig {
	public static final ForgeConfigSpec SPEC;
	public static final Common COMMON;

	static {
		ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		builder.comment(
			"Plugin registration runs off the render thread.",
			"A container opened before it finishes shows a progress bar.",
			"Changes apply after a restart."
		).push("loading");
		COMMON = new Common(builder);
		builder.pop();
		SPEC = builder.build();
	}

	private JeAsyncConfig() {
	}

	public static class Common {
		public final ForgeConfigSpec.IntValue threads;
		public final ForgeConfigSpec.BooleanValue deferFocusLinks;
		public final ForgeConfigSpec.BooleanValue skipAnvilEnchantRecipes;
		public final ForgeConfigSpec.BooleanValue skipAnvilRepairRecipes;
		public final ForgeConfigSpec.BooleanValue filterEmptyRecipes;
		public final ForgeConfigSpec.ConfigValue<List<? extends String>> skippedRecipeSerializers;
		public final ForgeConfigSpec.ConfigValue<List<? extends String>> dedupRecipeSerializers;
		public final ForgeConfigSpec.ConfigValue<List<? extends String>> skippedCategories;

		Common(ForgeConfigSpec.Builder builder) {
			threads = builder
				.comment("Worker threads. 0 uses the processor count, and at least 2.")
				.defineInRange("threads", 0, 0, 256);

			deferFocusLinks = builder
				.comment(
					"Focus links hold the per-slot ingredient lists JEI matches clicked items against.",
					"When this is on they are left out of recipe registration and are built once per recipe",
					"on its first visibility query, then cached. Unqueried recipes hold nothing.",
					"Recipe visibility results are unchanged."
				)
				.define("deferFocusLinks", true);

			skipAnvilEnchantRecipes = builder
				.comment(
					"JEI builds anvil recipes that pair every registered ingredient with every",
					"enchantment and level, the same cross product as the grindstone disenchant",
					"recipes. When this is on they are skipped; turning it off builds the",
					"enchanted-book results."
				)
				.define("skipAnvilEnchantRecipes", true);

			skipAnvilRepairRecipes = builder
				.comment(
					"JEI builds anvil repair recipes: vanilla's small fixed list of repairable",
					"pairs, the same kind of list the grindstone keeps. On by default so that, with",
					"the enchantment scan also skipped, the anvil category holds no recipes and hides",
					"itself; turning it off builds the repair pair list."
				)
				.define("skipAnvilRepairRecipes", true);

			filterEmptyRecipes = builder
				.comment(
					"JEI registers whatever a plugin put into the layout, including rows that carry no",
					"input, no catalyst and no output. Air counts as nothing here: JEI cannot hold",
					"an air item, so an ingredient slot the plugin fed only with air ends up with an",
					"empty list, and the row is an empty picture that no click can look up with.",
					"A recipe is dropped only when every role is empty. An empty output role alone",
					"is not enough: it may hold fluids or slots the category draws itself, so",
					"recipes that declare no output slot at all (fueling, compost, ingredient info)",
					"are kept.",
					"When this is on such rows are not registered; turning it off builds them again."
				)
				.define("filterEmptyRecipes", true);

			skippedRecipeSerializers = builder
				.comment(
					"JEI recipes whose underlying vanilla recipe has one of these recipe serializer",
					"ids are dropped at registration: JEI builds no rows, no focus links and no",
					"ingredient suppliers for them, and a category left with no visible recipes",
					"hides itself.",
					"Tinkers' Construct's tipped-tool table is no longer listed here by default: it is",
					"now collapsed to one recipe per effect by dedupRecipeSerializers instead of",
					"dropped whole. Add a serializer id here to drop it entirely instead.",
					"Each entry is a recipe serializer id with namespace, such as",
					"tconstruct:tipped_tool_transform. Only recipes that are vanilla Recipe objects",
					"are matched; purely synthetic JEI recipes are never filtered."
				)
				.defineListAllowEmpty(
					List.of("skippedRecipeSerializers"),
					List.of(),
					o -> o instanceof String && ((String) o).contains(":")
				);

			dedupRecipeSerializers = builder
				.comment(
					"JEI recipes whose underlying vanilla recipe has one of these recipe serializer",
					"ids are kept but collapsed to one recipe per effect, so the category shows its",
					"real recipes without the redundant cross product.",
					"TippedToolTransformRecipe is a datapack recipe: packs and add-ons write one per",
					"tool layout and material, so a modded pack carries dozens to hundreds that share",
					"a handful of tipped effects. This keeps one recipe per effect and drops the",
					"duplicate copies, like the anvil keeps its repair list and drops only",
					"the redundant enchantment copies.",
					"The effect is whichever modifier-, enchantment- or affix-typed field the recipe",
					"class declares, so Apothic and Apotheosis affix serializers added here collapse",
					"to one recipe per affix the same way.",
					"Each entry is a recipe serializer id with namespace. Only vanilla Recipe objects",
					"are matched; synthetic JEI recipes pass through untouched."
				)
				.defineListAllowEmpty(
					List.of("dedupRecipeSerializers"),
					List.of("tconstruct:tipped_tool_transform"),
					o -> o instanceof String && ((String) o).contains(":")
				);

			skippedCategories = builder
				.comment(
					"JEI recipe category ids whose registration is dropped entirely. This is the",
					"category-level matching skippedRecipeSerializers cannot do: many mod plugins",
					"hand JEI synthetic recipe objects that are not vanilla Recipe, so no serializer",
					"can be read, but every recipe in one of these categories is still dropped from",
					"its registration call.",
					"The drop lands where the add-on sees the recipes, so the skip is free of rows,",
					"focus links and catalysts, but the plugin still pays its own generation cost.",
					"A category left with no recipes hides itself.",
					"Each entry is a category id with namespace, such as irons_spellbooks:arcane_anvil."
				)
				.defineListAllowEmpty(
					List.of("skippedCategories"),
					List.of(),
					o -> o instanceof String && ((String) o).contains(":")
				);
		}
	}
}
