package dev.jeasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Puts {@code ISearchField.setValue} and {@code setFocused} back on {@code GuiTextFieldFilter}.
 * <p>
 * The Forge jar renames those overrides to {@code EditBox}'s SRG names and leaves the interface
 * methods unimplemented. {@code IngredientListOverlayController.init} calls the interface, the GUI
 * plugin aborts, and the overlay is never registered.
 * <p>
 * Mysticpasta1 — the published 15.74.0.225 jar has {@code m_94144_} and {@code m_93692_} only.
 */
public final class SearchFieldBridge {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String OWNER = "mezz/jei/gui/input/GuiTextFieldFilter";
	private static final String SET_VALUE = "(Ljava/lang/String;)V";
	private static final String SET_FOCUSED = "(Z)V";

	private SearchFieldBridge() {
	}

	public static void prepare(ClassNode classNode) {
		if (classNode == null || !OWNER.equals(classNode.name)) {
			return;
		}
		boolean added = false;
		added |= bridge(classNode, "setValue", SET_VALUE, "m_94144_", Opcodes.ALOAD);
		added |= bridge(classNode, "setFocused", SET_FOCUSED, "m_93692_", Opcodes.ILOAD);
		if (added) {
			LOGGER.info("jeasync: search field keeps the ISearchField names the Forge jar remapped away");
		}
	}

	private static boolean bridge(ClassNode classNode, String name, String desc, String renamed, int argOpcode) {
		if (has(classNode, name, desc) || !has(classNode, renamed, desc)) {
			return false;
		}
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(argOpcode, 1));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, classNode.name, renamed, desc, false));
		insns.add(new InsnNode(Opcodes.RETURN));
		method.instructions = insns;
		method.maxStack = 2;
		method.maxLocals = 2;
		classNode.methods.add(method);
		return true;
	}

	private static boolean has(ClassNode classNode, String name, String desc) {
		for (MethodNode method : classNode.methods) {
			if (name.equals(method.name) && desc.equals(method.desc)) {
				return true;
			}
		}
		return false;
	}
}
