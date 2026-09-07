package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

import luowei.fortress_besieged.siege.SiegeProgressionFlags;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * World stage + tier unlock from advancements and demon chunks.
 * {@code worldOwned} is informational only and does not gate stages.
 * Natural siege filters tiers by type duty windows; stage {@code spawn} windows
 * supply chance only.
 */
public final class SiegeProgression {
	public static final String TIER_ROADKILL = "roadkill";
	public static final String TIER_SMALL = "small";
	public static final String TIER_HOST = "host";
	public static final String TIER_LEGION = "legion";
	public static final String TIER_HELL = "hell";

	public static final String UNLOCK_ALWAYS = "always";
	public static final String UNLOCK_HOST = "host";
	public static final String UNLOCK_LEGION = "legion";

	private final List<Stage> stages;

	public SiegeProgression(List<Stage> stages) {
		this.stages = stages == null || stages.isEmpty()
				? List.of(defaultJourney())
				: List.copyOf(stages);
	}

	public List<Stage> stages() {
		return stages;
	}

	public WorldSnapshot snapshot(ServerLevel level, UUID targetId) {
		int owned = PbsTerritoryAdapter.queryOwnedCount(level, targetId);
		boolean demon = PbsTerritoryAdapter.hasDemonChunks(level);
		MinecraftServer server = level.getServer();
		SiegeProgressionFlags.refresh(server, targetId);
		boolean iron = SiegeProgressionFlags.hasIron(server, targetId);
		boolean ironArmor = SiegeProgressionFlags.hasIronArmor(server, targetId);
		boolean diamond = SiegeProgressionFlags.hasDiamond(server, targetId);
		return new WorldSnapshot(owned, demon, iron, ironArmor, diamond, SiegeDifficulty.of(level));
	}

	/**
	 * Highest satisfied stage (终末 &gt; 困难 &gt; 故土 &gt; 旅途).
	 */
	public Stage resolveStage(WorldSnapshot world) {
		Stage best = stages.get(0);
		int bestRank = rank(best, world);
		for (Stage stage : stages) {
			if (!stageUnlocked(stage, world)) {
				continue;
			}
			int r = rank(stage, world);
			if (r >= bestRank) {
				bestRank = r;
				best = stage;
			}
		}
		return best;
	}

	public boolean stageUnlocked(Stage stage, WorldSnapshot world) {
		if (stage == null) {
			return false;
		}
		if (stage.requireDemonChunk() && !world.hasDemonChunk()) {
			return false;
		}
		return switch (stage.unlock() == null ? UNLOCK_ALWAYS : stage.unlock()) {
			case UNLOCK_HOST -> world.hostStageUnlocked();
			case UNLOCK_LEGION -> world.legionUnlocked();
			default -> true;
		};
	}

	public boolean tierUnlocked(String tier, WorldSnapshot world) {
		if (tier == null) {
			return false;
		}
		Difficulty difficulty = world == null ? Difficulty.HARD : world.difficulty();
		boolean relaxed = SiegeDifficulty.usesRelaxedUnlocks(difficulty);
		return switch (tier) {
			case TIER_ROADKILL -> true;
			case TIER_SMALL -> !relaxed || world.hasIron();
			case TIER_HOST -> SiegeDifficulty.isHard(difficulty)
					? world.hasIronArmor()
					: world.hasDiamond();
			case TIER_LEGION -> SiegeDifficulty.isHard(difficulty)
					? world.hasDiamond()
					: world.hasDiamond() && world.hasDemonChunk();
			case TIER_HELL -> world.hasDemonChunk();
			default -> false;
		};
	}

	/**
	 * Cluster-type duty window (overworld clock). Hell is not gated here.
	 */
	public static SpawnWindow dutyWindow(String tier) {
		if (tier == null) {
			return null;
		}
		return switch (tier) {
			case TIER_ROADKILL -> new SpawnWindow(7000, 18000, 1.0f);
			case TIER_SMALL -> new SpawnWindow(9000, 23000, 1.0f);
			case TIER_HOST -> new SpawnWindow(10000, 22000, 1.0f);
			case TIER_LEGION -> new SpawnWindow(11000, 19000, 1.0f);
			default -> null;
		};
	}

	public static boolean tierOnDuty(String tier, long timeOfDay) {
		SpawnWindow window = dutyWindow(tier);
		if (window == null) {
			return true;
		}
		return SiegeDayClock.inWindow(window, timeOfDay);
	}

	public boolean hasOnDutyWeight(Stage stage, WorldSnapshot world, TierRoll roll, long timeOfDay) {
		if (roll == null) {
			roll = TierRoll.overworldSiege();
		}
		Map<String, Double> weights;
		if (roll.mergeUnlocked()) {
			weights = mergeUnlockedWeights(world);
		} else if (stage == null || stage.weights().isEmpty()) {
			weights = Map.of(TIER_SMALL, 1.0D);
		} else {
			weights = stage.weights();
		}
		for (Map.Entry<String, Double> entry : weights.entrySet()) {
			double w = entry.getValue() == null ? 0.0D : entry.getValue();
			if (w > 0.0D && keepTier(entry.getKey(), world, roll, timeOfDay)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Eligibility window for natural rolls: overworld uses {@code spawn};
	 * nether uses {@code netherSpawn} (default all day). Chance is read from this;
	 * type duty windows decide which tiers may appear.
	 */
	public SpawnWindow dueWindow(ServerLevel level, Stage stage) {
		if (stage == null) {
			stage = stages.get(0);
		}
		if (SiegeDayClock.isNether(level)) {
			return stage.netherSpawn();
		}
		return stage.spawn();
	}

	/**
	 * Draw a tier from the stage table; locked tiers are weight 0 then renormalized.
	 * Illager has no roadkill: caller remaps after race is known, or pass {@code remapRoadkillToSmall}.
	 */
	public String rollTier(Stage stage, WorldSnapshot world, Random random, boolean remapRoadkillToSmall) {
		return rollTier(stage, world, random, remapRoadkillToSmall, TierRoll.overworldSiege(), 0L);
	}

	/**
	 * @param excludeHell wilderness packs spawn only on {@code NATURAL}; hell weight is 0
	 */
	public String rollTier(
			Stage stage,
			WorldSnapshot world,
			Random random,
			boolean remapRoadkillToSmall,
			boolean excludeHell
	) {
		return rollTier(stage, world, random, remapRoadkillToSmall, new TierRoll(excludeHell, false, false, false), 0L);
	}

	public String rollTier(
			Stage stage,
			WorldSnapshot world,
			Random random,
			boolean remapRoadkillToSmall,
			TierRoll roll
	) {
		return rollTier(stage, world, random, remapRoadkillToSmall, roll, 0L);
	}

	public String rollTier(
			Stage stage,
			WorldSnapshot world,
			Random random,
			boolean remapRoadkillToSmall,
			TierRoll roll,
			long timeOfDay
	) {
		if (roll == null) {
			roll = TierRoll.overworldSiege();
		}
		Map<String, Double> weights;
		if (roll.mergeUnlocked()) {
			weights = mergeUnlockedWeights(world);
		} else if (stage == null || stage.weights().isEmpty()) {
			weights = Map.of(TIER_SMALL, 1.0D);
		} else {
			weights = stage.weights();
		}
		TierRoll keep = roll;
		String tier = WeightedPicker.pickProbability(
				weights,
				random,
				t -> keepTier(t, world, keep, timeOfDay));
		if (tier == null) {
			tier = TIER_SMALL;
		}
		if (remapRoadkillToSmall && TIER_ROADKILL.equals(tier)) {
			return TIER_SMALL;
		}
		return tier;
	}

	public Map<String, Double> mergeUnlockedWeights(WorldSnapshot world) {
		Map<String, Double> merged = new LinkedHashMap<>();
		for (Stage stage : stages) {
			if (!stageUnlocked(stage, world)) {
				continue;
			}
			for (Map.Entry<String, Double> entry : stage.weights().entrySet()) {
				double w = entry.getValue() == null ? 0.0D : entry.getValue();
				if (w <= 0.0D) {
					continue;
				}
				merged.merge(entry.getKey(), w, Double::sum);
			}
		}
		return merged;
	}

	public Map<String, Object> toJsonMap() {
		Map<String, Object> json = new LinkedHashMap<>();
		List<Map<String, Object>> stageJson = new ArrayList<>();
		for (Stage stage : stages) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", stage.id());
			row.put("name", stage.name());
			row.put("unlock", stage.unlock());
			if (stage.requireDemonChunk()) {
				row.put("requireDemonChunk", true);
			}
			row.put("spawn", spawnJson(stage.spawn()));
			row.put("netherSpawn", spawnJson(stage.netherSpawn()));
			row.put("weights", stage.weights());
			stageJson.add(row);
		}
		json.put("stages", stageJson);
		return json;
	}

	private static Map<String, Object> spawnJson(SpawnWindow spawn) {
		Map<String, Object> spawnJson = new LinkedHashMap<>();
		spawnJson.put("windowStart", spawn.windowStart());
		spawnJson.put("windowEnd", spawn.windowEnd());
		spawnJson.put("chance", spawn.chance());
		return spawnJson;
	}

	/**
	 * Default natural-spawn chance by stage id. Window times are unused by the
	 * natural scheduler (type duty windows are used instead) but kept as JSON defaults.
	 */
	public static SpawnWindow defaultSpawnWindow(String stageId) {
		if (stageId == null) {
			stageId = "journey";
		}
		return switch (stageId) {
			case "homeland" -> new SpawnWindow(10000, 22000, 0.12f);
			case "hard" -> new SpawnWindow(11000, 19000, 0.15f);
			case "end" -> new SpawnWindow(16000, 23000, 0.20f);
			default -> new SpawnWindow(7000, 18000, 0.10f);
		};
	}

	public static SpawnWindow defaultNetherSpawnWindow(float chance) {
		return SiegeDayClock.defaultNetherDueWindow(chance);
	}

	private boolean keepTier(String tier, WorldSnapshot world, TierRoll roll, long timeOfDay) {
		if (roll.excludeHell() && TIER_HELL.equals(tier)) {
			return false;
		}
		if (roll.excludeRoadkill() && TIER_ROADKILL.equals(tier)) {
			return false;
		}
		if (roll.applyDutyWindows() && !tierOnDuty(tier, timeOfDay)) {
			return false;
		}
		return tierUnlocked(tier, world);
	}

	private static int rank(Stage stage, WorldSnapshot world) {
		if (!world.legionUnlocked()) {
			if (UNLOCK_LEGION.equals(stage.unlock())) {
				return -1;
			}
		}
		int base = switch (stage.unlock() == null ? UNLOCK_ALWAYS : stage.unlock()) {
			case UNLOCK_LEGION -> 2;
			case UNLOCK_HOST -> 1;
			default -> 0;
		};
		if (stage.requireDemonChunk()) {
			base += 1;
		}
		return base;
	}

	private static Stage defaultJourney() {
		Map<String, Double> weights = new LinkedHashMap<>();
		weights.put(TIER_ROADKILL, 0.7D);
		weights.put(TIER_SMALL, 0.3D);
		return new Stage("journey", "旅途", UNLOCK_ALWAYS, false, weights);
	}

	/**
	 * Inclusive start, exclusive end; end does not wrap midnight.
	 * Natural rolls are eligible while {@code timeOfDay ∈ [windowStart, windowEnd)}.
	 */
	public record SpawnWindow(int windowStart, int windowEnd, float chance) {
		public SpawnWindow {
			windowStart = Math.max(0, Math.min(23999, windowStart));
			windowEnd = Math.max(windowStart + 1, Math.min(24000, windowEnd));
			chance = Math.max(0.0f, Math.min(1.0f, chance));
		}

		public int length() {
			return windowEnd - windowStart;
		}
	}

	/**
	 * @param excludeHell wilderness: hell weight 0
	 * @param excludeRoadkill nether HOSTILE_BORDER / siege: roadkill weight 0
	 * @param mergeUnlocked nether off-hours 全选: sum unlocked stage weights then renormalize
	 * @param applyDutyWindows natural siege: keep only cluster types whose duty window is open
	 */
	public record TierRoll(boolean excludeHell, boolean excludeRoadkill, boolean mergeUnlocked, boolean applyDutyWindows) {
		public static TierRoll overworldSiege() {
			return new TierRoll(false, false, false, false);
		}

		public static TierRoll of(ServerLevel level, Stage stage, boolean wilderness) {
			return of(level, stage, wilderness, false);
		}

		public static TierRoll of(ServerLevel level, Stage stage, boolean wilderness, boolean dutyWindows) {
			if (wilderness) {
				return new TierRoll(true, false, false, false);
			}
			boolean nether = SiegeDayClock.isNether(level);
			if (dutyWindows) {
				return new TierRoll(false, nether, false, true);
			}
			boolean inHours = stage != null && SiegeDayClock.inWindow(stage.spawn(), SiegeDayClock.timeOfDay(level));
			return new TierRoll(false, nether, nether && !inHours, false);
		}
	}

	public record Stage(
			String id,
			String name,
			String unlock,
			boolean requireDemonChunk,
			Map<String, Double> weights,
			SpawnWindow spawn,
			SpawnWindow netherSpawn
	) {
		public Stage {
			id = id == null ? "journey" : id;
			name = name == null ? id : name;
			unlock = unlock == null ? UNLOCK_ALWAYS : unlock;
			weights = weights == null ? Map.of() : Map.copyOf(weights);
			spawn = spawn == null ? defaultSpawnWindow(id) : spawn;
			netherSpawn = netherSpawn == null ? defaultNetherSpawnWindow(spawn.chance()) : netherSpawn;
		}

		public Stage(String id, String name, String unlock, boolean requireDemonChunk, Map<String, Double> weights) {
			this(id, name, unlock, requireDemonChunk, weights, defaultSpawnWindow(id), null);
		}

		public Stage(
				String id,
				String name,
				String unlock,
				boolean requireDemonChunk,
				Map<String, Double> weights,
				SpawnWindow spawn
		) {
			this(id, name, unlock, requireDemonChunk, weights, spawn, null);
		}
	}

	public record WorldSnapshot(
			int worldOwned,
			boolean hasDemonChunk,
			boolean hasIron,
			boolean hasIronArmor,
			boolean hasDiamond,
			Difficulty difficulty
	) {
		/** 故土阶段：困难看铁甲，其余看铁锭. */
		public boolean hostStageUnlocked() {
			return SiegeDifficulty.isHard(difficulty) ? hasIronArmor : hasIron;
		}

		public boolean hostUnlocked() {
			return hostStageUnlocked();
		}

		public boolean legionUnlocked() {
			return hasDiamond;
		}
	}
}
