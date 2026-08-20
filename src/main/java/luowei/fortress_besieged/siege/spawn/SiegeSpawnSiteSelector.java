package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Siege spawn site selection:
 * <ol>
 *   <li>Territory chunk center from PBS {@code queryTerritoryCentroid}</li>
 *   <li>Sample up to {@link SiegeSpawnConfig#BORDER_SAMPLE_COUNT} BORDER chunks
 *       from {@code queryOrgTerritory(...).border()}</li>
 *   <li>Around each sample, collect {@code HOSTILE_BORDER} within Chebyshev
 *       ≤ {@link SiegeSpawnConfig#HOSTILE_EXPAND_CHEBYSHEV}; keep nearest
 *       ≤ {@link SiegeSpawnConfig#NEAREST_CANDIDATE_LIMIT} by distance to center</li>
 *   <li>Each candidate accepted independently with
 *       {@code p = SITE_ACCEPT_PROBABILITY} (Bernoulli); count may be 0</li>
 * </ol>
 * TODO: 加一个占领和边界的占比，来区分领土类型，改变集群类型和取边界区块数
 */
public final class SiegeSpawnSiteSelector {
	/** Alias of {@link SiegeSpawnConfig#NEAREST_CANDIDATE_LIMIT}. */
	public static final int NEAREST_CANDIDATE_LIMIT = SiegeSpawnConfig.NEAREST_CANDIDATE_LIMIT;

	private SiegeSpawnSiteSelector() {
	}

	public record SiteSelection(
			UUID targetId,
			ChunkPos territoryCenter,
			List<ChunkPos> nearestHostileBorders,
			List<ChunkPos> chosenSites,
			double expectedSites
	) {
		/** Expected Bernoulli count ≈ candidates × p. */
		public SiteSelection(
				UUID targetId,
				ChunkPos territoryCenter,
				List<ChunkPos> nearestHostileBorders,
				List<ChunkPos> chosenSites
		) {
			this(
					targetId,
					territoryCenter,
					nearestHostileBorders,
					chosenSites,
					nearestHostileBorders == null
							? 0.0
							: nearestHostileBorders.size() * (double) SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY);
		}
	}

	/**
	 * @return selection; {@code chosenSites} empty if no territory / no HOSTILE_BORDER / all Bernoulli rejects
	 */
	public static SiteSelection select(ServerLevel level, UUID targetId, Random random) {
		ChunkPos center = PbsTerritoryAdapter.getTerritoryCenter(level, targetId).orElse(null);
		if (center == null) {
			return new SiteSelection(targetId, null, List.of(), List.of());
		}
		List<ChunkPos> nearest = PbsTerritoryAdapter.findNearestHostileBorders(
				level,
				center,
				SiegeSpawnConfig.NEAREST_CANDIDATE_LIMIT,
				targetId,
				random,
				SiegeSpawnConfig.BORDER_SAMPLE_COUNT,
				SiegeSpawnConfig.HOSTILE_EXPAND_CHEBYSHEV);
		List<ChunkPos> chosen = pickSites(nearest, random);
		return new SiteSelection(targetId, center, List.copyOf(nearest), List.copyOf(chosen));
	}

	/**
	 * Independent Bernoulli trial per candidate with {@link SiegeSpawnConfig#SITE_ACCEPT_PROBABILITY}.
	 * Order of {@code chosen} follows candidate distance order (nearest-first list).
	 */
	static List<ChunkPos> pickSites(List<ChunkPos> candidates, Random random) {
		if (candidates.isEmpty()) {
			return List.of();
		}
		List<ChunkPos> chosen = new ArrayList<>();
		for (ChunkPos candidate : candidates) {
			if (random.nextFloat() < SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY) {
				chosen.add(candidate);
			}
		}
		return List.copyOf(chosen);
	}
}
