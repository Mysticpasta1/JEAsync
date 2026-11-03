package dev.jeasync.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * JEI 0.233 and 0.234 are built with an optimizer that reuses argument slots for primitives inside
 * render, input, and plugin methods. A mixin callback then reloads those slots expecting
 * {@code this} or the method arguments and fails verification. Copy every argument into a fresh
 * local at method entry and rewrite the body to use those shadows, leaving the real argument slots
 * intact for the whole method. Copying up front keeps the rewrite valid across branches.
 */
public final class LocalSlotFixer {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String RENDER_GUI = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V";
	private static final Map<String, Set<String>> TARGETS = Map.of(
		"mezz/jei/library/render/FluidTankRenderer", Set.of(
			"render(Lnet/minecraft/client/gui/GuiGraphics;Ljava/lang/Object;II)V"
		),
		"mezz/jei/gui/recipes/RecipeCategoryTab", Set.of(
			"draw(ZLnet/minecraft/client/gui/GuiGraphics;II)V"
		),
		"mezz/jei/library/gui/recipes/RecipeLayout", Set.of(
			"drawRecipe(Lnet/minecraft/client/gui/GuiGraphics;II)V"
		),
		"mezz/jei/library/recipes/collect/RecipeMap", Set.of(
			"getRecipeTypes(Lmezz/jei/api/ingredients/ITypedIngredient;)Ljava/util/stream/Stream;"
		),
		"mezz/jei/gui/overlay/bookmarks/BookmarkOverlay", Set.of(
			"updateBounds(Lmezz/jei/api/gui/handlers/IGuiProperties;Ljava/util/Set;)V",
			"createInputHandler()Lmezz/jei/common/input/IInputTarget;"
		),
		"mezz/jei/library/load/PluginCaller", Set.of(
			"callOnPlugins(Ljava/lang/String;Ljava/util/List;Ljava/util/function/Consumer;)V"
		),
		"mezz/jei/gui/recipes/RecipeTransferButtonController", Set.of(
			"transferRecipe(ZZ)Z"
		),
		"mezz/jei/forge/platform/FluidHelper", Set.of(
			"getTooltip(Ljava/util/List;Lnet/minecraftforge/fluids/FluidStack;Lnet/minecraft/world/item/TooltipFlag;)V"
		),
		"mezz/jei/gui/recipes/lookups/IngredientLookupState", Set.of(
			"create(Lmezz/jei/api/recipe/IRecipeManager;Lmezz/jei/api/recipe/IFocusGroup;Ljava/util/List;Lmezz/jei/common/transfer/RecipeTransferService;)Lmezz/jei/gui/recipes/lookups/ILookupState;"
		),
		"mezz/jei/gui/recipes/RecipesGui", Set.of(
			RENDER_GUI,
			"m_88315_" + RENDER_GUI.substring("render".length()),
			"method_25394" + RENDER_GUI.substring("render".length())
		)
	);

	private LocalSlotFixer() {
	}

	public static void prepare(ClassNode classNode) {
		Set<String> methods = TARGETS.get(classNode.name);
		if (methods == null) {
			return;
		}
		for (MethodNode method : classNode.methods) {
			if (methods.contains(method.name + method.desc) && fix(method)) {
				LOGGER.info("jeasync: moved JEI's reused argument slots out of {}.{} for mixin callbacks", classNode.name, method.name);
			}
		}
	}

	private static boolean fix(MethodNode method) {
		int argumentEnd = argumentEnd(method);
		if (argumentEnd == 0) {
			return false;
		}
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		int[] shadow = new int[argumentEnd];
		int nextLocal = method.maxLocals;
		InsnList prefix = new InsnList();
		if (!isStatic) {
			shadow[0] = nextLocal++;
			prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
			prefix.add(new VarInsnNode(Opcodes.ASTORE, shadow[0]));
		}
		int slot = isStatic ? 0 : 1;
		for (Type type : Type.getArgumentTypes(method.desc)) {
			shadow[slot] = nextLocal;
			nextLocal += type.getSize();
			prefix.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
			prefix.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), shadow[slot]));
			slot += type.getSize();
		}
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof VarInsnNode var && var.var < argumentEnd) {
				var.var = shadow[var.var];
			} else if (insn instanceof IincInsnNode inc && inc.var < argumentEnd) {
				inc.var = shadow[inc.var];
			}
		}
		method.instructions.insert(prefix);
		method.maxLocals = nextLocal;
		removeFrames(method);
		return true;
	}

	private static int argumentEnd(MethodNode method) {
		int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (Type type : Type.getArgumentTypes(method.desc)) {
			slot += type.getSize();
		}
		return slot;
	}

	private static void removeFrames(MethodNode method) {
		List<AbstractInsnNode> frames = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FrameNode) {
				frames.add(insn);
			}
		}
		for (AbstractInsnNode frame : frames) {
			method.instructions.remove(frame);
		}
	}
}
