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
	public static final float SITE_ACCEPT_PROBABILITY = 0.15f;

	/**
	 * Fraction of the PBS EntityPollList to siege each natural wave / {@code /siege spawn}.
	 * Sample count = clamp(round(n × ratio), 1..n) when the poll is non-empty.
	 */
	public static final double TARGET_SAMPLE_RATIO = 0.2;

	/**
	 * Day-time of day (getDayTime % 24000) that triggers a natural siege wave.
	 * 11000 ≈ shortly before nightfall.
	 */
	public static final int NATURAL_TRIGGER_DAY_TIME = 11000;

	private SiegeSpawnConfig() {
	}
}
