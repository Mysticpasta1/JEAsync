package dev.jeasync.mixin.thread;

import dev.jeasync.MixinApplied;
import dev.jeasync.WorkerEnvironment;
import net.minecraftforge.eventbus.EventSubclassTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forge builds event subclasses by loading the parent class through the <em>context</em> classloader.
 * A worker whose context loader is the application loader cannot see mod or Minecraft classes, logs
 * "Could not find parent", and the event handler is silently never built.
 * <p>
 * When the context loader cannot see the game, the parent is loaded through the game loader instead.
 * Threads that already have the transforming loader are left alone.
 */
@Mixin(value = EventSubclassTransformer.class, remap = false)
public class EventSubclassTransformerMixin {
	private static boolean marked;

	@Redirect(
		method = "getClassLoader",
		at = @At(value = "INVOKE", target = "Ljava/lang/Thread;getContextClassLoader()Ljava/lang/ClassLoader;"),
		remap = false
	)
	private ClassLoader jeasync$gameLoader(Thread thread) {
		if (!marked) {
			marked = true;
			MixinApplied.mark("thread.EventSubclassTransformerMixin");
		}
		return WorkerEnvironment.loaderForEvents(thread.getContextClassLoader());
	}
}
