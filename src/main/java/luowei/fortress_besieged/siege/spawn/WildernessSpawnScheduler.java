package luowei.fortress_besieged.siege.spawn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegePlayerProtection;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Wilderness pack ticker: per-player leave-territory timer, then attempt
 * chance, then NATURAL site pick from wilderness templates.
 */
public final class WildernessSpawnScheduler {
	private static final Map<ResourceKey<Level>, Map<UUID, Long>> AWAY_SINCE = new HashMap<>();

	private WildernessSpawnScheduler() {
	}

	public static void onWorldTick(ServerLevel level) {
		if (SiegeDifficulty.isPeaceful(level)) {
			return;
		}
		WildernessPackTracker.pruneLevel(level);
		tickAwayPlayers(level);
	}

	private static void tickAwayPlayers(ServerLevel level) {
		long gameTime = level.getGameTime();
		Map<UUID, Long> awaySince = AWAY_SINCE.computeIfAbsent(level.dimension(), key -> new HashMap<>());
		Set<UUID> seen = new HashSet<>();
		int budget = WildernessSpawnConfig.BUDGET_PER_TICK;
		MinecraftServer server = level.getServer();
		for (ServerPlayer player : level.players()) {
			if (player == null || player.isSpectator() || !player.isAlive()) {
				continue;
			}
			UUID playerId = player.getUUID();
			seen.add(playerId);
			if (trySpawnForPlayer(level, server, player, playerId, gameTime, awaySince, budget > 0)) {
				budget--;
			}
		}
		awaySince.keySet().removeIf(id -> !seen.contains(id));
		if (awaySince.isEmpty()) {
			AWAY_SINCE.remove(level.dimension());
		}
	}

	/**
	 * @return true if a pack actually spawned (consumes the per-tick budget)
	 */
	private static boolean trySpawnForPlayer(
			ServerLevel level,
			MinecraftServer server,
			ServerPlayer player,
			UUID playerId,
			long gameTime,
			Map<UUID, Long> awaySince,
			boolean budgetLeft
	) {
		String name = SiegeSpawnMonitor.target(level, playerId);
		UUID pollTargetId = PbsTerritoryAdapter.resolvePollTargetForPlayer(server, playerId);
		if (!SiegePlayerProtection.isTimeToBesiege(server, pollTargetId)) {
			awaySince.remove(playerId);
			FortressBesieged.LOGGER.debug(
					"[wilderness spawn] skip player={} reason=protected pollTarget={}",
					name,
					SiegeSpawnMonitor.target(level, pollTargetId));
			return false;
		}
		if (isOwnTerritory(level, player, pollTargetId)) {
			awaySince.remove(playerId);
			return false;
		}

		awaySince.putIfAbsent(playerId, gameTime);
		if (gameTime - awaySince.get(playerId) < WildernessSpawnConfig.AWAY_INTERVAL_TICKS) {
			return false;
		}
		if (!budgetLeft) {
			return false;
		}

		Random random = new Random(level.random.nextLong());
		float chance = SiegeClusterConfig.wilderness().attemptChance();
		if (!(random.nextFloat() < chance)) {
			awaySince.put(playerId, gameTime);
			FortressBesieged.LOGGER.debug(
					"[wilderness spawn] chance miss player={} chance={}",
					name,
					chance);
			return false;
		}

		WildernessSpawner.SpawnResult result = WildernessSpawner.spawnAround(level, player, pollTargetId, random);
		awaySince.put(playerId, gameTime);
		return !result.mobs().isEmpty();
	}

	/** Own or org OCCUPIED/BORDER. Anyone else's claim, NATURAL, HOSTILE, DEMON are away. */
	private static boolean isOwnTerritory(ServerLevel level, ServerPlayer player, UUID pollTargetId) {
		ChunkPos chunk = player.chunkPosition();
		if (!PbsTerritoryAdapter.isAssaultable(level, chunk)) {
			return false;
		}
		if (pollTargetId == null) {
			return false;
		}
		return PbsTerritoryAdapter.getOccupyingOrg(level, chunk)
				.map(pollTargetId::equals)
				.orElse(false);
	}

	public static void clearLevel(ResourceKey<Level> dimension) {
		if (dimension != null) {
			AWAY_SINCE.remove(dimension);
		}
	}

	public static void clearAll() {
		AWAY_SINCE.clear();
	}
}
