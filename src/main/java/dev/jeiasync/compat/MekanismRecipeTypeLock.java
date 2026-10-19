package dev.jeiasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Keeps Mekanism's JEI recipe-type set and map on one lock.
 * Plugins register types while {@code findType} drains the set, and an unlocked add is missed or throws.
 */
public final class MekanismRecipeTypeLock {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String OWNER = "mekanism/client/jei/MekanismJEIRecipeType";
	private static final String LAZY_NEOFORGE = "net/neoforged/neoforge/common/util/Lazy";
	private static final String LAZY_FORGE = "net/minecraftforge/common/util/Lazy";

	private MekanismRecipeTypeLock() {
	}

	public static void apply(ClassNode classNode) {
		MethodNode findType = find(classNode, "findType", null);
		String lazy = LAZY_NEOFORGE;
		MethodNode init = find(classNode, "<init>", initDesc(lazy));
		if (init == null) {
			lazy = LAZY_FORGE;
			init = find(classNode, "<init>", initDesc(lazy));
		}
		if (findType == null || init == null) {
			LOGGER.warn("JEI Async: Mekanism recipe type lock skipped; the class shape changed");
			return;
		}
		findType.access |= Opcodes.ACC_SYNCHRONIZED;
		lockConstructor(init, lazy);
		LOGGER.info("JEI Async: Mekanism JEI recipe types register under one lock");
	}

	private static String initDesc(String lazy) {
		return "(L" + lazy + ";Ljava/lang/Class;)V";
	}

	private static void lockConstructor(MethodNode init, String lazy) {
		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();
		LabelNode after = new LabelNode();
		LabelNode addToSet = new LabelNode();
		LabelNode doneLock = new LabelNode();
		InsnList insns = new InsnList();
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false));
		insns.add(new LdcInsnNode(Type.getObjectType(OWNER)));
		insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
		insns.add(new InsnNode(Opcodes.MONITORENTER));
		insns.add(start);
		insns.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "allKnownTypes", "Ljava/util/Set;"));
		insns.add(new JumpInsnNode(Opcodes.IFNONNULL, addToSet));
		insns.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "knownTypes", "Ljava/util/Map;"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "uid", "()Lnet/minecraft/resources/ResourceLocation;", false));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(new JumpInsnNode(Opcodes.GOTO, doneLock));
		insns.add(addToSet);
		insns.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "allKnownTypes", "Ljava/util/Set;"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
		insns.add(new InsnNode(Opcodes.POP));
		insns.add(doneLock);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
		insns.add(new InsnNode(Opcodes.MONITOREXIT));
		insns.add(end);
		insns.add(new JumpInsnNode(Opcodes.GOTO, after));
		insns.add(handler);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
		insns.add(new InsnNode(Opcodes.MONITOREXIT));
		insns.add(new InsnNode(Opcodes.ATHROW));
		insns.add(after);
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insns.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, "lazyUid", "L" + lazy + ";"));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
		insns.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, "recipeClass", "Ljava/lang/Class;"));
		insns.add(new InsnNode(Opcodes.RETURN));
		init.instructions = insns;
		init.tryCatchBlocks.clear();
		init.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
		if (init.localVariables != null) {
			init.localVariables.clear();
		}
		init.maxStack = 4;
		init.maxLocals = 4;
	}

	private static MethodNode find(ClassNode classNode, String name, String desc) {
		for (MethodNode method : classNode.methods) {
			if (method.name.equals(name) && (desc == null || desc.equals(method.desc))) {
				return method;
			}
		}
		return null;
	}
}
