package luowei.fortress_besieged.siege.spawn;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.CountBucket;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.CountSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.EnchantSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.LootSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.SpeciesSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.StatsSpec;

/**
 * Cluster template configs. Siege and wilderness each have their own file
 * (stages + templates). Missing files are filled from the matching default
 * (siege keeps documented army sizes; wilderness uses the smaller pack counts).
 */
public final class SiegeClusterConfig {
	public static final String FILE_NAME = "fortress-besieged-clusters.json";
	public static final String FILE_NAME_WILDERNESS = "fortress-besieged-wilderness.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static SiegeClusterConfig SIEGE = defaults();
	private static SiegeClusterConfig WILDERNESS = wildernessDefaults();

	private final SiegeProgression progression;
	private final Map<String, SiegeClusterTemplate> templates;
	private final boolean wildernessFile;
	private final float attemptChance;

	private SiegeClusterConfig(SiegeProgression progression, List<SiegeClusterTemplate> templates) {
		this(progression, templates, false, WildernessSpawnConfig.ATTEMPT_CHANCE);
	}

	private SiegeClusterConfig(
			SiegeProgression progression,
			List<SiegeClusterTemplate> templates,
			boolean wildernessFile,
			float attemptChance
	) {
		this.progression = progression == null
				? (wildernessFile ? SiegeClusterDefaults.wildernessProgression() : SiegeClusterDefaults.progression())
				: progression;
		Map<String, SiegeClusterTemplate> map = new LinkedHashMap<>();
		for (SiegeClusterTemplate template : templates) {
			if (template != null) {
				map.put(template.id(), template);
			}
		}
		this.templates = Map.copyOf(map);
		this.wildernessFile = wildernessFile;
		this.attemptChance = clampChance(attemptChance);
	}

	/** Wilderness scan Bernoulli; unused by siege files. */
	public float attemptChance() {
		return attemptChance;
	}

	/** Siege / {@code /siege spawn} roster. */
	public static SiegeClusterConfig siege() {
		return SIEGE;
	}

	/** Wilderness pack roster. Independent stages and templates. */
	public static SiegeClusterConfig wilderness() {
		return WILDERNESS;
	}

	/** Same as {@link #siege()}. */
	public static SiegeClusterConfig get() {
		return SIEGE;
	}

	public SiegeProgression progression() {
		return progression;
	}

	public Optional<SiegeClusterTemplate> getTemplate(String id) {
		return Optional.ofNullable(templates.get(id));
	}

	public SiegeClusterTemplate requireTemplate(String id) {
		return getTemplate(id).orElseThrow(() -> new IllegalArgumentException("Unknown cluster template: " + id));
	}

	public Collection<SiegeClusterTemplate> templates() {
		return templates.values();
	}

	public List<SiegeClusterTemplate> templatesFor(String race, String tier) {
		List<SiegeClusterTemplate> list = new ArrayList<>();
		for (SiegeClusterTemplate template : templates.values()) {
			if ((race == null || race.equals(template.race()))
					&& (tier == null || tier.equals(template.tier()))) {
				list.add(template);
			}
		}
		return list;
	}

	public static void load() {
		SIEGE = loadFile(FILE_NAME, "[siege cluster]", false);
		WILDERNESS = loadFile(FILE_NAME_WILDERNESS, "[wilderness cluster]", true);
	}

	private static SiegeClusterConfig loadFile(String fileName, String logTag, boolean wilderness) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
		SiegeClusterConfig fallback = wilderness ? wildernessDefaults() : defaults();
		try {
			if (!Files.exists(path)) {
				save(path, fallback);
				FortressBesieged.LOGGER.info("{} wrote default config {} ({} templates)",
						logTag, path, fallback.templates.size());
				return fallback;
			}
			SiegeClusterConfig config;
			try (BufferedReader reader = Files.newBufferedReader(path)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				config = json == null ? fallback : fromJson(json, wilderness);
			}
			FortressBesieged.LOGGER.info(
					"{} loaded {} templates={} stages={} attemptChance={}",
					logTag,
					path,
					config.templates.size(),
					config.progression.stages().size(),
					wilderness ? config.attemptChance : "-");
			return config;
		} catch (Exception e) {
			FortressBesieged.LOGGER.warn("{} failed to load {}, using defaults: {}", logTag, path, e.toString());
			return fallback;
		}
	}

	public static SiegeClusterConfig defaults() {
		return new SiegeClusterConfig(SiegeClusterDefaults.progression(), SiegeClusterDefaults.templates());
	}

	public static SiegeClusterConfig wildernessDefaults() {
		return new SiegeClusterConfig(
				SiegeClusterDefaults.wildernessProgression(),
				SiegeClusterDefaults.wildernessTemplates(),
				true,
				WildernessSpawnConfig.ATTEMPT_CHANCE);
	}

	private static void save(Path path, SiegeClusterConfig config) throws IOException {
		Files.createDirectories(path.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config.toJson(), writer);
		}
	}

	private JsonObject toJson() {
		JsonObject json = new JsonObject();
		if (wildernessFile) {
			json.addProperty("attemptChance", attemptChance);
		}
		json.add("progression", GSON.toJsonTree(progression.toJsonMap()));
		JsonArray clusters = new JsonArray();
		for (SiegeClusterTemplate template : templates.values()) {
			clusters.add(GSON.toJsonTree(template.toJsonMap()));
		}
		json.add("clusters", clusters);
		return json;
	}

	private static SiegeClusterConfig fromJson(JsonObject json, boolean wilderness) {
		SiegeProgression progression = parseProgression(
				json.has("progression") && json.get("progression").isJsonObject()
						? json.getAsJsonObject("progression")
						: new JsonObject(),
				wilderness);
		List<SiegeClusterTemplate> templates = new ArrayList<>();
		if (json.has("clusters") && json.get("clusters").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("clusters")) {
				if (!element.isJsonObject()) {
					continue;
				}
				try {
					templates.add(parseTemplate(element.getAsJsonObject()));
				} catch (RuntimeException e) {
					FortressBesieged.LOGGER.warn("[cluster config] skip invalid template: {}", e.toString());
				}
			}
		}
		if (templates.isEmpty()) {
			templates.addAll(wilderness
					? SiegeClusterDefaults.wildernessTemplates()
					: SiegeClusterDefaults.templates());
		}
		float chance = wilderness
				? (float) getDouble(json, "attemptChance", WildernessSpawnConfig.ATTEMPT_CHANCE)
				: WildernessSpawnConfig.ATTEMPT_CHANCE;
		return new SiegeClusterConfig(progression, templates, wilderness, chance);
	}

	private static boolean loggedLegacyUnlockKeys;

	private static SiegeProgression parseProgression(JsonObject json, boolean wilderness) {
		if ((json.has("hostUnlockWorldOwned") || json.has("legionUnlockWorldOwned"))
				&& !loggedLegacyUnlockKeys) {
			loggedLegacyUnlockKeys = true;
			FortressBesieged.LOGGER.info(
					"[siege cluster] ignoring hostUnlockWorldOwned/legionUnlockWorldOwned; "
							+ "stages unlock by iron / iron-armor / diamond advancements");
		}
		SiegeProgression fallback = wilderness
				? SiegeClusterDefaults.wildernessProgression()
				: SiegeClusterDefaults.progression();
		List<SiegeProgression.Stage> stages = new ArrayList<>();
		if (json.has("stages") && json.get("stages").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("stages")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject row = element.getAsJsonObject();
				String stageId = getString(row, "id", "journey");
				SiegeProgression.SpawnWindow spawnFallback = wilderness
						? new SiegeProgression.SpawnWindow(0, 24000, WildernessSpawnConfig.ATTEMPT_CHANCE)
						: SiegeProgression.defaultSpawnWindow(stageId);
				SiegeProgression.SpawnWindow spawn = parseSpawnWindow(row, "spawn", spawnFallback);
				SiegeProgression.SpawnWindow netherSpawn = parseSpawnWindow(row, "netherSpawn",
						SiegeProgression.defaultNetherSpawnWindow(spawn.chance()));
				stages.add(new SiegeProgression.Stage(
						stageId,
						getString(row, "name", "旅途"),
						getString(row, "unlock", SiegeProgression.UNLOCK_ALWAYS),
						getBool(row, "requireDemonChunk", false),
						getDoubleMap(row, "weights"),
						spawn,
						netherSpawn));
			}
		}
		if (stages.isEmpty()) {
			stages = fallback.stages();
		}
		return new SiegeProgression(stages);
	}

	private static SiegeProgression.SpawnWindow parseSpawnWindow(
			JsonObject stageJson,
			String key,
			SiegeProgression.SpawnWindow fallback
	) {
		if (!stageJson.has(key) || !stageJson.get(key).isJsonObject()) {
			return fallback;
		}
		JsonObject spawn = stageJson.getAsJsonObject(key);
		return new SiegeProgression.SpawnWindow(
				getInt(spawn, "windowStart", fallback.windowStart()),
				getInt(spawn, "windowEnd", fallback.windowEnd()),
				(float) getDouble(spawn, "chance", fallback.chance()));
	}

	private static SiegeClusterTemplate parseTemplate(JsonObject json) {
		String id = getString(json, "id", null);
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("cluster missing id");
		}
		CountSpec count = parseCountSpec(json);
		List<SpeciesSpec> species = new ArrayList<>();
		if (json.has("species") && json.get("species").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("species")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject row = element.getAsJsonObject();
				species.add(new SpeciesSpec(
						getString(row, "entity", "minecraft:zombie"),
						getInt(row, "weight", 1),
						getBool(row, "baby", false),
						parseStats(row.has("stats") && row.get("stats").isJsonObject()
								? row.getAsJsonObject("stats")
								: null)));
			}
		}
		Map<String, Map<String, Integer>> slots = new LinkedHashMap<>();
		if (json.has("slots") && json.get("slots").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("slots").entrySet()) {
				if (entry.getValue().isJsonObject()) {
					slots.put(entry.getKey(), intMap(entry.getValue().getAsJsonObject()));
				}
			}
		}
		Map<String, String> easterSlots = new LinkedHashMap<>();
		if (json.has("easterEggSlots") && json.get("easterEggSlots").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("easterEggSlots").entrySet()) {
				if (entry.getValue().isJsonPrimitive()) {
					easterSlots.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		}
		EnchantSpec enchant = EnchantSpec.NONE;
		if (json.has("enchant") && json.get("enchant").isJsonObject()) {
			JsonObject ench = json.getAsJsonObject("enchant");
			int min = 0;
			int max = 0;
			if (ench.has("level") && ench.get("level").isJsonArray() && ench.getAsJsonArray("level").size() >= 2) {
				min = ench.getAsJsonArray("level").get(0).getAsInt();
				max = ench.getAsJsonArray("level").get(1).getAsInt();
			}
			enchant = new EnchantSpec(
					getDouble(ench, "armorChance", 0.0D),
					getDouble(ench, "weaponChance", 0.0D),
					min, max);
		}
		String tier = getString(json, "tier", "small");
		LootSpec loot = LootSpec.forTier(tier);
		if (json.has("loot") && json.get("loot").isJsonObject()) {
			JsonObject lootJson = json.getAsJsonObject("loot");
			loot = new LootSpec(
					getString(lootJson, "item", "minecraft:emerald"),
					getDouble(lootJson, "chance", 0.0D),
					getInt(lootJson, "min", 0),
					getInt(lootJson, "max", 0));
		}
		return new SiegeClusterTemplate(
				id,
				getString(json, "name", id),
				getString(json, "race", "undead"),
				tier,
				getString(json, "size", "medium"),
				getString(json, "dimension", null),
				count,
				species,
				parseStats(json.has("stats") && json.get("stats").isJsonObject()
						? json.getAsJsonObject("stats")
						: null),
				slots,
				json.has("durability") && json.get("durability").isJsonObject()
						? intMap(json.getAsJsonObject("durability"))
						: Map.of(),
				enchant,
				getString(json, "mainHandEmptyFallback", null),
				getDouble(json, "bannerChance", 0.0D),
				getDouble(json, "easterEggChance", 0.0D),
				easterSlots,
				loot);
	}

	private static StatsSpec parseStats(JsonObject json) {
		if (json == null) {
			return StatsSpec.EMPTY;
		}
		return new StatsSpec(
				json.has("health") && json.get("health").isJsonObject()
						? intMap(json.getAsJsonObject("health")) : Map.of(),
				json.has("speed") && json.get("speed").isJsonObject()
						? intMap(json.getAsJsonObject("speed")) : Map.of(),
				json.has("attack") && json.get("attack").isJsonObject()
						? intMap(json.getAsJsonObject("attack")) : Map.of());
	}

	private static Map<String, Integer> intMap(JsonObject json) {
		Map<String, Integer> map = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
				map.put(entry.getKey(), entry.getValue().getAsInt());
			}
		}
		return map;
	}

	private static Map<String, Double> getDoubleMap(JsonObject json, String key) {
		Map<String, Double> map = new LinkedHashMap<>();
		if (!json.has(key) || !json.get(key).isJsonObject()) {
			return map;
		}
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(key).entrySet()) {
			if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
				map.put(entry.getKey(), entry.getValue().getAsDouble());
			}
		}
		return map;
	}

	private static int getInt(JsonObject json, String key, int fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsDouble() : fallback;
	}

	private static boolean getBool(JsonObject json, String key, boolean fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback;
	}

	private static String getString(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	private static CountSpec parseCountSpec(JsonObject json) {
		JsonObject countJson = json.has("count") && json.get("count").isJsonObject()
				? json.getAsJsonObject("count")
				: new JsonObject();
		CountSpec base = parseCountRange(countJson);
		Map<String, CountSpec> byDifficulty = new LinkedHashMap<>();
		if (json.has("countByDifficulty") && json.get("countByDifficulty").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("countByDifficulty").entrySet()) {
				if (entry.getValue() != null && entry.getValue().isJsonObject()) {
					byDifficulty.put(entry.getKey().toLowerCase(), parseCountRange(entry.getValue().getAsJsonObject()));
				}
			}
		}
		return new CountSpec(base.min(), base.max(), base.buckets(), byDifficulty);
	}

	private static CountSpec parseCountRange(JsonObject countJson) {
		List<CountBucket> buckets = new ArrayList<>();
		if (countJson.has("buckets") && countJson.get("buckets").isJsonArray()) {
			for (JsonElement element : countJson.getAsJsonArray("buckets")) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject row = element.getAsJsonObject();
				buckets.add(new CountBucket(
						getInt(row, "min", 3),
						getInt(row, "max", 3),
						getInt(row, "weight", 1)));
			}
		}
		return new CountSpec(
				getInt(countJson, "min", 3),
				getInt(countJson, "max", 3),
				buckets);
	}

	private static float clampChance(float chance) {
		if (Float.isNaN(chance)) {
			return WildernessSpawnConfig.ATTEMPT_CHANCE;
		}
		return Math.max(0.0f, Math.min(1.0f, chance));
	}
}
