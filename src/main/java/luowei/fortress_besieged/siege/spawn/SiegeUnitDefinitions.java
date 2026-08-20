package luowei.fortress_besieged.siege.spawn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;

/**
 * Built-in siege unit definitions (equipment + entity type).
 * Recipes reference these by id via {@link SiegeMobSpawnEntry}.
 */
public final class SiegeUnitDefinitions {
	private static final Map<String, SiegeUnitDefinition> UNITS = new LinkedHashMap<>();

	static {
		register(SiegeUnitDefinition.bare("zombie", EntityType.ZOMBIE));
		register(new SiegeUnitDefinition(
				"zombie_armed",
				EntityType.ZOMBIE,
				Items.LEATHER_HELMET,
				Items.LEATHER_CHESTPLATE,
				null,
				null,
				Items.IRON_SWORD,
				null));
		register(new SiegeUnitDefinition(
				"zombie_heavy",
				EntityType.ZOMBIE,
				Items.IRON_HELMET,
				Items.IRON_CHESTPLATE,
				Items.IRON_LEGGINGS,
				Items.IRON_BOOTS,
				Items.IRON_SWORD,
				Items.SHIELD));
		register(SiegeUnitDefinition.bare("husk", EntityType.HUSK));
		register(new SiegeUnitDefinition(
				"skeleton_archer",
				EntityType.SKELETON,
				Items.LEATHER_HELMET,
				null,
				null,
				null,
				Items.BOW,
				Items.ARROW));
		register(SiegeUnitDefinition.bare("spider", EntityType.SPIDER));
		register(SiegeUnitDefinition.bare("creeper", EntityType.CREEPER));
		register(SiegeUnitDefinition.bare("witch", EntityType.WITCH));
		register(new SiegeUnitDefinition(
				"vindicator",
				EntityType.VINDICATOR,
				null,
				null,
				null,
				null,
				Items.IRON_AXE,
				null));
	}

	private SiegeUnitDefinitions() {
	}

	public static void register(SiegeUnitDefinition unit) {
		UNITS.put(unit.id(), unit);
	}

	public static Optional<SiegeUnitDefinition> get(String id) {
		return Optional.ofNullable(UNITS.get(id));
	}

	public static SiegeUnitDefinition require(String id) {
		return get(id).orElseThrow(() -> new IllegalArgumentException("Unknown unit: " + id));
	}

	public static Collection<SiegeUnitDefinition> all() {
		return UNITS.values();
	}
}
