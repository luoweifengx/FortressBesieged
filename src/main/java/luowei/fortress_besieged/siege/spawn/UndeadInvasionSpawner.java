package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.SpeciesSpec;

/**
 * One nether-roster cluster per wave, on a hostile chunk around the event anchor.
 * Hell templates stay on {@link HellSpawnScheduler}.
 */
public final class UndeadInvasionSpawner {
	/** Chebyshev chunk radius around the anchor, excluding the anchor itself. */
	public static final int HOSTILE_RADIUS = 8;
	/** How far to look for an OCCUPIED/BORDER chunk when the anchor itself is not one. */
	private static final int ASSAULT_SEARCH_RADIUS = 16;
	/** Feet must be below the bedrock ceiling (y=123–127). */
	private static final int MAX_FEET_Y = 119;
	/** Above the bedrock floor (y=0–4). */
	private static final int MIN_FEET_Y = 5;

	private UndeadInvasionSpawner() {
	}

	public static int countHostileAround(ServerLevel level, ChunkPos anchor) {
		return hostileAround(level, anchor).size();
	}

	public static List<ChunkPos> hostileAround(ServerLevel level, ChunkPos anchor) {
		List<ChunkPos> found = new ArrayList<>();
		if (level == null || anchor == null) {
			return found;
		}
		for (int dx = -HOSTILE_RADIUS; dx <= HOSTILE_RADIUS; dx++) {
			for (int dz = -HOSTILE_RADIUS; dz <= HOSTILE_RADIUS; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) > HOSTILE_RADIUS) {
					continue;
				}
				if (dx == 0 && dz == 0) {
					continue;
				}
				ChunkPos pos = new ChunkPos(anchor.x + dx, anchor.z + dz);
				if (PbsTerritoryAdapter.isSiegeSpawnSite(level, pos)) {
					found.add(pos);
				}
			}
		}
		return found;
	}

	/**
	 * @return 1 if a cluster spawned, 0 if this wave had nowhere to land or no roster
	 */
	public static int spawnWave(ServerLevel level, ChunkPos anchor, UUID targetId, Random random) {
		if (level == null || anchor == null || level.getDifficulty() == Difficulty.PEACEFUL) {
			return 0;
		}
		List<ChunkPos> sites = hostileAround(level, anchor);
		sites.removeIf(site -> hasClusterAt(level, site));
		if (sites.isEmpty()) {
			FortressBesieged.LOGGER.info(
					"[siege] undead invasion wave skipped anchor={} reason=no_hostile_site radius={}",
					anchor,
					HOSTILE_RADIUS);
			return 0;
		}
		SiegeClusterTemplate template = rollNether(level, random);
		if (template == null) {
			FortressBesieged.LOGGER.info("[siege] undead invasion wave skipped reason=no_nether_template");
			return 0;
		}
		ChunkPos site = sites.get(random.nextInt(sites.size()));
		List<Mob> mobs = spawnMobs(level, site, template, random);
		if (mobs.isEmpty()) {
			FortressBesieged.LOGGER.info(
					"[siege] undead invasion wave skipped chunk={} reason=no_mobs template={}",
					site,
					template.id());
			return 0;
		}
		SiegeCluster cluster = SiegeClusterManager.createCluster(level, site, mobs);
		if (cluster == null) {
			return 0;
		}
		cluster.setTemplateInfo(template.id(), template.name(), template.race(), template.tier());
		if (targetId != null) {
			cluster.setSiegeTargetId(targetId);
		}
		ChunkPos assault = assaultTarget(level, anchor);
		cluster.startAssaultOn(assault);
		Mob sample = mobs.get(0);
		FortressBesieged.LOGGER.info(
				"[siege] undead invasion wave chunk={} pos={},{},{} anchor={} assault={} mobs={} template={}",
				site,
				sample.getBlockX(),
				sample.getBlockY(),
				sample.getBlockZ(),
				anchor,
				assault,
				mobs.size(),
				template.id());
		return 1;
	}

	/**
	 * Nether heightmap is the bedrock roof. Any standable floor from y=119 down
	 * to y=5 is valid; a column with none of those is skipped.
	 */
	private static List<Mob> spawnMobs(
			ServerLevel level,
			ChunkPos chunk,
			SiegeClusterTemplate template,
			Random random
	) {
		int count = template.rollCount(random, level.getDifficulty());
		List<Mob> spawned = new ArrayList<>(count);
		int baseX = chunk.getMinBlockX() + 8;
		int baseZ = chunk.getMinBlockZ() + 8;
		for (int i = 0; i < count; i++) {
			SpeciesSpec species = template.rollSpecies(random, SiegeDayClock.dayIndex(level));
			if (species == null) {
				continue;
			}
			EntityType<? extends Mob> type = SiegeTemplateApplier.resolveType(species.entity());
			if (type == null) {
				continue;
			}
			int ox = Mth.clamp(random.nextInt(13) - 6, -7, 7);
			int oz = Mth.clamp(random.nextInt(13) - 6, -7, 7);
			BlockPos pos = findStand(level, baseX + ox, baseZ + oz);
			if (pos == null) {
				continue;
			}
			BlockPos lifted = SiegeTemplateApplier.offsetSpawnPos(type, pos, random);
			if (lifted.getY() <= MAX_FEET_Y) {
				pos = lifted;
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

	private static BlockPos findStand(ServerLevel level, int x, int z) {
		int min = Math.max(level.getMinY() + 1, MIN_FEET_Y);
		int max = Math.min(level.getMaxY() - 2, MAX_FEET_Y);
		for (int y = max; y >= min; y--) {
			if (canStand(level, x, y, z)) {
				return new BlockPos(x, y, z);
			}
		}
		return null;
	}

	private static boolean canStand(ServerLevel level, int x, int y, int z) {
		BlockPos feet = new BlockPos(x, y, z);
		if (!level.getWorldBorder().isWithinBounds(feet)) {
			return false;
		}
		if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) {
			return false;
		}
		BlockState floor = level.getBlockState(feet.below());
		if (!floor.isCollisionShapeFullBlock(level, feet.below())) {
			return false;
		}
		return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
	}

	private static ChunkPos assaultTarget(ServerLevel level, ChunkPos anchor) {
		if (PbsTerritoryAdapter.isAssaultable(level, anchor)) {
			return anchor;
		}
		List<ChunkPos> owned = PbsTerritoryAdapter.findNearestAssaultChunks(level, anchor, ASSAULT_SEARCH_RADIUS);
		return owned.isEmpty() ? anchor : owned.get(0);
	}

	private static SiegeClusterTemplate rollNether(ServerLevel level, Random random) {
		List<SiegeClusterTemplate> matches = new ArrayList<>();
		for (SiegeClusterTemplate template : SiegeClusterConfig.siege().templates()) {
			if (template.isNetherRoster() && !template.isHell() && SiegeTemplateSelector.allowedIn(template, level)) {
				matches.add(template);
			}
		}
		if (matches.isEmpty()) {
			return null;
		}
		return matches.get(random.nextInt(matches.size()));
	}

	private static boolean hasClusterAt(ServerLevel level, ChunkPos chunk) {
		for (SiegeCluster cluster : SiegeClusterManager.clusters()) {
			if (cluster.getLevel() != level || cluster.isDisbanded()) {
				continue;
			}
			if (chunk.equals(cluster.getAnchorChunk())) {
				return true;
			}
		}
		return false;
	}
}
