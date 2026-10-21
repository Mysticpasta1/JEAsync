package dev.jeiasync.mixin.search;

import dev.jeiasync.MixinApplied;
import mezz.jei.common.search.LuceneSearchStorage;
import mezz.jei.modshade.org.apache.lucene.analysis.Analyzer;
import mezz.jei.modshade.org.apache.lucene.document.Document;
import mezz.jei.modshade.org.apache.lucene.document.Field;
import mezz.jei.modshade.org.apache.lucene.document.IntPoint;
import mezz.jei.modshade.org.apache.lucene.document.NumericDocValuesField;
import mezz.jei.modshade.org.apache.lucene.document.StringField;
import mezz.jei.modshade.org.apache.lucene.index.IndexWriter;
import mezz.jei.modshade.org.apache.lucene.index.IndexWriterConfig;
import mezz.jei.modshade.org.apache.lucene.index.SerialMergeScheduler;
import mezz.jei.modshade.org.apache.lucene.index.TieredMergePolicy;
import mezz.jei.modshade.org.apache.lucene.store.Directory;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Writes JEI's Lucene search index in batches.
 * <p>
 * {@link LuceneSearchStorage#put} appends every search string to {@code pending} and
 * {@link LuceneSearchStorage} only drains that list inside {@code refresh}. Tooltip and tag
 * indexing does that once, after every ingredient has been visited, so the Java strings and the
 * index bytes are both live together. That is the multi-gigabyte gap between a finished load and
 * the load itself.
 * <p>
 * Batches are written with one {@link IndexWriter} kept open for the build. Merges stay on the
 * indexing thread and are capped, so a background merge cannot allocate another copy of the index.
 * {@code refresh} and {@code removeAll} still do the final reader open and the deletes; they need
 * this writer closed first because the directory allows one writer.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = LuceneSearchStorage.class, remap = false)
public abstract class LuceneSearchStorageMixin {
	@Unique
	private static final int JEIASYNC_BATCH = 1024;

	@Unique
	private static final int JEIASYNC_BATCH_CHARS = 262144;

	@Shadow
	@Final
	private ArrayList<Object> pending;

	@Shadow
	@Final
	private Directory directory;

	@Shadow
	@Final
	private static Analyzer ANALYZER;

	@Shadow
	@Final
	private static String KEY_FIELD;

	@Shadow
	@Final
	private static String VALUE_FIELD;

	@Shadow
	private boolean readerDirty;

	@Unique
	@Nullable
	private IndexWriter jeiasync$writer;

	@Unique
	private int jeiasync$pendingChars;

	@Unique
	private static volatile boolean jeiasync$marked;

	@Unique
	@Nullable
	private static MethodHandle jeiasync$keyCall;

	@Unique
	@Nullable
	private static MethodHandle jeiasync$valueCall;

	@Unique
	@Nullable
	private static Method jeiasync$keyReflect;

	@Unique
	@Nullable
	private static Method jeiasync$valueReflect;

	/**
	 * @author Mysticpasta1
	 * @reason Hold a bounded batch of search strings, then write them, so the rest can be collected.
	 */
	@Inject(method = "put", at = @At("RETURN"), remap = false)
	private void jeiasync$flushSearchBatch(String key, Object value, CallbackInfo ci) {
		if (key != null) {
			this.jeiasync$pendingChars += key.length();
		}
		if (this.pending.size() >= JEIASYNC_BATCH || this.jeiasync$pendingChars >= JEIASYNC_BATCH_CHARS) {
			this.jeiasync$writePending();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason JEI opens its own writer in refresh. Close the batch writer first or the directory lock fails.
	 */
	@Inject(method = "refresh", at = @At("HEAD"), remap = false)
	private void jeiasync$closeSearchWriter(CallbackInfo ci) {
		this.jeiasync$closeWriter();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Deletes open a second writer. The batch writer has to be closed first.
	 */
	@Inject(method = "removeAll", at = @At("HEAD"), remap = false)
	private void jeiasync$closeSearchWriterForDelete(CallbackInfoReturnable<Boolean> cir) {
		this.jeiasync$closeWriter();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy one batch into the open index and drop those string references.
	 */
	@Unique
	private void jeiasync$writePending() {
		synchronized (this) {
			int count = this.pending.size();
			if (count == 0) {
				this.jeiasync$pendingChars = 0;
				return;
			}
			if (!jeiasync$marked) {
				jeiasync$marked = true;
				MixinApplied.mark("search.LuceneSearchStorageMixin");
				LogManager.getLogger(LuceneSearchStorageMixin.class).info(
					"JEI Async: writing search strings in batches so they are not all held until the index closes"
				);
			}
			IndexWriter writer = this.jeiasync$openWriter();
			try {
				for (int i = 0; i < count; i++) {
					Object entry = this.pending.get(i);
					Document document = new Document();
					document.add(new StringField(KEY_FIELD, jeiasync$keyOf(entry), Field.Store.NO));
					int valueId = jeiasync$valueOf(entry);
					document.add(new NumericDocValuesField(VALUE_FIELD, valueId));
					document.add(new IntPoint(VALUE_FIELD, valueId));
					writer.addDocument(document);
				}
			} catch (IOException e) {
				this.jeiasync$abandonWriter(e);
				throw new UncheckedIOException(e);
			} catch (RuntimeException e) {
				this.jeiasync$abandonWriter(e);
				throw e;
			}
			if (this.pending.size() == count) {
				this.pending.clear();
			} else {
				this.pending.subList(0, count).clear();
			}
			this.jeiasync$pendingChars = 0;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason One writer for the build. A new one per batch would re-read the directory every time.
	 */
	@Unique
	private IndexWriter jeiasync$openWriter() {
		IndexWriter writer = this.jeiasync$writer;
		if (writer != null) {
			return writer;
		}
		TieredMergePolicy policy = new TieredMergePolicy();
		// Default max merged segment is 5GB, which copies a finished index again while strings are still live.
		policy.setMaxMergedSegmentMB(32);
		policy.setMaxMergeAtOnce(4);
		IndexWriterConfig config = new IndexWriterConfig(ANALYZER);
		config.setRAMBufferSizeMB(8);
		config.setMergePolicy(policy);
		config.setMergeScheduler(new SerialMergeScheduler());
		try {
			writer = new IndexWriter(this.directory, config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		this.jeiasync$writer = writer;
		return writer;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Commit the batches and release the directory lock before JEI opens a reader or a deleting writer.
	 */
	@Unique
	private void jeiasync$closeWriter() {
		synchronized (this) {
			IndexWriter writer = this.jeiasync$writer;
			if (writer == null) {
				return;
			}
			this.jeiasync$writer = null;
			try {
				writer.close();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			this.readerDirty = true;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason A failed batch must not leave the directory locked for the rest of startup.
	 */
	@Unique
	private void jeiasync$abandonWriter(Exception failure) {
		IndexWriter writer = this.jeiasync$writer;
		this.jeiasync$writer = null;
		if (writer == null) {
			return;
		}
		try {
			writer.close();
		} catch (IOException closeFailure) {
			failure.addSuppressed(closeFailure);
		}
		this.readerDirty = true;
	}

	@Unique
	private static String jeiasync$keyOf(Object entry) {
		jeiasync$bind(entry);
		try {
			MethodHandle call = jeiasync$keyCall;
			if (call != null) {
				return (String) call.invokeExact(entry);
			}
			return (String) jeiasync$keyReflect.invoke(entry);
		} catch (Throwable failure) {
			throw new IllegalStateException("JEI Async: cannot read a search key", failure);
		}
	}

	@Unique
	private static int jeiasync$valueOf(Object entry) {
		jeiasync$bind(entry);
		try {
			MethodHandle call = jeiasync$valueCall;
			if (call != null) {
				return (int) call.invokeExact(entry);
			}
			return (Integer) jeiasync$valueReflect.invoke(entry);
		} catch (Throwable failure) {
			throw new IllegalStateException("JEI Async: cannot read a search value", failure);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The pending entry type is a private record. Read it from the merged class, and fall back if that lookup is refused.
	 */
	@Unique
	private static void jeiasync$bind(Object entry) {
		if (jeiasync$keyCall != null || jeiasync$keyReflect != null) {
			return;
		}
		synchronized (LuceneSearchStorageMixin.class) {
			if (jeiasync$keyCall != null || jeiasync$keyReflect != null) {
				return;
			}
			Class<?> type = entry.getClass();
			try {
				MethodHandles.Lookup lookup = MethodHandles.lookup();
				jeiasync$keyCall = lookup.findVirtual(type, "key", MethodType.methodType(String.class))
					.asType(MethodType.methodType(String.class, Object.class));
				jeiasync$valueCall = lookup.findVirtual(type, "valueId", MethodType.methodType(int.class))
					.asType(MethodType.methodType(int.class, Object.class));
			} catch (ReflectiveOperationException lookupFailure) {
				try {
					Method key = type.getDeclaredMethod("key");
					Method value = type.getDeclaredMethod("valueId");
					key.setAccessible(true);
					value.setAccessible(true);
					jeiasync$keyReflect = key;
					jeiasync$valueReflect = value;
				} catch (ReflectiveOperationException reflectiveFailure) {
					lookupFailure.addSuppressed(reflectiveFailure);
					throw new IllegalStateException("JEI Async: cannot read a search index entry", lookupFailure);
				}
			}
		}
	}
}
