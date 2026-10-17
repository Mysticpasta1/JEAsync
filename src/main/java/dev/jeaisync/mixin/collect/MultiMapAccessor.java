package dev.jeiasync.mixin.collect;

import mezz.jei.core.collect.MultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Reaches MultiMap's backing map from subclasses that inherit it.
 * <p>
 * SetMultiMap and ListMultiMap both override methods that read {@code map} directly, so their
 * mixins need the field. But {@code map} is declared on {@code MultiMap}, not on the subclasses,
 * and a mixin's {@code @Shadow} only matches fields declared in its own target class. Shadowing
 * it from the subclass mixins fails with "field map was not located in the target class".
 * <p>
 * An accessor on the declaring class avoids that. At runtime Mixin adds this interface to
 * MultiMap, so every subclass instance satisfies it; the cast is only needed to satisfy javac,
 * since a standalone mixin class does not inherit from its target.
 */
@Mixin(MultiMap.class)
public interface MultiMapAccessor {
	@Accessor("map")
	Map<?, ?> jeiAsyncBackingMap();
}
