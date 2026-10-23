package dev.jeasync;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.IElementSearch;

import java.util.Comparator;
import java.util.List;

/**
 * Snapshots the ingredient filter hands to a worker.
 * Kept outside {@code dev.jeasync.mixin} so the merged JEI class can load them.
 */
public final class FilterWork {
	private FilterWork() {
	}

	public record Index(
		Comparator<IListElement<?>> comparator,
		IElementSearch search,
		List<IListElementInfo<?>> elementInfos,
		long elapsedMillis
	) {
	}

	public record Tokens(List<ElementPrefixParser.TokenInfo> toSearch, List<ElementPrefixParser.TokenInfo> toRemove) {
		public boolean isEmpty() {
			return toSearch.isEmpty() && toRemove.isEmpty();
		}
	}

	/** A filtered ingredient list and how long the filter took, before element wrappers are built. */
	public record Filtered(List<ITypedIngredient<?>> ingredients, long elapsedMillis) {
	}
}
