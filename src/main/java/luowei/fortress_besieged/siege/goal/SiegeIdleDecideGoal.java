package luowei.fortress_besieged.siege.goal;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterState;
import luowei.fortress_besieged.siege.SiegeUnitMode;
import luowei.fortress_besieged.siege.SiegeUnitRecord;

/**
 * IDLE 降频决策：不占用 MOVE/LOOK，可与游荡并行；选中 ATTACK/DESTROY 后由更高优先级 Goal 接管。
 */
public class SiegeIdleDecideGoal extends Goal {
	private final Mob mob;
	private final SiegeCluster cluster;
	private int cooldown;

	public SiegeIdleDecideGoal(Mob mob, SiegeCluster cluster) {
		this.mob = mob;
		this.cluster = cluster;
	}

	@Override
	public boolean canUse() {
		if (cluster.isDisbanded() || cluster.getState() != SiegeClusterState.ASSAULT) {
			return false;
		}
		SiegeUnitRecord record = cluster.getUnit(mob);
		return record != null && record.getMode() == SiegeUnitMode.IDLE;
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		cooldown = 0;
	}

	@Override
	public void tick() {
		mob.setTarget(null);
		if (--cooldown > 0) {
			return;
		}
		cooldown = SiegeTargetDecision.DECISION_INTERVAL_TICKS;
		SiegeTargetDecision.tryDecideFromIdle(mob, cluster);
	}
}
