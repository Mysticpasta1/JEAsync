package loadtest;

import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;

/**
 * Stands in for a plugin that cannot run off the main thread and does not implement
 * IAsyncCompatiblePlugin to say so - the case the all-plugins-async default has to survive.
 * <p>
 * Real plugins discover this by touching main-thread-only state and getting an exception; that is
 * not available here because {@code Minecraft} is client-only and this jar is loaded on the
 * dedicated server too. Detecting the worker thread by name is the honest equivalent for a
 * synthetic plugin: it is the same signal, produced without the crash.
 * <p>
 * Expected log output: an error from the off-thread attempt, then "Re-running 1 plugin(s) on the
 * calling thread", then this plugin running inline in every later phase with no further attempts.
 */
@JeiPlugin
public class OffThreadUnsafePlugin implements mezz.jei.api.IModPlugin {
	private static final String WORKER_THREAD_PREFIX = "JEI Async Plugin Loader";

	@Override
	public ResourceLocation getPluginUid() {
		return ResourceLocation.fromNamespaceAndPath("loadtest", "off_thread_unsafe");
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		assertMainThread("registerRecipes");
	}

	@Override
	public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
		// A second phase, so the demotion is visible as the plugin running inline every time
		// rather than recovering only once.
		assertMainThread("registerRecipeCatalysts");
	}

	private static void assertMainThread(String what) {
		if (Thread.currentThread().getName().startsWith(WORKER_THREAD_PREFIX)) {
			throw new IllegalStateException(what + " called off the main thread");
		}
	}
}