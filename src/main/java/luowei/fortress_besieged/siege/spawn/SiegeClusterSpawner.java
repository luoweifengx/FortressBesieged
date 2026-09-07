package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.SiegeSounds;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.SpeciesSpec;

/**
 * Spawns mobs from a cluster template (or legacy recipe) on chosen sites and creates clusters
 * with an initial assault target (nearest OCCUPIED/BORDER to the anchor).
 */
public final class SiegeClusterSpawner {
	private SiegeClusterSpawner() {
	}

	public record SpawnResult(
			SiegeSpawnSiteSelector.SiteSelection sites,
			List<SiegeCluster> clusters,
			String message,
			SiegeTemplateSelector.Selection selection
	) {
		public SpawnResult(
				SiegeSpawnSiteSelector.SiteSelection sites,
				List<SiegeCluster> clusters,
				String message
		) {
			this(sites, clusters, message, null);
		}
	}

	/** Natural wave / {@code /siege spawn}: roll stage → tier → template. */
	public static SpawnResult spawnRolled(ServerLevel level, UUID targetId, Random random) {
		long t0 = SiegeSpawnMonitor.nanos();
		long tTpl = SiegeSpawnMonitor.nanos();
		SiegeTemplateSelector.Selection selection = SiegeTemplateSelector.roll(level, targetId, random);
		logTemplateTiming(tTpl, selection);
		return spawnSelection(level, targetId, selection, random, t0);
	}

	public static SpawnResult spawnById(ServerLevel level, UUID targetId, String idOrLegacy, Random random) {
		long t0 = SiegeSpawnMonitor.nanos();
		long tTpl = SiegeSpawnMonitor.nanos();
		SiegeTemplateSelector.Selection selection =
				SiegeTemplateSelector.resolve(level, targetId, idOrLegacy, random);
		logTemplateTiming(tTpl, selection);
		return spawnSelection(level, targetId, selection, random, t0);
	}

	public static SpawnResult spawnSelection(
			ServerLevel level,
			UUID targetId,
			SiegeTemplateSelector.Selection selection,
			Random random
	) {
		return spawnSelection(level, targetId, selection, random, SiegeSpawnMonitor.nanos());
	}

	private static SpawnResult spawnSelection(
			ServerLevel level,
			UUID targetId,
			SiegeTemplateSelector.Selection selection,
			Random random,
			long cycleStart
	) {
		String player = SiegeSpawnMonitor.target(level, targetId);
		if (selection == null || selection.template() == null) {
			SpawnResult result = new SpawnResult(
					new SiegeSpawnSiteSelector.SiteSelection(targetId, null, List.of(), List.of()),
					List.of(),
					"No cluster template available",
					selection);
			logSpawnFail(level, player, result.message(), cycleStart);
			return result;
		}
		SiegeClusterTemplate template = selection.template();
		boolean hell = template.isHell();
		if (hell && !PbsTerritoryAdapter.hasDemonChunks(level)) {
			SpawnResult result = new SpawnResult(
					new SiegeSpawnSiteSelector.SiteSelection(targetId, null, List.of(), List.of()),
					List.of(),
					"Hell template '" + template.id() + "' needs demon chunks in this dimension",
					selection);
			logSpawnFail(level, player, result.message(), cycleStart);
			return result;
		}

		List<ChunkPos> owned = PbsTerritoryAdapter.getOwnedChunks(level, targetId);
		if (owned.isEmpty()) {
			SpawnResult result = new SpawnResult(
					new SiegeSpawnSiteSelector.SiteSelection(targetId, null, List.of(), List.of()),
					List.of(),
					"Target has no OCCUPIED/BORDER territory",
					selection);
			logSpawnFail(level, player, result.message(), cycleStart);
			return result;
		}

		long tSite = SiegeSpawnMonitor.nanos();
		SiegeSpawnSiteSelector.SiteSelection sites = hell
				? SiegeSpawnSiteSelector.selectDemon(level, targetId, random)
				: SiegeSpawnSiteSelector.select(level, targetId, random);
		SiegeSpawnMonitor.timing(
				"site",
				tSite,
				"player={} hell={} center={} candidates={} chosen={}",
				player,
				hell,
				SiegeSpawnMonitor.chunk(sites.territoryCenter()),
				sites.nearestHostileBorders().size(),
				sites.chosenSites().size());
		if (sites.territoryCenter() == null) {
			SpawnResult result = new SpawnResult(sites, List.of(), "Target has no OCCUPIED/BORDER territory", selection);
			logSpawnFail(level, player, result.message(), cycleStart);
			return result;
		}
		if (hell) {
			if (sites.chosenSites().isEmpty()) {
				SpawnResult result = new SpawnResult(sites, List.of(),
						"No demon-chunk anchor for hell template '" + template.id() + "'",
						selection);
				logSpawnFail(level, player, result.message(), cycleStart);
				return result;
			}
		} else {
			if (sites.nearestHostileBorders().isEmpty()) {
				SpawnResult result = new SpawnResult(sites, List.of(),
						"No HOSTILE_BORDER near sampled BORDER (sample="
								+ SiegeSpawnConfig.BORDER_SAMPLE_COUNT
								+ ", expandChebyshev≤"
								+ SiegeSpawnConfig.HOSTILE_EXPAND_CHEBYSHEV
								+ ")",
						selection);
				logSpawnFail(level, player, result.message(), cycleStart);
				return result;
			}
			if (sites.chosenSites().isEmpty()) {
				SpawnResult result = new SpawnResult(sites, List.of(),
						"Bernoulli rejected all " + sites.nearestHostileBorders().size()
								+ " candidate(s) (p=" + SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY
								+ ", E≈" + String.format("%.2f", sites.expectedSites()) + ")",
						selection);
				logSpawnFail(level, player, result.message(), cycleStart);
				return result;
			}
		}

		String toward = hell ? "nearest_owned siteMode=demon" : "nearest_owned";
		long tSpawn = SiegeSpawnMonitor.nanos();
		List<SiegeCluster> created = new ArrayList<>();
		for (ChunkPos site : sites.chosenSites()) {
			long tOne = SiegeSpawnMonitor.nanos();
			List<Mob> mobs = spawnMobsInChunk(level, site, template, random);
			if (mobs.isEmpty()) {
				SiegeSpawnMonitor.debug(
						"site spawn failed player={} chunk={} pos={} reason=no_mobs dim={} gameTime={}",
						player, SiegeSpawnMonitor.chunk(site), SiegeSpawnMonitor.blockPos(level, site),
						SiegeSpawnMonitor.dim(level), level.getGameTime());
				continue;
			}
			ChunkPos assaultTarget = PbsTerritoryAdapter.findNearestOwnedChunk(owned, site);
			SiegeCluster cluster = SiegeClusterManager.createCluster(level, site, mobs);
			if (cluster == null) {
				SiegeSpawnMonitor.debug(
						"createCluster skipped player={} chunk={} reason=no_eligible_mobs",
						player, SiegeSpawnMonitor.chunk(site));
				continue;
			}
			cluster.setTemplateInfo(template.id(), template.name(), template.race(), template.tier());
			cluster.setSiegeTargetId(targetId);
			if (assaultTarget != null) {
				cluster.startAssaultOn(assaultTarget);
			}
			created.add(cluster);
			SiegeSpawnMonitor.spawnedCluster(level, targetId, cluster, toward, cluster.describeTemplate());
			SiegeSpawnMonitor.timing(
					"spawn_site",
					tOne,
					"cluster={} chunk={} mobs={}",
					cluster.getId(), SiegeSpawnMonitor.chunk(site), mobs.size());
		}
		SiegeSpawnMonitor.timing("spawn", tSpawn, "player={} clusters={}", player, created.size());
		if (created.isEmpty()) {
			SpawnResult result = new SpawnResult(sites, List.of(),
					"Sites chosen but mob spawn failed (blocked terrain?)", selection);
			logSpawnFail(level, player, result.message(), cycleStart);
			return result;
		}
		String stage = selection.stage() == null ? "?" : selection.stage().id();
		SpawnResult result = new SpawnResult(sites, created,
				"Spawned " + created.size() + " cluster(s) template='" + template.id()
						+ "' (" + template.name() + ") race=" + template.race()
						+ " tier=" + template.tier() + " stage=" + stage
						+ " worldOwned=" + selection.world().worldOwned(),
				selection);
		SiegeSpawnMonitor.cycle(
				true,
				cycleStart,
				"player={} clusters={} dim={} gameTime={} template={}",
				player, created.size(), SiegeSpawnMonitor.dim(level), level.getGameTime(), template.id());
		SiegeSounds.playInvasionHorn(level, targetId, created);
		return result;
	}

	private static void logTemplateTiming(long startNanos, SiegeTemplateSelector.Selection selection) {
		String id = selection == null || selection.template() == null ? "null" : selection.template().id();
		String tier = selection == null ? "?" : selection.tier();
		String stage = selection == null || selection.stage() == null ? "?" : selection.stage().id();
		SiegeSpawnMonitor.timing("template", startNanos, "id={} tier={} stage={}", id, tier, stage);
	}

	private static void logSpawnFail(ServerLevel level, String player, String message, long cycleStart) {
		SiegeSpawnMonitor.debug(
				"spawn fail player={} dim={} gameTime={} msg={}",
				player, SiegeSpawnMonitor.dim(level), level.getGameTime(), message);
		SiegeSpawnMonitor.cycle(
				false,
				cycleStart,
				"player={} clusters=0 dim={} gameTime={} msg={}",
				player, SiegeSpawnMonitor.dim(level), level.getGameTime(), message);
	}

	/**
	 * Legacy recipe path (kept for in-code callers). Prefer {@link #spawnRolled} / {@link #spawnById}.
	 */
	public static SpawnResult spawnForTarget(
			ServerLevel level,
			UUID targetId,
			SiegeSpawnRecipe recipe,
			Random random
	) {
		long t0 = SiegeSpawnMonitor.nanos();
		String player = SiegeSpawnMonitor.target(level, targetId);
		List<ChunkPos> owned = PbsTerritoryAdapter.getOwnedChunks(level, targetId);
		if (owned.isEmpty()) {
			SpawnResult result = new SpawnResult(
					new SiegeSpawnSiteSelector.SiteSelection(targetId, null, List.of(), List.of()),
					List.of(),
					"Target has no OCCUPIED/BORDER territory");
			logSpawnFail(level, player, result.message(), t0);
			return result;
		}

		long tSite = SiegeSpawnMonitor.nanos();
		SiegeSpawnSiteSelector.SiteSelection sites = SiegeSpawnSiteSelector.select(level, targetId, random);
		SiegeSpawnMonitor.timing(
				"site",
				tSite,
				"player={} hell=false center={} candidates={} chosen={}",
				player,
				SiegeSpawnMonitor.chunk(sites.territoryCenter()),
				sites.nearestHostileBorders().size(),
				sites.chosenSites().size());
		if (sites.territoryCenter() == null) {
			SpawnResult result = new SpawnResult(sites, List.of(), "Target has no OCCUPIED/BORDER territory");
			logSpawnFail(level, player, result.message(), t0);
			return result;
		}
		if (sites.nearestHostileBorders().isEmpty()) {
			SpawnResult result = new SpawnResult(sites, List.of(),
					"No HOSTILE_BORDER near sampled BORDER (sample="
							+ SiegeSpawnConfig.BORDER_SAMPLE_COUNT
							+ ", expandChebyshev≤"
							+ SiegeSpawnConfig.HOSTILE_EXPAND_CHEBYSHEV
							+ ")");
			logSpawnFail(level, player, result.message(), t0);
			return result;
		}
		if (sites.chosenSites().isEmpty()) {
			SpawnResult result = new SpawnResult(sites, List.of(),
					"Bernoulli rejected all " + sites.nearestHostileBorders().size()
							+ " candidate(s) (p=" + SiegeSpawnConfig.SITE_ACCEPT_PROBABILITY
							+ ", E≈" + String.format("%.2f", sites.expectedSites()) + ")");
			logSpawnFail(level, player, result.message(), t0);
			return result;
		}

		long tSpawn = SiegeSpawnMonitor.nanos();
		List<SiegeCluster> created = new ArrayList<>();
		for (ChunkPos site : sites.chosenSites()) {
			List<Mob> mobs = spawnMobsInChunk(level, site, recipe, random);
			if (mobs.isEmpty()) {
				SiegeSpawnMonitor.debug(
						"site spawn failed player={} chunk={} pos={} reason=no_mobs dim={} gameTime={}",
						player, SiegeSpawnMonitor.chunk(site), SiegeSpawnMonitor.blockPos(level, site),
						SiegeSpawnMonitor.dim(level), level.getGameTime());
				continue;
			}
			ChunkPos assaultTarget = PbsTerritoryAdapter.findNearestOwnedChunk(owned, site);
			SiegeCluster cluster = SiegeClusterManager.createCluster(level, site, mobs);
			if (cluster == null) {
				SiegeSpawnMonitor.debug(
						"createCluster skipped player={} chunk={} reason=no_eligible_mobs",
						player, SiegeSpawnMonitor.chunk(site));
				continue;
			}
			cluster.setSiegeTargetId(targetId);
			if (assaultTarget != null) {
				cluster.startAssaultOn(assaultTarget);
			}
			created.add(cluster);
			SiegeSpawnMonitor.spawnedCluster(level, targetId, cluster, "nearest_owned", recipe.id());
		}
		SiegeSpawnMonitor.timing("spawn", tSpawn, "player={} clusters={}", player, created.size());
		if (created.isEmpty()) {
			SpawnResult result = new SpawnResult(sites, List.of(), "Sites chosen but mob spawn failed (blocked terrain?)");
			logSpawnFail(level, player, result.message(), t0);
			return result;
		}
		SpawnResult result = new SpawnResult(sites, created,
				"Spawned " + created.size() + " cluster(s) with recipe '" + recipe.id() + "'");
		SiegeSpawnMonitor.cycle(
				true,
				t0,
				"player={} clusters={} dim={} gameTime={} template={}",
				player, created.size(), SiegeSpawnMonitor.dim(level), level.getGameTime(), recipe.id());
		SiegeSounds.playInvasionHorn(level, targetId, created);
		return result;
	}

	public static List<Mob> spawnMobsInChunk(
			ServerLevel level,
			ChunkPos chunk,
			SiegeClusterTemplate template,
			Random random
	) {
		int count = template.rollCount(random);
		List<Mob> spawned = new ArrayList<>(count);
		int baseX = chunk.getMinBlockX() + 8;
		int baseZ = chunk.getMinBlockZ() + 8;

		for (int i = 0; i < count; i++) {
			SpeciesSpec species = template.rollSpecies(random);
			if (species == null) {
				continue;
			}
			EntityType<? extends Mob> type = SiegeTemplateApplier.resolveType(species.entity());
			if (type == null) {
				continue;
			}
			BlockPos pos = SiegeTemplateApplier.offsetSpawnPos(type, randomSpawnPos(level, baseX, baseZ, random), random);
			if (pos == null) {
				continue;
			}
			Mob mob = type.spawn(level, pos, EntitySpawnReason.EVENT);
			if (mob == null) {
				continue;
			}
			mob.setPersistenceRequired();
			SiegeTemplateApplier.apply(level, mob, template, species, random);
			spawned.add(mob);
		}
		return spawned;
	}

	public static List<Mob> spawnMobsInChunk(
			ServerLevel level,
			ChunkPos chunk,
			SiegeSpawnRecipe recipe,
			Random random
	) {
		List<SiegeUnitDefinition> units = recipe.rollUnits(random);
		List<Mob> spawned = new ArrayList<>(units.size());
		int baseX = chunk.getMinBlockX() + 8;
		int baseZ = chunk.getMinBlockZ() + 8;

		for (SiegeUnitDefinition unit : units) {
			BlockPos pos = randomSpawnPos(level, baseX, baseZ, random);
			if (pos == null) {
				continue;
			}
			Mob mob = unit.type().spawn(level, pos, EntitySpawnReason.EVENT);
			if (mob == null) {
				continue;
			}
			mob.setPersistenceRequired();
			unit.equip(mob);
			spawned.add(mob);
		}
		return spawned;
	}

	private static BlockPos randomSpawnPos(ServerLevel level, int baseX, int baseZ, Random random) {
		int ox = Mth.clamp(random.nextInt(13) - 6, -7, 7);
		int oz = Mth.clamp(random.nextInt(13) - 6, -7, 7);
		int x = baseX + ox;
		int z = baseZ + oz;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos pos = new BlockPos(x, y, z);
		if (!level.getWorldBorder().isWithinBounds(pos)) {
			return null;
		}
		return pos;
	}
}
