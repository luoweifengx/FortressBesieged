package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.goal.SiegeAiHelper;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.SpeciesSpec;
import luowei.player_block_status.lib.chunk.ChunkState;

/**
 * Spawns an ephemeral wilderness pack in {@code NATURAL} columns around a player.
 * No persistence, no chunk tickets, no {@code SiegeCluster}.
 */
public final class WildernessSpawner {
	private WildernessSpawner() {
	}

	public record SpawnResult(List<Mob> mobs, BlockPos origin, String message, SiegeTemplateSelector.Selection selection) {
	}

	public static SpawnResult spawnAround(ServerLevel level, ServerPlayer player, UUID pollTargetId, Random random) {
		long t0 = System.nanoTime();
		String playerName = SiegeSpawnMonitor.target(level, player.getUUID());
		BlockPos origin = findOrigin(level, player, random);
		if (origin == null) {
			SpawnResult result = new SpawnResult(List.of(), null, "no NATURAL site in spawn ring", null);
			debugFail(playerName, result.message());
			return result;
		}
		if (PbsTerritoryAdapter.getState(level, new ChunkPos(origin)) != ChunkState.NATURAL) {
			SpawnResult result = new SpawnResult(List.of(), origin, "origin chunk is not NATURAL", null);
			debugFail(playerName, result.message());
			return result;
		}

		SiegeTemplateSelector.Selection selection = SiegeTemplateSelector.rollWilderness(level, pollTargetId, random);
		if (selection == null || selection.template() == null) {
			SpawnResult result = new SpawnResult(List.of(), origin, "no cluster template available", selection);
			debugFail(playerName, result.message());
			return result;
		}
		SiegeClusterTemplate template = selection.template();
		if (template.isHell()) {
			SpawnResult result = new SpawnResult(
					List.of(),
					origin,
					"hell template '" + template.id() + "' skipped on NATURAL chunk",
					selection);
			debugFail(playerName, result.message());
			return result;
		}

		List<Mob> mobs = spawnMembers(level, origin, template, random);
		if (mobs.isEmpty()) {
			SpawnResult result = new SpawnResult(List.of(), origin, "site found but mob spawn failed", selection);
			debugFail(playerName, result.message());
			return result;
		}
		WildernessPackTracker.register(level, player.getUUID(), mobs);
		String stage = selection.stage() == null ? "?" : selection.stage().id();
		FortressBesieged.LOGGER.info(
				"[wilderness spawn] spawned player={} dim={} pos={} chunk={} mobs={} template={} race={} tier={} stage={} gameTime={}",
				playerName,
				SiegeSpawnMonitor.dim(level),
				origin.getX() + "," + origin.getY() + "," + origin.getZ(),
				new ChunkPos(origin).x + "," + new ChunkPos(origin).z,
				mobs.size(),
				template.id(),
				template.race(),
				template.tier(),
				stage,
				level.getGameTime());
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		if (ms >= SiegeSpawnMonitor.SLOW_CYCLE_MS) {
			FortressBesieged.LOGGER.warn("[wilderness spawn] SLOW spawn ms={} player={} mobs={}", ms, playerName, mobs.size());
		}
		return new SpawnResult(
				mobs,
				origin,
				"Spawned " + mobs.size() + " wilderness mob(s) template='" + template.id() + "'",
				selection);
	}

	private static List<Mob> spawnMembers(
			ServerLevel level,
			BlockPos origin,
			SiegeClusterTemplate template,
			Random random
	) {
		int count = template.rollCount(random);
		List<Mob> spawned = new ArrayList<>(count);
		RandomSource randomSource = RandomSource.create(random.nextLong());
		for (int i = 0; i < count; i++) {
			SpeciesSpec species = template.rollSpecies(random);
			if (species == null) {
				continue;
			}
			EntityType<? extends Mob> type = SiegeTemplateApplier.resolveType(species.entity());
			if (type == null) {
				continue;
			}
			BlockPos pos = SiegeTemplateApplier.offsetSpawnPos(
					type, memberPos(level, origin, random, randomSource), random);
			if (pos == null) {
				continue;
			}
			Mob mob = type.spawn(level, pos, EntitySpawnReason.NATURAL);
			if (mob == null) {
				continue;
			}
			SiegeTemplateApplier.apply(level, mob, template, species, random);
			SiegeAiHelper.installWilderness(mob);
			spawned.add(mob);
		}
		return spawned;
	}

	private static BlockPos findOrigin(ServerLevel level, ServerPlayer player, Random random) {
		for (int i = 0; i < WildernessSpawnConfig.SITE_ATTEMPTS; i++) {
			BlockPos column = randomColumn(player, random);
			BlockPos pos = firstValidInColumn(level, column, random);
			if (pos != null && distanceOk(level, pos, player)) {
				return pos;
			}
		}
		return null;
	}

	private static BlockPos memberPos(
			ServerLevel level,
			BlockPos origin,
			Random random,
			RandomSource randomSource
	) {
		int spread = WildernessSpawnConfig.MEMBER_SPREAD_BLOCKS;
		for (int i = 0; i < 6; i++) {
			int ox = random.nextInt(spread * 2 + 1) - spread;
			int oz = random.nextInt(spread * 2 + 1) - spread;
			BlockPos column = new BlockPos(origin.getX() + ox, origin.getY(), origin.getZ() + oz);
			BlockPos pos = firstValidInColumn(level, column, random);
			if (pos != null) {
				return pos;
			}
		}
		return firstValidInColumn(level, origin, random);
	}

	private static BlockPos randomColumn(ServerPlayer player, Random random) {
		double min = WildernessSpawnConfig.MIN_PLAYER_DISTANCE;
		double max = WildernessSpawnConfig.MAX_PLAYER_DISTANCE;
		double dist = min + random.nextDouble() * (max - min);
		double angle = random.nextDouble() * Math.PI * 2.0D;
		int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
		int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
		return new BlockPos(x, Mth.floor(player.getY()), z);
	}

	private static BlockPos firstValidInColumn(
			ServerLevel level,
			BlockPos column,
			Random random
	) {
		int x = column.getX();
		int z = column.getZ();
		ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
		if (!level.hasChunk(chunk.x, chunk.z)) {
			return null;
		}
		if (PbsTerritoryAdapter.getState(level, chunk) != ChunkState.NATURAL) {
			return null;
		}
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		int minY = level.getMinY() + 1;
		int samples = 1 + WildernessSpawnConfig.Y_SAMPLES_PER_COLUMN;
		for (int i = 0; i < samples; i++) {
			int y;
			if (i == 0) {
				y = surface;
			} else {
				int span = Math.max(1, surface - minY);
				y = minY + random.nextInt(span);
			}
			BlockPos pos = new BlockPos(x, y, z);
			if (isValidSpawnPos(level, pos)) {
				return pos;
			}
		}
		return null;
	}

	private static boolean isValidSpawnPos(
			ServerLevel level,
			BlockPos pos
	) {
		if (!level.getWorldBorder().isWithinBounds(pos)) {
			return false;
		}
		if (!level.isPositionEntityTicking(pos)) {
			return false;
		}
		ChunkPos chunk = new ChunkPos(pos);
		if (PbsTerritoryAdapter.getState(level, chunk) != ChunkState.NATURAL) {
			return false;
		}
		if (!level.getFluidState(pos).isEmpty()) {
			return false;
		}
		BlockState feet = level.getBlockState(pos);
		BlockState head = level.getBlockState(pos.above());
		BlockState floor = level.getBlockState(pos.below());
		if (!feet.getCollisionShape(level, pos).isEmpty() || !head.getCollisionShape(level, pos.above()).isEmpty()) {
			return false;
		}
		if (!floor.isCollisionShapeFullBlock(level, pos.below())) {
			return false;
		}
		return true;
	}

	private static boolean distanceOk(ServerLevel level, BlockPos pos, ServerPlayer owner) {
		double x = pos.getX() + 0.5D;
		double y = pos.getY();
		double z = pos.getZ() + 0.5D;
		double minSq = (double) WildernessSpawnConfig.MIN_PLAYER_DISTANCE * WildernessSpawnConfig.MIN_PLAYER_DISTANCE;
		double maxSq = (double) WildernessSpawnConfig.MAX_PLAYER_DISTANCE * WildernessSpawnConfig.MAX_PLAYER_DISTANCE;
		if (owner.distanceToSqr(x, y, z) > maxSq) {
			return false;
		}
		for (ServerPlayer other : level.players()) {
			if (other.isSpectator()) {
				continue;
			}
			if (other.distanceToSqr(x, y, z) < minSq) {
				return false;
			}
		}
		return true;
	}

	private static void debugFail(String player, String message) {
		FortressBesieged.LOGGER.debug("[wilderness spawn] fail player={} msg={}", player, message);
	}
}
