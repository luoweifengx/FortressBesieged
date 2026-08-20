package luowei.fortress_besieged.siege.spawn;

/**
 * One weighted entry in a {@link SiegeSpawnRecipe}: unit definition + recipe weight.
 */
public record SiegeMobSpawnEntry(SiegeUnitDefinition unit, int weight) {
	public SiegeMobSpawnEntry {
		if (unit == null) {
			throw new IllegalArgumentException("unit");
		}
		if (weight <= 0) {
			throw new IllegalArgumentException("weight must be > 0");
		}
	}

	/** Convenience: look up a registered unit by id. */
	public static SiegeMobSpawnEntry of(String unitId, int weight) {
		return new SiegeMobSpawnEntry(SiegeUnitDefinitions.require(unitId), weight);
	}
}
