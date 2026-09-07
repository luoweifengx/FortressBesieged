package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.ZombifiedPiglin;

import luowei.fortress_besieged.siege.SiegeSpawnedMobs;
import luowei.fortress_besieged.siege.goal.SiegeAiHelper;

/**
 * 围城/野外刷出的僵尸猪灵保持持久仇恨，不因 NeutralMob 计时结束而原谅。
 */
@Mixin(ZombifiedPiglin.class)
public class SiegeZombifiedPiglinMixin {
	@Inject(method = "setRemainingPersistentAngerTime", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$keepPersistentAnger(int remaining, CallbackInfo ci) {
		ZombifiedPiglin self = (ZombifiedPiglin) (Object) this;
		if (remaining <= 0 && SiegeSpawnedMobs.isModSpawned(self)) {
			ci.cancel();
		}
	}

	@Inject(method = "customServerAiStep", at = @At("HEAD"))
	private void fortressBesieged$refreshAnger(ServerLevel level, CallbackInfo ci) {
		ZombifiedPiglin self = (ZombifiedPiglin) (Object) this;
		if (!SiegeSpawnedMobs.isModSpawned(self)) {
			return;
		}
		if (self.getRemainingPersistentAngerTime() < 400) {
			SiegeAiHelper.angerZombifiedPiglin(self);
		}
	}
}
