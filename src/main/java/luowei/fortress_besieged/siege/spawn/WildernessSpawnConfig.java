package luowei.fortress_besieged.siege.spawn;

/**
 * Tunables for the wilderness pack pipeline (NATURAL, chase-only).
 * Overworld, nether, and end share one sparse player-around scanner.
 */
public final class WildernessSpawnConfig {
	/**
	 * How often (world game-time ticks) to scan online players for a spawn attempt.
	 * 4000 = 200 seconds on Hard; Normal ×2, Easy ×3.
	 */
	public static final int SCAN_INTERVAL_TICKS = 4000;

	/**
	 * Default Bernoulli chance written as {@code attemptChance} in
	 * {@code fortress-besieged-wilderness.json}. Runtime value is the loaded config.
	 */
	public static final float ATTEMPT_CHANCE = 0.40f;

	/**
	 * After a passed-chance attempt that failed (no NATURAL site, no mobs),
	 * short backoff so we do not retry every scan.
	 */
	public static final int FAIL_COOLDOWN_TICKS = 6000;

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

	/** Max successful pack spawns per dimension per scan tick. */
	public static final int BUDGET_PER_SCAN = 1;

	private WildernessSpawnConfig() {
	}
}
