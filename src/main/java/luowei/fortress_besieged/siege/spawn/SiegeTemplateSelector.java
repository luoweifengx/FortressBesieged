package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;

/**
 * Resolve world stage → tier → race → concrete cluster template.
 */
public final class SiegeTemplateSelector {
	public static final String LEGACY_DEFAULT = "default";
	public static final String LEGACY_LIGHT = "light";
	public static final String LEGACY_HEAVY = "heavy";

	private SiegeTemplateSelector() {
	}

	public record Selection(
			SiegeProgression.Stage stage,
			SiegeProgression.WorldSnapshot world,
			String tier,
			SiegeClusterTemplate template
	) {
	}

	/** {@code /siege spawn} with no id / {@code default}: unlocked tiers, no duty window. */
	public static Selection roll(ServerLevel level, UUID targetId, Random random) {
		return roll(level, targetId, random, false);
	}

	public static Selection roll(ServerLevel level, UUID targetId, Random random, boolean naturalDutyWindows) {
		return roll(level, targetId, random, SiegeClusterConfig.siege(), true, naturalDutyWindows);
	}

	/** Hell-tier only: dimension-filtered hell templates. */
	public static Selection rollHell(ServerLevel level, UUID targetId, Random random) {
		SiegeClusterConfig config = SiegeClusterConfig.siege();
		SiegeProgression progression = config.progression();
		SiegeProgression.WorldSnapshot world = progression.snapshot(level, targetId);
		SiegeProgression.Stage stage = progression.resolveStage(world);
		List<SiegeClusterTemplate> matches = new ArrayList<>();
		for (SiegeClusterTemplate template : config.templatesFor(null, SiegeProgression.TIER_HELL)) {
			if (allowedIn(template, level)) {
				matches.add(template);
			}
		}
		SiegeClusterTemplate template = matches.isEmpty()
				? null
				: matches.get(random.nextInt(matches.size()));
		return new Selection(stage, world, SiegeProgression.TIER_HELL, template);
	}

	/**
	 * Wilderness packs: own stages + templates. Hell weight is still 0
	 * (spawn chunk is {@code NATURAL}, never {@code DEMON}).
	 */
	public static Selection rollWilderness(ServerLevel level, UUID targetId, Random random) {
		return roll(level, targetId, random, SiegeClusterConfig.wilderness(), true, false);
	}

	private static Selection roll(
			ServerLevel level,
			UUID targetId,
			Random random,
			SiegeClusterConfig config,
			boolean excludeHell,
			boolean naturalDutyWindows
	) {
		SiegeProgression progression = config.progression();
		SiegeProgression.WorldSnapshot world = progression.snapshot(level, targetId);
		SiegeProgression.Stage stage = progression.resolveStage(world);
		SiegeProgression.TierRoll tierRoll = SiegeProgression.TierRoll.of(level, stage, excludeHell, naturalDutyWindows);
		long timeOfDay = SiegeDayClock.timeOfDay(level);
		String tier = progression.rollTier(stage, world, random, false, tierRoll, timeOfDay);
		SiegeClusterTemplate template = pickTemplate(config, level, tier, random, excludeHell, tierRoll.excludeRoadkill());
		if (template == null) {
			template = config.templates().stream()
					.filter(t -> allowedIn(t, level))
					.filter(t -> !excludeHell || !t.isHell())
					.findFirst()
					.orElse(null);
		}
		if (excludeHell && template != null && template.isHell()) {
			template = null;
		}
		String actualTier = template == null ? tier : template.tier();
		Selection selection = new Selection(stage, world, actualTier, template);
		if (SiegeSpawnMonitor.isDebug()) {
			SiegeSpawnMonitor.debug(
					"template player={} stage={} worldOwned={} iron={} ironArmor={} diamond={} demon={} tier={} id={} race={} hell={} wilderness={} netherMerge={} duty={}",
					SiegeSpawnMonitor.target(level, targetId),
					stage == null ? "?" : stage.id(),
					world.worldOwned(),
					world.hasIron(),
					world.hasIronArmor(),
					world.hasDiamond(),
					world.hasDemonChunk(),
					actualTier,
					template == null ? "null" : template.id(),
					template == null ? "?" : template.race(),
					template != null && template.isHell(),
					excludeHell,
					tierRoll.mergeUnlocked(),
					tierRoll.applyDutyWindows());
		}
		return selection;
	}

	/**
	 * Command / legacy ids: {@code default} rolls by stage; {@code light}/{@code heavy}
	 * map to templates; anything else is a template id.
	 */
	public static Selection resolve(
			ServerLevel level,
			UUID targetId,
			String idOrLegacy,
			Random random
	) {
		if (idOrLegacy == null || idOrLegacy.isBlank() || LEGACY_DEFAULT.equals(idOrLegacy)) {
			return roll(level, targetId, random);
		}
		String mapped = switch (idOrLegacy) {
			case LEGACY_LIGHT -> "undead_roadkill";
			case LEGACY_HEAVY -> "undead_host";
			default -> idOrLegacy;
		};
		SiegeClusterTemplate template = SiegeClusterConfig.siege().getTemplate(mapped).orElse(null);
		if (template == null) {
			throw new IllegalArgumentException("Unknown cluster template or recipe: " + idOrLegacy);
		}
		SiegeProgression progression = SiegeClusterConfig.siege().progression();
		SiegeProgression.WorldSnapshot world = progression.snapshot(level, targetId);
		Selection selection = new Selection(progression.resolveStage(world), world, template.tier(), template);
		if (SiegeSpawnMonitor.isDebug()) {
			SiegeSpawnMonitor.debug(
					"template player={} stage={} worldOwned={} tier={} id={} race={} resolve={}",
					SiegeSpawnMonitor.target(level, targetId),
					selection.stage() == null ? "?" : selection.stage().id(),
					world.worldOwned(),
					template.tier(),
					template.id(),
					template.race(),
					idOrLegacy);
		}
		return selection;
	}

	public static boolean isKnownId(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}
		if (LEGACY_DEFAULT.equals(id) || LEGACY_LIGHT.equals(id) || LEGACY_HEAVY.equals(id)) {
			return true;
		}
		return SiegeClusterConfig.siege().getTemplate(id).isPresent();
	}

	public static List<String> suggestionIds() {
		List<String> ids = new ArrayList<>();
		ids.add(LEGACY_DEFAULT);
		ids.add(LEGACY_LIGHT);
		ids.add(LEGACY_HEAVY);
		for (SiegeClusterTemplate template : SiegeClusterConfig.siege().templates()) {
			ids.add(template.id());
		}
		return ids;
	}

	private static SiegeClusterTemplate pickTemplate(
			SiegeClusterConfig config,
			ServerLevel level,
			String tier,
			Random random,
			boolean excludeHell,
			boolean excludeRoadkill
	) {
		if (excludeHell && SiegeProgression.TIER_HELL.equals(tier)) {
			return null;
		}
		String race = pickRace(config, level, random, excludeHell);
		String actualTier = tier;
		if (excludeRoadkill && SiegeProgression.TIER_ROADKILL.equals(actualTier)) {
			actualTier = SiegeProgression.TIER_SMALL;
		}
		if ("illager".equals(race) && SiegeProgression.TIER_ROADKILL.equals(actualTier)) {
			actualTier = SiegeProgression.TIER_SMALL;
		}
		List<SiegeClusterTemplate> matches = filterAllowed(
				config.templatesFor(race, actualTier), level, excludeHell, excludeRoadkill);
		if (matches.isEmpty()) {
			matches = filterAllowed(config.templatesFor(null, actualTier), level, excludeHell, excludeRoadkill);
		}
		if (matches.isEmpty() && SiegeProgression.TIER_ROADKILL.equals(actualTier)) {
			matches = filterAllowed(
					config.templatesFor(race, SiegeProgression.TIER_SMALL), level, excludeHell, excludeRoadkill);
		}
		if (matches.isEmpty()) {
			return null;
		}
		return matches.get(random.nextInt(matches.size()));
	}

	private static List<SiegeClusterTemplate> filterAllowed(
			List<SiegeClusterTemplate> matches,
			ServerLevel level,
			boolean excludeHell,
			boolean excludeRoadkill
	) {
		List<SiegeClusterTemplate> filtered = new ArrayList<>();
		for (SiegeClusterTemplate template : matches) {
			if (!allowedIn(template, level)) {
				continue;
			}
			if (excludeHell && template.isHell()) {
				continue;
			}
			if (excludeRoadkill && SiegeProgression.TIER_ROADKILL.equals(template.tier())) {
				continue;
			}
			filtered.add(template);
		}
		return filtered;
	}

	static boolean allowedIn(SiegeClusterTemplate template, ServerLevel level) {
		if (template == null) {
			return false;
		}
		boolean netherWorld = SiegeDayClock.isNether(level);
		return netherWorld == template.isNetherRoster();
	}

	private static String pickRace(
			SiegeClusterConfig config,
			ServerLevel level,
			Random random,
			boolean excludeHell
	) {
		List<String> races = new ArrayList<>();
		for (SiegeClusterTemplate template : config.templates()) {
			if (!allowedIn(template, level)) {
				continue;
			}
			if (excludeHell && template.isHell()) {
				continue;
			}
			if (!races.contains(template.race())) {
				races.add(template.race());
			}
		}
		if (races.isEmpty()) {
			return SiegeDayClock.isNether(level) ? "nether" : "undead";
		}
		return races.get(random.nextInt(races.size()));
	}
}
