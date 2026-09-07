package luowei.fortress_besieged.siege.spawn;

/**
 * Hell-tier wave: independent of siege lottery. Each demon chunk rolls
 * {@link #SPAWN_CHANCE} every {@link #SCAN_INTERVAL_TICKS}.
 */
public final class HellSpawnConfig {
	/** 20 seconds. */
	public static final int SCAN_INTERVAL_TICKS = 400;

	/** Per demon chunk Bernoulli; was the end-stage hell weight. */
	public static final float SPAWN_CHANCE = 0.008f;

	/**
	 * Locked-aggro leash in blocks (horizontal). 7×7 chunks is only the
	 * acquire ring; chase continues until this distance from home (hell)
	 * or from the mob (other clusters).
	 */
	public static final double AGGRO_LEASH_BLOCKS = 128.0D;

	private HellSpawnConfig() {
	}
}
