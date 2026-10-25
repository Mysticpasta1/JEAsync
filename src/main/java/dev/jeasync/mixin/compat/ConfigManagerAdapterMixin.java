package dev.jeasync.mixin.compat;

import dev.jeasync.MixinApplied;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps JEI's config adapter readable while one of its schemas is inactive.
 * <p>
 * JEI 15.74 moved its configs to the standalone config library, which activates a schema only once
 * it has a world to resolve against: a client schema against the player's world, a server schema
 * against the server it is connected to. Outside a world there is no such path, so
 * {@code IConfigSchema.getPath()} comes back empty and the adapter answers with
 * {@code IllegalStateException: Config schema is inactive: jei}. Configured 2.2.3 reads that path
 * for the file name it shows in its own list, and takes the exception as a crash.
 * <p>
 * This offers the same file the library would name once it has a world, working from what the
 * schema already carries rather than from a name guessed in advance, in step with how confident
 * each answer is:
 * <ol>
 * <li>the schema's own registration or default path, where the library exposes one;</li>
 * <li>the path resolver it carries: a layered resolver keeps the default file the per-world file
 * sits on top of, and the per-world resolver keeps the file's location relative to that root;</li>
 * <li>a file name read off the schema's id, which JEI registers as the file name it passed in.</li>
 * </ol>
 * Only step 3 can name a file that is not the one the schema will use, and it is reached only when
 * the schema itself cannot name one. JEI's own config screens load values through the schema, not
 * through this method, so what is offered here decides the name an outside reader sees; it never
 * moves where JEI reads its values from.
 * <p>
 * The delegate is typed as the library's interface, which has no fallback accessor, so every step
 * reaches the implementation reflectively and a shape the library does not have simply falls to
 * the next step. When nothing answers, the original exception is left standing.
 * <p>
 * Targets upstream 15.74.0.229.
 */
@Mixin(targets = "mezz.jei.common.config.ConfigManagerAdapter$ConfigFile", remap = false)
public abstract class ConfigManagerAdapterMixin {
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger();
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	@Inject(method = "getPath()Ljava/nio/file/Path;", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$readInactiveSchemaPath(CallbackInfoReturnable<Path> cir) {
		MixinApplied.mark("compat.ConfigManagerAdapterMixin");
		// A record names its accessor after the component, and this mixin does not compile
		// against the adapter, so the schema comes from the generated delegate() method.
		Object delegate = invoke(this, "delegate");
		if (delegate == null) {
			return;
		}
		// A live schema answers for itself; this only ever stands in for the missing path.
		if (isPresent(invoke(delegate, "getPath"))) {
			return;
		}
		Path fallback = firstPath(
			invoke(delegate, "getRegistrationPath"),
			invoke(delegate, "getDefaultPath"),
			fromPathResolver(delegate),
			fromIdentifier(delegate)
		);
		if (fallback == null) {
			return;
		}
		// A config UI can read the same inactive schema on every refresh, so say it once.
		if (REPORTED.add(delegate.getClass().getName() + "#" + invoke(delegate, "getId"))) {
			JEI_ASYNC_LOGGER.info(
				"jeasync: config schema {} is inactive, naming its file {} so a config reader can still list it",
				invoke(delegate, "getModId"), fallback.getFileName()
			);
		}
		cir.setReturnValue(fallback);
	}

	/**
	 * Walks the resolver the schema carries, at most three layers deep: that covers a layered
	 * resolver over a per-world resolver, which is how the library builds a client file today.
	 * A layer answers with its own default path, its resolved path, or the file it stores as a
	 * field — the per-world resolver holds only a supplier of the world directory, so its record
	 * component for the file inside that directory is what names the file there.
	 */
	private static Path fromPathResolver(Object schema) {
		Object resolver = fieldValue(schema, "pathResolver");
		for (int depth = 0; depth < 3 && resolver != null; depth++) {
			Path direct = pathOf(invoke(resolver, "resolveDefaultPath"));
			if (direct != null) {
				return direct;
			}
			direct = pathOf(invoke(resolver, "resolvePath"));
			if (direct != null) {
				return direct;
			}
			direct = pathField(resolver);
			if (direct != null) {
				return FMLPaths.CONFIGDIR.get().resolve(direct);
			}
			resolver = nestedResolver(resolver);
		}
		return null;
	}

	private static Object nestedResolver(Object resolver) {
		for (Field field : resolver.getClass().getDeclaredFields()) {
			if (field.getType().getName().endsWith("ConfigSchemaPathResolver")) {
				return fieldValue(resolver, field.getName());
			}
		}
		return null;
	}

	/**
	 * A {@code Path} held by a resolver is either the file itself or its location relative to the
	 * config directory, and a layered resolver names its default before any wrapped layer, so the
	 * first such field is the answer.
	 */
	private static Path pathField(Object owner) {
		for (Field field : owner.getClass().getDeclaredFields()) {
			if (field.getType() == Path.class) {
				Object value = fieldValue(owner, field.getName());
				if (value instanceof Path path) {
					return path;
				}
			}
		}
		return null;
	}

	/**
	 * Last resort. JEI registers its server schema with an id that is the file name, so a bare
	 * {@code *.ini} id names the file under the config directory. An id that is only a mod id is
	 * not a file name, and is left alone rather than dressed up as one.
	 */
	private static Path fromIdentifier(Object schema) {
		String id = (String) invoke(schema, "getId");
		if (id != null && id.endsWith(".ini") && !containsSeparator(id)) {
			return FMLPaths.CONFIGDIR.get().resolve(id);
		}
		return null;
	}

	private static boolean containsSeparator(String value) {
		return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
	}

	private static Path firstPath(Object... results) {
		for (Object result : results) {
			Path path = pathOf(result);
			if (path != null) {
				return path;
			}
		}
		return null;
	}

	/**
	 * The library answers with an {@code Optional} wherever a path can genuinely be missing, and
	 * the steps below answer with a bare {@code Path} once they have one, so both shapes are read.
	 */
	private static Path pathOf(Object result) {
		if (result instanceof Path path) {
			return path;
		}
		if (result instanceof Optional<?> optional && optional.isPresent()
			&& optional.get() instanceof Path path) {
			return path;
		}
		return null;
	}

	private static boolean isPresent(Object result) {
		return result instanceof Optional<?> optional && optional.isPresent();
	}

	private static Object fieldValue(Object owner, String name) {
		if (owner == null) {
			return null;
		}
		try {
			Field field = owner.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(owner);
		} catch (ReflectiveOperationException | RuntimeException | Error e) {
			return null;
		}
	}

	private static Object invoke(Object owner, String name) {
		try {
			Method method = owner.getClass().getMethod(name);
			method.setAccessible(true);
			return method.invoke(owner);
		} catch (ReflectiveOperationException | RuntimeException | Error e) {
			// A shape the library does not have, or one it has not published yet: next step.
			return null;
		}
	}
}
