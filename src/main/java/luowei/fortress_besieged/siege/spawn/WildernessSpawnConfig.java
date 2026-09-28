package luowei.fortress_besieged.siege.spawn;

/**
 * Tunables for the wilderness pack pipeline (NATURAL, chase-only).
 * Overworld, nether, and end share one leave-territory timer per player.
 */
public final class WildernessSpawnConfig {
	/**
	 * Ticks a player must stay outside their own OCCUPIED/BORDER before
	 * a spawn attempt. Same on every non-peaceful difficulty (30 seconds).
	 */
	public static final int AWAY_INTERVAL_TICKS = 600;

	/**
	 * Default chance written as {@code attemptChance} in
	 * {@code fortress-besieged-wilderness.json}. 1.0 = always try site pick
	 * after the away timer. Runtime value is the loaded config.
	 */
	public static final float ATTEMPT_CHANCE = 1.0f;

	/**
	 * Easy pack size is the wilderness {@code count} range. Normal and Hard
	 * multiply that range (rounded, min {@link SiegeClusterTemplate#MIN_COUNT}).
	 */
	public static final double COUNT_MULTIPLIER_NORMAL = 1.4D;
	public static final double COUNT_MULTIPLIER_HARD = 1.9D;

	/**
	 * Vanilla hostile spawn: never on top of a player (24 blocks).
	 */
	public static final int MIN_PLAYER_DISTANCE = 24;

	/**
	 * Stay inside the vanilla monster despawn sphere so freshly spawned
	 * members are not instantly discarded.
	 */
	public static final int MAX_PLAYER_DISTANCE = 96;

	/** Random xz offset for pack members; they need not huddle. */
	public static final int MEMBER_SPREAD_BLOCKS = 12;

	/** Random columns around the player to try per attempt. */
	public static final int SITE_ATTEMPTS = 12;

	/** Extra Y samples per column (caves), in addition to the surface heightmap. */
	public static final int Y_SAMPLES_PER_COLUMN = 3;

	/** Max successful pack spawns per dimension per world tick. */
	public static final int BUDGET_PER_TICK = 1;

	private WildernessSpawnConfig() {
	}
}
