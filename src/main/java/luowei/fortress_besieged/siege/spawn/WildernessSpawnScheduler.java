package luowei.fortress_besieged.siege.spawn;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegePlayerProtection;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * Wilderness pack ticker for every dimension: player-around sparse scan,
 * then attempt chance, then NATURAL site pick, then wilderness templates.
 */
public final class WildernessSpawnScheduler {
	private static final Map<ResourceKey<Level>, Long> LAST_SCAN = new HashMap<>();
	private static final Map<UUID, Long> NEXT_ATTEMPT_AT = new HashMap<>();

	private WildernessSpawnScheduler() {
	}

	public static void onWorldTick(ServerLevel level) {
		if (SiegeDifficulty.isPeaceful(level)) {
			return;
		}
		WildernessPackTracker.pruneLevel(level);
		scanSparse(level);
	}

	private static void scanSparse(ServerLevel level) {
		long gameTime = level.getGameTime();
		long last = LAST_SCAN.getOrDefault(level.dimension(), Long.MIN_VALUE);
		int scanInterval = SiegeDifficulty.scaledInterval(
				WildernessSpawnConfig.SCAN_INTERVAL_TICKS,
				SiegeDifficulty.of(level));
		if (last != Long.MIN_VALUE && gameTime - last < scanInterval) {
			return;
		}
		LAST_SCAN.put(level.dimension(), gameTime);

		int budget = WildernessSpawnConfig.BUDGET_PER_SCAN;
		for (ServerPlayer player : level.players()) {
			if (budget <= 0) {
				break;
			}
			if (trySpawnForPlayer(level, player, gameTime)) {
				budget--;
			}
		}
	}

	private static boolean trySpawnForPlayer(ServerLevel level, ServerPlayer player, long gameTime) {
		if (player == null || player.isSpectator() || !player.isAlive()) {
			return false;
		}
		UUID playerId = player.getUUID();
		String name = SiegeSpawnMonitor.target(level, playerId);
		long readyAt = NEXT_ATTEMPT_AT.getOrDefault(playerId, 0L);
		if (gameTime < readyAt) {
			return false;
		}

		MinecraftServer server = level.getServer();
		UUID pollTargetId = PbsTerritoryAdapter.resolvePollTargetForPlayer(server, playerId);
		if (!SiegePlayerProtection.isTimeToBesiege(server, pollTargetId)) {
			FortressBesieged.LOGGER.debug(
					"[wilderness spawn] skip player={} reason=protected pollTarget={}",
					name,
					SiegeSpawnMonitor.target(level, pollTargetId));
			return false;
		}

		Random random = new Random(level.random.nextLong());
		float chance = SiegeClusterConfig.wilderness().attemptChance();
		if (!(random.nextFloat() < chance)) {
			FortressBesieged.LOGGER.debug(
					"[wilderness spawn] chance miss player={} chance={}",
					name,
					chance);
			return false;
		}

		WildernessSpawner.SpawnResult result = WildernessSpawner.spawnAround(level, player, pollTargetId, random);
		if (result.mobs().isEmpty()) {
			NEXT_ATTEMPT_AT.put(playerId, gameTime + WildernessSpawnConfig.FAIL_COOLDOWN_TICKS);
			return false;
		}
		return true;
	}

	public static void clearLevel(ResourceKey<Level> dimension) {
		if (dimension != null) {
			LAST_SCAN.remove(dimension);
		}
	}

	public static void clearAll() {
		LAST_SCAN.clear();
		NEXT_ATTEMPT_AT.clear();
	}

	/** Exposed for tests; not a player API. */
	static void setCooldown(UUID playerId, long nextAttemptAt) {
		if (playerId != null) {
			NEXT_ATTEMPT_AT.put(playerId, nextAttemptAt);
		}
	}
}
