package dev.jeasync.mixin.collect;

import dev.jeasync.IngredientUidIndexCalls;
import dev.jeasync.IngredientUidIndexCalls.Call;
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
 * {@code java.lang.module.ResolutionException: Modules jei and jeasync export package
 * mezz.jei.library.recipes.collect to module minecraft}.
 * <p>
 * Targets upstream 19.76.1.481.
 */
@Mixin(targets = "mezz.jei.library.recipes.collect.IngredientUidIndex", remap = false)
public abstract class IngredientUidIndexMixin {
	@Unique
	private volatile ReentrantLock jeasync$registrationLock;

	@Unique
	private ReentrantLock jeasync$lock() {
		ReentrantLock lock = this.jeasync$registrationLock;
		if (lock != null) {
			return lock;
		}
		synchronized (this) {
			lock = this.jeasync$registrationLock;
			if (lock == null) {
				lock = new ReentrantLock();
				this.jeasync$registrationLock = lock;
			}
			return lock;
		}
	}

	@Inject(method = "get(Lmezz/jei/api/ingredients/IIngredientType;Ljava/lang/Object;)Lmezz/jei/library/recipes/collect/MatchBuckets;", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$lockGet(IIngredientType<?> ingredientType, Object uid, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.getOne();
		if (handle == null || jeasync$lock().isHeldByCurrentThread()) {
			return;
		}
		jeasync$guard(cir, IngredientUidIndexCalls.getCall(this, handle, ingredientType, uid));
	}

	@Inject(method = "get(Lmezz/jei/api/ingredients/IIngredientType;Ljava/lang/Object;Ljava/lang/Object;)Lmezz/jei/library/recipes/collect/MatchBuckets;", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$lockGetPair(IIngredientType<?> ingredientType, Object exactUid, Object groupingUid, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.getPair();
		if (handle == null || jeasync$lock().isHeldByCurrentThread()) {
			return;
		}
		jeasync$guard(cir, IngredientUidIndexCalls.getPairCall(this, handle, ingredientType, exactUid, groupingUid));
	}

	@Inject(method = "computeExactIfAbsent", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$lockComputeExact(IIngredientType<?> ingredientType, Object uid, Supplier<?> valueSupplier, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.computeExact();
		if (handle == null || jeasync$lock().isHeldByCurrentThread()) {
			return;
		}
		jeasync$guard(cir, IngredientUidIndexCalls.computeCall(this, handle, ingredientType, uid, IngredientUidIndexCalls.guarding(valueSupplier)));
	}

	@Inject(method = "computeGroupingIfAbsent", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$lockComputeGrouping(IIngredientType<?> ingredientType, Object uid, Supplier<?> valueSupplier, CallbackInfoReturnable<Object> cir) {
		MethodHandle handle = IngredientUidIndexCalls.computeGrouping();
		if (handle == null || jeasync$lock().isHeldByCurrentThread()) {
			return;
		}
		jeasync$guard(cir, IngredientUidIndexCalls.computeCall(this, handle, ingredientType, uid, IngredientUidIndexCalls.guarding(valueSupplier)));
	}

	@Inject(method = "forEach(Lmezz/jei/library/recipes/collect/IngredientUidIndex$EntryConsumer;)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$lockForEach(@Coerce Object consumer, CallbackInfo ci) {
		MethodHandle handle = IngredientUidIndexCalls.forEach();
		if (handle == null || jeasync$lock().isHeldByCurrentThread()) {
			return;
		}
		jeasync$guardVoid(ci, IngredientUidIndexCalls.forEachCall(this, handle, consumer));
	}

	/**
	 * Ordinary methods, not lambdas. A lambda's bootstrap handle points at this mixin class.
	 * Mixin registers that class as unloadable, so the merged index then fails with
	 * {@code IngredientUidIndexMixin is invalid}. The {@link Call} values are created in
	 * {@link IngredientUidIndexCalls}, which is outside the mixin package, and use typed
	 * {@code MethodHandle.invokeExact} calls rather than rebuilding an argument array per
	 * index lookup.
	 */
	@Unique
	private void jeasync$guard(CallbackInfoReturnable<Object> cir, Call call) {
		ReentrantLock lock = jeasync$lock();
		if (lock.isHeldByCurrentThread()) {
			return;
		}
		dev.jeasync.MixinApplied.mark("collect.IngredientUidIndexMixin");
		lock.lock();
		try {
			cir.setReturnValue(call.run());
		} catch (Throwable throwable) {
			cir.cancel();
			IngredientUidIndexCalls.rethrow(throwable);
		} finally {
			lock.unlock();
		}
	}

	@Unique
	private void jeasync$guardVoid(CallbackInfo ci, Call call) {
		ReentrantLock lock = jeasync$lock();
		if (lock.isHeldByCurrentThread()) {
			return;
		}
		dev.jeasync.MixinApplied.mark("collect.IngredientUidIndexMixin");
		lock.lock();
		try {
			call.run();
			ci.cancel();
		} catch (Throwable throwable) {
			ci.cancel();
			IngredientUidIndexCalls.rethrow(throwable);
		} finally {
			lock.unlock();
		}
	}
}