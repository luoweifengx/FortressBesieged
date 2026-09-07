package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;

import luowei.fortress_besieged.siege.spawn.WildernessPackTracker;

/**
 * Wilderness packs are ephemeral: chunk unload / restart must not write them back.
 */
@Mixin(Entity.class)
public class WildernessNoSaveMixin {
	@Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$wildernessNotSaved(CallbackInfoReturnable<Boolean> cir) {
		if (WildernessPackTracker.isMember((Entity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
