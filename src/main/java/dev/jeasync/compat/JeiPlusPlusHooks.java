package dev.jeasync.compat;

import java.lang.reflect.Method;

/**
 * Entry point installed into JEI's recipe layouts.
 * The layouts class loads even when JEI++ is absent, so this type does not name JEI++ classes.
 */
public final class JeiPlusPlusHooks {
	private JeiPlusPlusHooks() {
	}

	public static Object recipeTreePress(Object layouts, Object input) {
		try {
			Class<?> bridge = Class.forName("dev.jeasync.compat.JeiPlusPlusBridge");
			Method press = bridge.getMethod("recipeTreePress", Object.class, Object.class);
			return press.invoke(null, layouts, input);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
