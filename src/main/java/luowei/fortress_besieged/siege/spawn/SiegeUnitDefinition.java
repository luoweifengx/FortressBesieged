package luowei.fortress_besieged.siege.spawn;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Siege unit definition: entity type plus optional armor / weapons.
 * <p>
 * Equipment fields may be {@code null} (no item in that slot).
 * Recipe weight lives on {@link SiegeMobSpawnEntry}, not here, so the same
 * unit can be reused with different weights across recipes.
 */
public final class SiegeUnitDefinition {
	private final String id;
	private final EntityType<? extends Mob> type;
	private final Item helmet;
	private final Item chestplate;
	private final Item leggings;
	private final Item boots;
	private final Item mainHand;
	private final Item offHand;

	public SiegeUnitDefinition(
			String id,
			EntityType<? extends Mob> type,
			Item helmet,
			Item chestplate,
			Item leggings,
			Item boots,
			Item mainHand,
			Item offHand
	) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id");
		}
		if (type == null) {
			throw new IllegalArgumentException("type");
		}
		this.id = id;
		this.type = type;
		this.helmet = helmet;
		this.chestplate = chestplate;
		this.leggings = leggings;
		this.boots = boots;
		this.mainHand = mainHand;
		this.offHand = offHand;
	}

	/** Bare unit: type only, no equipment. */
	public static SiegeUnitDefinition bare(String id, EntityType<? extends Mob> type) {
		return new SiegeUnitDefinition(id, type, null, null, null, null, null, null);
	}

	public String id() {
		return id;
	}

	public EntityType<? extends Mob> type() {
		return type;
	}

	public Item helmet() {
		return helmet;
	}

	public Item chestplate() {
		return chestplate;
	}

	public Item leggings() {
		return leggings;
	}

	public Item boots() {
		return boots;
	}

	public Item mainHand() {
		return mainHand;
	}

	public Item offHand() {
		return offHand;
	}

	/** Apply configured gear; drop chance set to 0 so gear is not farmed. */
	public void equip(Mob mob) {
		if (mob == null) {
			return;
		}
		setSlot(mob, EquipmentSlot.HEAD, helmet);
		setSlot(mob, EquipmentSlot.CHEST, chestplate);
		setSlot(mob, EquipmentSlot.LEGS, leggings);
		setSlot(mob, EquipmentSlot.FEET, boots);
		setSlot(mob, EquipmentSlot.MAINHAND, mainHand);
		setSlot(mob, EquipmentSlot.OFFHAND, offHand);
	}

	private static void setSlot(Mob mob, EquipmentSlot slot, Item item) {
		if (item == null) {
			return;
		}
		mob.setItemSlot(slot, new ItemStack(item));
		mob.setDropChance(slot, 0.0F);
	}
}
