package luowei.fortress_besieged.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.world.BossEvent;

import luowei.fortress_besieged.siege.spawn.UndeadInvasionBar;

/**
 * Vanilla lerps boss-bar progress over 100 ms of wall time. The undead
 * invasion bar snaps to the last server value so tick freeze does not slide it.
 */
@Mixin(LerpingBossEvent.class)
public abstract class LerpingBossEventMixin {
	@Shadow
	protected float targetPercent;

	@Inject(method = "getProgress", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$snapUndeadInvasion(CallbackInfoReturnable<Float> cir) {
		if (UndeadInvasionBar.isBar((BossEvent) (Object) this)) {
			cir.setReturnValue(this.targetPercent);
		}
	}
}
