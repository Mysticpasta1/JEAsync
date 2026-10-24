package dev.jeasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Speeds Apotheosis enchantment-level lookups and Iron's Spells arcane anvil registration.
 * <p>
 * The targets are not on the compile classpath. The empty optional mixins exist so this runs
 * while those classes load.
 * <p>
 * Mysticpasta1 — anvil registration was reading affixes on plain items, and arcane anvil encoded
 * the same spell once per weapon.
 */
public final class ArcaneRegistration {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String ADVENTURE = "dev.shadowsoffire.apotheosis.adventure.AdventureEvents";
	private static final String ENCH_LEVELS = "(Ldev/shadowsoffire/placebo/events/GetEnchantmentLevelEvent;)V";
	private static final String ARCANE = "io.redspace.ironsspellbooks.jei.ArcaneAnvilJeiRecipe";
	private static final String ARCANE_MAKER = "io.redspace.ironsspellbooks.jei.ArcaneAnvilRecipeMaker";
	private static final String GET_RECIPES =
		"(Lmezz/jei/api/recipe/vanilla/IVanillaRecipeFactory;Lio/redspace/ironsspellbooks/jei/JeiPlugin$ItemFinder;)"
			+ "Ljava/util/List;";
	private static final String FINDER = "io.redspace.ironsspellbooks.jei.JeiPlugin$ItemFinder";
	private static final String SCROLL_OWNER = "io/redspace/ironsspellbooks/api/spells/ISpellContainer";
	private static final String SCROLL_DESC =
		"(Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;ILnet/minecraft/world/item/ItemStack;)"
			+ "Lio/redspace/ironsspellbooks/api/spells/ISpellContainer;";
	private static final String AFFINITY_OWNER = "io/redspace/ironsspellbooks/api/item/curios/AffinityData";
	private static final String AFFINITY_DESC =
		"(Lnet/minecraft/world/item/ItemStack;Lio/redspace/ironsspellbooks/api/item/curios/AffinityData;)V";

	private ArcaneRegistration() {
	}

	public static void prepare(ClassNode classNode) {
		if (classNode == null || classNode.name == null) {
			return;
		}
		String name = classNode.name.replace('/', '.');
		if (ADVENTURE.equals(name)) {
			skipPlainAffixes(classNode);
		} else if (ARCANE.equals(name)) {
			reuseRecipeTags(classNode);
			oneStackPerRecipe(classNode);
		} else if (ARCANE_MAKER.equals(name)) {
			oneArcaneRecipePerType(classNode);
		} else if (FINDER.equals(name)) {
			classifyItemsOnce(classNode);
		}
	}

	private static void skipPlainAffixes(ClassNode classNode) {
		MethodNode method = find(classNode, "enchLevels", ENCH_LEVELS);
		if (method == null || method.instructions == null) {
			LOGGER.warn("jeasync: affix level check left unchanged; enchLevels changed shape");
			return;
		}
		LabelNode keep = new LabelNode();
		InsnList prefix = new InsnList();
		prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
		prefix.add(new MethodInsnNode(
			Opcodes.INVOKEVIRTUAL,
			"dev/shadowsoffire/placebo/events/GetEnchantmentLevelEvent",
			"getStack",
			"()Lnet/minecraft/world/item/ItemStack;",
			false
		));
		prefix.add(new MethodInsnNode(
			Opcodes.INVOKESTATIC,
			"dev/jeasync/AffixLevelSkip",
			"plain",
			"(Lnet/minecraft/world/item/ItemStack;)Z",
			false
		));
		prefix.add(new JumpInsnNode(Opcodes.IFEQ, keep));
		prefix.add(new InsnNode(Opcodes.RETURN));
		prefix.add(keep);
		prefix.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(prefix);
		method.maxStack = Math.max(method.maxStack, 2);
		LOGGER.info("jeasync: enchantment levels skip items that have no affix data");
	}

	private static void reuseRecipeTags(ClassNode classNode) {
		int scrolls = 0;
		int affinity = 0;
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call)) {
					continue;
				}
				if ("createScrollContainer".equals(call.name) && SCROLL_OWNER.equals(call.owner) && SCROLL_DESC.equals(call.desc)) {
					method.instructions.set(call, new MethodInsnNode(
						Opcodes.INVOKESTATIC,
						"dev/jeasync/ArcaneRecipeTags",
						"applyScroll",
						"(Ljava/lang/Object;ILnet/minecraft/world/item/ItemStack;)Ljava/lang/Object;",
						false
					));
					scrolls++;
				} else if ("set".equals(call.name) && AFFINITY_OWNER.equals(call.owner) && AFFINITY_DESC.equals(call.desc)) {
					method.instructions.set(call, new MethodInsnNode(
						Opcodes.INVOKESTATIC,
						"dev/jeasync/ArcaneRecipeTags",
						"applyAffinity",
						"(Lnet/minecraft/world/item/ItemStack;Ljava/lang/Object;)V",
						false
					));
					affinity++;
				}
			}
		}
		if (scrolls == 0 || affinity == 0) {
			LOGGER.warn(
				"jeasync: arcane anvil tag reuse found {} scroll calls and {} affinity calls",
				scrolls,
				affinity
			);
			return;
		}
		LOGGER.info("jeasync: arcane anvil reuses stored spell tags");
	}

	private static void oneArcaneRecipePerType(ClassNode classNode) {
		MethodNode method = find(classNode, "getRecipes", GET_RECIPES);
		if (method == null || method.instructions == null) {
			LOGGER.warn("jeasync: arcane anvil examples left unchanged; getRecipes changed shape");
			return;
		}
		method.instructions.clear();
		if (method.tryCatchBlocks != null) {
			method.tryCatchBlocks.clear();
		}
		if (method.localVariables != null) {
			method.localVariables.clear();
		}
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		body.add(new VarInsnNode(Opcodes.ALOAD, 1));
		body.add(new MethodInsnNode(
			Opcodes.INVOKESTATIC,
			"dev/jeasync/ArcaneRecipeExamples",
			"oneOfEach",
			"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;",
			false
		));
		body.add(new InsnNode(Opcodes.ARETURN));
		method.instructions.add(body);
		method.maxStack = Math.max(method.maxStack, 2);
		LOGGER.info("jeasync: arcane anvil shows one recipe of each type");
	}

	/**
	 * Imbue and affinity attune call {@code getEnabledSpells} and {@code rangeClosed} while drawing
	 * one recipe, and that walk is every spell and every level. Both calls in this class become the
	 * one-spell and one-level helpers. Scroll upgrade and item upgrade do not use them.
	 */
	private static void oneStackPerRecipe(ClassNode classNode) {
		int spells = 0;
		int levels = 0;
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null) {
				continue;
			}
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call)) {
					continue;
				}
				if ("getEnabledSpells".equals(call.name)
					&& "io/redspace/ironsspellbooks/api/registry/SpellRegistry".equals(call.owner)
					&& "()Ljava/util/List;".equals(call.desc)) {
					method.instructions.set(call, new MethodInsnNode(
						Opcodes.INVOKESTATIC,
						"dev/jeasync/ArcaneRecipeExamples",
						"oneSpell",
						"()Ljava/util/List;",
						false
					));
					spells++;
				} else if ("rangeClosed".equals(call.name)
					&& "java/util/stream/IntStream".equals(call.owner)
					&& "(II)Ljava/util/stream/IntStream;".equals(call.desc)) {
					method.instructions.set(call, new MethodInsnNode(
						Opcodes.INVOKESTATIC,
						"dev/jeasync/ArcaneRecipeExamples",
						"oneLevel",
						"(II)Ljava/util/stream/IntStream;",
						false
					));
					levels++;
				}
			}
		}
		if (spells == 0 || levels == 0) {
			LOGGER.warn(
				"jeasync: arcane anvil draw still expands; found {} spell walks and {} level walks",
				spells,
				levels
			);
			return;
		}
		LOGGER.info("jeasync: arcane anvil draws one stack of each ingredient");
	}

	private static void classifyItemsOnce(ClassNode classNode) {
		MethodNode method = find(classNode, "<init>", "(Lmezz/jei/api/runtime/IIngredientManager;)V");
		if (method == null || method.instructions == null) {
			LOGGER.warn("jeasync: item classify skipped; the Iron's Spells finder changed shape");
			return;
		}
		int replaced = 0;
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode call)) {
				continue;
			}
			if (!"getAllItemStacks".equals(call.name) || !"()Ljava/util/Collection;".equals(call.desc)) {
				continue;
			}
			method.instructions.set(call, new MethodInsnNode(
				Opcodes.INVOKESTATIC,
				"dev/jeasync/IssItemScan",
				"representative",
				"(Ljava/lang/Object;)Ljava/util/Collection;",
				false
			));
			replaced++;
		}
		if (replaced == 0) {
			LOGGER.warn("jeasync: item classify skipped; getAllItemStacks was not in the finder");
			return;
		}
		LOGGER.info("jeasync: arcane anvil classifies each item once");
	}

	private static MethodNode find(ClassNode classNode, String name, String desc) {
		for (MethodNode method : classNode.methods) {
			if (name.equals(method.name) && desc.equals(method.desc)) {
				return method;
			}
		}
		return null;
	}
}
