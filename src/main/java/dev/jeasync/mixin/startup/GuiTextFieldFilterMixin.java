package dev.jeasync.mixin.startup;

import mezz.jei.gui.input.GuiTextFieldFilter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks {@link GuiTextFieldFilter} so {@link dev.jeasync.mixin.JeAsyncMixinPlugin} can add the
 * {@code ISearchField} methods the Forge jar remapped onto {@code EditBox}. Mixin rejects adding
 * those public methods from the mixin class when the dev jar already has them.
 */
@Mixin(value = GuiTextFieldFilter.class, remap = false)
public abstract class GuiTextFieldFilterMixin {
}
