package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.siege.SiegePlayerProtection;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeProgression.SpawnWindow;
import luowei.fortress_besieged.siege.spawn.SiegeProgression.Stage;

/**
 * Natural siege: while a target is inside the stage window, roll {@code chance}
 * on a cadence. A miss does not lock the rest of the day. Command waves
 * ({@code /siege spawn}) bypass this clock.
 */
public final class SiegeSpawnScheduler {
	private static final Map<ResourceKey<Level>, DimensionState> STATES = new HashMap<>();

	private SiegeSpawnScheduler() {
	}

	public static void onWorldTick(ServerLevel level) {
		long dayTime = SiegeDayClock.dayTime(level);
		long timeOfDay = Math.floorMod(dayTime, 24000L);
		long dayIndex = Math.floorDiv(dayTime, 24000L);
		DimensionState state = STATES.computeIfAbsent(level.dimension(), key -> new DimensionState());
		boolean firstSeen = state.dayIndex == Long.MIN_VALUE;
		boolean crossedMidnight = state.dayIndex != dayIndex;
		if (crossedMidnight) {
			if (!firstSeen && level.dimension().equals(Level.OVERWORLD)) {
				SiegePlayerProtection.onOverworldDayAdvance(level.getServer());
			}
			state.dayIndex = dayIndex;
			state.dueQueue.clear();
			for (Target target : state.targets.values()) {
				target.queued = false;
			}
		}
		long gameTime = level.getGameTime();
		if (crossedMidnight
				|| state.lastPlanGameTime < 0L
				|| gameTime - state.lastPlanGameTime >= SiegeSpawnConfig.NATURAL_PLAN_INTERVAL_TICKS) {
			refreshTargets(level, state);
			state.lastPlanGameTime = gameTime;
		}
		enqueueInWindow(state, timeOfDay, gameTime);
		int budget = SiegeSpawnConfig.NATURAL_SPAWN_BUDGET_PER_TICK;
		for (int i = 0; i < budget; i++) {
			UUID targetId = state.dueQueue.poll();
			if (targetId == null) {
				break;
			}
			Target target = state.targets.get(targetId);
			if (target == null) {
				continue;
			}
			target.queued = false;
			fireTarget(level, target, timeOfDay, gameTime);
		}
	}

	/**
	 * Refresh PBS poll. Protected targets are dropped. Window cache is only
	 * used to decide eligibility; fire re-snapshots before rolling.
	 */
	private static void refreshTargets(ServerLevel level, DimensionState state) {
		long t0 = SiegeSpawnMonitor.nanos();
		MinecraftServer server = level.getServer();
		List<UUID> poll = PbsTerritoryAdapter.listSiegeTargets(server);
		if (poll.isEmpty()) {
			state.targets.clear();
			SiegeSpawnMonitor.timing("plan", t0, "dim={} poll=0", SiegeSpawnMonitor.dim(level));
			return;
		}
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		Set<UUID> seen = new HashSet<>();
		int skippedProtected = 0;
		int tracked = 0;
		for (UUID targetId : poll) {
			seen.add(targetId);
			if (!SiegePlayerProtection.isTimeToBesiege(server, targetId)) {
				state.targets.remove(targetId);
				skippedProtected++;
				continue;
			}
			Stage stage = progression.resolveStage(progression.snapshot(level, targetId));
			SpawnWindow window = progression.dueWindow(level, stage);
			Target target = state.targets.computeIfAbsent(targetId, Target::new);
			target.stageId = stage.id();
			target.windowStart = window.windowStart();
			target.windowEnd = window.windowEnd();
			target.chance = window.chance();
			tracked++;
		}
		state.targets.keySet().removeIf(id -> !seen.contains(id));
		long ms = SiegeSpawnMonitor.msSince(t0);
		if (ms >= SiegeSpawnMonitor.SLOW_CYCLE_MS) {
			SiegeSpawnMonitor.warn(
					"SLOW plan ms={} dim={} poll={} tracked={} skippedProtected={}",
					ms, SiegeSpawnMonitor.dim(level), poll.size(), tracked, skippedProtected);
		} else {
			SiegeSpawnMonitor.timing(
					"plan",
					t0,
					"dim={} poll={} tracked={} skippedProtected={}",
					SiegeSpawnMonitor.dim(level), poll.size(), tracked, skippedProtected);
		}
	}

	private static void enqueueInWindow(DimensionState state, long timeOfDay, long gameTime) {
		for (Target target : state.targets.values()) {
			if (target.queued || gameTime < target.nextAttemptAt) {
				continue;
			}
			if (timeOfDay < target.windowStart || timeOfDay >= target.windowEnd) {
				continue;
			}
			state.dueQueue.add(target.targetId);
			target.queued = true;
		}
	}

	private static void fireTarget(ServerLevel level, Target target, long timeOfDay, long gameTime) {
		long t0 = SiegeSpawnMonitor.nanos();
		MinecraftServer server = level.getServer();
		if (!SiegePlayerProtection.isTimeToBesiege(server, target.targetId)) {
			target.nextAttemptAt = gameTime + SiegeSpawnConfig.NATURAL_PLAN_INTERVAL_TICKS;
			return;
		}
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		Stage current = progression.resolveStage(progression.snapshot(level, target.targetId));
		SpawnWindow window = progression.dueWindow(level, current);
		target.stageId = current.id();
		target.windowStart = window.windowStart();
		target.windowEnd = window.windowEnd();
		target.chance = window.chance();
		String player = SiegeSpawnMonitor.target(level, target.targetId);
		if (!SiegeDayClock.inWindow(window, timeOfDay)) {
			SiegeSpawnMonitor.debug(
					"skip player={} stage={} outside window timeOfDay={} dim={}",
					player, current.id(), timeOfDay, SiegeSpawnMonitor.dim(level));
			return;
		}

		target.nextAttemptAt = gameTime + SiegeSpawnConfig.NATURAL_PLAN_INTERVAL_TICKS;
		SiegeSpawnMonitor.debug(
				"picked player={} stage={} timeOfDay={} gameTime={} dim={} source=natural",
				player, current.id(), timeOfDay, gameTime, SiegeSpawnMonitor.dim(level));

		Random random = new Random(level.random.nextLong());
		if (!(random.nextFloat() < window.chance())) {
			SiegeSpawnMonitor.debug(
					"chance miss player={} stage={} chance={} timeOfDay={} dim={}",
					player, current.id(), window.chance(), timeOfDay, SiegeSpawnMonitor.dim(level));
			SiegeSpawnMonitor.timing("fire", t0, "player={} result=chance_miss", player);
			return;
		}

		SiegeClusterSpawner.SpawnResult result =
				SiegeClusterSpawner.spawnRolled(level, target.targetId, random);
		SiegeSpawnMonitor.timing(
				"fire",
				t0,
				"player={} clusters={} source=natural",
				player, result.clusters().size());
	}

	public static void clearDebounce() {
		STATES.clear();
	}

	private static final class DimensionState {
		long dayIndex = Long.MIN_VALUE;
		long lastPlanGameTime = -1L;
		final Map<UUID, Target> targets = new HashMap<>();
		final ArrayDeque<UUID> dueQueue = new ArrayDeque<>();
	}

	private static final class Target {
		final UUID targetId;
		String stageId;
		int windowStart;
		int windowEnd;
		float chance;
		boolean queued;
		long nextAttemptAt;

		Target(UUID targetId) {
			this.targetId = targetId;
		}
	}
}
