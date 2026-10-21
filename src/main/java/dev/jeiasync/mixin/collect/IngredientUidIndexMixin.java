package dev.jeiasync.mixin.collect;

import dev.jeiasync.IngredientUidIndexCalls;
import mezz.jei.api.ingredients.IIngredientType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Locks the per-ingredient recipe rows while several plugins fill one index.
 * <p>
 * {@code get}, {@code forEach}, and both {@code compute*IfAbsent} methods share one lock for the
 * whole call. The row map and each fastutil row throw if they are updated together. The collection
 * a compute stores is safe to {@code add} to afterwards, which is what the callers do once the
 * method has returned.
 * <p>
 * Mysticpasta1 — this mod no longer builds those rows itself. JEI allocates the row map.
 * <p>
 * The target is named as a string because {@code IngredientUidIndex} and {@code MatchBuckets} are
 * package-private. Compiling a mixin into JEI's own package does reach them, and it does work as a
 * compile target, but it makes the mod jar export a package the JEI jar already exports and
 * ModLauncher rejects the launch outright:
 * {@code java.lang.module.ResolutionException: Modules jei and jeiasync export package
 * mezz.jei.library.recipes.collect to module minecraft}.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(targets = "mezz.jei.library.recipes.collect.IngredientUidIndex", remap = false)
public abstract class IngredientUidIndexMixin {
	@Unique
	private volatile ReentrantLock jeiasync$registrationLock;

	@Unique
	private ReentrantLock jeiasync$lock() {
		ReentrantLock lock = this.jeiasync$registrationLock;
		if (lock != null) {
			return lock;
		}
		synchronized (this) {
			lock = this.jeiasync$registrationLock;
			if (lock == null) {
				lock = new ReentrantLock();
				this.jeiasync$registrationLock = lock;
			}
			return lock;
		}
	}

	@Inject(method = "get(Lmezz/jei/api/ingredients/IIngredientType;Ljava/lang/Object;)Lmezz/jei/library/recipes/collect/MatchBuckets;", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$lockGet(IIngredientType<?> ingredientType, Object uid, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.getOne();
		if (handle == null) {
			return;
		}
		jeiasync$guard(cir, handle, new Object[] { ingredientType, uid });
	}

	@Inject(method = "get(Lmezz/jei/api/ingredients/IIngredientType;Ljava/lang/Object;Ljava/lang/Object;)Lmezz/jei/library/recipes/collect/MatchBuckets;", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$lockGetPair(IIngredientType<?> ingredientType, Object exactUid, Object groupingUid, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.getPair();
		if (handle == null) {
			return;
		}
		jeiasync$guard(cir, handle, new Object[] { ingredientType, exactUid, groupingUid });
	}

	@Inject(method = "computeExactIfAbsent", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$lockComputeExact(IIngredientType<?> ingredientType, Object uid, Supplier<?> valueSupplier, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.computeExact();
		if (handle == null) {
			return;
		}
		jeiasync$guard(cir, handle, new Object[] { ingredientType, uid, IngredientUidIndexCalls.guarding(valueSupplier) });
	}

	@Inject(method = "computeGroupingIfAbsent", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$lockComputeGrouping(IIngredientType<?> ingredientType, Object uid, Supplier<?> valueSupplier, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.computeGrouping();
		if (handle == null) {
			return;
		}
		jeiasync$guard(cir, handle, new Object[] { ingredientType, uid, IngredientUidIndexCalls.guarding(valueSupplier) });
	}

	@Inject(method = "forEach(Lmezz/jei/library/recipes/collect/IngredientUidIndex$EntryConsumer;)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$lockForEach(@Coerce Object consumer, CallbackInfo ci) {
		MethodHandle handle = IngredientUidIndexCalls.forEach();
		if (handle == null) {
			return;
		}
		jeiasync$guardVoid(ci, handle, new Object[] { consumer });
	}

	/**
	 * Ordinary methods, not lambdas. A lambda's bootstrap handle points at this mixin class.
	 * Mixin registers that class as unloadable, so the merged index then fails with
	 * {@code IngredientUidIndexMixin is invalid}.
	 */
	@Unique
	private void jeiasync$guard(CallbackInfoReturnable<Object> cir, MethodHandle handle, Object[] args) {
		ReentrantLock lock = jeiasync$lock();
		if (lock.isHeldByCurrentThread()) {
			return;
		}
		dev.jeiasync.MixinApplied.mark("collect.IngredientUidIndexMixin");
		lock.lock();
		try {
			cir.setReturnValue(jeiasync$invoke(handle, args));
		} catch (Throwable throwable) {
			cir.cancel();
			IngredientUidIndexCalls.rethrow(throwable);
		} finally {
			lock.unlock();
		}
	}

	@Unique
	private void jeiasync$guardVoid(CallbackInfo ci, MethodHandle handle, Object[] args) {
		ReentrantLock lock = jeiasync$lock();
		if (lock.isHeldByCurrentThread()) {
			return;
		}
		dev.jeiasync.MixinApplied.mark("collect.IngredientUidIndexMixin");
		lock.lock();
		try {
			jeiasync$invoke(handle, args);
			ci.cancel();
		} catch (Throwable throwable) {
			ci.cancel();
			IngredientUidIndexCalls.rethrow(throwable);
		} finally {
			lock.unlock();
		}
	}

	@Unique
	private Object jeiasync$invoke(MethodHandle handle, Object[] args) throws Throwable {
		Object[] full = new Object[args.length + 1];
		full[0] = this;
		System.arraycopy(args, 0, full, 1, args.length);
		return handle.invokeWithArguments(full);
	}
}