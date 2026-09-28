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
 *   <li>Around each sample, collect {@code HOSTILE_BORDER} and {@code HOSTILE}
 *       at Chebyshev {@link SiegeSpawnConfig#HOSTILE_EXPAND_MIN_CHEBYSHEV}–
 *       {@link SiegeSpawnConfig#HOSTILE_EXPAND_CHEBYSHEV}; keep nearest
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
	 * @return selection; {@code chosenSites} empty if no territory / no spawn site / all Bernoulli rejects
	 */
	public static SiteSelection select(ServerLevel level, UUID targetId, Random random) {
		return selectHostileBorder(level, targetId, random);
	}

	public static SiteSelection selectHostileBorder(ServerLevel level, UUID targetId, Random random) {
		ChunkPos center = PbsTerritoryAdapter.getTerritoryCenter(level, targetId).orElse(null);
		if (center == null) {
			SiegeSpawnMonitor.debug(
					"site skip player={} reason=no_territory dim={}",
					SiegeSpawnMonitor.target(level, targetId), SiegeSpawnMonitor.dim(level));
			return new SiteSelection(targetId, null, List.of(), List.of());
		}
		List<ChunkPos> nearest = PbsTerritoryAdapter.findNearestHostileBorders(
				level,
				center,
				SiegeSpawnConfig.NEAREST_CANDIDATE_LIMIT,
				targetId,
				random,
				SiegeSpawnConfig.BORDER_SAMPLE_COUNT,
				SiegeSpawnConfig.HOSTILE_EXPAND_MIN_CHEBYSHEV,
				SiegeSpawnConfig.HOSTILE_EXPAND_CHEBYSHEV);
		List<ChunkPos> chosen = pickSites(nearest, random);
		if (SiegeSpawnMonitor.isDebug()) {
			SiegeSpawnMonitor.debug(
					"site player={} center={} candidates={} chosen={} E≈{} p={} dim={}",
					SiegeSpawnMonitor.target(level, targetId),
					SiegeSpawnMonitor.chunk(center),
					nearest.size(),
					chosen.size(),
					String.format("%.2f", nearest.size() * (double) SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY),
					SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY,
					SiegeSpawnMonitor.dim(level));
		}
		return new SiteSelection(targetId, center, List.copyOf(nearest), List.copyOf(chosen));
	}

	/**
	 * Hell-tier sites: sample demon chunks as anchors (optionally snap to nearby
	 * HOSTILE_BORDER / HOSTILE). Does not run the ordinary Bernoulli pipeline.
	 */
	public static SiteSelection selectDemon(ServerLevel level, UUID targetId, Random random) {
		ChunkPos center = PbsTerritoryAdapter.getTerritoryCenter(level, targetId).orElse(null);
		if (center == null) {
			SiegeSpawnMonitor.debug(
					"site skip player={} reason=no_territory mode=demon dim={}",
					SiegeSpawnMonitor.target(level, targetId), SiegeSpawnMonitor.dim(level));
			return new SiteSelection(targetId, null, List.of(), List.of());
		}
		List<ChunkPos> demons = PbsTerritoryAdapter.queryDemonChunks(level);
		if (demons.isEmpty()) {
			SiegeSpawnMonitor.debug(
					"site skip player={} reason=no_demon_chunks dim={}",
					SiegeSpawnMonitor.target(level, targetId), SiegeSpawnMonitor.dim(level));
			return new SiteSelection(targetId, center, List.of(), List.of());
		}
		List<ChunkPos> chosen = PbsTerritoryAdapter.sampleDemonAnchorSites(
				level, 1, random, SiegeSpawnConfig.HOSTILE_EXPAND_CHEBYSHEV);
		SiegeSpawnMonitor.debug(
				"site player={} center={} mode=demon demonChunks={} chosen={} dim={}",
				SiegeSpawnMonitor.target(level, targetId),
				SiegeSpawnMonitor.chunk(center),
				demons.size(),
				chosen.size(),
				SiegeSpawnMonitor.dim(level));
		return new SiteSelection(targetId, center, List.copyOf(demons), List.copyOf(chosen), chosen.size());
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
