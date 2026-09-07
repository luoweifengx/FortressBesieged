package luowei.fortress_besieged.siege.spawn;

/**
 * Tunable constants for siege spawn / natural waves.
 * Prefer changing these over hard-coding in selectors.
 */
public final class SiegeSpawnConfig {
	/**
	 * How many BORDER chunks to sample when collecting HOSTILE_BORDER candidates.
	 * TODO: 加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数
	 */
	public static final int BORDER_SAMPLE_COUNT = 4;

	/**
	 * Chebyshev expand radius (chunks) around each sampled BORDER when searching
	 * for {@code HOSTILE_BORDER}.
	 */
	public static final int HOSTILE_EXPAND_CHEBYSHEV = 2;

	/** Keep at most this many nearest HOSTILE_BORDER candidates. */
	public static final int NEAREST_CANDIDATE_LIMIT = 20;

	/**
	 * Per-candidate Bernoulli accept probability for spawning a cluster.
	 * Expected sites ≈ candidates × this value; may be 0.
	 */
	public static final float SITE_ACCEPT_PROBABILITY = 0.4f;

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
	 * type is on duty. Scaled by vanilla difficulty (×1 hard, ×2 normal, ×3 easy).
	 * Hard base is 2000 ticks = 100 seconds. A miss does not consume the rest of the day.
	 */
	public static final int NATURAL_PLAN_INTERVAL_TICKS = 2000;

	/** Max natural {@code spawnRolled} calls per dimension per world tick. */
	public static final int NATURAL_SPAWN_BUDGET_PER_TICK = 1;

	/**
	 * New-player protection: overworld game days ({@code dayTime/24000}) before
	 * {@code isTimeToBesiege} becomes true. Sticky once true.
	 */
	public static final int NEW_PLAYER_PROTECTION_DAYS = 1;

	private SiegeSpawnConfig() {
	}
}
