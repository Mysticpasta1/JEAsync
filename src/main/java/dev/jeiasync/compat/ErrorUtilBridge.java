package dev.jeiasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Adds the void {@code checkNotNull(Object, String)} that JEI 15.71 removed.
 * Mixin refuses a public static method on the mixin class, so the method is written onto
 * {@code ErrorUtil} before Mixin merges that class.
 */
public final class ErrorUtilBridge {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String DESC = "(Ljava/lang/Object;Ljava/lang/String;)V";

	private ErrorUtilBridge() {
	}

	public static void prepare(ClassNode classNode) {
		if (classNode == null || !"mezz/jei/common/util/ErrorUtil".equals(classNode.name)) {
			return;
		}
		for (MethodNode method : classNode.methods) {
			if ("checkNotNull".equals(method.name) && DESC.equals(method.desc)) {
				return;
			}
		}
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "checkNotNull", DESC, null, null);
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new LdcInsnNode(" must not be null."));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(new LdcInsnNode("load.ErrorUtilMixin"));
		insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/jeiasync/MixinApplied", "mark", "(Ljava/lang/String;)V", false));
		insns.add(new InsnNode(Opcodes.RETURN));
		method.instructions = insns;
		method.maxStack = 3;
		method.maxLocals = 2;
		classNode.methods.add(method);
		LOGGER.info("JEI Async: restored void ErrorUtil.checkNotNull for plugins built before JEI 15.71");
	}
}
