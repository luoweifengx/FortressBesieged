package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.VillageSiege;

/**
 * Disables vanilla village zombie sieges.
 */
@Mixin(VillageSiege.class)
public class VillageSiegeMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$disableVillageSiege(ServerLevel level, boolean spawnEnemies, boolean spawnFriendlies, CallbackInfo ci) {
		ci.cancel();
	}
}
