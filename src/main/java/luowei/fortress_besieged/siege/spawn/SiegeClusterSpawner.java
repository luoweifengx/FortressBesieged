package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Spawns mobs from a recipe on chosen HOSTILE_BORDER chunks and creates clusters
 * with an initial assault target (nearest OCCUPIED/BORDER to the anchor).
 */
public final class SiegeClusterSpawner {
	private SiegeClusterSpawner() {
	}

	public record SpawnResult(
			SiegeSpawnSiteSelector.SiteSelection sites,
			List<SiegeCluster> clusters,
			String message
	) {
	}

	/**
	 * Full pipeline: site select → spawn recipe mobs per site → create cluster
	 * with nearest owned chunk as {@code targetChunk}, enter ASSAULT.
	 * Skips targets with no OCCUPIED/BORDER territory.
	 */
	public static SpawnResult spawnForTarget(
			ServerLevel level,
			UUID targetId,
			SiegeSpawnRecipe recipe,
			Random random
	) {
		List<ChunkPos> owned = PbsTerritoryAdapter.getOwnedChunks(level, targetId);
		if (owned.isEmpty()) {
			return new SpawnResult(
					new SiegeSpawnSiteSelector.SiteSelection(targetId, null, List.of(), List.of()),
					List.of(),
					"Target has no OCCUPIED/BORDER territory");
		}

		SiegeSpawnSiteSelector.SiteSelection sites = SiegeSpawnSiteSelector.select(level, targetId, random);
		if (sites.territoryCenter() == null) {
			return new SpawnResult(sites, List.of(), "Target has no OCCUPIED/BORDER territory");
		}
		if (sites.nearestHostileBorders().isEmpty()) {
			return new SpawnResult(sites, List.of(),
					"No HOSTILE_BORDER near sampled BORDER (sample="
							+ SiegeSpawnConfig.BORDER_SAMPLE_COUNT
							+ ", expandChebyshev≤"
							+ SiegeSpawnConfig.HOSTILE_EXPAND_CHEBYSHEV
							+ ")");
		}
		if (sites.chosenSites().isEmpty()) {
			return new SpawnResult(sites, List.of(),
					"Bernoulli rejected all " + sites.nearestHostileBorders().size()
							+ " candidate(s) (p=" + SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY
							+ ", E≈" + String.format("%.2f", sites.expectedSites()) + ")");
		}

		List<SiegeCluster> created = new ArrayList<>();
		for (ChunkPos site : sites.chosenSites()) {
			List<Mob> mobs = spawnMobsInChunk(level, site, recipe, random);
			if (mobs.isEmpty()) {
				continue;
			}
			ChunkPos assaultTarget = PbsTerritoryAdapter.findNearestOwnedChunk(owned, site);
			SiegeCluster cluster = SiegeClusterManager.createCluster(level, site, mobs);
			if (cluster == null) {
				continue;
			}
			if (assaultTarget != null) {
				cluster.startAssaultOn(assaultTarget);
			}
			created.add(cluster);
		}
		if (created.isEmpty()) {
			return new SpawnResult(sites, List.of(), "Sites chosen but mob spawn failed (blocked terrain?)");
		}
		return new SpawnResult(sites, created,
				"Spawned " + created.size() + " cluster(s) with recipe '" + recipe.id() + "'");
	}

	public static List<Mob> spawnMobsInChunk(
			ServerLevel level,
			ChunkPos chunk,
			SiegeSpawnRecipe recipe,
			Random random
	) {
		List<SiegeUnitDefinition> units = recipe.rollUnits(random);
		List<Mob> spawned = new ArrayList<>(units.size());
		int baseX = chunk.getMinBlockX() + 8;
		int baseZ = chunk.getMinBlockZ() + 8;

		for (SiegeUnitDefinition unit : units) {
			int ox = Mth.clamp(random.nextInt(13) - 6, -7, 7);
			int oz = Mth.clamp(random.nextInt(13) - 6, -7, 7);
			int x = baseX + ox;
			int z = baseZ + oz;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.getWorldBorder().isWithinBounds(pos)) {
				continue;
			}
			Mob mob = unit.type().spawn(level, pos, EntitySpawnReason.EVENT);
			if (mob == null) {
				continue;
			}
			mob.setPersistenceRequired();
			unit.equip(mob);
			spawned.add(mob);
		}
		return spawned;
	}
}
