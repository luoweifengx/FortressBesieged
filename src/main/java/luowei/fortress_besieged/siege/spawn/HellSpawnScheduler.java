package luowei.fortress_besieged.siege.spawn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.SiegePlayerProtection;
import luowei.fortress_besieged.siege.SiegeSounds;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Independent hell-tier waves: every {@link HellSpawnConfig#SCAN_INTERVAL_TICKS}
 * each demon chunk rolls {@link HellSpawnConfig#SPAWN_CHANCE}. Only while at
 * least one 终末 (demon-chunk) siege target exists in this dimension.
 */
public final class HellSpawnScheduler {
	private static final Map<ResourceKey<Level>, Long> LAST_SCAN = new HashMap<>();

	private HellSpawnScheduler() {
	}

	public static void onWorldTick(ServerLevel level) {
		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			return;
		}
		long gameTime = level.getGameTime();
		long last = LAST_SCAN.getOrDefault(level.dimension(), Long.MIN_VALUE);
		if (last != Long.MIN_VALUE && gameTime - last < HellSpawnConfig.SCAN_INTERVAL_TICKS) {
			return;
		}
		LAST_SCAN.put(level.dimension(), gameTime);

		List<ChunkPos> demons = PbsTerritoryAdapter.queryDemonChunks(level);
		if (demons == null || demons.isEmpty()) {
			return;
		}
		if (!hasEndStageTarget(level)) {
			return;
		}

		Random random = new Random(level.random.nextLong());
		for (ChunkPos demon : demons) {
			if (demon == null || hasHellClusterAt(level, demon)) {
				continue;
			}
			if (random.nextFloat() >= HellSpawnConfig.SPAWN_CHANCE) {
				continue;
			}
			trySpawnAt(level, demon, random);
		}
	}

	private static void trySpawnAt(ServerLevel level, ChunkPos demon, Random random) {
		EndTarget pick = findNearestEndTarget(level, demon);
		if (pick == null) {
			SiegeSpawnMonitor.debug(
					"hell skip chunk={} reason=no_end_target dim={}",
					SiegeSpawnMonitor.chunk(demon),
					SiegeSpawnMonitor.dim(level));
			return;
		}
		SiegeTemplateSelector.Selection selection = SiegeTemplateSelector.rollHell(level, pick.targetId, random);
		if (selection == null || selection.template() == null) {
			SiegeSpawnMonitor.debug(
					"hell skip chunk={} reason=no_template dim={}",
					SiegeSpawnMonitor.chunk(demon),
					SiegeSpawnMonitor.dim(level));
			return;
		}
		SiegeClusterTemplate template = selection.template();
		List<Mob> mobs = SiegeClusterSpawner.spawnMobsInChunk(level, demon, template, random);
		if (mobs.isEmpty()) {
			SiegeSpawnMonitor.debug(
					"hell skip chunk={} reason=no_mobs dim={}",
					SiegeSpawnMonitor.chunk(demon),
					SiegeSpawnMonitor.dim(level));
			return;
		}
		SiegeCluster cluster = SiegeClusterManager.createCluster(level, demon, mobs);
		if (cluster == null) {
			return;
		}
		cluster.setTemplateInfo(template.id(), template.name(), template.race(), template.tier());
		cluster.setSiegeTargetId(pick.targetId);
		cluster.startAssaultOn(pick.ownedChunk);
		SiegeSounds.broadcastHellSpawn(level, demon);
		SiegeSpawnMonitor.info(
				"hell spawned chunk={} pos={} mobs={} template={} target={} dim={}",
				SiegeSpawnMonitor.chunk(demon),
				blockPos(level, demon),
				mobs.size(),
				template.id(),
				SiegeSpawnMonitor.target(level, pick.targetId),
				SiegeSpawnMonitor.dim(level));
	}

	private static boolean hasEndStageTarget(ServerLevel level) {
		MinecraftServer server = level.getServer();
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		for (UUID targetId : PbsTerritoryAdapter.listSiegeTargets(server)) {
			if (!SiegePlayerProtection.isTimeToBesiege(server, targetId)) {
				continue;
			}
			SiegeProgression.Stage stage = progression.resolveStage(progression.snapshot(level, targetId));
			if (stage == null || !stage.requireDemonChunk()) {
				continue;
			}
			if (!PbsTerritoryAdapter.getOwnedChunks(level, targetId).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static EndTarget findNearestEndTarget(ServerLevel level, ChunkPos demon) {
		MinecraftServer server = level.getServer();
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		UUID bestId = null;
		ChunkPos bestOwned = null;
		int bestDist = Integer.MAX_VALUE;
		for (UUID targetId : PbsTerritoryAdapter.listSiegeTargets(server)) {
			if (!SiegePlayerProtection.isTimeToBesiege(server, targetId)) {
				continue;
			}
			SiegeProgression.WorldSnapshot world = progression.snapshot(level, targetId);
			SiegeProgression.Stage stage = progression.resolveStage(world);
			if (stage == null || !stage.requireDemonChunk()) {
				continue;
			}
			List<ChunkPos> owned = PbsTerritoryAdapter.getOwnedChunks(level, targetId);
			ChunkPos nearest = PbsTerritoryAdapter.findNearestOwnedChunk(owned, demon);
			if (nearest == null) {
				continue;
			}
			int dist = SiegeCluster.chebyshevChunkDistance(demon, nearest);
			if (dist < bestDist) {
				bestDist = dist;
				bestId = targetId;
				bestOwned = nearest;
			}
		}
		return bestId == null ? null : new EndTarget(bestId, bestOwned);
	}

	private static boolean hasHellClusterAt(ServerLevel level, ChunkPos demon) {
		for (SiegeCluster cluster : SiegeClusterManager.clusters()) {
			if (cluster.getLevel() != level || cluster.isDisbanded() || !cluster.isHell()) {
				continue;
			}
			if (demon.equals(cluster.getAnchorChunk())) {
				return true;
			}
		}
		return false;
	}

	private static String blockPos(ServerLevel level, ChunkPos chunk) {
		int x = chunk.getMiddleBlockX();
		int z = chunk.getMiddleBlockZ();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, y, z).toShortString();
	}

	public static void clearLevel(ResourceKey<Level> dimension) {
		if (dimension != null) {
			LAST_SCAN.remove(dimension);
		}
	}

	public static void clearAll() {
		LAST_SCAN.clear();
	}

	private record EndTarget(UUID targetId, ChunkPos ownedChunk) {
	}
}
