package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayDeque;
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
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.siege.SiegePlayerProtection;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeProgression.SpawnWindow;
import luowei.fortress_besieged.siege.spawn.SiegeProgression.Stage;
import luowei.fortress_besieged.siege.spawn.SiegeProgression.TierRoll;
import luowei.fortress_besieged.siege.spawn.SiegeProgression.WorldSnapshot;

/**
 * Natural siege: each online player in this dimension is one roll slot on a
 * fixed {@link SiegeSpawnConfig#NATURAL_PLAN_INTERVAL_TICKS} cadence. A hit looks
 * up the player's org (or the player if solo) and runs the full spawn pipeline.
 * Several members of one org can hit in the same cycle and each run is separate.
 * Command waves ({@code /siege spawn}) still sample the PBS poll.
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
			for (Slot slot : state.slots.values()) {
				slot.queued = false;
			}
		}
		if (SiegeDifficulty.isPeaceful(level)) {
			return;
		}
		long gameTime = level.getGameTime();
		int interval = interval(level);
		if (crossedMidnight
				|| state.lastPlanGameTime < 0L
				|| gameTime - state.lastPlanGameTime >= interval) {
			refreshSlots(level, state);
			state.lastPlanGameTime = gameTime;
		}
		enqueueOnDuty(level, state, timeOfDay, gameTime);
		int budget = SiegeSpawnConfig.NATURAL_SPAWN_BUDGET_PER_TICK;
		for (int i = 0; i < budget; i++) {
			UUID playerId = state.dueQueue.poll();
			if (playerId == null) {
				break;
			}
			Slot slot = state.slots.get(playerId);
			if (slot == null) {
				continue;
			}
			slot.queued = false;
			fireSlot(level, slot, timeOfDay, gameTime);
		}
	}

	private static int interval(ServerLevel level) {
		if (SiegeDifficulty.isPeaceful(level)) {
			return Integer.MAX_VALUE;
		}
		return SiegeSpawnConfig.NATURAL_PLAN_INTERVAL_TICKS;
	}

	/**
	 * One slot per online player in this dimension. Protected poll targets are
	 * dropped. Shared org snapshots are reused within a plan pass.
	 */
	private static void refreshSlots(ServerLevel level, DimensionState state) {
		long t0 = SiegeSpawnMonitor.nanos();
		MinecraftServer server = level.getServer();
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		Set<UUID> seen = new HashSet<>();
		Map<UUID, WorldSnapshot> snapshots = new HashMap<>();
		int skippedProtected = 0;
		int tracked = 0;
		for (ServerPlayer player : level.players()) {
			if (player == null || player.isSpectator() || !player.isAlive()) {
				continue;
			}
			UUID playerId = player.getUUID();
			seen.add(playerId);
			UUID pollTargetId = PbsTerritoryAdapter.resolvePollTargetForPlayer(server, playerId);
			if (!SiegePlayerProtection.isTimeToBesiege(server, pollTargetId)) {
				state.slots.remove(playerId);
				skippedProtected++;
				continue;
			}
			WorldSnapshot world = snapshots.computeIfAbsent(
					pollTargetId,
					id -> progression.snapshot(level, id));
			Stage stage = progression.resolveStage(world);
			SpawnWindow window = progression.dueWindow(level, stage);
			Slot slot = state.slots.computeIfAbsent(playerId, Slot::new);
			slot.pollTargetId = pollTargetId;
			slot.world = world;
			slot.stageId = stage.id();
			slot.chance = window.chance();
			tracked++;
		}
		state.slots.keySet().removeIf(id -> !seen.contains(id));
		long ms = SiegeSpawnMonitor.msSince(t0);
		if (ms >= SiegeSpawnMonitor.SLOW_CYCLE_MS) {
			SiegeSpawnMonitor.warn(
					"SLOW plan ms={} dim={} online={} tracked={} skippedProtected={}",
					ms, SiegeSpawnMonitor.dim(level), seen.size(), tracked, skippedProtected);
		} else {
			SiegeSpawnMonitor.timing(
					"plan",
					t0,
					"dim={} online={} tracked={} skippedProtected={}",
					SiegeSpawnMonitor.dim(level), seen.size(), tracked, skippedProtected);
		}
	}

	private static void enqueueOnDuty(ServerLevel level, DimensionState state, long timeOfDay, long gameTime) {
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		for (Slot slot : state.slots.values()) {
			if (slot.queued || gameTime < slot.nextAttemptAt) {
				continue;
			}
			if (slot.world == null || slot.pollTargetId == null) {
				continue;
			}
			Stage stage = progression.resolveStage(slot.world);
			TierRoll roll = TierRoll.of(level, stage, false, true);
			if (!progression.hasOnDutyWeight(stage, slot.world, roll, timeOfDay)) {
				continue;
			}
			state.dueQueue.add(slot.playerId);
			slot.queued = true;
		}
	}

	private static void fireSlot(ServerLevel level, Slot slot, long timeOfDay, long gameTime) {
		long t0 = SiegeSpawnMonitor.nanos();
		MinecraftServer server = level.getServer();
		int interval = interval(level);
		ServerPlayer player = server.getPlayerList().getPlayer(slot.playerId);
		if (player == null || player.isSpectator() || !player.isAlive() || player.level() != level) {
			slot.nextAttemptAt = gameTime + interval;
			return;
		}
		UUID pollTargetId = PbsTerritoryAdapter.resolvePollTargetForPlayer(server, slot.playerId);
		slot.pollTargetId = pollTargetId;
		if (!SiegePlayerProtection.isTimeToBesiege(server, pollTargetId)) {
			slot.nextAttemptAt = gameTime + interval;
			return;
		}
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		WorldSnapshot world = progression.snapshot(level, pollTargetId);
		Stage current = progression.resolveStage(world);
		SpawnWindow window = progression.dueWindow(level, current);
		slot.world = world;
		slot.stageId = current.id();
		slot.chance = window.chance();
		String name = SiegeSpawnMonitor.target(level, slot.playerId);
		String org = SiegeSpawnMonitor.target(level, pollTargetId);
		TierRoll roll = TierRoll.of(level, current, false, true);
		if (!progression.hasOnDutyWeight(current, world, roll, timeOfDay)) {
			SiegeSpawnMonitor.debug(
					"skip player={} org={} stage={} no on-duty tier timeOfDay={} dim={}",
					name, org, current.id(), timeOfDay, SiegeSpawnMonitor.dim(level));
			return;
		}

		slot.nextAttemptAt = gameTime + interval;
		Random random = new Random(level.random.nextLong());
		boolean bloodMoon = BloodMoon.isActive(level);
		SiegeSpawnMonitor.debug(
				"picked player={} org={} stage={} timeOfDay={} gameTime={} dim={} source=natural bloodMoon={}",
				name, org, current.id(), timeOfDay, gameTime, SiegeSpawnMonitor.dim(level), bloodMoon);
		if (!bloodMoon && !(random.nextFloat() < window.chance())) {
			SiegeSpawnMonitor.debug(
					"chance miss player={} org={} stage={} chance={} timeOfDay={} dim={}",
					name, org, current.id(), window.chance(), timeOfDay, SiegeSpawnMonitor.dim(level));
			SiegeSpawnMonitor.timing("fire", t0, "player={} result=chance_miss", name);
			return;
		}

		SiegeClusterSpawner.SpawnResult result =
				SiegeClusterSpawner.spawnRolled(level, pollTargetId, random, true);
		SiegeSpawnMonitor.timing(
				"fire",
				t0,
				"player={} org={} clusters={} source=natural",
				name, org, result.clusters().size());
	}

	public static void clearDebounce() {
		STATES.clear();
	}

	private static final class DimensionState {
		long dayIndex = Long.MIN_VALUE;
		long lastPlanGameTime = -1L;
		final Map<UUID, Slot> slots = new HashMap<>();
		final ArrayDeque<UUID> dueQueue = new ArrayDeque<>();
	}

	private static final class Slot {
		final UUID playerId;
		UUID pollTargetId;
		WorldSnapshot world;
		String stageId;
		float chance;
		boolean queued;
		long nextAttemptAt;

		Slot(UUID playerId) {
			this.playerId = playerId;
		}
	}
}
