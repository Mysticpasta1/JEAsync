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
		public final ForgeConfigSpec.IntValue recipeRowCapacity;
		public final ForgeConfigSpec.BooleanValue deferFocusLinks;
		public final ForgeConfigSpec.IntValue grindstoneDisenchantLevels;
		public final ForgeConfigSpec.BooleanValue skipGrindstoneRepairRecipes;
		public final ForgeConfigSpec.BooleanValue skipAnvilEnchantRecipes;
		public final ForgeConfigSpec.BooleanValue skipAnvilRepairRecipes;
		public final ForgeConfigSpec.ConfigValue<List<? extends String>> skippedRecipeSerializers;

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

			recipeRowCapacity = builder
				.comment(
					"Kept so existing config files still load.",
					"Recipe rows are JEI's own maps. This value is not applied."
				)
				.defineInRange("recipeRowCapacity", 512, 1, 1 << 20);

			grindstoneDisenchantLevels = builder
				.comment(
					"JEI builds a disenchant recipe for every enchantment, item and level, up to 10.",
					"Because a crash in one mod's enchantment took those recipes down wholesale, JEI",
					"no longer stops early, and a pack that never saw them before now holds the full",
					"cross product: items x enchantments x levels.",
					"Levels above 1 repeat the same inputs and the same plain-item result, so they",
					"multiply recipes and heap without adding information. This is the largest single",
					"heap cost JEI builds in a big pack.",
					"1 keeps one recipe per enchantment.",
					"-1 restores JEI's own behaviour (all levels up to 10).",
					"0 removes the disenchant recipes; the grindstone category hides itself if the",
					"repair recipes do not cover it."
				)
				.defineInRange("grindstoneDisenchantLevels", 0, -1, 255);

			skipGrindstoneRepairRecipes = builder
				.comment(
					"JEI builds grindstone repair recipes: the two-slot empty-result pairs, one per",
					"repairable item. When this is on they are skipped, matching the other",
					"grindstone recipes the add-on filters; turning it off builds them."
				)
				.define("skipGrindstoneRepairRecipes", true);

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
					"pairs. On by default so the other anvil and grindstone filters behave the",
					"same way; turning it off builds them."
				)
				.define("skipAnvilRepairRecipes", true);

			skippedRecipeSerializers = builder
				.comment(
					"JEI recipes whose underlying vanilla recipe has one of these recipe serializer",
					"ids are dropped at registration: JEI builds no rows, no focus links and no",
					"ingredient suppliers for them, and a category left with no visible recipes",
					"hides itself.",
					"TippedToolTransformRecipe, Tinkers' Construct's tipped-tool table, is a",
					"datapack recipe: packs and add-ons write one per tool layout they cover, so",
					"a modded pack carries dozens to hundreds, and every check is crash-protected",
					"now, so they all register.",
					"Each entry is a recipe serializer id with namespace, such as",
					"tconstruct:tipped_tool_transform. Only recipes that are vanilla Recipe objects",
					"are matched; purely synthetic JEI recipes are never filtered."
				)
				.defineListAllowEmpty(
					List.of("skippedRecipeSerializers"),
					List.of("tconstruct:tipped_tool_transform"),
					o -> o instanceof String && ((String) o).contains(":")
				);
		}
	}
}
