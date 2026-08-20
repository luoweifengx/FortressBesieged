package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Samples a proportional subset of PBS siege targets for a spawn wave.
 * <p>
 * Weights are currently <strong>uniform</strong> (weight=1 per poll entry).
 * Territory-size weighting can be plugged into {@link #weightOf} later.
 */
public final class SiegeTargetSampler {
	private SiegeTargetSampler() {
	}

	/**
	 * @return distinct target UUIDs (may be empty if poll empty)
	 */
	public static List<UUID> sampleWaveTargets(MinecraftServer server, ServerLevel level, Random random) {
		List<UUID> poll = PbsTerritoryAdapter.listSiegeTargets(server);
		if (poll.isEmpty()) {
			return List.of();
		}
		int want = sampleCount(poll.size());
		return weightedSampleWithoutReplacement(poll, level, want, random);
	}

	/** How many targets to draw from a poll of size {@code n}. */
	public static int sampleCount(int n) {
		if (n <= 0) {
			return 0;
		}
		int rounded = (int) Math.round(n * SiegeSpawnConfig.TARGET_SAMPLE_RATIO);
		return Math.min(n, Math.max(1, rounded));
	}

	/**
	 * Uniform weight for now. Future: return owned-chunk count (or similar)
	 * so larger territories are more likely to be sieged.
	 */
	static int weightOf(ServerLevel level, UUID targetId) {
		return 1;
	}

	static List<UUID> weightedSampleWithoutReplacement(
			List<UUID> poll,
			ServerLevel level,
			int want,
			Random random
	) {
		want = Math.min(want, poll.size());
		if (want <= 0) {
			return List.of();
		}
		List<UUID> pool = new ArrayList<>(poll);
		List<UUID> picked = new ArrayList<>(want);
		for (int i = 0; i < want; i++) {
			int totalWeight = 0;
			int[] weights = new int[pool.size()];
			for (int j = 0; j < pool.size(); j++) {
				weights[j] = Math.max(1, weightOf(level, pool.get(j)));
				totalWeight += weights[j];
			}
			int roll = random.nextInt(totalWeight);
			int acc = 0;
			int chosen = pool.size() - 1;
			for (int j = 0; j < pool.size(); j++) {
				acc += weights[j];
				if (roll < acc) {
					chosen = j;
					break;
				}
			}
			picked.add(pool.remove(chosen));
		}
		return List.copyOf(picked);
	}
}
