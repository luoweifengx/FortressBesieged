package luowei.fortress_besieged.siege.goal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterState;
import luowei.fortress_besieged.siege.SiegeUnitMode;
import luowei.fortress_besieged.siege.SiegeUnitRecord;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * IDLE 态目标决策：首次 1/3 找人，之后 1/30；未中或无人则挖最近 PBS 可破块。
 */
public final class SiegeTargetDecision {
	/** 生成后首次 IDLE 决策：找敌对概率 1/3 */
	public static final int INITIAL_ATTACK_CHANCE_DENOM = 3;
	/** 之后 IDLE 决策：找敌对概率 1/30 */
	public static final int LATER_ATTACK_CHANCE_DENOM = 30;
	/** 决策降频（tick） */
	public static final int DECISION_INTERVAL_TICKS = 20;
	/** AABB 搜敌半径下限 */
	public static final double MIN_SCAN_RANGE = 16.0D;
	/** AABB 搜敌半径上限 */
	public static final double MAX_SCAN_RANGE = 40.0D;
	/** 选块 / FREE 判定：中心区块 Chebyshev 半径 1（3×3，含对角） */
	public static final int BREAKABLE_CHUNK_RADIUS = 1;

	private SiegeTargetDecision() {
	}

	/**
	 * 在 ASSAULT 下从 IDLE 尝试转换到 ATTACK 或 DESTROY。
	 *
	 * @return 是否已离开 IDLE
	 */
	public static boolean tryDecideFromIdle(Mob mob, SiegeCluster cluster) {
		if (mob == null || cluster == null || cluster.isDisbanded()) {
			return false;
		}
		if (cluster.getState() != SiegeClusterState.ASSAULT) {
			return false;
		}
		if (!(mob.level() instanceof ServerLevel serverLevel)) {
			return false;
		}
		SiegeUnitRecord record = cluster.getUnit(mob);
		if (record == null || record.getMode() != SiegeUnitMode.IDLE) {
			return false;
		}

		boolean firstRoll = !record.isFirstTargetRollDone();
		int denom = firstRoll ? INITIAL_ATTACK_CHANCE_DENOM : LATER_ATTACK_CHANCE_DENOM;
		record.markFirstTargetRollDone();
		boolean hell = cluster.isHell();
		int roll = hell ? 0 : mob.getRandom().nextInt(denom);
		boolean attackHit = hell || roll == 0;

		if (attackHit) {
			LivingEntity target = findNearestAttackTarget(mob, serverLevel, cluster);
			if (target != null) {
				record.assignAttack(target);
				logIdleDecision(mob, firstRoll, denom, roll, "ATTACK", null);
				return true;
			}
		}

		if (SiegeAiHelper.isGhast(mob)) {
			mob.setTarget(null);
			logIdleDecision(mob, firstRoll, denom, roll, "IDLE", null);
			return false;
		}

		BlockPos block = findNearestBreakableNear(serverLevel, cluster, mob);
		if (block != null) {
			record.assignDestroy(block);
			mob.setTarget(null);
			logIdleDecision(mob, firstRoll, denom, roll, "DESTROY", block);
			return true;
		}
		mob.setTarget(null);
		logIdleDecision(mob, firstRoll, denom, roll, "IDLE", null);
		return false;
	}

	public static LivingEntity findNearestAttackTarget(Mob mob, ServerLevel level) {
		return findNearestAttackTarget(mob, level, null);
	}

	/**
	 * 最近可攻击目标。{@code cluster} 非空时还要求目标在该集群中心 7×7 内。
	 */
	public static LivingEntity findNearestAttackTarget(Mob mob, ServerLevel level, SiegeCluster cluster) {
		double range = scanRange(mob);
		AABB box = mob.getBoundingBox().inflate(range, range * 0.5D, range);
		List<LivingEntity> found = level.getEntitiesOfClass(
				LivingEntity.class,
				box,
				SiegeTargeting::isValidAttackTarget);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity entity : found) {
			if (cluster != null && !cluster.isInWarningZone(entity)) {
				continue;
			}
			double dist = mob.distanceToSqr(entity);
			if (dist < bestDist) {
				bestDist = dist;
				best = entity;
			}
		}
		return best;
	}

	/**
	 * 以怪当前所在 chunk 为中心（3×3，含对角）收集 PBS 可破放置块，选距该怪最近的一块。
	 * 优先跳过已被其他单位占用的格；若全部占用则回退到最近那块。
	 */
	public static BlockPos findNearestBreakableNear(ServerLevel level, SiegeCluster cluster, Mob mob) {
		if (level == null || mob == null) {
			return null;
		}
		List<BlockPos> candidates = collectBreakables(level, mob.chunkPosition());
		UUID selfId = mob.getUUID();
		Vec3 from = mob.position();
		BlockPos bestFree = null;
		double bestFreeDist = Double.MAX_VALUE;
		BlockPos bestAny = null;
		double bestAnyDist = Double.MAX_VALUE;
		for (BlockPos pos : candidates) {
			double dist = from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
			if (dist < bestAnyDist) {
				bestAnyDist = dist;
				bestAny = pos;
			}
			if (cluster != null && cluster.isTrackedBlockByOther(pos, selfId)) {
				continue;
			}
			if (dist < bestFreeDist) {
				bestFreeDist = dist;
				bestFree = pos;
			}
		}
		return bestFree != null ? bestFree : bestAny;
	}

	/** {@code centerChunk} 的 3×3（含对角）内是否还有可破 PBS 放置块。 */
	public static boolean hasAnyBreakable(ServerLevel level, ChunkPos centerChunk) {
		return !collectBreakables(level, centerChunk).isEmpty();
	}

	private static List<BlockPos> collectBreakables(ServerLevel level, ChunkPos centerChunk) {
		List<BlockPos> all = new ArrayList<>();
		if (level == null || centerChunk == null) {
			return all;
		}
		for (int dx = -BREAKABLE_CHUNK_RADIUS; dx <= BREAKABLE_CHUNK_RADIUS; dx++) {
			for (int dz = -BREAKABLE_CHUNK_RADIUS; dz <= BREAKABLE_CHUNK_RADIUS; dz++) {
				all.addAll(PbsTerritoryAdapter.getBreakablePlacedBlocks(
						level, new ChunkPos(centerChunk.x + dx, centerChunk.z + dz)));
			}
		}
		return all;
	}

	private static void logIdleDecision(
			Mob mob, boolean firstRoll, int denom, int roll, String choice, BlockPos destroyPos) {
		FortressBesieged.LOGGER.debug(
				"[siege idle] decide uuid={} firstRoll={} denom={} roll={} hit={} choice={} destroyPos={}",
				mob.getUUID(),
				firstRoll,
				denom,
				roll,
				roll == 0,
				choice,
				destroyPos);
	}

	private static double scanRange(Mob mob) {
		double follow = mob.getAttribute(Attributes.FOLLOW_RANGE) != null
				? mob.getAttributeValue(Attributes.FOLLOW_RANGE)
				: MIN_SCAN_RANGE;
		return Math.max(MIN_SCAN_RANGE, Math.min(MAX_SCAN_RANGE, follow));
	}
}
