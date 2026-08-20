package luowei.fortress_besieged.siege.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 围城接近用的方块查询辅助（原三层规划已由 {@link luowei.fortress_besieged.siege.path.SiegeCostPathfinder} 替代）。
 */
public final class SiegeApproachPlanner {
	public static final double REACH = 1.5D;
	public static final double REACH_SQ = REACH * REACH;
	static final int STUCK_TICKS = 40;

	private SiegeApproachPlanner() {
	}

	public static boolean inReach(Vec3 feet, BlockPos assigned) {
		return feet.distanceToSqr(Vec3.atCenterOf(assigned)) <= REACH_SQ;
	}

	public static boolean inReach(BlockPos foot, BlockPos assigned) {
		return Vec3.atBottomCenterOf(foot).distanceToSqr(Vec3.atCenterOf(assigned)) <= REACH_SQ;
	}

	public static boolean isUnbreakable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && state.getDestroySpeed(level, pos) < 0.0F;
	}

	public static boolean isSolid(Level level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	public static boolean isFluid(Level level, BlockPos pos) {
		return !level.getBlockState(pos).getFluidState().isEmpty();
	}

	public static boolean isPassable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	public static boolean isBodyClear(Level level, BlockPos foot) {
		return isPassable(level, foot) && isPassable(level, foot.above());
	}

	public static boolean isSturdySupport(Level level, BlockPos pos) {
		return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
	}

	public static boolean canPlaceCobble(Level level, BlockPos pos, BlockPos assigned) {
		if (pos.equals(assigned)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!state.isAir() && state.getDestroySpeed(level, pos) < 0.0F) {
			return false;
		}
		return state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced();
	}
}
