package dev.jeiasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Replaces the tool-name arguments on Tinkers with Enchants anvil debug lines.
 * <p>
 * The mod is not on the compile classpath. The empty optional mixin exists so this runs while
 * that class loads. The skip still raises the anvil cost, and a successful combine still writes
 * the output.
 * <p>
 * Mysticpasta1 — those debug arguments formatted every rejected book enchant.
 */
public final class AnvilEnchantFormat {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String HANDLER = "t.tinkerswithenchants.events.AnvilEnchantHandler";
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final String COMPONENT = "()Lnet/minecraft/network/chat/Component;";
	private static final String TEXT = "net/minecraft/network/chat/Component";

	private AnvilEnchantFormat() {
	}

	public static void prepare(ClassNode classNode) {
		if (classNode == null || classNode.name == null) {
			return;
		}
		if (!HANDLER.equals(classNode.name.replace('/', '.'))) {
			return;
		}
		int replaced = 0;
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || !isDisplayName(call)) {
					continue;
				}
				AbstractInsnNode next = nextReal(call);
				if (!(next instanceof MethodInsnNode text) || !isGetString(text)) {
					continue;
				}
				method.instructions.set(call, new MethodInsnNode(
					Opcodes.INVOKESTATIC,
					"dev/jeiasync/AnvilEnchantNames",
					"display",
					"(Lnet/minecraft/world/item/ItemStack;)Ljava/lang/String;",
					false
				));
				method.instructions.remove(text);
				replaced++;
			}
		}
		if (replaced != 2) {
			LOGGER.warn("JEI Async: anvil enchant name skip found {} display-name calls", replaced);
			return;
		}
		LOGGER.info("JEI Async: anvil recipe registration skips tool names on enchant debug lines");
	}

	private static boolean isDisplayName(MethodInsnNode call) {
		if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !STACK.equals(call.owner) || !COMPONENT.equals(call.desc)) {
			return false;
		}
		return "m_41611_".equals(call.name) || "getDisplayName".equals(call.name);
	}

	private static boolean isGetString(MethodInsnNode call) {
		return call.getOpcode() == Opcodes.INVOKEINTERFACE
			&& TEXT.equals(call.owner)
			&& "getString".equals(call.name)
			&& "()Ljava/lang/String;".equals(call.desc);
	}

	/** Labels, line numbers, and frames have a negative opcode and do not consume the stack. */
	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		for (AbstractInsnNode next = insn.getNext(); next != null; next = next.getNext()) {
			if (next.getOpcode() >= 0) {
				return next;
			}
		}
		return null;
	}
}
