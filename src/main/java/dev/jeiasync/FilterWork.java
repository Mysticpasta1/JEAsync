package dev.jeiasync;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.IElementSearch;

import java.util.Comparator;
import java.util.List;

/**
 * Snapshots the ingredient filter hands to a worker.
 * Kept outside {@code dev.jeiasync.mixin} so the merged JEI class can load them.
 */
public final class FilterWork {
	private FilterWork() {
	}

	public record Config(boolean lowMemorySlowSearchEnabled, IIngredientFilterConfig filterConfig) {
		public Config(IClientConfig clientConfig, IIngredientFilterConfig filterConfig) {
			this(clientConfig.isLowMemorySlowSearchEnabled(), filterConfig);
		}
	}

	public record Index(Comparator<IListElement<?>> comparator, IElementSearch search, long elapsedMillis) {
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
