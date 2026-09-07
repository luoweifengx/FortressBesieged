package luowei.fortress_besieged.siege;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

import luowei.fortress_besieged.FortressBesieged;

/**
 * 集群运行时行为开关。启动时从 {@code config/fortress-besieged-behavior.json} 加载，缺失则写默认。
 */
public final class SiegeClusterBehaviorConfig {
	public static final String FILE_NAME = "fortress-besieged-behavior.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static SiegeClusterBehaviorConfig INSTANCE = defaults();

	/**
	 * FREE：追击目标离开中心 7×7 时，是否把受影响单位回满血。默认 {@code false}。
	 */
	public final boolean healOnTargetLeaveZone;

	private SiegeClusterBehaviorConfig(boolean healOnTargetLeaveZone) {
		this.healOnTargetLeaveZone = healOnTargetLeaveZone;
	}

	public static SiegeClusterBehaviorConfig get() {
		return INSTANCE;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (!Files.exists(path)) {
				INSTANCE = defaults();
				save(path, INSTANCE);
				FortressBesieged.LOGGER.info("[siege behavior] wrote default config {}", path);
				return;
			}
			try (BufferedReader reader = Files.newBufferedReader(path)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				INSTANCE = json == null ? defaults() : fromJson(json);
			}
			FortressBesieged.LOGGER.info(
					"[siege behavior] loaded {} healOnTargetLeaveZone={}",
					path,
					INSTANCE.healOnTargetLeaveZone);
		} catch (Exception e) {
			INSTANCE = defaults();
			FortressBesieged.LOGGER.warn("[siege behavior] failed to load {}, using defaults: {}", path, e.toString());
		}
	}

	public static SiegeClusterBehaviorConfig defaults() {
		return new SiegeClusterBehaviorConfig(false);
	}

	private static void save(Path path, SiegeClusterBehaviorConfig config) throws IOException {
		Files.createDirectories(path.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config.toJson(), writer);
		}
	}

	private JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("healOnTargetLeaveZone", healOnTargetLeaveZone);
		json.addProperty("_comment",
				"healOnTargetLeaveZone: FREE 阶段目标离开中心 7x7 时是否将受影响单位回满血。默认 false。");
		return json;
	}

	private static SiegeClusterBehaviorConfig fromJson(JsonObject json) {
		boolean heal = json.has("healOnTargetLeaveZone")
				&& json.get("healOnTargetLeaveZone").isJsonPrimitive()
				&& json.get("healOnTargetLeaveZone").getAsBoolean();
		return new SiegeClusterBehaviorConfig(heal);
	}
}
