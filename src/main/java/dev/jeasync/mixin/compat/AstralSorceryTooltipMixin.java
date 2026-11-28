package dev.jeasync.mixin.compat;

import dev.jeasync.MixinApplied;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Keeps Astral Sorcery's component-tooltip loop away from {@code MappedRegistry.iterator()}.
 * <p>
 * Astral Sorcery 2.0.3 iterates {@code BuiltInRegistries.DATA_COMPONENT_TYPE} with
 * {@code Registry.forEach}. That iterator is built from the registry's ID-to-value list, and
 * NeoForge can leave null slots in that list after a registry sync where the client is missing an
 * entry (or during a registry restore at disconnect). The loop then throws a
 * {@link NullPointerException} before Astral Sorcery ever sees a component type, which shows up as
 * a large event-bus error while the vanilla creative-search tree is building tooltips on a worker
 * thread.
 * <p>
 * Iterating {@code entrySet()} reads the name-to-value map instead. It contains every registered
 * component type and cannot contain the ID-list null slots. The behavior for a healthy registry is
 * unchanged; the null check only skips a malformed entry instead of failing the whole tooltip.
 * <p>
 * The mixin config is optional and its plugin only enables it when Astral Sorcery is installed.
 */
@Mixin(targets = "hellfirepvp.astralsorcery.common.event.handler.TooltipEventHandler", remap = false)
public abstract class AstralSorceryTooltipMixin {
	@Unique
	private static final Logger jeasync$logger = LogManager.getLogger();

	@Unique
	private static volatile boolean jeasync$marked;

	@Redirect(
		method = "addComponentTooltip",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/core/Registry;forEach(Ljava/util/function/Consumer;)V"
		),
		remap = false
	)
	private static void jeasync$forEachComponent(Registry<DataComponentType<?>> registry, Consumer<DataComponentType<?>> action) {
		if (!jeasync$marked) {
			jeasync$marked = true;
			MixinApplied.mark("compat.AstralSorceryTooltipMixin");
			jeasync$logger.info("jeasync: Astral Sorcery tooltip guard applied");
		}
		for (Map.Entry<ResourceKey<DataComponentType<?>>, DataComponentType<?>> entry : registry.entrySet()) {
			DataComponentType<?> type = entry.getValue();
			if (type != null) {
				action.accept(type);
			}
		}
	}
}
