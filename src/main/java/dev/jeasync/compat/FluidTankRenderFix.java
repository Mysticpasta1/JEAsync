package dev.jeasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * JEI 15.74 compiles {@code FluidTankRenderer.render} so the tank width is stored with
 * {@code istore_0}. That slot is {@code this} on an instance method. The method still verifies on
 * its own, because it never loads slot 0 again. A mixin that enters or leaves the method with
 * {@code aload_0} then fails verification, and the failed define is cached for the client thread.
 * <p>
 * The width and height reads move to a new slot. Slot 0 stays the renderer, so those hooks load
 * {@code this}.
 */
public final class FluidTankRenderFix {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String OWNER = "mezz/jei/library/render/FluidTankRenderer";

	private FluidTankRenderFix() {
	}

	public static void repair(ClassNode classNode) {
		if (classNode == null || !OWNER.equals(classNode.name)) {
			return;
		}
		int repaired = 0;
		for (MethodNode method : classNode.methods) {
			if ((method.access & Opcodes.ACC_STATIC) != 0) {
				continue;
			}
			if (repairInstanceMethod(method)) {
				repaired++;
			}
		}
		if (repaired > 0) {
			LOGGER.info("jeasync: FluidTankRenderer keeps local 0 as this in {} method(s)", repaired);
		}
	}

	private static boolean repairInstanceMethod(MethodNode method) {
		boolean clobbersThis = false;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ISTORE && var.var == 0) {
				clobbersThis = true;
				break;
			}
		}
		if (!clobbersThis) {
			return false;
		}
		int slot = method.maxLocals;
		method.maxLocals = slot + 1;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof VarInsnNode var) || var.var != 0) {
				continue;
			}
			int opcode = var.getOpcode();
			if (opcode == Opcodes.ISTORE || opcode == Opcodes.ILOAD) {
				var.var = slot;
			}
		}
		return true;
	}
}
