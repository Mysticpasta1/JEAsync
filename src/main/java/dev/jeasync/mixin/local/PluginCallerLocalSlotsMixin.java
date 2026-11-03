package dev.jeasync.mixin.local;

import mezz.jei.library.load.PluginCaller;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = PluginCaller.class, priority = 1, remap = false)
public interface PluginCallerLocalSlotsMixin {
}
