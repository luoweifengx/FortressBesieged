package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.spawn.WildernessPackTracker;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * Siege-spawned mobs do not deal friendly-fire to other monsters (vanilla or siege).
 */
@Mixin(LivingEntity.class)
public class SiegeFriendlyFireMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$cancelMonsterFriendlyFire(
			ServerLevel level,
			DamageSource source,
			float amount,
			CallbackInfoReturnable<Boolean> cir
	) {
		LivingEntity self = (LivingEntity) (Object) this;
		Entity attacker = source.getEntity();
		if (!(attacker instanceof Mob mob)
				|| (SiegeClusterManager.getClusterForMob(mob) == null && !WildernessPackTracker.isMember(mob))) {
			return;
		}
		if (SiegeTargeting.isPlayerFriendly(self)) {
			return;
		}
		if (self.getType().getCategory() == MobCategory.MONSTER) {
			cir.setReturnValue(false);
		}
	}
}
