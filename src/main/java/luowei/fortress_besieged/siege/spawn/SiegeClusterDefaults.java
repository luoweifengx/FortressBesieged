package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.CountBucket;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.CountSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.EnchantSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.LootSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.SpeciesSpec;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.StatsSpec;

/**
 * Default contents for siege and wilderness json: 4 stages + 亡灵 5 档 + 掠夺者 4 档 + 烬军 5 档.
 * Siege numbers match {@code docs/siege-cluster-templates.md}.
 * Wilderness uses the same templates with smaller counts and a flat 40% attempt chance.
 */
public final class SiegeClusterDefaults {
	private SiegeClusterDefaults() {
	}

	public static SiegeProgression progression() {
		List<SiegeProgression.Stage> stages = new ArrayList<>();
		stages.add(new SiegeProgression.Stage(
				"journey", "旅途", "always", false,
				weights("roadkill", 0.7, "small", 0.3),
				new SiegeProgression.SpawnWindow(7000, 18000, 0.10f)));
		stages.add(new SiegeProgression.Stage(
				"homeland", "故土", "host", false,
				weights("small", 0.5, "roadkill", 0.2, "host", 0.3),
				new SiegeProgression.SpawnWindow(10000, 22000, 0.12f)));
		stages.add(new SiegeProgression.Stage(
				"hard", "困难", "legion", false,
				weights("legion", 0.5, "host", 0.3, "small", 0.15, "roadkill", 0.05),
				new SiegeProgression.SpawnWindow(11000, 19000, 0.15f)));
		stages.add(new SiegeProgression.Stage(
				"end", "终末", "legion", true,
				weights("legion", 0.708, "host", 0.2, "small", 0.022, "roadkill", 0.07),
				new SiegeProgression.SpawnWindow(16000, 23000, 0.20f)));
		return new SiegeProgression(stages);
	}

	/**
	 * Same stage weights as siege; attempt chance is {@link WildernessSpawnConfig#ATTEMPT_CHANCE}
	 * all day (wilderness scheduler does not use time windows).
	 */
	public static SiegeProgression wildernessProgression() {
		float chance = WildernessSpawnConfig.ATTEMPT_CHANCE;
		SiegeProgression.SpawnWindow allDay = new SiegeProgression.SpawnWindow(0, 24000, chance);
		List<SiegeProgression.Stage> stages = new ArrayList<>();
		for (SiegeProgression.Stage stage : progression().stages()) {
			stages.add(new SiegeProgression.Stage(
					stage.id(),
					stage.name(),
					stage.unlock(),
					stage.requireDemonChunk(),
					stage.weights(),
					allDay,
					allDay));
		}
		return new SiegeProgression(stages);
	}

	public static List<SiegeClusterTemplate> templates() {
		return List.of(
				undeadRoadkill(),
				undeadWarbandSmall(),
				undeadHost(),
				undeadLegion(),
				netherWraith(),
				illagerBandSmall(),
				illagerHost(),
				illagerLegion(),
				illagerWraith(),
				netherRoadkill(),
				netherWarbandSmall(),
				netherHost(),
				netherLegion(),
				netherAshWraith());
	}

	public static List<SiegeClusterTemplate> wildernessTemplates() {
		List<SiegeClusterTemplate> list = new ArrayList<>();
		for (SiegeClusterTemplate template : templates()) {
			list.add(template.withCount(wildernessCount(template.tier())));
		}
		return list;
	}

	private static CountSpec wildernessCount(String tier) {
		return switch (tier == null ? "" : tier) {
			case "roadkill" -> new CountSpec(3, 5, List.of());
			case "small" -> new CountSpec(6, 8, List.of());
			case "host", "legion" -> new CountSpec(10, 15, List.of());
			default -> new CountSpec(10, 15, List.of());
		};
	}

	private static SiegeClusterTemplate undeadRoadkill() {
		return new SiegeClusterTemplate(
				"undead_roadkill", "路边野尸", "undead", "roadkill", "mixed",
				new CountSpec(5, 100, List.of(
						new CountBucket(5, 15, 40),
						new CountBucket(16, 40, 35),
						new CountBucket(41, 70, 18),
						new CountBucket(71, 100, 7))),
				List.of(
						species("minecraft:zombie", 70),
						species("minecraft:husk", 10),
						species("minecraft:skeleton", 18),
						new SpeciesSpec("minecraft:zombie", 2, true, StatsSpec.EMPTY)),
				stats(
						nums("6", 40, "8", 45, "10", 15),
						speedRoadkill(),
						nums("1.0", 70, "2.0", 30)),
				slots(
						slot("helmet", "empty", 50,
								"minecraft:leather_helmet", 38,
								"minecraft:golden_helmet", 8,
								"minecraft:chainmail_helmet", 4),
						slot("chestplate", "empty", 70,
								"minecraft:leather_chestplate", 22,
								"minecraft:golden_chestplate", 6,
								"minecraft:chainmail_chestplate", 2),
						slot("leggings", "empty", 76,
								"minecraft:leather_leggings", 18,
								"minecraft:golden_leggings", 5,
								"minecraft:chainmail_leggings", 1),
						slot("boots", "empty", 82,
								"minecraft:leather_boots", 14,
								"minecraft:golden_boots", 4),
						slot("mainHand", "empty", 55,
								"minecraft:wooden_shovel", 15,
								"minecraft:wooden_axe", 12,
								"minecraft:wooden_pickaxe", 12,
								"minecraft:iron_shovel", 4,
								"minecraft:iron_pickaxe", 2),
						slot("offHand", "empty", 100)),
				durability(50, 35, 12, 3),
				new EnchantSpec(0.0D, 0.0D, 0, 0),
				null, 0.0D, 0.0D, Map.of(), loot(0.1D, 1, 1));
	}

	private static SiegeClusterTemplate undeadWarbandSmall() {
		return new SiegeClusterTemplate(
				"undead_warband_small", "小股亡灵", "undead", "small", "medium",
				new CountSpec(15, 20, List.of()),
				List.of(
						species("minecraft:zombie", 62),
						species("minecraft:husk", 10),
						species("minecraft:skeleton", 18),
						species("minecraft:stray", 5),
						species("minecraft:zombie_villager", 5)),
				stats(
						nums("12", 35, "14", 40, "16", 25),
						speedSmall(),
						nums("2.0", 60, "3.0", 40)),
				slots(
						slot("helmet", "empty", 25,
								"minecraft:leather_helmet", 48,
								"minecraft:golden_helmet", 10,
								"minecraft:chainmail_helmet", 9,
								"minecraft:iron_helmet", 8),
						slot("chestplate", "empty", 35,
								"minecraft:leather_chestplate", 42,
								"minecraft:golden_chestplate", 8,
								"minecraft:chainmail_chestplate", 8,
								"minecraft:iron_chestplate", 7),
						slot("leggings", "empty", 42,
								"minecraft:leather_leggings", 38,
								"minecraft:golden_leggings", 7,
								"minecraft:chainmail_leggings", 7,
								"minecraft:iron_leggings", 6),
						slot("boots", "empty", 48,
								"minecraft:leather_boots", 34,
								"minecraft:golden_boots", 7,
								"minecraft:chainmail_boots", 6,
								"minecraft:iron_boots", 5),
						slot("mainHand", "empty", 15,
								"minecraft:wooden_sword", 40,
								"minecraft:stone_sword", 25,
								"minecraft:iron_sword", 18,
								"minecraft:golden_sword", 2),
						slot("mainHandRanged", "empty", 15, "minecraft:bow", 85),
						slot("offHand", "empty", 100)),
				durability(30, 40, 25, 5),
				new EnchantSpec(0.05D, 0.08D, 5, 12),
				null, 0.0D, 0.0D, Map.of(), loot(0.2D, 1, 2));
	}

	private static SiegeClusterTemplate undeadHost() {
		return new SiegeClusterTemplate(
				"undead_host", "一群亡灵", "undead", "host", "medium",
				new CountSpec(28, 32, List.of()),
				List.of(
						species("minecraft:zombie", 55),
						species("minecraft:husk", 8),
						species("minecraft:skeleton", 22),
						species("minecraft:stray", 8),
						species("minecraft:drowned", 5),
						species("minecraft:witch", 2)),
				stats(
						nums("20", 25, "24", 35, "28", 25, "32", 15),
						speedHost(),
						nums("3.0", 100)),
				slots(
						slot("helmet", "empty", 8,
								"minecraft:leather_helmet", 7,
								"minecraft:chainmail_helmet", 11,
								"minecraft:iron_helmet", 71,
								"minecraft:diamond_helmet", 3),
						slot("chestplate", "empty", 12,
								"minecraft:leather_chestplate", 7,
								"minecraft:chainmail_chestplate", 11,
								"minecraft:iron_chestplate", 68,
								"minecraft:diamond_chestplate", 2),
						slot("leggings", "empty", 16,
								"minecraft:leather_leggings", 7,
								"minecraft:chainmail_leggings", 10,
								"minecraft:iron_leggings", 65,
								"minecraft:diamond_leggings", 2),
						slot("boots", "empty", 20,
								"minecraft:leather_boots", 6,
								"minecraft:chainmail_boots", 10,
								"minecraft:iron_boots", 62,
								"minecraft:diamond_boots", 2),
						slot("mainHand", "empty", 5,
								"minecraft:wooden_sword", 8,
								"minecraft:stone_sword", 12,
								"minecraft:iron_sword", 72,
								"minecraft:diamond_sword", 3),
						slot("mainHandRanged", "empty", 5, "minecraft:bow", 95),
						slot("offHand", "empty", 92, "minecraft:shield", 8)),
				durability(10, 35, 40, 15),
				new EnchantSpec(0.12D, 0.18D, 8, 18),
				null, 0.0D, 0.02D,
				Map.of(
						"helmet", "minecraft:diamond_helmet",
						"chestplate", "minecraft:leather_chestplate",
						"leggings", "minecraft:iron_leggings",
						"boots", "minecraft:golden_boots",
						"mainHand", "minecraft:wooden_sword"),
				loot(0.5D, 1, 4));
	}

	private static SiegeClusterTemplate undeadLegion() {
		return new SiegeClusterTemplate(
				"undead_legion", "亡灵军团", "undead", "legion", "large",
				new CountSpec(50, 70, List.of()),
				List.of(
						species("minecraft:zombie", 55),
						species("minecraft:skeleton", 20),
						species("minecraft:stray", 8),
						species("minecraft:wither_skeleton", 6),
						species("minecraft:witch", 6),
						species("minecraft:husk", 5)),
				stats(
						nums("35", 20, "42", 35, "48", 30, "55", 15),
						speedLegion(),
						nums("3.5", 50, "4.5", 50)),
				slots(
						slot("helmet", "empty", 0,
								"minecraft:iron_helmet", 18,
								"minecraft:diamond_helmet", 80,
								"minecraft:netherite_helmet", 2),
						slot("chestplate", "empty", 2,
								"minecraft:iron_chestplate", 18,
								"minecraft:diamond_chestplate", 78,
								"minecraft:netherite_chestplate", 2),
						slot("leggings", "empty", 2,
								"minecraft:iron_leggings", 18,
								"minecraft:diamond_leggings", 78,
								"minecraft:netherite_leggings", 2),
						slot("boots", "empty", 2,
								"minecraft:iron_boots", 18,
								"minecraft:diamond_boots", 78,
								"minecraft:netherite_boots", 2),
						slot("mainHand", "empty", 5,
								"minecraft:iron_sword", 22,
								"minecraft:diamond_sword", 70,
								"minecraft:netherite_sword", 3),
						slot("mainHandRanged", "minecraft:bow", 100),
						slot("offHand", "empty", 80, "minecraft:shield", 20)),
				durability(5, 20, 45, 30),
				new EnchantSpec(0.28D, 0.35D, 15, 25),
				"minecraft:iron_sword", 0.0D, 0.0D, Map.of(), loot(0.7D, 2, 8));
	}

	private static SiegeClusterTemplate netherWraith() {
		return new SiegeClusterTemplate(
				"nether_wraith", "地狱亡魂", "undead", "hell", "small",
				new CountSpec(10, 20, List.of()),
				List.of(
						species("minecraft:zombie", 40),
						species("minecraft:wither_skeleton", 25),
						species("minecraft:zombified_piglin", 15),
						species("minecraft:skeleton", 10),
						species("minecraft:witch", 10)),
				stats(
						nums("60", 25, "72", 35, "84", 25, "90", 15),
						speedHell(),
						nums("4.0", 50, "6.0", 50)),
				slots(
						slot("helmet", "empty", 0, "minecraft:netherite_helmet", 88, "minecraft:diamond_helmet", 12),
						slot("chestplate", "empty", 0, "minecraft:netherite_chestplate", 88, "minecraft:diamond_chestplate", 12),
						slot("leggings", "empty", 0, "minecraft:netherite_leggings", 88, "minecraft:diamond_leggings", 12),
						slot("boots", "empty", 0, "minecraft:netherite_boots", 88, "minecraft:diamond_boots", 12),
						slot("mainHand", "empty", 4,
								"minecraft:netherite_sword", 70,
								"minecraft:diamond_sword", 18,
								"minecraft:netherite_axe", 8),
						slot("mainHandRanged", "minecraft:bow", 100),
						slot("offHand", "empty", 60, "minecraft:shield", 40)),
				durability(2, 10, 38, 50),
				new EnchantSpec(0.75D, 0.85D, 20, 30),
				"minecraft:netherite_sword", 0.0D, 0.0D, Map.of(), loot(0.3D, 10, 30));
	}

	private static SiegeClusterTemplate illagerBandSmall() {
		return new SiegeClusterTemplate(
				"illager_band_small", "小股掠夺", "illager", "small", "medium",
				new CountSpec(15, 20, List.of()),
				List.of(
						new SpeciesSpec("minecraft:pillager", 55, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100))),
						new SpeciesSpec("minecraft:vindicator", 35, false,
								new StatsSpec(Map.of(), Map.of(), nums("5.0", 100))),
						new SpeciesSpec("minecraft:witch", 10, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100)))),
				stats(nums("16", 35, "20", 40, "22", 25), speedSmall(), Map.of()),
				slots(
						slot("helmet", "empty", 30,
								"minecraft:leather_helmet", 40,
								"minecraft:chainmail_helmet", 20,
								"minecraft:iron_helmet", 10),
						slot("chestplate", "empty", 42,
								"minecraft:leather_chestplate", 32,
								"minecraft:chainmail_chestplate", 18,
								"minecraft:iron_chestplate", 8),
						slot("leggings", "empty", 50,
								"minecraft:leather_leggings", 28,
								"minecraft:chainmail_leggings", 15,
								"minecraft:iron_leggings", 7),
						slot("boots", "empty", 55,
								"minecraft:leather_boots", 25,
								"minecraft:chainmail_boots", 13,
								"minecraft:iron_boots", 7),
						slot("mainHand", "minecraft:iron_axe", 100),
						slot("mainHandRanged", "empty", 10, "minecraft:crossbow", 90),
						slot("offHand", "empty", 100)),
				durability(25, 40, 28, 7),
				new EnchantSpec(0.06D, 0.10D, 5, 12),
				null, 0.0D, 0.0D, Map.of(), loot(0.2D, 1, 2));
	}

	private static SiegeClusterTemplate illagerHost() {
		return new SiegeClusterTemplate(
				"illager_host", "掠夺大队", "illager", "host", "medium",
				new CountSpec(28, 32, List.of()),
				List.of(
						new SpeciesSpec("minecraft:pillager", 40, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100))),
						new SpeciesSpec("minecraft:vindicator", 40, false,
								new StatsSpec(Map.of(), Map.of(), nums("5.0", 100))),
						species("minecraft:evoker", 8),
						new SpeciesSpec("minecraft:witch", 12, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100)))),
				stats(nums("24", 30, "28", 45, "32", 25), speedHost(), Map.of()),
				slots(
						slot("helmet", "empty", 10,
								"minecraft:chainmail_helmet", 14,
								"minecraft:iron_helmet", 72,
								"minecraft:diamond_helmet", 4),
						slot("chestplate", "empty", 16,
								"minecraft:chainmail_chestplate", 13,
								"minecraft:iron_chestplate", 67,
								"minecraft:diamond_chestplate", 4),
						slot("leggings", "empty", 20,
								"minecraft:chainmail_leggings", 12,
								"minecraft:iron_leggings", 64,
								"minecraft:diamond_leggings", 4),
						slot("boots", "empty", 24,
								"minecraft:chainmail_boots", 11,
								"minecraft:iron_boots", 61,
								"minecraft:diamond_boots", 4),
						slot("mainHand", "minecraft:iron_axe", 92, "minecraft:diamond_axe", 8),
						slot("mainHandRanged", "minecraft:crossbow", 100),
						slot("offHand", "empty", 100)),
				durability(10, 32, 42, 16),
				new EnchantSpec(0.15D, 0.18D, 8, 18),
				null, 0.0D, 0.0D, Map.of(), loot(0.5D, 1, 4));
	}

	private static SiegeClusterTemplate illagerLegion() {
		StatsSpec ravagerHealth = new StatsSpec(nums("100", 40, "120", 40, "140", 20), Map.of(), Map.of());
		return new SiegeClusterTemplate(
				"illager_legion", "灾厄军团", "illager", "legion", "large",
				new CountSpec(50, 70, List.of()),
				List.of(
						new SpeciesSpec("minecraft:vindicator", 45, false,
								new StatsSpec(Map.of(), Map.of(), nums("5.0", 100))),
						new SpeciesSpec("minecraft:pillager", 25, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100))),
						species("minecraft:evoker", 15),
						new SpeciesSpec("minecraft:ravager", 5, false, ravagerHealth),
						new SpeciesSpec("minecraft:witch", 10, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100)))),
				stats(nums("28", 20, "36", 40, "42", 25, "48", 15), speedLegion(), Map.of()),
				slots(
						slot("helmet", "empty", 0,
								"minecraft:iron_helmet", 25,
								"minecraft:diamond_helmet", 72,
								"minecraft:netherite_helmet", 3),
						slot("chestplate", "empty", 4,
								"minecraft:iron_chestplate", 24,
								"minecraft:diamond_chestplate", 69,
								"minecraft:netherite_chestplate", 3),
						slot("leggings", "empty", 4,
								"minecraft:iron_leggings", 24,
								"minecraft:diamond_leggings", 69,
								"minecraft:netherite_leggings", 3),
						slot("boots", "empty", 6,
								"minecraft:iron_boots", 24,
								"minecraft:diamond_boots", 67,
								"minecraft:netherite_boots", 3),
						slot("mainHand",
								"minecraft:iron_axe", 40,
								"minecraft:diamond_axe", 55,
								"minecraft:netherite_axe", 5),
						slot("mainHandRanged", "minecraft:crossbow", 100),
						slot("offHand", "empty", 100)),
				durability(5, 20, 45, 30),
				new EnchantSpec(0.28D, 0.35D, 15, 25),
				null, 0.08D, 0.0D, Map.of(), loot(0.7D, 2, 8));
	}

	private static SiegeClusterTemplate illagerWraith() {
		StatsSpec ravagerHealth = new StatsSpec(nums("120", 50, "160", 50), Map.of(), Map.of());
		return new SiegeClusterTemplate(
				"illager_wraith", "暗影会众", "illager", "hell", "small",
				new CountSpec(10, 20, List.of()),
				List.of(
						species("minecraft:evoker", 30),
						new SpeciesSpec("minecraft:vindicator", 40, false,
								new StatsSpec(Map.of(), Map.of(), nums("5.0", 100))),
						new SpeciesSpec("minecraft:ravager", 15, false, ravagerHealth),
						new SpeciesSpec("minecraft:witch", 15, false,
								new StatsSpec(Map.of(), Map.of(), nums("2.0", 100)))),
				stats(nums("40", 25, "55", 40, "70", 35), speedHell(), Map.of()),
				slots(
						slot("helmet", "empty", 0, "minecraft:netherite_helmet", 80, "minecraft:diamond_helmet", 20),
						slot("chestplate", "empty", 0, "minecraft:netherite_chestplate", 80, "minecraft:diamond_chestplate", 20),
						slot("leggings", "empty", 0, "minecraft:netherite_leggings", 80, "minecraft:diamond_leggings", 20),
						slot("boots", "empty", 0, "minecraft:netherite_boots", 80, "minecraft:diamond_boots", 20),
						slot("mainHand", "minecraft:netherite_axe", 70, "minecraft:diamond_axe", 30),
						slot("offHand", "empty", 80, "minecraft:totem_of_undying", 20)),
				durability(2, 10, 38, 50),
				new EnchantSpec(0.70D, 0.85D, 20, 30),
				null, 0.0D, 0.0D, Map.of(), loot(0.3D, 10, 30));
	}

	private static SiegeClusterTemplate netherRoadkill() {
		return new SiegeClusterTemplate(
				"nether_roadkill", "路边烬尸", "nether", "roadkill", "mixed", "nether",
				new CountSpec(5, 100, List.of(
						new CountBucket(5, 15, 40),
						new CountBucket(16, 40, 35),
						new CountBucket(41, 70, 18),
						new CountBucket(71, 100, 7))),
				List.of(
						species("minecraft:zombified_piglin", 80),
						species("minecraft:skeleton", 18),
						new SpeciesSpec("minecraft:zombified_piglin", 2, true, StatsSpec.EMPTY)),
				stats(
						nums("6", 40, "8", 45, "10", 15),
						speedRoadkill(),
						nums("1.0", 70, "2.0", 30)),
				slots(
						slot("helmet", "empty", 50,
								"minecraft:golden_helmet", 35,
								"minecraft:leather_helmet", 11,
								"minecraft:chainmail_helmet", 4),
						slot("chestplate", "empty", 70,
								"minecraft:golden_chestplate", 21,
								"minecraft:leather_chestplate", 7,
								"minecraft:chainmail_chestplate", 2),
						slot("leggings", "empty", 76,
								"minecraft:golden_leggings", 17,
								"minecraft:leather_leggings", 5,
								"minecraft:chainmail_leggings", 2),
						slot("boots", "empty", 82,
								"minecraft:golden_boots", 13,
								"minecraft:leather_boots", 4,
								"minecraft:chainmail_boots", 1),
						slot("mainHand", "empty", 55,
								"minecraft:golden_shovel", 15,
								"minecraft:golden_axe", 12,
								"minecraft:golden_pickaxe", 12,
								"minecraft:stone_shovel", 4,
								"minecraft:stone_pickaxe", 2),
						slot("offHand", "empty", 100)),
				durability(50, 35, 12, 3),
				new EnchantSpec(0.0D, 0.0D, 0, 0),
				null, 0.0D, 0.0D, Map.of(), loot(0.1D, 1, 1));
	}

	private static SiegeClusterTemplate netherWarbandSmall() {
		return new SiegeClusterTemplate(
				"nether_warband_small", "小股烬军", "nether", "small", "medium", "nether",
				new CountSpec(15, 20, List.of()),
				List.of(
						species("minecraft:zombified_piglin", 72),
						species("minecraft:skeleton", 18),
						species("minecraft:wither_skeleton", 10)),
				stats(
						nums("12", 35, "14", 40, "16", 25),
						speedSmall(),
						nums("2.0", 60, "3.0", 40)),
				slots(
						slot("helmet", "empty", 25,
								"minecraft:golden_helmet", 53,
								"minecraft:chainmail_helmet", 9,
								"minecraft:leather_helmet", 7,
								"minecraft:iron_helmet", 6),
						slot("chestplate", "empty", 35,
								"minecraft:golden_chestplate", 46,
								"minecraft:chainmail_chestplate", 8,
								"minecraft:leather_chestplate", 6,
								"minecraft:iron_chestplate", 5),
						slot("leggings", "empty", 42,
								"minecraft:golden_leggings", 41,
								"minecraft:chainmail_leggings", 7,
								"minecraft:leather_leggings", 6,
								"minecraft:iron_leggings", 4),
						slot("boots", "empty", 48,
								"minecraft:golden_boots", 37,
								"minecraft:chainmail_boots", 6,
								"minecraft:leather_boots", 5,
								"minecraft:iron_boots", 4),
						slot("mainHand", "empty", 15,
								"minecraft:golden_sword", 40,
								"minecraft:stone_sword", 25,
								"minecraft:iron_sword", 18,
								"minecraft:wooden_sword", 2),
						slot("mainHandRanged", "empty", 15, "minecraft:bow", 85),
						slot("offHand", "empty", 100)),
				durability(30, 40, 25, 5),
				new EnchantSpec(0.05D, 0.08D, 5, 12),
				null, 0.0D, 0.0D, Map.of(), loot(0.2D, 1, 2));
	}

	private static SiegeClusterTemplate netherHost() {
		StatsSpec ghastHealth = new StatsSpec(nums("10", 40, "14", 45, "16", 15), Map.of(), Map.of());
		return new SiegeClusterTemplate(
				"nether_host", "一群烬军", "nether", "host", "medium", "nether",
				new CountSpec(28, 32, List.of()),
				List.of(
						species("minecraft:zombified_piglin", 55),
						species("minecraft:skeleton", 22),
						species("minecraft:wither_skeleton", 21),
						new SpeciesSpec("minecraft:ghast", 2, false, ghastHealth)),
				stats(
						nums("20", 25, "24", 35, "28", 25, "32", 15),
						speedHost(),
						nums("3.0", 100)),
				slots(
						slot("helmet", "empty", 8,
								"minecraft:iron_helmet", 71,
								"minecraft:golden_helmet", 11,
								"minecraft:chainmail_helmet", 7,
								"minecraft:diamond_helmet", 3),
						slot("chestplate", "empty", 12,
								"minecraft:iron_chestplate", 68,
								"minecraft:golden_chestplate", 11,
								"minecraft:chainmail_chestplate", 7,
								"minecraft:diamond_chestplate", 2),
						slot("leggings", "empty", 16,
								"minecraft:iron_leggings", 65,
								"minecraft:golden_leggings", 10,
								"minecraft:chainmail_leggings", 7,
								"minecraft:diamond_leggings", 2),
						slot("boots", "empty", 20,
								"minecraft:iron_boots", 62,
								"minecraft:golden_boots", 10,
								"minecraft:chainmail_boots", 6,
								"minecraft:diamond_boots", 2),
						slot("mainHand", "empty", 5,
								"minecraft:golden_sword", 8,
								"minecraft:stone_sword", 12,
								"minecraft:iron_sword", 72,
								"minecraft:diamond_sword", 3),
						slot("mainHandRanged", "empty", 5, "minecraft:bow", 95),
						slot("offHand", "empty", 92, "minecraft:shield", 8)),
				durability(10, 35, 40, 15),
				new EnchantSpec(0.12D, 0.18D, 8, 18),
				null, 0.0D, 0.0D, Map.of(), loot(0.5D, 1, 4));
	}

	private static SiegeClusterTemplate netherLegion() {
		StatsSpec ghastHealth = new StatsSpec(nums("14", 40, "16", 45, "20", 15), Map.of(), Map.of());
		return new SiegeClusterTemplate(
				"nether_legion", "烬炎军团", "nether", "legion", "large", "nether",
				new CountSpec(50, 70, List.of()),
				List.of(
						species("minecraft:zombified_piglin", 50),
						species("minecraft:wither_skeleton", 24),
						species("minecraft:skeleton", 20),
						new SpeciesSpec("minecraft:ghast", 6, false, ghastHealth)),
				stats(
						nums("35", 20, "42", 35, "48", 30, "55", 15),
						speedLegion(),
						nums("3.5", 50, "4.5", 50)),
				slots(
						slot("helmet", "empty", 0,
								"minecraft:iron_helmet", 18,
								"minecraft:diamond_helmet", 80,
								"minecraft:netherite_helmet", 2),
						slot("chestplate", "empty", 2,
								"minecraft:iron_chestplate", 18,
								"minecraft:diamond_chestplate", 78,
								"minecraft:netherite_chestplate", 2),
						slot("leggings", "empty", 2,
								"minecraft:iron_leggings", 18,
								"minecraft:diamond_leggings", 78,
								"minecraft:netherite_leggings", 2),
						slot("boots", "empty", 2,
								"minecraft:iron_boots", 18,
								"minecraft:diamond_boots", 78,
								"minecraft:netherite_boots", 2),
						slot("mainHand", "empty", 5,
								"minecraft:iron_sword", 22,
								"minecraft:diamond_sword", 70,
								"minecraft:netherite_sword", 3),
						slot("mainHandRanged", "minecraft:bow", 100),
						slot("offHand", "empty", 80, "minecraft:shield", 20)),
				durability(5, 20, 45, 30),
				new EnchantSpec(0.28D, 0.35D, 15, 25),
				"minecraft:iron_sword", 0.0D, 0.0D, Map.of(), loot(0.7D, 2, 8));
	}

	private static SiegeClusterTemplate netherAshWraith() {
		StatsSpec ghastHealth = new StatsSpec(nums("16", 35, "20", 40, "24", 25), Map.of(), Map.of());
		return new SiegeClusterTemplate(
				"nether_ash_wraith", "熔核亡魂", "nether", "hell", "small", "nether",
				new CountSpec(10, 20, List.of()),
				List.of(
						species("minecraft:wither_skeleton", 35),
						species("minecraft:zombified_piglin", 25),
						new SpeciesSpec("minecraft:ghast", 25, false, ghastHealth),
						species("minecraft:skeleton", 15)),
				stats(
						nums("60", 25, "72", 35, "84", 25, "90", 15),
						speedHell(),
						nums("4.0", 50, "6.0", 50)),
				slots(
						slot("helmet", "empty", 0, "minecraft:netherite_helmet", 88, "minecraft:diamond_helmet", 12),
						slot("chestplate", "empty", 0, "minecraft:netherite_chestplate", 88, "minecraft:diamond_chestplate", 12),
						slot("leggings", "empty", 0, "minecraft:netherite_leggings", 88, "minecraft:diamond_leggings", 12),
						slot("boots", "empty", 0, "minecraft:netherite_boots", 88, "minecraft:diamond_boots", 12),
						slot("mainHand", "empty", 4,
								"minecraft:netherite_sword", 70,
								"minecraft:diamond_sword", 18,
								"minecraft:netherite_axe", 8),
						slot("mainHandRanged", "minecraft:bow", 100),
						slot("offHand", "empty", 60, "minecraft:shield", 40)),
				durability(2, 10, 38, 50),
				new EnchantSpec(0.75D, 0.85D, 20, 30),
				"minecraft:netherite_sword", 0.0D, 0.0D, Map.of(), loot(0.3D, 10, 30));
	}

	private static LootSpec loot(double chance, int min, int max) {
		return new LootSpec("minecraft:emerald", chance, min, max);
	}

	private static SpeciesSpec species(String entity, int weight) {
		return new SpeciesSpec(entity, weight, false, StatsSpec.EMPTY);
	}

	private static StatsSpec stats(Map<String, Integer> health, Map<String, Integer> speed, Map<String, Integer> attack) {
		return new StatsSpec(health, speed, attack);
	}

	private static Map<String, Integer> speedRoadkill() {
		return nums("0.18", 20, "0.20", 25, "0.23", 28, "0.26", 17, "0.30", 10);
	}

	private static Map<String, Integer> speedSmall() {
		return nums("0.20", 12, "0.23", 25, "0.26", 28, "0.30", 22, "0.33", 13);
	}

	private static Map<String, Integer> speedHost() {
		return nums("0.20", 8, "0.23", 18, "0.26", 28, "0.30", 28, "0.34", 18);
	}

	private static Map<String, Integer> speedLegion() {
		return nums("0.22", 8, "0.25", 18, "0.28", 27, "0.32", 27, "0.36", 20);
	}

	private static Map<String, Integer> speedHell() {
		return nums("0.23", 6, "0.26", 14, "0.30", 28, "0.34", 28, "0.38", 24);
	}

	private static Map<String, Integer> durability(int ruined, int worn, int used, int fresh) {
		return nums("ruined", ruined, "worn", worn, "used", used, "fresh", fresh);
	}

	@SafeVarargs
	private static Map<String, Map<String, Integer>> slots(SlotPair... pairs) {
		Map<String, Map<String, Integer>> map = new LinkedHashMap<>();
		for (SlotPair pair : pairs) {
			map.put(pair.name, pair.weights);
		}
		return map;
	}

	private static SlotPair slot(String name, Object... kv) {
		return new SlotPair(name, nums(kv));
	}

	private record SlotPair(String name, Map<String, Integer> weights) {
	}

	private static Map<String, Double> weights(Object... kv) {
		Map<String, Double> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			map.put(String.valueOf(kv[i]), ((Number) kv[i + 1]).doubleValue());
		}
		return map;
	}

	private static Map<String, Integer> nums(Object... kv) {
		Map<String, Integer> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			map.put(String.valueOf(kv[i]), ((Number) kv[i + 1]).intValue());
		}
		return map;
	}
}
