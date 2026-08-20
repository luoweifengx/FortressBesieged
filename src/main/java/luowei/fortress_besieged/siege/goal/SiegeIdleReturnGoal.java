package luowei.fortress_besieged.siege.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeUnitMode;
import luowei.fortress_besieged.siege.SiegeUnitRecord;

/**
 * IDLE 且不在 {@code targetChunk} 时：导航回进攻区块中心。
 * 已在目标区块内则不抢 MOVE，交给游荡 / {@link SiegeIdleDecideGoal}。
 */
public class SiegeIdleReturnGoal extends Goal {
	private final Mob mob;
	private final SiegeCluster cluster;

	public SiegeIdleReturnGoal(Mob mob, SiegeCluster cluster) {
		this.mob = mob;
		this.cluster = cluster;
		this.setFlags(SiegeAiHelper.moveLookFlags());
	}

	@Override
	public boolean canUse() {
		if (cluster.isDisbanded()) {
			return false;
		}
		SiegeUnitRecord record = cluster.getUnit(mob);
		if (record == null || record.getMode() != SiegeUnitMode.IDLE) {
			return false;
		}
		ChunkPos target = cluster.getTargetChunk();
		return target != null && !mob.chunkPosition().equals(target);
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void start() {
		mob.setTarget(null);
		moveHome();
	}

	@Override
	public void tick() {
		mob.setTarget(null);
		ChunkPos target = cluster.getTargetChunk();
		if (target == null) {
			return;
		}
		if (mob.chunkPosition().equals(target)) {
			mob.getNavigation().stop();
			return;
		}
		if (mob.getNavigation().isDone()) {
			moveHome();
		}
	}

	@Override
	public void stop() {
		mob.getNavigation().stop();
	}

	private void moveHome() {
		ChunkPos target = cluster.getTargetChunk();
		if (target == null || !(mob.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		BlockPos center = new BlockPos(target.getMiddleBlockX(), mob.blockPosition().getY(), target.getMiddleBlockZ());
		int y = serverLevel.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
		mob.getNavigation().moveTo(center.getX() + 0.5D, y, center.getZ() + 0.5D, 1.0D);
	}
}
