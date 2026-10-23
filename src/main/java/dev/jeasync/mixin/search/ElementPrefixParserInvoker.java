package dev.jeasync.mixin.search;

import mezz.jei.common.search.PrefixInfo;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearchIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The index-to-prefix lookup is package-private to {@code mezz.jei.gui.search}. The parallel
 * index build needs it to know which prefix every worker fills, so the invoker is the only way to
 * reach it from outside the package.
 */
@Mixin(value = ElementPrefixParser.class, remap = false)
public interface ElementPrefixParserInvoker {
	@Invoker(value = "getPrefixInfo", remap = false)
	PrefixInfo<IListElementInfo<?>, IListElement<?>> jeasync$invokeGetPrefixInfo(ElementSearchIndex searchIndex);
}
