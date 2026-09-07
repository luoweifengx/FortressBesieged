package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.EnchantSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.SpeciesSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.StatsSpec;

/**
 * Roll one mob from a template: species → stats → six slots → durability → enchant.
 */
public final class SiegeTemplateApplier {
	/** Vanilla {@code Mob} default for armor / hand slots. */
	public static final float VANILLA_EQUIPMENT_DROP_CHANCE = 0.085F;
	public static final float EQUIPMENT_DROP_MULTIPLIER = 0.1F;
	public static final float EQUIPMENT_DROP_CHANCE = VANILLA_EQUIPMENT_DROP_CHANCE * EQUIPMENT_DROP_MULTIPLIER;

	private SiegeTemplateApplier() {
	}

	public static void setEquippedDropChance(Mob mob, EquipmentSlot slot) {
		mob.setDropChance(slot, EQUIPMENT_DROP_CHANCE);
	}

	public static EntityType<? extends Mob> resolveType(String entityId) {
		ResourceLocation id = parseId(entityId);
		if (id == null) {
			return null;
		}
		var holder = BuiltInRegistries.ENTITY_TYPE.get(id);
		if (holder.isEmpty()) {
			return null;
		}
		EntityType<?> type = holder.get().value();
		try {
			@SuppressWarnings("unchecked")
			EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) type;
			return mobType;
		} catch (ClassCastException e) {
			return null;
		}
	}

	/** Ghast 4×4 body: lift spawn Y so they don't clip into the floor. */
	public static BlockPos offsetSpawnPos(EntityType<?> type, BlockPos pos, Random random) {
		if (pos == null || type != EntityType.GHAST) {
			return pos;
		}
		int lift = 6 + random.nextInt(5);
		return pos.above(lift);
	}

	public static void apply(ServerLevel level, Mob mob, SiegeClusterTemplate template, SpeciesSpec species, Random random) {
		if (mob == null || template == null) {
			return;
		}
		if (species != null && species.baby() && mob instanceof Zombie zombie) {
			zombie.setBaby(true);
		}
		StatsSpec stats = species == null ? template.stats() : species.mergedStats(template.stats());
		applyStats(mob, stats, random);
		if (skipsEquipment(mob)) {
			return;
		}
		boolean easter = template.easterEggChance() > 0.0D && random.nextDouble() < template.easterEggChance();
		if (easter && !template.easterEggSlots().isEmpty()) {
			equipFixed(level, mob, template, template.easterEggSlots(), random);
			return;
		}
		rollAndEquipSlots(level, mob, template, random);
		if (mob instanceof Pillager && template.bannerChance() > 0.0D && random.nextDouble() < template.bannerChance()) {
			equipBanner(level, mob);
		}
	}

	private static void applyStats(Mob mob, StatsSpec stats, Random random) {
		Double health = pickNumber(stats.health(), random);
		if (health != null) {
			AttributeInstance inst = mob.getAttribute(Attributes.MAX_HEALTH);
			if (inst != null) {
				inst.setBaseValue(health);
			}
			mob.setHealth(health.floatValue());
		}
		if (mob instanceof Ghast) {
			return;
		}
		Double speed = pickNumber(stats.speed(), random);
		if (speed != null) {
			AttributeInstance inst = mob.getAttribute(Attributes.MOVEMENT_SPEED);
			if (inst != null) {
				inst.setBaseValue(speed);
			}
		}
		Double attack = pickNumber(stats.attack(), random);
		if (attack != null) {
			AttributeInstance inst = mob.getAttribute(Attributes.ATTACK_DAMAGE);
			if (inst != null) {
				inst.setBaseValue(attack);
			}
		}
	}

	private static void rollAndEquipSlots(ServerLevel level, Mob mob, SiegeClusterTemplate template, Random random) {
		equipRolled(level, mob, template, EquipmentSlot.HEAD, "helmet", random, false);
		equipRolled(level, mob, template, EquipmentSlot.CHEST, "chestplate", random, false);
		equipRolled(level, mob, template, EquipmentSlot.LEGS, "leggings", random, false);
		equipRolled(level, mob, template, EquipmentSlot.FEET, "boots", random, false);
		equipRolled(level, mob, template, EquipmentSlot.MAINHAND, mainHandSlotKey(mob, template), random, true);
		if (shouldRollOffhand(mob, template)) {
			equipRolled(level, mob, template, EquipmentSlot.OFFHAND, "offHand", random, false);
		}
	}

	private static String mainHandSlotKey(Mob mob, SiegeClusterTemplate template) {
		if (isRangedMob(mob) && template.slots().containsKey("mainHandRanged")) {
			return "mainHandRanged";
		}
		return "mainHand";
	}

	private static boolean shouldRollOffhand(Mob mob, SiegeClusterTemplate template) {
		Map<String, Integer> off = template.slots().get("offHand");
		if (off == null || off.isEmpty()) {
			return false;
		}
		boolean hasTotem = off.keySet().stream().anyMatch(SiegeTemplateApplier::isTotem);
		if (hasTotem && !isMeleeHumanoid(mob)) {
			return false;
		}
		return true;
	}

	private static void equipRolled(
			ServerLevel level,
			Mob mob,
			SiegeClusterTemplate template,
			EquipmentSlot slot,
			String slotKey,
			Random random,
			boolean mainHand
	) {
		if (!mob.canUseSlot(slot)) {
			return;
		}
		Map<String, Integer> pool = template.slots().get(slotKey);
		if (pool == null || pool.isEmpty()) {
			return;
		}
		Map<String, Integer> filtered = WeightedPicker.filter(pool, key -> allowItem(mob, slotKey, key, mainHand));
		String picked = WeightedPicker.pick(filtered, random);
		if (picked == null || "empty".equals(picked)) {
			if (mainHand && template.mainHandEmptyFallback() != null) {
				picked = template.mainHandEmptyFallback();
			} else {
				return;
			}
		}
		ItemStack stack = createStack(level, template, picked, slot, random);
		if (stack.isEmpty()) {
			if (mainHand && template.mainHandEmptyFallback() != null) {
				stack = createStack(level, template, template.mainHandEmptyFallback(), slot, random);
			}
			if (stack.isEmpty()) {
				return;
			}
		}
		mob.setItemSlot(slot, stack);
		setEquippedDropChance(mob, slot);
	}

	private static void equipFixed(
			ServerLevel level,
			Mob mob,
			SiegeClusterTemplate template,
			Map<String, String> fixed,
			Random random
	) {
		equipFixedSlot(level, mob, template, EquipmentSlot.HEAD, fixed.get("helmet"), random);
		equipFixedSlot(level, mob, template, EquipmentSlot.CHEST, fixed.get("chestplate"), random);
		equipFixedSlot(level, mob, template, EquipmentSlot.LEGS, fixed.get("leggings"), random);
		equipFixedSlot(level, mob, template, EquipmentSlot.FEET, fixed.get("boots"), random);
		equipFixedSlot(level, mob, template, EquipmentSlot.MAINHAND, fixed.get("mainHand"), random);
		equipFixedSlot(level, mob, template, EquipmentSlot.OFFHAND, fixed.get("offHand"), random);
	}

	private static void equipFixedSlot(
			ServerLevel level,
			Mob mob,
			SiegeClusterTemplate template,
			EquipmentSlot slot,
			String itemId,
			Random random
	) {
		if (itemId == null || "empty".equals(itemId) || !mob.canUseSlot(slot)) {
			return;
		}
		ItemStack stack = createStack(level, template, itemId, slot, random);
		if (stack.isEmpty()) {
			return;
		}
		mob.setItemSlot(slot, stack);
		setEquippedDropChance(mob, slot);
	}

	private static void equipBanner(ServerLevel level, Mob mob) {
		ItemStack banner = Raid.getOminousBannerInstance(
				level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN));
		if (banner == null || banner.isEmpty()) {
			return;
		}
		mob.setItemSlot(EquipmentSlot.HEAD, banner);
		setEquippedDropChance(mob, EquipmentSlot.HEAD);
	}

	private static ItemStack createStack(
			ServerLevel level,
			SiegeClusterTemplate template,
			String itemId,
			EquipmentSlot slot,
			Random random
	) {
		Item item = resolveItem(itemId);
		if (item == null || item == Items.AIR) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = new ItemStack(item);
		applyDurability(stack, template.durability(), random);
		maybeEnchant(level, stack, template.enchant(), slot, random);
		return stack;
	}

	private static void applyDurability(ItemStack stack, Map<String, Integer> weights, Random random) {
		if (!stack.isDamageableItem() || weights == null || weights.isEmpty()) {
			return;
		}
		String band = WeightedPicker.pick(weights, random);
		float min;
		float max;
		switch (band == null ? "used" : band) {
			case "ruined" -> { min = 0.05F; max = 0.25F; }
			case "worn" -> { min = 0.25F; max = 0.55F; }
			case "fresh" -> { min = 0.85F; max = 1.00F; }
			default -> { min = 0.55F; max = 0.85F; }
		}
		float pct = min + random.nextFloat() * (max - min);
		int maxDmg = stack.getMaxDamage();
		int remain = Mth.clamp(Math.round(maxDmg * pct), 1, maxDmg);
		stack.setDamageValue(maxDmg - remain);
	}

	private static void maybeEnchant(
			ServerLevel level,
			ItemStack stack,
			EnchantSpec spec,
			EquipmentSlot slot,
			Random random
	) {
		if (spec == null || spec.levelMax() <= 0 && spec.levelMin() <= 0) {
			return;
		}
		boolean armor = slot == EquipmentSlot.HEAD
				|| slot == EquipmentSlot.CHEST
				|| slot == EquipmentSlot.LEGS
				|| slot == EquipmentSlot.FEET;
		boolean weapon = slot == EquipmentSlot.MAINHAND;
		double chance = armor ? spec.armorChance() : (weapon ? spec.weaponChance() : 0.0D);
		if (chance <= 0.0D || random.nextDouble() >= chance) {
			return;
		}
		var lookup = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		List<Holder<Enchantment>> pool = enchantPool(lookup, stack, armor);
		if (pool.isEmpty()) {
			return;
		}
		int count = 1 + random.nextInt(3);
		List<Holder<Enchantment>> copy = new ArrayList<>(pool);
		for (int i = 0; i < count && !copy.isEmpty(); i++) {
			Holder<Enchantment> holder = copy.remove(random.nextInt(copy.size()));
			int max = Math.max(1, holder.value().getMaxLevel());
			int levelValue = rollEnchantLevel(spec.levelMin(), spec.levelMax(), max, random);
			stack.enchant(holder, levelValue);
		}
		if (armor && random.nextDouble() < 0.02D) {
			lookup.get(Enchantments.BINDING_CURSE).ifPresent(h -> stack.enchant(h, 1));
		}
		if (random.nextDouble() < 0.02D) {
			lookup.get(Enchantments.VANISHING_CURSE).ifPresent(h -> stack.enchant(h, 1));
		}
	}

	private static List<Holder<Enchantment>> enchantPool(
			net.minecraft.core.HolderLookup.RegistryLookup<Enchantment> lookup,
			ItemStack stack,
			boolean armor
	) {
		List<Holder<Enchantment>> pool = new ArrayList<>();
		if (armor) {
			add(lookup, pool, Enchantments.PROTECTION);
			add(lookup, pool, Enchantments.PROJECTILE_PROTECTION);
			add(lookup, pool, Enchantments.FIRE_PROTECTION);
			add(lookup, pool, Enchantments.THORNS);
			return pool;
		}
		if (stack.is(Items.BOW)) {
			add(lookup, pool, Enchantments.POWER);
			add(lookup, pool, Enchantments.FLAME);
			add(lookup, pool, Enchantments.PUNCH);
			return pool;
		}
		if (stack.is(Items.CROSSBOW)) {
			add(lookup, pool, Enchantments.PIERCING);
			add(lookup, pool, Enchantments.QUICK_CHARGE);
			add(lookup, pool, Enchantments.MULTISHOT);
			return pool;
		}
		add(lookup, pool, Enchantments.SHARPNESS);
		add(lookup, pool, Enchantments.KNOCKBACK);
		add(lookup, pool, Enchantments.FIRE_ASPECT);
		return pool;
	}

	private static void add(
			net.minecraft.core.HolderLookup.RegistryLookup<Enchantment> lookup,
			List<Holder<Enchantment>> pool,
			net.minecraft.resources.ResourceKey<Enchantment> key
	) {
		lookup.get(key).ifPresent(pool::add);
	}

	private static int rollEnchantLevel(int tableMin, int tableMax, int enchMax, Random random) {
		int table = WeightedPicker.inRange(Math.max(0, tableMin), Math.max(tableMin, tableMax), random);
		int raw;
		if (table <= 12) {
			raw = 1 + random.nextInt(2);
		} else if (table <= 18) {
			raw = 1 + random.nextInt(3);
		} else if (table <= 25) {
			raw = 2 + random.nextInt(3);
		} else {
			raw = 3 + random.nextInt(3);
		}
		return Mth.clamp(raw, 1, enchMax);
	}

	private static boolean allowItem(Mob mob, String slotKey, String itemId, boolean mainHand) {
		if ("empty".equals(itemId)) {
			return true;
		}
		boolean weaponSlot = mainHand || "mainHand".equals(slotKey) || "mainHandRanged".equals(slotKey);
		if (!weaponSlot) {
			return !isTotem(itemId) || isMeleeHumanoid(mob);
		}
		if (mob instanceof Vindicator) {
			return isAxe(itemId);
		}
		if (mob instanceof Pillager) {
			return isCrossbow(itemId);
		}
		if ("mainHandRanged".equals(slotKey)) {
			return isBow(itemId) || isCrossbow(itemId);
		}
		if (!isRangedMob(mob)) {
			return !isBow(itemId) && !isCrossbow(itemId);
		}
		return true;
	}

	private static boolean skipsEquipment(Mob mob) {
		return mob instanceof Witch || mob instanceof Evoker || mob instanceof Ravager || mob instanceof Ghast;
	}

	private static boolean isRangedMob(Mob mob) {
		if (mob instanceof WitherSkeleton) {
			return false;
		}
		return mob instanceof AbstractSkeleton || mob instanceof Pillager;
	}

	private static boolean isMeleeHumanoid(Mob mob) {
		return mob instanceof Vindicator || (mob instanceof AbstractIllager && !(mob instanceof Pillager) && !(mob instanceof Evoker));
	}

	private static boolean isBow(String id) {
		return id != null && (id.endsWith(":bow") || "bow".equals(id));
	}

	private static boolean isCrossbow(String id) {
		return id != null && (id.endsWith(":crossbow") || "crossbow".equals(id));
	}

	private static boolean isAxe(String id) {
		return id != null && (id.endsWith("_axe") || id.endsWith(":axe"));
	}

	private static boolean isTotem(String id) {
		return id != null && id.contains("totem");
	}

	private static Double pickNumber(Map<String, Integer> weights, Random random) {
		String key = WeightedPicker.pick(weights, random);
		if (key == null) {
			return null;
		}
		try {
			return Double.parseDouble(key);
		} catch (NumberFormatException e) {
			FortressBesieged.LOGGER.warn("[siege cluster] not a number key: {}", key);
			return null;
		}
	}

	private static Item resolveItem(String raw) {
		ResourceLocation id = parseId(raw);
		if (id == null) {
			return Items.AIR;
		}
		var holder = BuiltInRegistries.ITEM.get(id);
		return holder.isEmpty() ? Items.AIR : holder.get().value();
	}

	private static ResourceLocation parseId(String raw) {
		if (raw == null || raw.isBlank() || "empty".equals(raw)) {
			return null;
		}
		String id = raw.contains(":") ? raw : "minecraft:" + raw;
		return ResourceLocation.tryParse(id);
	}
}
