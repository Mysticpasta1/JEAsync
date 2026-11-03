package dev.jeasync.mixin.local;

import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = BookmarkOverlay.class, priority = 1, remap = false)
public interface BookmarkOverlayLocalSlotsMixin {
}
