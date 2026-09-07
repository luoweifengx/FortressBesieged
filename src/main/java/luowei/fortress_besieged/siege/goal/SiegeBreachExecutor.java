package luowei.fortress_besieged.siege.goal;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.path.SiegePathCostConfig;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;

/**
 * 按队列执行 WALK / PLACE / BREAK。走路用 MoveControl 跟格，不用原版 PathNavigation。
 * WALK 超时返回 STUCK；路上 BREAK 被挖掉时改成 WALK，不整段重规划。
 */
public final class SiegeBreachExecutor {
	private static final double WALK_SPEED = 1.0D;

	enum Status {
		RUNNING,
		FINISHED,
		/** WALK（或执行层判定卡住的一步）超时。 */
		STUCK,
		FAILED
	}

	private final List<BreachAction> actions = new ArrayList<>();
	private int index;
	private int ticksOnAction;
	private float breakProgress;
	private int lastProgress = -1;

	void setActions(List<BreachAction> actions) {
		this.actions.clear();
		if (actions != null && !actions.isEmpty()) {
			this.actions.addAll(actions);
		}
		this.index = 0;
		this.ticksOnAction = 0;
		this.breakProgress = 0.0F;
		this.lastProgress = -1;
	}

	void clearCrack(ServerLevel level, Mob mob) {
		if (level == null || mob == null || index >= actions.size()) {
			return;
		}
		BreachAction current = actions.get(index);
		if (current.type() == BreachAction.Type.BREAK) {
			level.destroyBlockProgress(mob.getId(), current.pos(), -1);
		}
	}

	void clear() {
		actions.clear();
		index = 0;
		ticksOnAction = 0;
		breakProgress = 0.0F;
		lastProgress = -1;
	}

	boolean isFinished() {
		return index >= actions.size();
	}

	/**
	 * 队列中尚未执行完的 {@code BREAK pos} 改成 {@code WALK pos}（块已不在，走路即可）。
	 * 不重搜整条路。
	 *
	 * @return 是否改写了至少一步
	 */
	boolean rewriteBreakToWalk(ServerLevel level, Mob mob, BlockPos pos) {
		if (pos == null || index >= actions.size()) {
			return false;
		}
		boolean changed = false;
		for (int i = index; i < actions.size(); i++) {
			BreachAction action = actions.get(i);
			if (action.type() != BreachAction.Type.BREAK || !pos.equals(action.pos())) {
				continue;
			}
			if (i == index && level != null && mob != null) {
				level.destroyBlockProgress(mob.getId(), pos, -1);
			}
			actions.set(i, new BreachAction(BreachAction.Type.WALK, action.pos()));
			changed = true;
			if (i == index) {
				ticksOnAction = 0;
				breakProgress = 0.0F;
				lastProgress = -1;
			}
		}
		return changed;
	}

	Status tick(Mob mob, SiegeCluster cluster, BlockPos assigned) {
		if (!(mob.level() instanceof ServerLevel serverLevel) || assigned == null) {
			return Status.FAILED;
		}
		if (index >= actions.size()) {
			return Status.FINISHED;
		}
		BreachAction action = actions.get(index);
		ticksOnAction++;
		return switch (action.type()) {
			case WALK -> tickWalk(mob, action.pos());
			case PLACE -> tickPlace(serverLevel, mob, action.pos(), assigned);
			case BREAK -> tickBreak(serverLevel, mob, cluster, action.pos(), assigned);
		};
	}

	private Status tickWalk(Mob mob, BlockPos pos) {
		BlockPos feet = mob.blockPosition();
		if (feet.equals(pos)) {
			advance();
			return index >= actions.size() ? Status.FINISHED : Status.RUNNING;
		}
		int timeout = SiegePathCostConfig.get().walkTimeoutTicks;
		if (ticksOnAction >= timeout) {
			FortressBesieged.LOGGER.debug(
					"[siege destroy] WALK_TIMEOUT mob={} uuid={} walkTo={} ticks={} timeout={}",
					mob.getType().getDescriptionId(),
					mob.getUUID(),
					pos,
					ticksOnAction,
					timeout);
			return Status.STUCK;
		}
		if (ticksOnAction == 1) {
			// 清残留 path，避免 navigation.tick 覆盖 MoveControl；不用原版导航走路
			mob.getNavigation().stop();
			FortressBesieged.LOGGER.debug(
					"[siege destroy] walkTo={} mob={} uuid={}",
					pos,
					mob.getType().getDescriptionId(),
					mob.getUUID());
		}
		mob.getLookControl().setLookAt(Vec3.atBottomCenterOf(pos));
		mob.getMoveControl().setWantedPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, WALK_SPEED);
		if (mob.onGround() && pos.getY() == feet.getY() + 1) {
			mob.getJumpControl().jump();
		}
		return Status.RUNNING;
	}

	private Status tickPlace(ServerLevel level, Mob mob, BlockPos pos, BlockPos assigned) {
		if (pos.equals(assigned)) {
			return Status.FAILED;
		}
		if (SiegeApproachPlanner.isSturdySupport(level, pos) && SiegeApproachPlanner.isSolid(level, pos)) {
			advance();
			return index >= actions.size() ? Status.FINISHED : Status.RUNNING;
		}
		if (!SiegeApproachPlanner.canPlaceCobble(level, pos, assigned)) {
			return Status.FAILED;
		}
		AABB box = new AABB(pos);
		boolean occupied = !level.getEntities(mob, box, SiegeBreachExecutor::blocksPlacement).isEmpty();
		if (occupied) {
			return Status.RUNNING;
		}
		if (!level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) {
			return Status.FAILED;
		}
		advance();
		return index >= actions.size() ? Status.FINISHED : Status.RUNNING;
	}

	private Status tickBreak(
			ServerLevel level,
			Mob mob,
			SiegeCluster cluster,
			BlockPos pos,
			BlockPos assigned
	) {
		if (pos.equals(assigned)) {
			// 不跳过 assigned BREAK：交给 Goal 按挖掘盒 tickBreakAssigned，避免空过后再因旧 reach 不敲
			return Status.FINISHED;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || (!SiegeApproachPlanner.isSolid(level, pos) && !SiegeApproachPlanner.isFluid(level, pos))) {
			level.destroyBlockProgress(mob.getId(), pos, -1);
			advance();
			return index >= actions.size() ? Status.FINISHED : Status.RUNNING;
		}
		if (state.getDestroySpeed(level, pos) < 0.0F) {
			level.destroyBlockProgress(mob.getId(), pos, -1);
			return Status.FAILED;
		}
		mob.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		mob.getNavigation().stop();
		breakProgress += SiegeMining.tickProgress(mob, state, level, pos);
		int progress = SiegeMining.crackStage(breakProgress);
		if (progress != lastProgress) {
			lastProgress = progress;
			level.destroyBlockProgress(mob.getId(), pos, progress);
		}
		if (breakProgress < 1.0F) {
			return Status.RUNNING;
		}
		boolean playerPlaced = PbsTerritoryAdapter.isPlayerPlacedBlock(level, pos);
		boolean tracked = cluster.isTrackedBlock(pos);
		boolean destroyed = level.destroyBlock(pos, false, mob);
		boolean gone = level.getBlockState(pos).isAir();
		level.destroyBlockProgress(mob.getId(), pos, -1);
		if (!(destroyed || gone)) {
			return Status.FAILED;
		}
		if (playerPlaced) {
			PbsTerritoryAdapter.notifyBlockRemoved(level, pos);
		}
		if (tracked) {
			cluster.onTrackedBlockDestroyed(pos, mob);
		}
		cluster.onRouteBlockRemoved(pos);
		advance();
		return index >= actions.size() ? Status.FINISHED : Status.RUNNING;
	}

	private void advance() {
		index++;
		ticksOnAction = 0;
		breakProgress = 0.0F;
		lastProgress = -1;
	}

	private static boolean blocksPlacement(Entity entity) {
		return entity.isAlive() && entity.isPickable() && !(entity instanceof ItemEntity);
	}
}
