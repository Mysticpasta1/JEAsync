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

		Common(ForgeConfigSpec.Builder builder) {
			threads = builder
				.comment("Worker threads. 0 uses the processor count, and at least 2.")
				.defineInRange("threads", 0, 0, 256);

			recipeRowCapacity = builder
				.comment(
					"Initial size of each per-ingredient recipe row.",
					"JEI starts these at 16, which rehashes the item row. 1 keeps that size."
				)
				.defineInRange("recipeRowCapacity", 512, 1, 1 << 20);
		}
	}
}
