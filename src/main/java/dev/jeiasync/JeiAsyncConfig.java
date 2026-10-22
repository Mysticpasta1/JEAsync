package dev.jeiasync;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config. Plugin registration always runs off the render thread.
 * <p>
 * A {@code -Djeiasync.*} property overrides the file. See {@link AsyncConfig}.
 */
public final class JeiAsyncConfig {
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

	private JeiAsyncConfig() {
	}

	public static class Common {
		public final ForgeConfigSpec.IntValue threads;
		public final ForgeConfigSpec.IntValue recipeRowCapacity;
		public final ForgeConfigSpec.BooleanValue deferFocusLinks;

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
		}
	}
}
