package luowei.fortress_besieged.siege.goal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * Wilderness-only: chase a {@link SiegeTargeting} whitelist target if one is in range.
 * No DESTROY / ASSAULT / idle-dice-to-mine. If nobody to chase, {@code canUse} is false
 * and wander takes over. Bow skeletons / ghasts only set the target and keep vanilla ranged AI.
 */
public class WildernessChaseGoal extends Goal {
	private final Mob mob;
	private LivingEntity target;
	private int attackCooldown;

	public WildernessChaseGoal(Mob mob) {
		this.mob = mob;
		if (!SiegeAiHelper.leaveVanillaRanged(mob)) {
			this.setFlags(SiegeAiHelper.moveLookFlags());
		}
	}

	@Override
	public boolean canUse() {
		if (!(mob.level() instanceof ServerLevel serverLevel)) {
			return false;
		}
		LivingEntity chase = SiegeTargetDecision.findNearestAttackTarget(mob, serverLevel);
		if (chase == null || !SiegeTargeting.isValidAttackTarget(chase)) {
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
