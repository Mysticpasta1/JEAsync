package dev.jeiasync.mixin.collect;

import dev.jeiasync.AsyncConfig;
import dev.jeiasync.IngredientUidIndexCalls;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import mezz.jei.api.ingredients.IIngredientType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Gives the per-ingredient-type rows of the recipe index an initial capacity.
 * <p>
 * {@code IngredientUidIndex.getOrCreateRow} builds each row with {@code new Object2ObjectOpenHashMap<>()},
 * which is fastutil's default capacity of 16. Rows are not small: one row exists per ingredient
 * type, keyed by ingredient uid, and for the item type in a large pack that is one entry per
 * distinct ingredient. Filling a map from 16 upwards rehashes at every power of two, and each
 * rehash allocates a fresh table and re-inserts every entry, so registration pays for several
 * copies of the whole row.
 * <p>
 * Sizing the row up front removes those intermediate tables. It does not remove the row itself -
 * that is still one map per type - and it deliberately does not shrink afterwards, because
 * trimming would allocate yet another table to give the slack back, which defeats the point when
 * the row is about to be filled again.
 * <p>
 * The capacity is a compromise, which is why it is configurable rather than a constant: too small
 * and the rehashes come back, too large and a type with three ingredients (fluids, tags) carries a
 * table sized for thousands. The default of 512 is chosen for the wide types that actually rehash.
 * It is set as {@code recipeRowCapacity} in the config file, and
 * {@code -Djeiasync.recipeRowCapacity=N} overrides that. Setting it to 1 gives upstream's
 * behaviour.
 * <p>
 * {@code get}, {@code forEach}, and both {@code compute*IfAbsent} methods share one lock for the
 * whole call. Several plugins fill one index at the same time, and the row map plus each fastutil
 * row throw if they are updated together. The collection a compute stores is also safe to
 * {@code add} to afterwards, which is what the callers do once the method has returned.
 * <p>
 * The target is named as a string and the change is a bytecode-level redirect rather than a
 * {@code @Shadow} on the row map, because {@code IngredientUidIndex} and {@code MatchBuckets} are
 * package-private. Compiling a mixin into JEI's own package does reach them, and it does work as a
 * compile target, but it makes the mod jar export a package the JEI jar already exports and
 * ModLauncher rejects the launch outright:
 * {@code java.lang.module.ResolutionException: Modules jei and jeiasync export package
 * mezz.jei.library.recipes.collect to module minecraft}. A redirect on the constructor needs
 * neither of the package-private types, so it survives that constraint.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(targets = "mezz.jei.library.recipes.collect.IngredientUidIndex", remap = false)
public abstract class IngredientUidIndexMixin {
	/**
	 * The map is built inside the lambda passed to {@code computeIfAbsent}, not in
	 * {@code getOrCreateRow} itself, so the allocation compiles into a synthetic method
	 * ({@code lambda$getOrCreateRow$2}), which is the only place the {@code NEW} to redirect exists.
	 * <p>
	 * Naming a synthetic lambda is normally a bad idea - the compiler renumbers them whenever the
	 * surrounding method changes. It is safe here because {@code mods.toml} pins one exact JEI
	 * build, so the bytecode being patched is fixed rather than floating. If a future port changes
	 * the name, the redirect finds no target and the mixin self-check names
	 * {@code IngredientUidIndex} as the culprit, which is the intended failure mode: loud, not
	 * silent.
	 */
	@Redirect(
		method = "lambda$getOrCreateRow$2",
		at = @At(
			value = "NEW",
			target = "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap"
		)
	)
	private static Object2ObjectOpenHashMap<Object, Object> jeiAsyncPresizedRow() {
		return new Object2ObjectOpenHashMap<>(AsyncConfig.getRecipeRowCapacity());
	}

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