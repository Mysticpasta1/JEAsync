package dev.jeasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Tinkers Jewelry Ex still extends tconjei's removed {@code AbstractMaterialStatsCategory}.
 * Current tconjei uses {@code MaterialStatsCategory}, whose constructor takes the fields the
 * subclass writes itself, so this adds the old one-argument constructor and retargets the subclass.
 */
public final class JewelryCategoryBridge {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String PARENT = "me/paypur/tconjei/jei/MaterialStatsCategory";
	private static final String REMOVED = "me/paypur/tconjei/jei/AbstractMaterialStatsCategory";
	private static final String JEWELRY = "com/oooooohmy/tinkers_jewelry_ex/jei/JewelryStatsCategory";
	private static final String GUI_HELPER = "mezz/jei/api/helpers/IGuiHelper";
	private static final String INIT = "(L" + GUI_HELPER + ";)V";

	private JewelryCategoryBridge() {
	}

	public static void prepare(ClassNode classNode) {
		if (classNode == null || classNode.name == null) {
			return;
		}
		if (PARENT.equals(classNode.name)) {
			addGuiHelperConstructor(classNode);
		} else if (JEWELRY.equals(classNode.name)) {
			retargetParent(classNode);
		}
	}

	private static void addGuiHelperConstructor(ClassNode classNode) {
		for (MethodNode method : classNode.methods) {
			if ("<init>".equals(method.name) && INIT.equals(method.desc)) {
				return;
			}
		}
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", INIT, null, null);
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new IntInsnNode(Opcodes.SIPUSH, 178));
		insns.add(new IntInsnNode(Opcodes.SIPUSH, 200));
		insns.add(new MethodInsnNode(
			Opcodes.INVOKEINTERFACE,
			GUI_HELPER,
			"createBlankDrawable",
			"(II)Lmezz/jei/api/gui/drawable/IDrawableStatic;",
			true
		));
		insns.add(new org.objectweb.asm.tree.FieldInsnNode(
			Opcodes.PUTFIELD,
			PARENT,
			"background",
			"Lmezz/jei/api/gui/drawable/IDrawable;"
		));
		insns.add(new InsnNode(Opcodes.RETURN));
		method.instructions = insns;
		method.maxStack = 4;
		method.maxLocals = 2;
		classNode.methods.add(method);
		LOGGER.info("jeasync: tconjei material stats accept the jewelry category constructor");
	}

	private static void retargetParent(ClassNode classNode) {
		classNode.superName = PARENT;
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && REMOVED.equals(call.owner)) {
					call.owner = PARENT;
				}
			}
		}
		LOGGER.info("jeasync: tinkers jewelry stats extend tconjei MaterialStatsCategory");
	}
}
