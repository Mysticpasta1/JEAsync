package dev.jeasync.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.service.MixinService;

/**
 * Rewrites JEI++ and AE2 Utility classes that still name types JEI 15.71 removed.
 * Mixin asks for another mod's mixin class through its bytecode loader, before any mixin can edit it.
 * Ordinary JEI++ classes are rewritten from {@code JeiPlusPlusClientClassMixin} instead.
 */
public final class JeiRemovedTypeRewrite {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String ICON_BUTTON = "mezz/jei/gui/elements/GuiIconButton";
	private static final String ICON_BUTTON_NOW = "mezz/jei/gui/elements/IconButton";
	private static final String TOGGLE_BUTTON = "mezz/jei/gui/elements/GuiIconToggleButton";
	private static final String USER_INPUT = "mezz/jei/gui/input/UserInput";
	private static final String USER_INPUT_NOW = "mezz/jei/common/input/UserInput";
	private static final String MOUSE_OVER = "mezz/jei/gui/input/IMouseOverable";
	private static final String MOUSE_OVER_NOW = "mezz/jei/common/input/IMouseOverable";
	private static final String INPUT_HANDLER = "mezz/jei/gui/input/IUserInputHandler";
	private static final String TREE_BUTTON = "com/lingmu0/JeiPlusPlusMod/client/RecipeTreeSidebarButton";
	private static final String BOOKMARK_OVERLAY = "com/lingmu0/JeiPlusPlusMod/mixin/BookmarkOverlayMixin";
	private static final Map<String, Kind> KINDS = Map.ofEntries(
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/RecipesGuiMixin", Kind.ICON_BUTTON),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/RecipesGuiAccessor", Kind.ICON_BUTTON),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/GuiIconToggleButtonMixin", Kind.ICON_BUTTON),
		Map.entry("com/lingmu0/JeiPlusPlusMod/client/CreativeTabGridCompat", Kind.INPUT),
		Map.entry(BOOKMARK_OVERLAY, Kind.INPUT),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/IngredientGridWithNavigationMixin", Kind.INPUT),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/IngredientGridWithNavigationModernMixin", Kind.INPUT),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/RecipeGuiTabsMixin", Kind.INPUT),
		Map.entry(BOOKMARK_OVERLAY + "$JeiPlusPlusRightClickHandler", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/RecipeGuiTabsMixin$TabScrollInputHandler", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/client/CreativeTabGridCompat$CreativeTabInputHandler", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/client/CreativeTabBar", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/client/CreativeTabSelector", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/client/IngredientListFeatures$GroupedIngredientElementHandler", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/client/RecipeTreeFavorites$SyntheticElement", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/RecipeGuiLayoutsMixin", Kind.MISSING),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/RecipeSlotClickTargetFactoryMixin", Kind.MISSING),
		Map.entry(TREE_BUTTON, Kind.TREE_BUTTON),
		Map.entry("com/lingmu0/JeiPlusPlusMod/mixin/BookmarkInputHandlerMixin", Kind.SKIP_MIXIN),
		Map.entry("com/lhy/ae2utility/mixin/jei/RecipeOptionButtonsMixin", Kind.AE2)
	);
	private static final Set<String> GAME_CLASSES = Set.of(
		"com/lingmu0/JeiPlusPlusMod/client/CreativeTabGridCompat",
		"com/lingmu0/JeiPlusPlusMod/client/CreativeTabGridCompat$CreativeTabInputHandler",
		"com/lingmu0/JeiPlusPlusMod/client/CreativeTabBar",
		"com/lingmu0/JeiPlusPlusMod/client/CreativeTabSelector",
		"com/lingmu0/JeiPlusPlusMod/client/IngredientListFeatures$GroupedIngredientElementHandler",
		"com/lingmu0/JeiPlusPlusMod/client/RecipeTreeFavorites$SyntheticElement",
		TREE_BUTTON
	);
	private static volatile boolean installed;

	private JeiRemovedTypeRewrite() {
	}

	public static boolean isGameClass(String className) {
		return className != null && GAME_CLASSES.contains(className.replace('.', '/'));
	}

	public static void apply(ClassNode classNode) {
		if (classNode == null || classNode.name == null) {
			return;
		}
		Kind kind = KINDS.get(classNode.name);
		if (kind == null) {
			return;
		}
		dispatch(classNode, kind);
		recomputeFrames(classNode);
	}

	public static byte[] rewrite(String className, byte[] bytes) {
		if (className == null || bytes == null || bytes.length == 0) {
			return bytes;
		}
		String internal = className.replace('.', '/');
		if (!KINDS.containsKey(internal)) {
			return bytes;
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		apply(node);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	public static void install() {
		if (installed) {
			return;
		}
		synchronized (JeiRemovedTypeRewrite.class) {
			if (installed) {
				return;
			}
			try {
				Object provider = MixinService.getService().getBytecodeProvider();
				Field field = findField(provider.getClass(), "transformerLoader");
				if (field == null) {
					LOGGER.warn("jeasync: mixin bytecode loader has no transformerLoader field");
					return;
				}
				field.setAccessible(true);
				Object current = field.get(provider);
				if (current == null) {
					LOGGER.warn("jeasync: mixin bytecode loader is not ready");
					return;
				}
				if (current instanceof Hook) {
					installed = true;
					return;
				}
				field.set(provider, new Hook((ILaunchPluginService.ITransformerLoader) current));
				installed = true;
				LOGGER.info("jeasync: JEI 15.71 type rewrite is installed");
			} catch (Throwable failure) {
				LOGGER.warn("jeasync: could not install the JEI 15.71 type rewrite", failure);
			}
		}
	}

	private static void dispatch(ClassNode classNode, Kind kind) {
		switch (kind) {
			case ICON_BUTTON -> retargetGuiIconButton(classNode);
			case INPUT -> retargetRemovedInput(classNode);
			case MISSING -> retargetMissingInput(classNode);
			case TREE_BUTTON -> recipeTreeButton(classNode);
			case SKIP_MIXIN -> skipMissingMixin(classNode);
			case AE2 -> {
				if (stubReturn(classNode, "ae2utility$prependButtons")) {
					LOGGER.info("jeasync: skipped AE2 Utility recipe option buttons that extend a removed JEI type");
				}
			}
		}
	}

	private static void retargetGuiIconButton(ClassNode classNode) {
		boolean changed = rewriteClass(classNode, ICON_BUTTON, ICON_BUTTON_NOW);
		if ("com/lingmu0/JeiPlusPlusMod/mixin/GuiIconToggleButtonMixin".equals(classNode.name)) {
			addPseudo(classNode);
			stubBooleanFalse(classNode, "jeiPlusPlus$isRecipeBookmark");
			changed = true;
		}
		if (changed) {
			LOGGER.info("jeasync: JEI++ GuiIconButton is IconButton in {}", classNode.name);
		}
	}

	private static void retargetRemovedInput(ClassNode classNode) {
		String name = classNode.name;
		boolean changed = false;
		if ("com/lingmu0/JeiPlusPlusMod/client/CreativeTabGridCompat".equals(name)) {
			changed = stubReturnArgument(classNode, "wrapInput", 1);
		} else if (BOOKMARK_OVERLAY.equals(name)) {
			retargetBookmarkOverlay(classNode);
			changed = true;
		} else if ("com/lingmu0/JeiPlusPlusMod/mixin/IngredientGridWithNavigationMixin".equals(name)) {
			changed = stubReturn(classNode, "jeiPlusPlus$wrapCreativeTabInput");
			if (retargetDrawInject(classNode)) {
				changed = true;
			}
		} else if ("com/lingmu0/JeiPlusPlusMod/mixin/IngredientGridWithNavigationModernMixin".equals(name)) {
			if (retargetDrawInject(classNode)) {
				changed = true;
			}
			replaceGridInput(classNode);
			changed = true;
		} else if ("com/lingmu0/JeiPlusPlusMod/mixin/RecipeGuiTabsMixin".equals(name)) {
			replaceTabScroll(classNode);
			changed = true;
		}
		if (changed) {
			LOGGER.info("jeasync: JEI++ GUI hooks follow JEI 15.71 off the render thread in {}", name);
		}
		retargetMissingInput(classNode);
	}

	private static void retargetBookmarkOverlay(ClassNode classNode) {
		renameMember(classNode, "screenPropertiesCache", "guiPropertiesCache");
		rewriteEverywhere(classNode, "mezz/jei/gui/overlay/ScreenPropertiesCache", "mezz/jei/gui/overlay/GuiPropertiesCache");
		rewriteEverywhere(classNode, TOGGLE_BUTTON, ICON_BUTTON_NOW);
		MethodNode place = findMethod(classNode, "jeiPlusPlus$placeTreeButton");
		if (place != null) {
			place.desc = "(Lmezz/jei/api/gui/handlers/IGuiProperties;Ljava/util/Set;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
			place.signature = null;
			if (place.localVariables != null) {
				place.localVariables.clear();
			}
		}
		replaceBookmarkInput(classNode);
		stubNullReturn(classNode, "lambda$jeiPlusPlus$addTreeButtonInput$0");
	}

	private static void replaceBookmarkInput(ClassNode classNode) {
		MethodNode method = findMethod(classNode, "jeiPlusPlus$addTreeButtonInput");
		if (method == null) {
			return;
		}
		String owner = BOOKMARK_OVERLAY;
		String button = TREE_BUTTON;
		String cir = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "jeiPlusPlus$ensureTreeButton", "()V", false));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "jeiPlusPlus$treeButton", "L" + button + ";"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cir, "getReturnValue", "()Ljava/lang/Object;", false));
		insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "mezz/jei/common/input/IInputTarget"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(
			Opcodes.INVOKESTATIC,
			"dev/jeasync/compat/JeiPlusPlusBridge",
			"wrapBookmark",
			"(Ljava/lang/Object;Lmezz/jei/common/input/IInputTarget;Ljava/lang/Object;)Lmezz/jei/common/input/IInputTarget;",
			false
		));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cir, "setReturnValue", "(Ljava/lang/Object;)V", false));
		insns.add(new InsnNode(Opcodes.RETURN));
		clearMethodBody(method, insns);
		method.desc = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
		method.signature = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<Lmezz/jei/common/input/IInputTarget;>;)V";
		method.maxStack = 4;
		method.maxLocals = 2;
	}

	private static void replaceGridInput(ClassNode classNode) {
		MethodNode method = findMethod(classNode, "jeiPlusPlus$wrapCreativeTabInput");
		if (method == null) {
			return;
		}
		String cir = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cir, "getReturnValue", "()Ljava/lang/Object;", false));
		insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "mezz/jei/common/input/IInputTarget"));
		insns.add(new MethodInsnNode(
			Opcodes.INVOKESTATIC,
			"dev/jeasync/compat/JeiPlusPlusBridge",
			"wrapGrid",
			"(Ljava/lang/Object;Lmezz/jei/common/input/IInputTarget;)Lmezz/jei/common/input/IInputTarget;",
			false
		));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cir, "setReturnValue", "(Ljava/lang/Object;)V", false));
		insns.add(new InsnNode(Opcodes.RETURN));
		clearMethodBody(method, insns);
		method.desc = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
		method.signature = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<Lmezz/jei/common/input/IInputTarget;>;)V";
		method.maxStack = 3;
		method.maxLocals = 2;
	}

	private static void replaceTabScroll(ClassNode classNode) {
		MethodNode method = findMethod(classNode, "jeiPlusPlus$wrapTabInput");
		if (method == null) {
			return;
		}
		setInject(method, "scroll", "HEAD");
		String owner = "com/lingmu0/JeiPlusPlusMod/mixin/RecipeGuiTabsMixin";
		String area = "mezz/jei/common/util/ImmutableRect2i";
		String logic = "mezz/jei/gui/recipes/IRecipeGuiLogic";
		String cir = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
		LabelNode end = new LabelNode();
		LabelNode nonzero = new LabelNode();
		LabelNode useX = new LabelNode();
		LabelNode store = new LabelNode();
		LabelNode areaCheck = new LabelNode();
		LabelNode prevPage = new LabelNode();
		LabelNode prevCat = new LabelNode();
		LabelNode handled = new LabelNode();
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.DLOAD, 7));
		insns.add(new InsnNode(Opcodes.DCONST_0));
		insns.add(new InsnNode(Opcodes.DCMPL));
		insns.add(new JumpInsnNode(Opcodes.IFNE, nonzero));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 5));
		insns.add(new InsnNode(Opcodes.DCONST_0));
		insns.add(new InsnNode(Opcodes.DCMPL));
		insns.add(new JumpInsnNode(Opcodes.IFEQ, end));
		insns.add(nonzero);
		insns.add(new VarInsnNode(Opcodes.DLOAD, 7));
		insns.add(new InsnNode(Opcodes.DCONST_0));
		insns.add(new InsnNode(Opcodes.DCMPL));
		insns.add(new JumpInsnNode(Opcodes.IFEQ, useX));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 7));
		insns.add(new JumpInsnNode(Opcodes.GOTO, store));
		insns.add(useX);
		insns.add(new VarInsnNode(Opcodes.DLOAD, 5));
		insns.add(store);
		insns.add(new VarInsnNode(Opcodes.DSTORE, 10));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 1));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 3));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "isPageNavigationBand", "(DD)Z", false));
		insns.add(new JumpInsnNode(Opcodes.IFEQ, areaCheck));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 10));
		insns.add(new InsnNode(Opcodes.DCONST_0));
		insns.add(new InsnNode(Opcodes.DCMPL));
		insns.add(new JumpInsnNode(Opcodes.IFGE, prevPage));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "nextPage", "()Z", false));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(new JumpInsnNode(Opcodes.GOTO, handled));
		insns.add(prevPage);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "previousPage", "()Z", false));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(new JumpInsnNode(Opcodes.GOTO, handled));
		insns.add(areaCheck);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "area", "L" + area + ";"));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 1));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 3));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, area, "contains", "(DD)Z", false));
		insns.add(new JumpInsnNode(Opcodes.IFEQ, end));
		insns.add(new VarInsnNode(Opcodes.DLOAD, 10));
		insns.add(new InsnNode(Opcodes.DCONST_0));
		insns.add(new InsnNode(Opcodes.DCMPL));
		insns.add(new JumpInsnNode(Opcodes.IFGE, prevCat));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "recipeGuiLogic", "L" + logic + ";"));
		insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, logic, "nextRecipeCategory", "()Z", true));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(new JumpInsnNode(Opcodes.GOTO, handled));
		insns.add(prevCat);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "recipeGuiLogic", "L" + logic + ";"));
		insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, logic, "previousRecipeCategory", "()Z", true));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(handled);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
		insns.add(new InsnNode(Opcodes.ICONST_1));
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cir, "setReturnValue", "(Ljava/lang/Object;)V", false));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 9));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cir, "cancel", "()V", false));
		insns.add(end);
		insns.add(new InsnNode(Opcodes.RETURN));
		clearMethodBody(method, insns);
		method.desc = "(DDDDLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
		method.signature = "(DDDDLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<Ljava/lang/Boolean;>;)V";
		method.maxStack = 5;
		method.maxLocals = 12;
	}

	private static boolean retargetMissingInput(ClassNode classNode) {
		boolean changed = stripInterface(classNode, INPUT_HANDLER);
		changed |= rewriteEverywhere(classNode, INPUT_HANDLER, "java/lang/Object");
		changed |= rewriteEverywhere(classNode, TOGGLE_BUTTON, "java/lang/Object");
		changed |= rewriteEverywhere(classNode, "mezz/jei/gui/input/handlers/CombinedInputHandler", "java/lang/Object");
		changed |= rewriteEverywhere(classNode, "mezz/jei/gui/input/handlers/ProxyInputHandler", "java/lang/Object");
		changed |= rewriteEverywhere(classNode, "mezz/jei/gui/input/handlers/SameElementInputHandler", "java/lang/Object");
		if (!BOOKMARK_OVERLAY.equals(classNode.name)) {
			changed |= rewriteEverywhere(classNode, TREE_BUTTON, "java/lang/Object");
		}
		changed |= rewriteEverywhere(classNode, USER_INPUT, USER_INPUT_NOW);
		changed |= rewriteEverywhere(classNode, MOUSE_OVER, MOUSE_OVER_NOW);
		changed |= renameMouseOverIndy(classNode);
		if (changed) {
			LOGGER.info("jeasync: JEI++ missing input types rewritten in {}", classNode.name);
		}
		return changed;
	}

	private static void recipeTreeButton(ClassNode classNode) {
		classNode.superName = "dev/jeasync/compat/LegacyToggleButton";
		rewriteEverywhere(classNode, TOGGLE_BUTTON, "dev/jeasync/compat/LegacyToggleButton");
		rewriteEverywhere(classNode, USER_INPUT, USER_INPUT_NOW);
		LOGGER.info("jeasync: JEI++ recipe tree button uses IconButton");
	}

	private static void skipMissingMixin(ClassNode classNode) {
		addPseudo(classNode);
		retargetMissingInput(classNode);
		for (MethodNode method : classNode.methods) {
			if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
				continue;
			}
			if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
				continue;
			}
			stubByReturn(method);
		}
		LOGGER.info("jeasync: skipped JEI++ mixin whose target JEI 15.71 removed: {}", classNode.name);
	}

	private static boolean rewriteEverywhere(ClassNode classNode, String from, String to) {
		boolean changed = rewriteClass(classNode, from, to);
		String signature = swap(classNode.signature, from, to);
		if (!same(signature, classNode.signature)) {
			classNode.signature = signature;
			changed = true;
		}
		changed |= rewriteAnnotationList(classNode.visibleAnnotations, from, to);
		changed |= rewriteAnnotationList(classNode.invisibleAnnotations, from, to);
		for (FieldNode field : classNode.fields) {
			changed |= rewriteAnnotationList(field.visibleAnnotations, from, to);
			changed |= rewriteAnnotationList(field.invisibleAnnotations, from, to);
		}
		for (MethodNode method : classNode.methods) {
			changed |= rewriteAnnotationList(method.visibleAnnotations, from, to);
			changed |= rewriteAnnotationList(method.invisibleAnnotations, from, to);
		}
		return changed;
	}

	private static boolean rewriteClass(ClassNode classNode, String from, String to) {
		boolean changed = rewriteFields(classNode.fields, from, to);
		changed |= rewriteMethodDescs(classNode.methods, from, to);
		for (MethodNode method : classNode.methods) {
			changed |= rewriteMethod(method, from, to);
		}
		return changed;
	}

	private static boolean rewriteFields(List<FieldNode> fields, String from, String to) {
		if (fields == null) {
			return false;
		}
		boolean changed = false;
		for (FieldNode field : fields) {
			String desc = swap(field.desc, from, to);
			String signature = swap(field.signature, from, to);
			if (!same(desc, field.desc)) {
				field.desc = desc;
				changed = true;
			}
			if (!same(signature, field.signature)) {
				field.signature = signature;
				changed = true;
			}
		}
		return changed;
	}

	private static boolean rewriteMethodDescs(List<MethodNode> methods, String from, String to) {
		if (methods == null) {
			return false;
		}
		boolean changed = false;
		for (MethodNode method : methods) {
			String desc = swap(method.desc, from, to);
			String signature = swap(method.signature, from, to);
			if (!same(desc, method.desc)) {
				method.desc = desc;
				changed = true;
			}
			if (!same(signature, method.signature)) {
				method.signature = signature;
				changed = true;
			}
		}
		return changed;
	}

	private static boolean rewriteMethod(MethodNode method, String from, String to) {
		boolean changed = false;
		if (method.localVariables != null) {
			for (org.objectweb.asm.tree.LocalVariableNode local : method.localVariables) {
				String desc = swap(local.desc, from, to);
				String signature = swap(local.signature, from, to);
				if (!same(desc, local.desc)) {
					local.desc = desc;
					changed = true;
				}
				if (!same(signature, local.signature)) {
					local.signature = signature;
					changed = true;
				}
			}
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode block : method.tryCatchBlocks) {
				String blockType = swap(block.type, from, to);
				if (!same(blockType, block.type)) {
					block.type = blockType;
					changed = true;
				}
			}
		}
		if (method.instructions == null) {
			return changed;
		}
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode methodInsn) {
				changed |= rewriteOwnerAndDesc(methodInsn, from, to);
			} else if (insn instanceof FieldInsnNode fieldInsn) {
				String owner = swap(fieldInsn.owner, from, to);
				String desc = swap(fieldInsn.desc, from, to);
				if (!same(owner, fieldInsn.owner)) {
					fieldInsn.owner = owner;
					changed = true;
				}
				if (!same(desc, fieldInsn.desc)) {
					fieldInsn.desc = desc;
					changed = true;
				}
			} else if (insn instanceof InvokeDynamicInsnNode dynamic) {
				String desc = swap(dynamic.desc, from, to);
				if (!same(desc, dynamic.desc)) {
					dynamic.desc = desc;
					changed = true;
				}
				changed |= rewriteBootstrapArgs(dynamic, from, to);
			} else if (insn instanceof TypeInsnNode typeInsn) {
				String desc = swap(typeInsn.desc, from, to);
				if (!same(desc, typeInsn.desc)) {
					typeInsn.desc = desc;
					changed = true;
				}
			} else if (insn instanceof FrameNode frame) {
				changed |= rewriteFrameList(frame.local, from, to);
				changed |= rewriteFrameList(frame.stack, from, to);
			} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
				String typeText = swap(type.getDescriptor(), from, to);
				if (!same(typeText, type.getDescriptor())) {
					ldc.cst = Type.getType(typeText);
					changed = true;
				}
			}
		}
		return changed;
	}

	private static boolean rewriteOwnerAndDesc(MethodInsnNode insn, String from, String to) {
		boolean changed = false;
		String owner = swap(insn.owner, from, to);
		String desc = swap(insn.desc, from, to);
		if (!same(owner, insn.owner)) {
			insn.owner = owner;
			changed = true;
		}
		if (!same(desc, insn.desc)) {
			insn.desc = desc;
			changed = true;
		}
		return changed;
	}

	private static boolean rewriteBootstrapArgs(InvokeDynamicInsnNode insn, String from, String to) {
		Object[] args = insn.bsmArgs;
		if (args == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (arg instanceof Type type) {
				String typeText = swap(type.getDescriptor(), from, to);
				if (!same(typeText, type.getDescriptor())) {
					args[i] = Type.getType(typeText);
					changed = true;
				}
			} else if (arg instanceof Handle handle) {
				String owner = swap(handle.getOwner(), from, to);
				String desc = swap(handle.getDesc(), from, to);
				if (!same(owner, handle.getOwner()) || !same(desc, handle.getDesc())) {
					args[i] = new Handle(handle.getTag(), owner, handle.getName(), desc, handle.isInterface());
					changed = true;
				}
			}
		}
		return changed;
	}

	private static boolean rewriteFrameList(List<Object> entries, String from, String to) {
		if (entries == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < entries.size(); i++) {
			Object entry = entries.get(i);
			if (entry == null) {
				continue;
			}
			String text = String.valueOf(entry);
			String swapped = swap(text, from, to);
			if (!same(swapped, text)) {
				entries.set(i, swapped);
				changed = true;
			}
		}
		return changed;
	}

	private static boolean rewriteAnnotationList(List<AnnotationNode> annotations, String from, String to) {
		if (annotations == null) {
			return false;
		}
		boolean changed = false;
		for (AnnotationNode annotation : annotations) {
			changed |= rewriteAnnotation(annotation, from, to);
		}
		return changed;
	}

	private static boolean rewriteAnnotation(AnnotationNode annotation, String from, String to) {
		if (annotation == null || annotation.values == null) {
			return false;
		}
		return rewriteAnnoValues(annotation.values, from, to);
	}

	private static boolean rewriteAnnoValues(List<?> values, String from, String to) {
		boolean changed = false;
		for (int i = 0; i < values.size(); i++) {
			Object value = values.get(i);
			if (value instanceof AnnotationNode nested) {
				changed |= rewriteAnnotation(nested, from, to);
			} else if (value instanceof List<?> nested) {
				changed |= rewriteAnnoValues(nested, from, to);
			} else if (value instanceof String text) {
				String swapped = swap(text, from, to);
				if (!same(swapped, text)) {
					@SuppressWarnings("unchecked")
					List<Object> mutable = (List<Object>) values;
					mutable.set(i, swapped);
					changed = true;
				}
			}
		}
		return changed;
	}

	private static boolean renameMouseOverIndy(ClassNode classNode) {
		boolean changed = false;
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof InvokeDynamicInsnNode dynamic)) {
					continue;
				}
				if (!"isMouseOver".equals(dynamic.name) || dynamic.desc == null || !dynamic.desc.contains(MOUSE_OVER_NOW)) {
					continue;
				}
				dynamic.name = "containsMouse";
				changed = true;
			}
		}
		return changed;
	}

	private static boolean stripInterface(ClassNode classNode, String internalName) {
		if (classNode.interfaces == null) {
			return false;
		}
		return classNode.interfaces.remove(internalName);
	}

	private static void renameMember(ClassNode classNode, String from, String to) {
		for (FieldNode field : classNode.fields) {
			if (from.equals(field.name)) {
				field.name = to;
			}
		}
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && from.equals(field.name)) {
					field.name = to;
				}
			}
		}
	}

	private static boolean retargetDrawInject(ClassNode classNode) {
		MethodNode method = findMethod(classNode, "jeiPlusPlus$drawCreativeTabs");
		if (method == null || method.visibleAnnotations == null) {
			return false;
		}
		boolean changed = false;
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (annotation.values == null) {
				continue;
			}
			List<Object> values = annotation.values;
			for (int j = 0; j < values.size() - 1; j += 2) {
				if (!"method".equals(String.valueOf(values.get(j)))) {
					continue;
				}
				Object names = values.get(j + 1);
				if (names instanceof List<?> list) {
					for (int k = 0; k < list.size(); k++) {
						if ("draw".equals(String.valueOf(list.get(k)))) {
							@SuppressWarnings("unchecked")
							List<Object> mutable = (List<Object>) list;
							mutable.set(k, "drawForeground");
							changed = true;
						}
					}
				} else if ("draw".equals(String.valueOf(names))) {
					values.set(j + 1, "drawForeground");
					changed = true;
				}
			}
		}
		if (changed) {
			LOGGER.info("jeasync: JEI++ ingredient grid draw is drawForeground in {}", classNode.name);
		}
		return changed;
	}

	private static void setInject(MethodNode method, String methodName, String atValue) {
		if (method.visibleAnnotations == null) {
			return;
		}
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotation.desc) || annotation.values == null) {
				continue;
			}
			List<Object> values = annotation.values;
			for (int j = 0; j < values.size() - 1; j += 2) {
				String key = String.valueOf(values.get(j));
				if ("method".equals(key)) {
					Object names = values.get(j + 1);
					if (names instanceof List<?> list) {
						for (int k = 0; k < list.size(); k++) {
							@SuppressWarnings("unchecked")
							List<Object> mutable = (List<Object>) list;
							mutable.set(k, methodName);
						}
					} else {
						values.set(j + 1, methodName);
					}
				} else if ("at".equals(key)) {
					for (AnnotationNode at : atList(values.get(j + 1))) {
						if (at.values == null) {
							continue;
						}
						List<Object> atValues = at.values;
						for (int n = 0; n < atValues.size() - 1; n += 2) {
							if ("value".equals(String.valueOf(atValues.get(n)))) {
								atValues.set(n + 1, atValue);
							}
						}
					}
				}
			}
		}
	}

	private static List<AnnotationNode> atList(Object ats) {
		if (ats instanceof AnnotationNode at) {
			return List.of(at);
		}
		if (ats instanceof List<?> list) {
			List<AnnotationNode> nodes = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof AnnotationNode at) {
					nodes.add(at);
				}
			}
			return nodes;
		}
		return List.of();
	}

	private static boolean stubReturn(ClassNode classNode, String name) {
		MethodNode method = findMethod(classNode, name);
		if (method == null) {
			return false;
		}
		InsnList insns = new InsnList();
		insns.add(new InsnNode(Opcodes.RETURN));
		clearMethodBody(method, insns);
		method.maxStack = 0;
		if (method.maxLocals < 1) {
			method.maxLocals = 1;
		}
		return true;
	}

	private static boolean stubReturnArgument(ClassNode classNode, String name, int slot) {
		MethodNode method = findMethod(classNode, name);
		if (method == null) {
			return false;
		}
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, slot));
		insns.add(new InsnNode(Opcodes.ARETURN));
		clearMethodBody(method, insns);
		method.maxStack = 1;
		if (method.maxLocals < slot + 1) {
			method.maxLocals = slot + 1;
		}
		return true;
	}

	private static boolean stubBooleanFalse(ClassNode classNode, String name) {
		MethodNode method = findMethod(classNode, name);
		if (method == null) {
			return false;
		}
		InsnList insns = new InsnList();
		insns.add(new InsnNode(Opcodes.ICONST_0));
		insns.add(new InsnNode(Opcodes.IRETURN));
		clearMethodBody(method, insns);
		method.maxStack = 1;
		if (method.maxLocals < 1) {
			method.maxLocals = 1;
		}
		return true;
	}

	private static boolean stubNullReturn(ClassNode classNode, String name) {
		MethodNode method = findMethod(classNode, name);
		if (method == null) {
			return false;
		}
		InsnList insns = new InsnList();
		insns.add(new InsnNode(Opcodes.ACONST_NULL));
		insns.add(new InsnNode(Opcodes.ARETURN));
		clearMethodBody(method, insns);
		method.maxStack = 1;
		if (method.maxLocals < 1) {
			method.maxLocals = 1;
		}
		return true;
	}

	private static void stubByReturn(MethodNode method) {
		InsnList insns = new InsnList();
		int returnType = Type.getReturnType(method.desc).getSort();
		if (returnType == Type.VOID) {
			insns.add(new InsnNode(Opcodes.RETURN));
			method.maxStack = 0;
		} else if (returnType >= Type.BOOLEAN && returnType <= Type.INT) {
			insns.add(new InsnNode(Opcodes.ICONST_0));
			insns.add(new InsnNode(Opcodes.IRETURN));
			method.maxStack = 1;
		} else if (returnType == Type.LONG) {
			insns.add(new InsnNode(Opcodes.LCONST_0));
			insns.add(new InsnNode(Opcodes.LRETURN));
			method.maxStack = 2;
		} else if (returnType == Type.FLOAT) {
			insns.add(new InsnNode(Opcodes.FCONST_0));
			insns.add(new InsnNode(Opcodes.FRETURN));
			method.maxStack = 1;
		} else if (returnType == Type.DOUBLE) {
			insns.add(new InsnNode(Opcodes.DCONST_0));
			insns.add(new InsnNode(Opcodes.DRETURN));
			method.maxStack = 2;
		} else {
			insns.add(new InsnNode(Opcodes.ACONST_NULL));
			insns.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
		}
		clearMethodBody(method, insns);
		if (method.maxLocals < 1) {
			method.maxLocals = 1;
		}
	}

	private static void addPseudo(ClassNode classNode) {
		List<AnnotationNode> annotations = classNode.visibleAnnotations;
		if (annotations == null) {
			annotations = new ArrayList<>();
			classNode.visibleAnnotations = annotations;
		}
		for (AnnotationNode annotation : annotations) {
			if ("Lorg/spongepowered/asm/mixin/Pseudo;".equals(annotation.desc)) {
				return;
			}
		}
		annotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Pseudo;"));
	}

	private static void clearMethodBody(MethodNode method, InsnList insns) {
		method.instructions = insns;
		if (method.tryCatchBlocks != null) {
			method.tryCatchBlocks.clear();
		}
		if (method.localVariables != null) {
			method.localVariables.clear();
		}
	}

	private static MethodNode findMethod(ClassNode classNode, String name) {
		for (MethodNode method : classNode.methods) {
			if (name.equals(method.name)) {
				return method;
			}
		}
		return null;
	}

	private static String swap(String value, String from, String to) {
		if (value == null || !value.contains(from)) {
			return value;
		}
		return value.replace(from, to);
	}

	private static boolean same(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}

	private static void recomputeFrames(ClassNode node) {
		stripFrames(node);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
			@Override
			protected String getCommonSuperClass(String type1, String type2) {
				return "java/lang/Object";
			}
		};
		node.accept(writer);
		ClassNode fresh = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(fresh, 0);
		node.version = fresh.version;
		node.access = fresh.access;
		node.name = fresh.name;
		node.signature = fresh.signature;
		node.superName = fresh.superName;
		node.interfaces = fresh.interfaces;
		node.sourceFile = fresh.sourceFile;
		node.sourceDebug = fresh.sourceDebug;
		node.module = fresh.module;
		node.outerClass = fresh.outerClass;
		node.outerMethod = fresh.outerMethod;
		node.outerMethodDesc = fresh.outerMethodDesc;
		node.visibleAnnotations = fresh.visibleAnnotations;
		node.invisibleAnnotations = fresh.invisibleAnnotations;
		node.visibleTypeAnnotations = fresh.visibleTypeAnnotations;
		node.invisibleTypeAnnotations = fresh.invisibleTypeAnnotations;
		node.attrs = fresh.attrs;
		node.innerClasses = fresh.innerClasses;
		node.nestHostClass = fresh.nestHostClass;
		node.nestMembers = fresh.nestMembers;
		node.permittedSubclasses = fresh.permittedSubclasses;
		node.recordComponents = fresh.recordComponents;
		node.fields = fresh.fields;
		node.methods = fresh.methods;
	}

	private static void stripFrames(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.instructions == null) {
				continue;
			}
			ListIterator<AbstractInsnNode> iterator = method.instructions.iterator();
			while (iterator.hasNext()) {
				if (iterator.next() instanceof FrameNode) {
					iterator.remove();
				}
			}
		}
	}

	private static Field findField(Class<?> type, String name) {
		Class<?> current = type;
		while (current != null) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		return null;
	}

	private enum Kind {
		ICON_BUTTON,
		INPUT,
		MISSING,
		TREE_BUTTON,
		SKIP_MIXIN,
		AE2
	}

	private static final class Hook implements ILaunchPluginService.ITransformerLoader {
		private final ILaunchPluginService.ITransformerLoader delegate;

		private Hook(ILaunchPluginService.ITransformerLoader delegate) {
			this.delegate = delegate;
		}

		@Override
		public byte[] buildTransformedClassNodeFor(String className) throws ClassNotFoundException {
			return rewrite(className, delegate.buildTransformedClassNodeFor(className));
		}
	}
}
