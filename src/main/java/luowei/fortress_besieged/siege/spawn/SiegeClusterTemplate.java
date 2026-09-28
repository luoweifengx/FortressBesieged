package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.world.Difficulty;

/**
 * One configurable siege cluster template (JSON {@code clusters[]} entry).
 */
public final class SiegeClusterTemplate {
	public static final int MIN_COUNT = 3;

	private final String id;
	private final String name;
	private final String race;
	private final String tier;
	private final String size;
	private final String dimension;
	private final CountSpec count;
	private final List<SpeciesSpec> species;
	private final StatsSpec stats;
	private final Map<String, Map<String, Integer>> slots;
	private final Map<String, Integer> durability;
	private final EnchantSpec enchant;
	private final String mainHandEmptyFallback;
	private final double bannerChance;
	private final double easterEggChance;
	private final Map<String, String> easterEggSlots;
	private final LootSpec loot;

	public SiegeClusterTemplate(
			String id,
			String name,
			String race,
			String tier,
			String size,
			CountSpec count,
			List<SpeciesSpec> species,
			StatsSpec stats,
			Map<String, Map<String, Integer>> slots,
			Map<String, Integer> durability,
			EnchantSpec enchant,
			String mainHandEmptyFallback,
			double bannerChance,
			double easterEggChance,
			Map<String, String> easterEggSlots,
			LootSpec loot
	) {
		this(
				id, name, race, tier, size, null, count, species, stats, slots, durability,
				enchant, mainHandEmptyFallback, bannerChance, easterEggChance, easterEggSlots, loot);
	}

	public SiegeClusterTemplate(
			String id,
			String name,
			String race,
			String tier,
			String size,
			String dimension,
			CountSpec count,
			List<SpeciesSpec> species,
			StatsSpec stats,
			Map<String, Map<String, Integer>> slots,
			Map<String, Integer> durability,
			EnchantSpec enchant,
			String mainHandEmptyFallback,
			double bannerChance,
			double easterEggChance,
			Map<String, String> easterEggSlots,
			LootSpec loot
	) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("cluster id");
		}
		this.id = id;
		this.name = name == null || name.isBlank() ? id : name;
		this.race = race == null ? "undead" : race;
		this.tier = tier == null ? "small" : tier;
		this.size = size == null ? "medium" : size;
		this.dimension = normalizeDimension(dimension, this.race);
		this.count = count == null ? new CountSpec(MIN_COUNT, MIN_COUNT, List.of()) : count;
		this.species = species == null ? List.of() : List.copyOf(species);
		this.stats = stats == null ? StatsSpec.EMPTY : stats;
		this.slots = slots == null ? Map.of() : copySlots(slots);
		this.durability = durability == null ? Map.of() : Map.copyOf(durability);
		this.enchant = enchant == null ? EnchantSpec.NONE : enchant;
		this.mainHandEmptyFallback = mainHandEmptyFallback;
		this.bannerChance = Math.max(0.0D, bannerChance);
		this.easterEggChance = Math.max(0.0D, easterEggChance);
		this.easterEggSlots = easterEggSlots == null ? Map.of() : Map.copyOf(easterEggSlots);
		this.loot = loot == null ? LootSpec.NONE : loot;
	}

	private static String normalizeDimension(String dimension, String race) {
		if (dimension == null || dimension.isBlank()) {
			return "nether".equals(race) ? "nether" : "overworld";
		}
		return dimension;
	}

	private static Map<String, Map<String, Integer>> copySlots(Map<String, Map<String, Integer>> slots) {
		Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, Integer>> entry : slots.entrySet()) {
			copy.put(entry.getKey(), entry.getValue() == null ? Map.of() : Map.copyOf(entry.getValue()));
		}
		return Map.copyOf(copy);
	}

	public SiegeClusterTemplate withCount(CountSpec count) {
		return new SiegeClusterTemplate(
				id, name, race, tier, size, dimension, count, species, stats, slots, durability,
				enchant, mainHandEmptyFallback, bannerChance, easterEggChance, easterEggSlots, loot);
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String race() {
		return race;
	}

	public String tier() {
		return tier;
	}

	public String size() {
		return size;
	}

	public String dimension() {
		return dimension;
	}

	/** Nether-only roster: race {@code nether} or {@code dimension=nether}. */
	public boolean isNetherRoster() {
		return "nether".equals(race) || "nether".equals(dimension);
	}

	public CountSpec count() {
		return count;
	}

	public List<SpeciesSpec> species() {
		return species;
	}

	public StatsSpec stats() {
		return stats;
	}

	public Map<String, Map<String, Integer>> slots() {
		return slots;
	}

	public Map<String, Integer> durability() {
		return durability;
	}

	public EnchantSpec enchant() {
		return enchant;
	}

	public String mainHandEmptyFallback() {
		return mainHandEmptyFallback;
	}

	public double bannerChance() {
		return bannerChance;
	}

	public double easterEggChance() {
		return easterEggChance;
	}

	public Map<String, String> easterEggSlots() {
		return easterEggSlots;
	}

	public LootSpec loot() {
		return loot;
	}

	public boolean isHell() {
		return "hell".equals(tier);
	}

	public int rollCount(Random random) {
		return rollCount(random, null);
	}

	public int rollCount(Random random, Difficulty difficulty) {
		CountSpec spec = count.forDifficulty(difficulty);
		int rolled;
		if (spec.buckets() != null && !spec.buckets().isEmpty()) {
			CountBucket bucket = pickBucket(spec, random);
			rolled = WeightedPicker.inRange(bucket.min(), bucket.max(), random);
		} else {
			rolled = WeightedPicker.inRange(spec.min(), spec.max(), random);
		}
		return Math.max(MIN_COUNT, rolled);
	}

	public SpeciesSpec rollSpecies(Random random, long dayIndex) {
		int total = 0;
		for (SpeciesSpec spec : species) {
			if (speciesUnlocked(spec, dayIndex)) {
				total += spec.weight();
			}
		}
		if (total <= 0) {
			return null;
		}
		int roll = random.nextInt(total);
		int acc = 0;
		SpeciesSpec last = null;
		for (SpeciesSpec spec : species) {
			if (!speciesUnlocked(spec, dayIndex)) {
				continue;
			}
			acc += spec.weight();
			last = spec;
			if (roll < acc) {
				return spec;
			}
		}
		return last;
	}

	private static boolean speciesUnlocked(SpeciesSpec spec, long dayIndex) {
		if (spec == null || spec.weight() <= 0) {
			return false;
		}
		if (isVindicator(spec.entity()) && dayIndex < SiegeSpawnConfig.VINDICATOR_MIN_DAY_INDEX) {
			return false;
		}
		return true;
	}

	private static boolean isVindicator(String entity) {
		return "minecraft:vindicator".equals(entity) || "vindicator".equals(entity);
	}

	private static CountBucket pickBucket(CountSpec spec, Random random) {
		int total = 0;
		for (CountBucket bucket : spec.buckets()) {
			total += Math.max(0, bucket.weight());
		}
		if (total <= 0) {
			return spec.buckets().get(0);
		}
		int roll = random.nextInt(total);
		int acc = 0;
		CountBucket last = spec.buckets().get(spec.buckets().size() - 1);
		for (CountBucket bucket : spec.buckets()) {
			if (bucket.weight() <= 0) {
				continue;
			}
			acc += bucket.weight();
			last = bucket;
			if (roll < acc) {
				return bucket;
			}
		}
		return last;
	}

	public record CountSpec(int min, int max, List<CountBucket> buckets, Map<String, CountSpec> byDifficulty) {
		public CountSpec {
			int lo = Math.min(min, max);
			int hi = Math.max(min, max);
			min = lo;
			max = hi;
			buckets = buckets == null ? List.of() : List.copyOf(buckets);
			if (byDifficulty == null || byDifficulty.isEmpty()) {
				byDifficulty = Map.of();
			} else {
				Map<String, CountSpec> copy = new LinkedHashMap<>();
				for (Map.Entry<String, CountSpec> entry : byDifficulty.entrySet()) {
					if (entry.getKey() == null || entry.getValue() == null) {
						continue;
					}
					CountSpec spec = entry.getValue();
					copy.put(entry.getKey(), new CountSpec(spec.min(), spec.max(), spec.buckets(), Map.of()));
				}
				byDifficulty = Map.copyOf(copy);
			}
		}

		public CountSpec(int min, int max, List<CountBucket> buckets) {
			this(min, max, buckets, Map.of());
		}

		public CountSpec forDifficulty(Difficulty difficulty) {
			String key = switch (difficulty == null ? Difficulty.NORMAL : difficulty) {
				case EASY -> "easy";
				case HARD -> "hard";
				default -> "normal";
			};
			CountSpec override = byDifficulty.get(key);
			return override == null ? this : override;
		}
	}

	public record CountBucket(int min, int max, int weight) {
	}

	public record SpeciesSpec(String entity, int weight, boolean baby, StatsSpec stats) {
		public SpeciesSpec {
			entity = entity == null ? "" : entity;
			stats = stats == null ? StatsSpec.EMPTY : stats;
		}

		public StatsSpec mergedStats(StatsSpec clusterStats) {
			StatsSpec base = clusterStats == null ? StatsSpec.EMPTY : clusterStats;
			return new StatsSpec(
					stats.health().isEmpty() ? base.health() : stats.health(),
					stats.speed().isEmpty() ? base.speed() : stats.speed(),
					stats.attack().isEmpty() ? base.attack() : stats.attack());
		}
	}

	public record StatsSpec(
			Map<String, Integer> health,
			Map<String, Integer> speed,
			Map<String, Integer> attack
	) {
		public static final StatsSpec EMPTY = new StatsSpec(Map.of(), Map.of(), Map.of());

		public StatsSpec {
			health = health == null ? Map.of() : Map.copyOf(health);
			speed = speed == null ? Map.of() : Map.copyOf(speed);
			attack = attack == null ? Map.of() : Map.copyOf(attack);
		}
	}

	public record EnchantSpec(double armorChance, double weaponChance, int levelMin, int levelMax) {
		public static final EnchantSpec NONE = new EnchantSpec(0.0D, 0.0D, 0, 0);
	}

	public record LootSpec(String item, double chance, int min, int max) {
		public static final LootSpec NONE = new LootSpec("", 0.0D, 0, 0);

		public LootSpec {
			item = item == null ? "" : item;
			chance = Math.max(0.0D, chance);
			min = Math.max(0, min);
			max = Math.max(min, max);
		}

		public boolean enabled() {
			return chance > 0.0D && max > 0 && !item.isBlank();
		}

		/** Used when a cluster JSON omits {@code loot}. Explicit {@code chance: 0} still disables. */
		public static LootSpec forTier(String tier) {
			return switch (tier == null ? "" : tier) {
				case "roadkill" -> new LootSpec("minecraft:emerald", 0.5D, 1, 1);
				case "small" -> new LootSpec("minecraft:emerald", 0.8D, 1, 2);
				case "host" -> new LootSpec("minecraft:emerald", 0.9D, 1, 4);
				case "legion" -> new LootSpec("minecraft:emerald", 0.1D, 2, 6);
				case "hell" -> new LootSpec("minecraft:emerald", 0.3D, 10, 30);
				default -> NONE;
			};
		}
	}

	public Map<String, Object> toJsonMap() {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", id);
		json.put("name", name);
		json.put("race", race);
		json.put("tier", tier);
		json.put("size", size);
		json.put("dimension", dimension);
		Map<String, Object> countJson = new LinkedHashMap<>();
		countJson.put("min", count.min());
		countJson.put("max", count.max());
		if (!count.buckets().isEmpty()) {
			List<Map<String, Integer>> buckets = new ArrayList<>();
			for (CountBucket bucket : count.buckets()) {
				Map<String, Integer> row = new LinkedHashMap<>();
				row.put("min", bucket.min());
				row.put("max", bucket.max());
				row.put("weight", bucket.weight());
				buckets.add(row);
			}
			countJson.put("buckets", buckets);
		}
		json.put("count", countJson);
		if (!count.byDifficulty().isEmpty()) {
			Map<String, Object> byDiff = new LinkedHashMap<>();
			for (Map.Entry<String, CountSpec> entry : count.byDifficulty().entrySet()) {
				CountSpec spec = entry.getValue();
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("min", spec.min());
				row.put("max", spec.max());
				byDiff.put(entry.getKey(), row);
			}
			json.put("countByDifficulty", byDiff);
		}
		List<Map<String, Object>> speciesJson = new ArrayList<>();
		for (SpeciesSpec spec : species) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("entity", spec.entity());
			row.put("weight", spec.weight());
			if (spec.baby()) {
				row.put("baby", true);
			}
			if (spec.stats() != StatsSpec.EMPTY
					&& (!spec.stats().health().isEmpty()
					|| !spec.stats().speed().isEmpty()
					|| !spec.stats().attack().isEmpty())) {
				row.put("stats", statsToMap(spec.stats()));
			}
			speciesJson.add(row);
		}
		json.put("species", speciesJson);
		json.put("stats", statsToMap(stats));
		json.put("slots", slots);
		json.put("durability", durability);
		Map<String, Object> enchantJson = new LinkedHashMap<>();
		enchantJson.put("armorChance", enchant.armorChance());
		enchantJson.put("weaponChance", enchant.weaponChance());
		enchantJson.put("level", List.of(enchant.levelMin(), enchant.levelMax()));
		json.put("enchant", enchantJson);
		if (mainHandEmptyFallback != null && !mainHandEmptyFallback.isBlank()) {
			json.put("mainHandEmptyFallback", mainHandEmptyFallback);
		}
		if (bannerChance > 0.0D) {
			json.put("bannerChance", bannerChance);
		}
		if (easterEggChance > 0.0D) {
			json.put("easterEggChance", easterEggChance);
			json.put("easterEggSlots", easterEggSlots);
		}
		if (loot.enabled() || !loot.item().isBlank()) {
			Map<String, Object> lootJson = new LinkedHashMap<>();
			lootJson.put("item", loot.item());
			lootJson.put("chance", loot.chance());
			lootJson.put("min", loot.min());
			lootJson.put("max", loot.max());
			json.put("loot", lootJson);
		}
		return json;
	}

	private static Map<String, Object> statsToMap(StatsSpec spec) {
		Map<String, Object> json = new LinkedHashMap<>();
		if (!spec.health().isEmpty()) {
			json.put("health", spec.health());
		}
		if (!spec.speed().isEmpty()) {
			json.put("speed", spec.speed());
		}
		if (!spec.attack().isEmpty()) {
			json.put("attack", spec.attack());
		}
		return json;
	}
}
