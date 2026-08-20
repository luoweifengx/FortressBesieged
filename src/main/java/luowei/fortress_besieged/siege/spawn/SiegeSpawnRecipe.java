package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Siege spawn recipe: total mob count + weighted unit-definition table.
 * <p>
 * Clusters do not absorb natural spawns; mobs are rolled from this recipe
 * and equipped from {@link SiegeUnitDefinition}.
 */
public final class SiegeSpawnRecipe {
	private final String id;
	private final int totalCount;
	private final List<SiegeMobSpawnEntry> entries;
	private final int totalWeight;

	public SiegeSpawnRecipe(String id, int totalCount, List<SiegeMobSpawnEntry> entries) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id");
		}
		if (totalCount < 3) {
			throw new IllegalArgumentException("totalCount must be >= 3 (cluster needs m>=3 for assault math)");
		}
		if (entries == null || entries.isEmpty()) {
			throw new IllegalArgumentException("entries");
		}
		this.id = id;
		this.totalCount = totalCount;
		this.entries = List.copyOf(entries);
		int w = 0;
		for (SiegeMobSpawnEntry entry : this.entries) {
			w += entry.weight();
		}
		this.totalWeight = w;
	}

	public String id() {
		return id;
	}

	public int totalCount() {
		return totalCount;
	}

	public List<SiegeMobSpawnEntry> entries() {
		return entries;
	}

	/** Weighted roll of {@link #totalCount} unit definitions (with replacement). */
	public List<SiegeUnitDefinition> rollUnits(Random random) {
		List<SiegeUnitDefinition> rolled = new ArrayList<>(totalCount);
		for (int i = 0; i < totalCount; i++) {
			rolled.add(pickOne(random));
		}
		return rolled;
	}

	public SiegeUnitDefinition pickOne(Random random) {
		int roll = random.nextInt(totalWeight);
		int acc = 0;
		for (SiegeMobSpawnEntry entry : entries) {
			acc += entry.weight();
			if (roll < acc) {
				return entry.unit();
			}
		}
		return entries.get(entries.size() - 1).unit();
	}
}
