package dev.jeasync.mixin.search;

import dev.jeasync.MixinApplied;
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
	private IndexWriter jeasync$writer;

	@Unique
	private int jeasync$pendingChars;

	@Unique
	private static volatile boolean jeasync$marked;

	@Unique
	@Nullable
	private static MethodHandle jeasync$keyCall;

	@Unique
	@Nullable
	private static MethodHandle jeasync$valueCall;

	@Unique
	@Nullable
	private static Method jeasync$keyReflect;

	@Unique
	@Nullable
	private static Method jeasync$valueReflect;

	/**
	 * @author Mysticpasta1
	 * @reason Hold a bounded batch of search strings, then write them, so the rest can be collected.
	 */
	@Inject(method = "put", at = @At("RETURN"), remap = false)
	private void jeasync$flushSearchBatch(String key, Object value, CallbackInfo ci) {
		if (key != null) {
			this.jeasync$pendingChars += key.length();
		}
		if (this.pending.size() >= JEIASYNC_BATCH || this.jeasync$pendingChars >= JEIASYNC_BATCH_CHARS) {
			this.jeasync$writePending();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason JEI opens its own writer in refresh. Close the batch writer first or the directory lock fails.
	 */
	@Inject(method = "refresh", at = @At("HEAD"), remap = false)
	private void jeasync$closeSearchWriter(CallbackInfo ci) {
		this.jeasync$closeWriter();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Deletes open a second writer. The batch writer has to be closed first.
	 */
	@Inject(method = "removeAll", at = @At("HEAD"), remap = false)
	private void jeasync$closeSearchWriterForDelete(CallbackInfoReturnable<Boolean> cir) {
		this.jeasync$closeWriter();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy one batch into the open index and drop those string references.
	 */
	@Unique
	private void jeasync$writePending() {
		synchronized (this) {
			int count = this.pending.size();
			if (count == 0) {
				this.jeasync$pendingChars = 0;
				return;
			}
			if (!jeasync$marked) {
				jeasync$marked = true;
				MixinApplied.mark("search.LuceneSearchStorageMixin");
				LogManager.getLogger(LuceneSearchStorageMixin.class).info(
					"jeasync: writing search strings in batches so they are not all held until the index closes"
				);
			}
			IndexWriter writer = this.jeasync$openWriter();
			try {
				for (int i = 0; i < count; i++) {
					Object entry = this.pending.get(i);
					Document document = new Document();
					document.add(new StringField(KEY_FIELD, jeasync$keyOf(entry), Field.Store.NO));
					int valueId = jeasync$valueOf(entry);
					document.add(new NumericDocValuesField(VALUE_FIELD, valueId));
					document.add(new IntPoint(VALUE_FIELD, valueId));
					writer.addDocument(document);
				}
			} catch (IOException e) {
				this.jeasync$abandonWriter(e);
				throw new UncheckedIOException(e);
			} catch (RuntimeException e) {
				this.jeasync$abandonWriter(e);
				throw e;
			}
			if (this.pending.size() == count) {
				this.pending.clear();
			} else {
				this.pending.subList(0, count).clear();
			}
			this.jeasync$pendingChars = 0;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason One writer for the build. A new one per batch would re-read the directory every time.
	 */
	@Unique
	private IndexWriter jeasync$openWriter() {
		IndexWriter writer = this.jeasync$writer;
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
		this.jeasync$writer = writer;
		return writer;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Commit the batches and release the directory lock before JEI opens a reader or a deleting writer.
	 */
	@Unique
	private void jeasync$closeWriter() {
		synchronized (this) {
			IndexWriter writer = this.jeasync$writer;
			if (writer == null) {
				return;
			}
			this.jeasync$writer = null;
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
	private void jeasync$abandonWriter(Exception failure) {
		IndexWriter writer = this.jeasync$writer;
		this.jeasync$writer = null;
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
	private static String jeasync$keyOf(Object entry) {
		jeasync$bind(entry);
		try {
			MethodHandle call = jeasync$keyCall;
			if (call != null) {
				return (String) call.invokeExact(entry);
			}
			return (String) jeasync$keyReflect.invoke(entry);
		} catch (Throwable failure) {
			throw new IllegalStateException("jeasync: cannot read a search key", failure);
		}
	}

	@Unique
	private static int jeasync$valueOf(Object entry) {
		jeasync$bind(entry);
		try {
			MethodHandle call = jeasync$valueCall;
			if (call != null) {
				return (int) call.invokeExact(entry);
			}
			return (Integer) jeasync$valueReflect.invoke(entry);
		} catch (Throwable failure) {
			throw new IllegalStateException("jeasync: cannot read a search value", failure);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The pending entry type is a private record. Read it from the merged class, and fall back if that lookup is refused.
	 */
	@Unique
	private static void jeasync$bind(Object entry) {
		if (jeasync$keyCall != null || jeasync$keyReflect != null) {
			return;
		}
		synchronized (LuceneSearchStorageMixin.class) {
			if (jeasync$keyCall != null || jeasync$keyReflect != null) {
				return;
			}
			Class<?> type = entry.getClass();
			try {
				MethodHandles.Lookup lookup = MethodHandles.lookup();
				jeasync$keyCall = lookup.findVirtual(type, "key", MethodType.methodType(String.class))
					.asType(MethodType.methodType(String.class, Object.class));
				jeasync$valueCall = lookup.findVirtual(type, "valueId", MethodType.methodType(int.class))
					.asType(MethodType.methodType(int.class, Object.class));
			} catch (ReflectiveOperationException lookupFailure) {
				try {
					Method key = type.getDeclaredMethod("key");
					Method value = type.getDeclaredMethod("valueId");
					key.setAccessible(true);
					value.setAccessible(true);
					jeasync$keyReflect = key;
					jeasync$valueReflect = value;
				} catch (ReflectiveOperationException reflectiveFailure) {
					lookupFailure.addSuppressed(reflectiveFailure);
					throw new IllegalStateException("jeasync: cannot read a search index entry", lookupFailure);
				}
			}
		}
	}
}
