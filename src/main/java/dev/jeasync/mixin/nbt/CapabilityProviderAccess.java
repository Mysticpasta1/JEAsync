package dev.jeasync.mixin.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls {@link CapabilityProvider#serializeCaps()}.
 * <p>
 * That method is declared on the capability provider, not on {@code ItemStack}. A shadow on the
 * stack mixin does not see it. Mixin adds this interface to the provider, so the cast is only
 * there to satisfy javac.
 */
@Mixin(value = CapabilityProvider.class, remap = false)
public interface CapabilityProviderAccess {
	@Invoker("serializeCaps")
	CompoundTag jeasync$serializeCaps();
}
