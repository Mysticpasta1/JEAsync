package dev.jeaisync.mixin.collect;

import mezz.jei.library.ingredients.itemStacks.TypedItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private {@code clearCache} added to {@link TypedItemStack}.
 * <p>
 * The method itself has to be private: Mixin refuses to add a non-private method to a target
 * ("contains non-private static method"). That makes it unreachable from outside the class, so a
 * static {@link Invoker} is used as the public entry point. Static invokers are supported for
 * static target methods, so this needs no cast and no per-instance access.
 */
@Mixin(TypedItemStack.class)
public interface TypedItemStackInvoker {
	@Invoker("clearCache")
	static void jeiAsyncClearCache() {
		throw new AssertionError();
	}
}