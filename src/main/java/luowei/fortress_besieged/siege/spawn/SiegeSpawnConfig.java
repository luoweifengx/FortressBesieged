package luowei.fortress_besieged.siege.spawn;

/**
 * Tunable constants for siege spawn / natural waves.
 * Prefer changing these over hard-coding in selectors.
 */
public final class SiegeSpawnConfig {
	/**
	 * How many BORDER chunks to sample when collecting siege spawn-site candidates
	 * ({@code HOSTILE_BORDER} and {@code HOSTILE}).
	 * TODO: 加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数
	 */
	public static final int BORDER_SAMPLE_COUNT = 4;

	/**
	 * Inclusive Chebyshev min/max around each sampled BORDER for spawn sites.
	 * Distance 0–1 (the BORDER itself and the adjacent ring) are skipped.
	 */
	public static final int HOSTILE_EXPAND_MIN_CHEBYSHEV = 2;
	public static final int HOSTILE_EXPAND_CHEBYSHEV = 4;

	/** Keep at most this many nearest spawn-site candidates. */
	public static final int NEAREST_CANDIDATE_LIMIT = 20;

	/**
	 * Per-candidate Bernoulli accept probability for spawning a cluster.
	 * Expected sites ≈ candidates × this value; may be 0.
	 */
	public static final float SITE_ACCEPT_PROBABILITY = 0.25f;

	/**
	 * Fraction of the PBS EntityPollList to siege for {@code /siege spawn} command waves.
	 * Natural refresh does not use this; it rolls each target's stage {@code chance}
	 * while the stage window is open.
	 * Sample count = clamp(round(n × ratio), 1..n) when the poll is non-empty.
	 */
	public static final double TARGET_SAMPLE_RATIO = 0.2;

	/**
	 * How often (world game-time ticks) to refresh the PBS poll, and the
	 * minimum gap between natural chance rolls for one online player while a cluster
	 * type is on duty. Same on easy / normal / hard (2000 ticks). A miss does not
	 * consume the rest of the day. Wilderness scans still use difficulty scaling.
	 */
	public static final int NATURAL_PLAN_INTERVAL_TICKS = 2000;

	/** Max natural {@code spawnRolled} calls per dimension per world tick. */
	public static final int NATURAL_SPAWN_BUDGET_PER_TICK = 1;

	/**
	 * New-player protection: overworld game days ({@code dayTime/24000}) before
	 * {@code isTimeToBesiege} becomes true. Sticky once true.
	 */
	public static final int NEW_PLAYER_PROTECTION_DAYS = 1;

	/**
	 * Vindicators stay out of species rolls until the overworld day index
	 * ({@code dayTime/24000}) reaches this value. Index 0 is the first day, so
	 * {@code 2} starts them after the second day. Locked weight stays with the
	 * rest of the template.
	 */
	public static final long VINDICATOR_MIN_DAY_INDEX = 2L;

	private SiegeSpawnConfig() {
	}
}
