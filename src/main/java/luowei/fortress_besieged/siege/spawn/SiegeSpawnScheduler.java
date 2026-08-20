package luowei.fortress_besieged.siege.spawn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;

/**
 * Natural siege wave scheduler: once per game-day per dimension when
 * {@code level.getDayTime() % 24000 == NATURAL_TRIGGER_DAY_TIME}.
 */
public final class SiegeSpawnScheduler {
	/** Last game-day index (dayTime / 24000) that already fired per dimension. */
	private static final Map<ResourceKey<Level>, Long> LAST_TRIGGER_DAY = new HashMap<>();

	private SiegeSpawnScheduler() {
	}

	public static void onWorldTick(ServerLevel level) {
		long dayTime = level.getDayTime();
		long timeOfDay = Math.floorMod(dayTime, 24000L);
		if (timeOfDay != SiegeSpawnConfig.NATURAL_TRIGGER_DAY_TIME) {
			return;
		}
		long dayIndex = dayTime / 24000L;
		ResourceKey<Level> dim = level.dimension();
		Long last = LAST_TRIGGER_DAY.get(dim);
		if (last != null && last == dayIndex) {
			return;
		}
		LAST_TRIGGER_DAY.put(dim, dayIndex);
		runNaturalWave(level);
	}

	/** Runs the full sample-targets → spawn pipeline and broadcasts results. */
	public static void runNaturalWave(ServerLevel level) {
		MinecraftServer server = level.getServer();
		Random random = new Random(level.random.nextLong());
		List<UUID> targets = SiegeTargetSampler.sampleWaveTargets(server, level, random);
		if (targets.isEmpty()) {
			FortressBesieged.LOGGER.debug("Natural siege wave skipped: empty poll in {}", level.dimension().location());
			return;
		}

		SiegeSpawnRecipe recipe = SiegeSpawnRecipes.getDefault();
		int clusterTotal = 0;
		for (UUID targetId : targets) {
			SiegeClusterSpawner.SpawnResult result =
					SiegeClusterSpawner.spawnForTarget(level, targetId, recipe, random);
			clusterTotal += result.clusters().size();
			broadcastSpawnResult(server, level, result);
		}
		if (clusterTotal == 0) {
			server.getPlayerList().broadcastSystemMessage(
					Component.literal("[Siege] Natural wave at dayTime="
							+ SiegeSpawnConfig.NATURAL_TRIGGER_DAY_TIME
							+ " in " + level.dimension().location()
							+ ": sampled " + targets.size()
							+ " target(s), no clusters spawned"),
					false);
		}
	}

	static void broadcastSpawnResult(
			MinecraftServer server,
			ServerLevel level,
			SiegeClusterSpawner.SpawnResult result
	) {
		if (result.clusters().isEmpty()) {
			return;
		}
		String dim = level.dimension().location().toString();
		for (SiegeCluster cluster : result.clusters()) {
			var anchor = cluster.getAnchorChunk();
			var target = cluster.getTargetChunk();
			int bx = (anchor.x << 4) + 8;
			int bz = (anchor.z << 4) + 8;
			server.getPlayerList().broadcastSystemMessage(
					Component.literal("[Siege] Cluster spawned in " + dim
							+ " chunk=" + anchor.x + "," + anchor.z
							+ " (~" + bx + ",~," + bz + ")"
							+ " targetChunk=" + (target == null ? "null" : (target.x + "," + target.z))
							+ " state=" + cluster.getState()
							+ " vs " + result.sites().targetId()),
					false);
		}
	}

	public static void clearDebounce() {
		LAST_TRIGGER_DAY.clear();
	}
}
