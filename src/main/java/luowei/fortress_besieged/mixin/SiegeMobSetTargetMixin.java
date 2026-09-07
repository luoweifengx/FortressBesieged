package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.spawn.WildernessPackTracker;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * 围城单位只打玩家友善目标，且目标必须在集群中心 7×7 内；
 * 锁定仇恨（受击打断 / 地狱预警）允许追出 7×7。
 */
@Mixin(Mob.class)
public class SiegeMobSetTargetMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$restrictSiegeTarget(LivingEntity target, CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		SiegeCluster cluster = SiegeClusterManager.getClusterForMob(self);
		if ((cluster == null && !WildernessPackTracker.isMember(self)) || target == null) {
			return;
		}
		if (!SiegeTargeting.isValidAttackTarget(target)) {
			ci.cancel();
			return;
		}
		if (cluster != null && !cluster.isInChaseZone(target)) {
			var record = cluster.getUnit(self);
			if (record == null || !record.hasLockedAggro()) {
				ci.cancel();
			}
		}
	}
}
