package luowei.fortress_besieged.siege.spawn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in / registerable spawn recipes. May later be driven by datapacks.
 * Preserves recipe ids {@code default} / {@code light} / {@code heavy}.
 */
public final class SiegeSpawnRecipes {
	public static final String DEFAULT_ID = "default";

	private static final Map<String, SiegeSpawnRecipe> RECIPES = new LinkedHashMap<>();

	static {
		// Ensure unit registry is initialized before recipe entries resolve unit ids.
		SiegeUnitDefinitions.all();

		register(new SiegeSpawnRecipe(DEFAULT_ID, 12, List.of(
				SiegeMobSpawnEntry.of("zombie_armed", 50),
				SiegeMobSpawnEntry.of("skeleton_archer", 30),
				SiegeMobSpawnEntry.of("spider", 20)
		)));
		register(new SiegeSpawnRecipe("light", 6, List.of(
				SiegeMobSpawnEntry.of("zombie", 50),
				SiegeMobSpawnEntry.of("husk", 30),
				SiegeMobSpawnEntry.of("zombie_armed", 20)
		)));
		register(new SiegeSpawnRecipe("heavy", 24, List.of(
				SiegeMobSpawnEntry.of("zombie_heavy", 35),
				SiegeMobSpawnEntry.of("skeleton_archer", 25),
				SiegeMobSpawnEntry.of("creeper", 15),
				SiegeMobSpawnEntry.of("witch", 10),
				SiegeMobSpawnEntry.of("vindicator", 15)
		)));
	}

	private SiegeSpawnRecipes() {
	}

	public static void register(SiegeSpawnRecipe recipe) {
		RECIPES.put(recipe.id(), recipe);
	}

	public static Optional<SiegeSpawnRecipe> get(String id) {
		return Optional.ofNullable(RECIPES.get(id));
	}

	public static SiegeSpawnRecipe require(String id) {
		return get(id).orElseThrow(() -> new IllegalArgumentException("Unknown recipe: " + id));
	}

	public static SiegeSpawnRecipe getDefault() {
		return require(DEFAULT_ID);
	}

	public static Collection<SiegeSpawnRecipe> all() {
		return RECIPES.values();
	}
}
