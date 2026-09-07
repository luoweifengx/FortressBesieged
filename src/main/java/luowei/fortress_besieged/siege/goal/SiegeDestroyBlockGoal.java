package luowei.fortress_besieged.siege.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeUnitMode;
import luowei.fortress_besieged.siege.SiegeUnitRecord;
import luowei.fortress_besieged.siege.path.SiegeCostPathfinder;
import luowei.fortress_besieged.siege.path.SiegePathCostConfig;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * DESTROY：一次规划产出完整接近队列（盒外贪心 + 盒内 A*），MoveControl 跟格执行；够近后敲击。
 * 规划失败 / WALK 超时只冷却重试同一目标；成功走完队列不强制 replan 冷却。
 */
public class SiegeDestroyBlockGoal extends Goal {
	private final Mob mob;
	private final SiegeCluster cluster;
	private final SiegeBreachExecutor executor = new SiegeBreachExecutor();

	private BlockPos targetPos;
	private BlockPos plannedAssigned;
	private float breakProgress;
	private int lastProgress = -1;
	private int replanCooldown;

	public SiegeDestroyBlockGoal(Mob mob, SiegeCluster cluster) {
		this.mob = mob;
		this.cluster = cluster;
		this.setFlags(SiegeAiHelper.moveLookFlags());
	}

	/**
	 * 路上某格已被挖掉：队列里对应 BREAK 改成 WALK，不重搜整段。
	 *
	 * @return 是否改写了至少一步
	 */
	boolean rewriteBreakToWalk(BlockPos pos) {
		if (pos == null) {
			return false;
		}
		ServerLevel serverLevel = mob.level() instanceof ServerLevel sl ? sl : null;
		boolean changed = executor.rewriteBreakToWalk(serverLevel, mob, pos);
		if (changed) {
			FortressBesieged.LOGGER.debug(
					"[siege destroy] rewrite BREAK->WALK pos={} mob={} uuid={}",
					pos,
					mob.getType().getDescriptionId(),
					mob.getUUID());
		}
		return changed;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public boolean canUse() {
		if (cluster.isDisbanded() || !(mob.level() instanceof ServerLevel)) {
			return false;
		}
		SiegeUnitRecord record = cluster.getUnit(mob);
		if (record == null || record.getMode() != SiegeUnitMode.DESTROY) {
			return false;
		}
		BlockPos pos = record.getTrackedBlock();
		if (pos == null || !PbsTerritoryAdapter.isPlayerPlacedBlock((ServerLevel) mob.level(), pos)) {
			record.returnToIdle();
			return false;
		}
		BlockState state = mob.level().getBlockState(pos);
		if (state.isAir()) {
			record.returnToIdle();
			return false;
		}
		mob.setTarget(null);
		targetPos = pos;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void start() {
		mob.setTarget(null);
		resetBreakProgress();
		replanCooldown = 0;
		FortressBesieged.LOGGER.debug(
				"[siege destroy] start mob={} uuid={} target={} state={}",
				mob.getType().getDescriptionId(),
				mob.getUUID(),
				targetPos,
				targetPos == null ? null : mob.level().getBlockState(targetPos));
		tryPlan(false);
	}

	@Override
	public void stop() {
		if (mob.level() instanceof ServerLevel serverLevel) {
			if (targetPos != null) {
				serverLevel.destroyBlockProgress(mob.getId(), targetPos, -1);
			}
			executor.clearCrack(serverLevel, mob);
		}
		executor.clear();
		mob.getNavigation().stop();
		resetBreakProgress();
		targetPos = null;
		plannedAssigned = null;
		replanCooldown = 0;
	}

	@Override
	public void tick() {
		if (targetPos == null || !(mob.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		mob.setTarget(null);
		SiegePathCostConfig cfg = SiegePathCostConfig.get();

		if (plannedAssigned == null || !plannedAssigned.equals(targetPos)) {
			resetBreakProgress();
			executor.clearCrack(serverLevel, mob);
			executor.clear();
			replanCooldown = 0;
			if (!tryPlan(false)) {
				return;
			}
		}

		mob.getLookControl().setLookAt(Vec3.atCenterOf(targetPos));
		// 先进入挖掘盒（脚格 / blockPosition 任一），再敲 assigned
		if (isInReach(cfg)) {
			tickBreakAssigned(serverLevel);
			return;
		}

		if (replanCooldown > 0) {
			replanCooldown--;
			if (executor.isFinished() && replanCooldown == 0) {
				tryPlan(false);
			}
			return;
		}

		SiegeBreachExecutor.Status status = executor.tick(mob, cluster, targetPos);
		switch (status) {
			case RUNNING -> {
			}
			case FINISHED -> {
				if (isInReach(cfg)) {
					tickBreakAssigned(serverLevel);
					return;
				}
				// 完整队列走完仍够不着：立即再规划（理想一次规划已到挖掘盒；无 replanInterval 惩罚）
				executor.clear();
				tryPlan(false);
			}
			case STUCK -> onPlanOrExecFailure(serverLevel, "WALK_TIMEOUT");
			case FAILED -> onPlanOrExecFailure(serverLevel, status.name());
		}
	}

	private void tickBreakAssigned(ServerLevel serverLevel) {
		executor.clearCrack(serverLevel, mob);
		executor.clear();
		mob.getNavigation().stop();
		BlockState state = serverLevel.getBlockState(targetPos);
		if (state.getDestroySpeed(serverLevel, targetPos) < 0.0F) {
			serverLevel.destroyBlockProgress(mob.getId(), targetPos, -1);
			resetBreakProgress();
			onPlanOrExecFailure(serverLevel, "FAILED");
			return;
		}
		breakProgress += SiegeMining.tickProgress(mob, state, serverLevel, targetPos);
		int progress = SiegeMining.crackStage(breakProgress);
		if (progress != lastProgress) {
			lastProgress = progress;
			serverLevel.destroyBlockProgress(mob.getId(), targetPos, progress);
		}
		if (breakProgress < 1.0F) {
			return;
		}
		BlockPos broken = targetPos;
		BlockState before = serverLevel.getBlockState(broken);
		boolean destroyed = serverLevel.destroyBlock(broken, false, mob);
		BlockState after = serverLevel.getBlockState(broken);
		boolean gone = after.isAir();
		FortressBesieged.LOGGER.debug(
				"[siege destroy] destroyBlock mob={} uuid={} pos={} before={} destroyBlockReturned={} after={} gone={}",
				mob.getType().getDescriptionId(),
				mob.getUUID(),
				broken,
				before,
				destroyed,
				after,
				gone);
		if (destroyed || gone) {
			PbsTerritoryAdapter.notifyBlockRemoved(serverLevel, broken);
			cluster.onTrackedBlockDestroyed(broken, mob);
			cluster.onRouteBlockRemoved(broken);
		} else {
			FortressBesieged.LOGGER.warn(
					"[siege destroy] destroy failed / block still present at {} after destroyBlock",
					broken);
		}
		serverLevel.destroyBlockProgress(mob.getId(), broken, -1);
		resetBreakProgress();
	}

	/**
	 * @param afterFailure 若为失败重试，使用更长冷却；成功规划则清冷却并装载队列
	 */
	private boolean tryPlan(boolean afterFailure) {
		plannedAssigned = targetPos;
		if (targetPos == null) {
			return false;
		}
		SiegePathCostConfig cfg = SiegePathCostConfig.get();
		if (isInReach(cfg)) {
			executor.clear();
			replanCooldown = 0;
			return true;
		}
		boolean inBox = SiegeCostPathfinder.inLocalBox(mob.blockPosition(), targetPos, cfg);
		SiegeCostPathfinder.Result result = inBox
				? SiegeCostPathfinder.findAstar(mob, targetPos)
				: SiegeCostPathfinder.findGreedyThenAstar(mob, targetPos);
		if (result == null) {
			onPlanOrExecFailure(
					mob.level() instanceof ServerLevel sl ? sl : null,
					afterFailure ? "RETRY_FAIL" : "PLAN_FAIL");
			return false;
		}
		if (mob.level() instanceof ServerLevel serverLevel) {
			executor.clearCrack(serverLevel, mob);
		}
		executor.setActions(result.actions);
		replanCooldown = 0;
		logPlanOk(result.mode, result);
		return true;
	}

	private void logPlanOk(String mode, SiegeCostPathfinder.Result result) {
		FortressBesieged.LOGGER.debug(
				"[siege destroy] plan ok mode={} mob={} uuid={} target={} actions={} cost={} expanded={}",
				mode,
				mob.getType().getDescriptionId(),
				mob.getUUID(),
				targetPos,
				result.actions.size(),
				result.cost,
				result.expanded);
	}

	private void onPlanOrExecFailure(ServerLevel serverLevel, String reason) {
		SiegePathCostConfig cfg = SiegePathCostConfig.get();
		if (serverLevel != null) {
			if (targetPos != null) {
				serverLevel.destroyBlockProgress(mob.getId(), targetPos, -1);
			}
			executor.clearCrack(serverLevel, mob);
		}
		executor.clear();
		mob.getNavigation().stop();
		resetBreakProgress();
		// 保留同一 assigned，长冷却后重试；绝不 requestDestroyReassign
		replanCooldown = cfg.failCooldownTicks;
		FortressBesieged.LOGGER.debug(
				"[siege destroy] path fail ({}) mob={} uuid={} target={} cooldown={} (same target, no reassign)",
				reason,
				mob.getType().getDescriptionId(),
				mob.getUUID(),
				targetPos,
				replanCooldown);
	}

	/**
	 * 脚格或实体 {@code blockPosition()} 任一进入挖掘盒即视为能敲 assigned（切比雪夫，默认 2）。
	 */
	private boolean isInReach(SiegePathCostConfig cfg) {
		return SiegeCostPathfinder.inReach(mob.position(), targetPos, cfg)
				|| SiegeCostPathfinder.inReach(mob.blockPosition(), targetPos, cfg);
	}

	private void resetBreakProgress() {
		breakProgress = 0.0F;
		lastProgress = -1;
	}
}
