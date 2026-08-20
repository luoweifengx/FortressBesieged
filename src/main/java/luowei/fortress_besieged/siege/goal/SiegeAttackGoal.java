package luowei.fortress_besieged.siege.goal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeUnitMode;
import luowei.fortress_besieged.siege.SiegeUnitRecord;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * ATTACK：用原版 navigation {@code moveTo} 追打单位自行选定的友善目标。
 */
public class SiegeAttackGoal extends Goal {
	private final Mob mob;
	private final SiegeCluster cluster;
	private LivingEntity target;
	private int attackCooldown;

	public SiegeAttackGoal(Mob mob, SiegeCluster cluster) {
		this.mob = mob;
		this.cluster = cluster;
		this.setFlags(SiegeAiHelper.moveLookFlags());
	}

	@Override
	public boolean canUse() {
		if (cluster.isDisbanded() || !(mob.level() instanceof ServerLevel serverLevel)) {
			return false;
		}
		SiegeUnitRecord record = cluster.getUnit(mob);
		if (record == null || record.getMode() != SiegeUnitMode.ATTACK) {
			return false;
		}
		LivingEntity chase = cluster.resolveChaseTarget(serverLevel, record);
		if (chase == null || !SiegeTargeting.isValidAttackTarget(chase)) {
			record.returnToIdle();
			return false;
		}
		target = chase;
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return canUse() && target != null && target.isAlive();
	}

	@Override
	public void start() {
		attackCooldown = 0;
		if (target != null) {
			mob.setTarget(target);
			mob.getNavigation().moveTo(target, 1.1D);
		}
	}

	@Override
	public void stop() {
		mob.setTarget(null);
		mob.getNavigation().stop();
		target = null;
	}

	@Override
	public void tick() {
		if (target == null) {
			return;
		}
		mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
		double reach = mob.getBbWidth() * 2.0F * mob.getBbWidth() * 2.0F + target.getBbWidth();
		double distSq = mob.distanceToSqr(target);
		if (distSq > reach) {
			mob.getNavigation().moveTo(target, 1.1D);
		} else {
			mob.getNavigation().stop();
			if (--attackCooldown <= 0) {
				attackCooldown = 20;
				mob.doHurtTarget(getServerLevel(), target);
			}
		}
	}

	private ServerLevel getServerLevel() {
		return (ServerLevel) mob.level();
	}
}
