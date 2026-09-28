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
 * ATTACK：近战用原版 navigation {@code moveTo} 追打；持弓骷髅 / 恶魂只 {@code setTarget}，对人射击走原版 Goal。
 * 恶魂打方块不走这里，由 {@link SiegeGhastBombardGoal} 发射火焰弹。
 * 锁定仇恨（受击打断 / 地狱预警）不受 7×7 限制，直到绳长丢失后再接回原工作。
 */
public class SiegeAttackGoal extends Goal {
	private final Mob mob;
	private final SiegeCluster cluster;
	private LivingEntity target;
	private int attackCooldown;

	public SiegeAttackGoal(Mob mob, SiegeCluster cluster) {
		this.mob = mob;
		this.cluster = cluster;
		if (!SiegeAiHelper.leaveVanillaRanged(mob)) {
			this.setFlags(SiegeAiHelper.moveLookFlags());
		}
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
			if (record.hasLockedAggro()) {
				cluster.onLockedAggroLost(mob);
			} else {
				record.returnToIdle();
			}
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
			if (!SiegeAiHelper.leaveVanillaRanged(mob)) {
				mob.getNavigation().moveTo(target, 1.1D);
			}
		}
	}

	@Override
	public void stop() {
		mob.setTarget(null);
		if (!SiegeAiHelper.leaveVanillaRanged(mob)) {
			mob.getNavigation().stop();
		}
		target = null;
	}

	@Override
	public void tick() {
		if (target == null) {
			return;
		}
		SiegeUnitRecord record = cluster.getUnit(mob);
		boolean locked = record != null && record.hasLockedAggro();
		if (!locked && !cluster.isInChaseZone(target)) {
			if (record != null) {
				record.returnToIdle();
			}
			return;
		}
		if (locked && !cluster.isWithinAggroLeash(mob, target)) {
			cluster.onLockedAggroLost(mob);
			return;
		}
		mob.setTarget(target);
		if (SiegeAiHelper.leaveVanillaRanged(mob)) {
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
