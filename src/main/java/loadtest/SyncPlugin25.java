package loadtest;

import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public class SyncPlugin25 implements dev.jeiasync.api.IAsyncCompatiblePlugin {
	public static final int WORK_MS = Integer.getInteger("loadtest.workMs", 20);

	@Override
	public boolean canExecuteAsync() {
		return false;
	}

	@Override
	public ResourceLocation getPluginUid() {
		return new ResourceLocation("loadtest", "sync25");
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		// Stand-in for the per-recipe work a real plugin does: enough CPU to dominate
		// its share of the registration phase.
		long deadline = System.nanoTime() + (WORK_MS * 1_000_000L);
		double sink = 0;
		while (System.nanoTime() < deadline) {
			for (int i = 0; i < 20_000; i++) {
				sink += Math.sqrt(i + 25);
			}
		}
		if (sink < 0) {
			throw new IllegalStateException();
		}
	}
}
