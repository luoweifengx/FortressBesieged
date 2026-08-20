package luowei.fortress_besieged.siege.path;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import luowei.fortress_besieged.FortressBesieged;

/**
 * DESTROY 接近代价表：硬度分档 + 方块 ID 覆盖，启动时从 config 加载。
 */
public final class SiegePathCostConfig {
	public static final String FILE_NAME = "fortress-besieged-pathfinding.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static SiegePathCostConfig INSTANCE = defaults();

	public final int airCost;
	public final float softHardnessMax;
	public final int softCost;
	public final int hardCost;
	public final int placeCost;
	public final Map<String, Integer> blockOverrides;
	public final int maxNodes;
	public final int searchExpand;
	public final int localBoxXz;
	public final int localBoxY;
	/** 盒外贪心前沿节点数（默认 2）；每次最多查询约 x×邻边 个 f。 */
	public final int greedyFrontierX;
	/** @deprecated 旧 realtime OPEN 上限；已停用，仅兼容旧配置。 */
	@Deprecated
	public final int realtimeMemory;
	/** @deprecated 旧 realtime 每次提交步数；已停用，仅兼容旧配置。 */
	@Deprecated
	public final int realtimeCommitSteps;
	/**
	 * 保留字段：失败冷却下限仍会参考它；成功走完完整队列后不再用它强制重规划。
	 */
	public final int replanIntervalTicks;
	public final int failCooldownTicks;
	public final int walkTimeoutTicks;
	public final int breakTickBase;
	public final int assignedBreakTicks;
	public final double reach;
	/** 挖掘盒 XZ 切比雪夫半径（非寻路局部盒 localBoxXz）。 */
	public final int mineReachXz;
	/** 挖掘盒 Y 切比雪夫半径（非寻路局部盒 localBoxY）。 */
	public final int mineReachY;

	private SiegePathCostConfig(
			int airCost,
			float softHardnessMax,
			int softCost,
			int hardCost,
			int placeCost,
			Map<String, Integer> blockOverrides,
			int maxNodes,
			int searchExpand,
			int localBoxXz,
			int localBoxY,
			int greedyFrontierX,
			int realtimeMemory,
			int realtimeCommitSteps,
			int replanIntervalTicks,
			int failCooldownTicks,
			int walkTimeoutTicks,
			int breakTickBase,
			int assignedBreakTicks,
			double reach,
			int mineReachXz,
			int mineReachY
	) {
		this.airCost = Math.max(1, airCost);
		this.softHardnessMax = Math.max(0.0F, softHardnessMax);
		this.softCost = Math.max(this.airCost, softCost);
		this.hardCost = Math.max(this.softCost, hardCost);
		this.placeCost = Math.max(this.airCost, placeCost);
		this.blockOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(blockOverrides));
		this.maxNodes = Math.max(64, maxNodes);
		this.searchExpand = Math.max(4, searchExpand);
		this.localBoxXz = Math.max(4, localBoxXz);
		this.localBoxY = Math.max(1, localBoxY);
		this.greedyFrontierX = Math.max(1, Math.min(8, greedyFrontierX));
		this.realtimeMemory = Math.max(8, Math.min(256, realtimeMemory));
		this.realtimeCommitSteps = Math.max(1, Math.min(4, realtimeCommitSteps));
		this.replanIntervalTicks = Math.max(1, replanIntervalTicks);
		this.failCooldownTicks = Math.max(this.replanIntervalTicks, failCooldownTicks);
		this.walkTimeoutTicks = Math.max(1, walkTimeoutTicks);
		this.breakTickBase = Math.max(1, breakTickBase);
		this.assignedBreakTicks = Math.max(1, assignedBreakTicks);
		this.reach = Math.max(0.5D, reach);
		this.mineReachXz = Math.max(1, mineReachXz);
		this.mineReachY = Math.max(1, mineReachY);
	}

	public static SiegePathCostConfig get() {
		return INSTANCE;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (!Files.exists(path)) {
				INSTANCE = defaults();
				save(path, INSTANCE);
				FortressBesieged.LOGGER.info("[siege path] wrote default config {}", path);
				return;
			}
			try (BufferedReader reader = Files.newBufferedReader(path)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				INSTANCE = json == null ? defaults() : fromJson(json);
			}
			FortressBesieged.LOGGER.info(
					"[siege path] loaded {} air={} soft<{}={} hard={} place={} maxNodes={} box={}x{} greedyX={} replan={} failCd={} walkTimeout={}",
					path,
					INSTANCE.airCost,
					INSTANCE.softHardnessMax,
					INSTANCE.softCost,
					INSTANCE.hardCost,
					INSTANCE.placeCost,
					INSTANCE.maxNodes,
					INSTANCE.localBoxXz,
					INSTANCE.localBoxY,
					INSTANCE.greedyFrontierX,
					INSTANCE.replanIntervalTicks,
					INSTANCE.failCooldownTicks,
					INSTANCE.walkTimeoutTicks);
		} catch (Exception e) {
			INSTANCE = defaults();
			FortressBesieged.LOGGER.warn("[siege path] failed to load {}, using defaults: {}", path, e.toString());
		}
	}

	public static SiegePathCostConfig defaults() {
		Map<String, Integer> overrides = new LinkedHashMap<>();
		// 草方块硬度恰为 0.6，按 <0.6 会落到 hard；显式标为软档便于核对
		overrides.put("minecraft:grass_block", 2);
		overrides.put("minecraft:dirt", 2);
		overrides.put("minecraft:coarse_dirt", 2);
		overrides.put("minecraft:podzol", 2);
		overrides.put("minecraft:obsidian", 8);
		overrides.put("minecraft:crying_obsidian", 8);
		overrides.put("minecraft:ancient_debris", 8);
		return new SiegePathCostConfig(
				1,
				0.6F,
				2,
				3,
				2,
				overrides,
				4000,
				16,
				16,
				16,
				2,
				48,
				1,
				30,
				100,
				40,
				50,
				60,
				1.5D,
				2,
				2);
	}

	public double reachSq() {
		return reach * reach;
	}

	/**
	 * 拆该格的通行代价；空气/可替换空位返回 empty（无需拆）；不可破坏返回 empty 且调用方应视作 INF。
	 */
	public OptionalInt breakCost(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()
				&& state.getCollisionShape(level, pos).isEmpty()) {
			return OptionalInt.empty();
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F) {
			return OptionalInt.empty();
		}
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		Integer override = blockOverrides.get(id.toString());
		if (override != null) {
			return OptionalInt.of(Math.max(1, override));
		}
		if (hardness == 0.0F || state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()) {
			return OptionalInt.of(airCost);
		}
		if (hardness < softHardnessMax) {
			return OptionalInt.of(softCost);
		}
		return OptionalInt.of(hardCost);
	}

	/** 方块是否因负硬度而不可通行（拆不掉）。 */
	public boolean isImpassable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		return state.getDestroySpeed(level, pos) < 0.0F;
	}

	private static void save(Path path, SiegePathCostConfig config) throws IOException {
		Files.createDirectories(path.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config.toJson(), writer);
		}
	}

	private JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("airCost", airCost);
		json.addProperty("softHardnessMax", softHardnessMax);
		json.addProperty("softCost", softCost);
		json.addProperty("hardCost", hardCost);
		json.addProperty("placeCost", placeCost);
		JsonObject overrides = new JsonObject();
		for (Map.Entry<String, Integer> e : blockOverrides.entrySet()) {
			overrides.addProperty(e.getKey(), e.getValue());
		}
		json.add("blockOverrides", overrides);
		json.addProperty("maxNodes", maxNodes);
		json.addProperty("searchExpand", searchExpand);
		json.addProperty("localBoxXz", localBoxXz);
		json.addProperty("localBoxY", localBoxY);
		json.addProperty("greedyFrontierX", greedyFrontierX);
		json.addProperty("realtimeMemory", realtimeMemory);
		json.addProperty("realtimeCommitSteps", realtimeCommitSteps);
		json.addProperty("replanIntervalTicks", replanIntervalTicks);
		json.addProperty("failCooldownTicks", failCooldownTicks);
		json.addProperty("walkTimeoutTicks", walkTimeoutTicks);
		json.addProperty("breakTickBase", breakTickBase);
		json.addProperty("assignedBreakTicks", assignedBreakTicks);
		json.addProperty("reach", reach);
		json.addProperty("mineReachXz", mineReachXz);
		json.addProperty("mineReachY", mineReachY);
		json.addProperty("_comment",
				"hardness<0 impassable; hardness==0 or air -> airCost; hardness<softHardnessMax -> softCost; else hardCost. Overrides by block id. "
						+ "DESTROY: Chebyshev cube max(|dx|,|dz|)<=localBoxXz and |dy|<=localBoxY (defaults 16/16 ~33^3) then in-box A*; "
						+ "outside: greedy frontier of greedyFrontierX nodes (default 2), parent tree reverse path, then A* — one plan yields full action queue. "
						+ "realtimeMemory/realtimeCommitSteps unused (legacy). "
						+ "Mining box: foot!=assigned and max(|dx|,|dz|)<=mineReachXz and |dy|<=mineReachY. "
						+ "failCooldownTicks=规划/WALK超时后重试同一目标的冷却；成功走完队列不因 replanIntervalTicks 强制冷却。");
		return json;
	}

	private static SiegePathCostConfig fromJson(JsonObject json) {
		SiegePathCostConfig d = defaults();
		Map<String, Integer> overrides = new LinkedHashMap<>(d.blockOverrides);
		if (json.has("blockOverrides") && json.get("blockOverrides").isJsonObject()) {
			overrides.clear();
			JsonObject obj = json.getAsJsonObject("blockOverrides");
			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
					overrides.put(e.getKey(), Math.max(1, e.getValue().getAsInt()));
				}
			}
		}
		return new SiegePathCostConfig(
				getInt(json, "airCost", d.airCost),
				getFloat(json, "softHardnessMax", d.softHardnessMax),
				getInt(json, "softCost", d.softCost),
				getInt(json, "hardCost", d.hardCost),
				getInt(json, "placeCost", d.placeCost),
				overrides,
				getInt(json, "maxNodes", d.maxNodes),
				getInt(json, "searchExpand", d.searchExpand),
				getInt(json, "localBoxXz", d.localBoxXz),
				getInt(json, "localBoxY", d.localBoxY),
				getInt(json, "greedyFrontierX", d.greedyFrontierX),
				getInt(json, "realtimeMemory", d.realtimeMemory),
				getInt(json, "realtimeCommitSteps", d.realtimeCommitSteps),
				getInt(json, "replanIntervalTicks", d.replanIntervalTicks),
				getInt(json, "failCooldownTicks", d.failCooldownTicks),
				getInt(json, "walkTimeoutTicks", d.walkTimeoutTicks),
				getInt(json, "breakTickBase", d.breakTickBase),
				getInt(json, "assignedBreakTicks", d.assignedBreakTicks),
				getDouble(json, "reach", d.reach),
				getInt(json, "mineReachXz", d.mineReachXz),
				getInt(json, "mineReachY", d.mineReachY));
	}

	private static int getInt(JsonObject json, String key, int fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
	}

	private static float getFloat(JsonObject json, String key, float fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsFloat() : fallback;
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsDouble() : fallback;
	}
}
