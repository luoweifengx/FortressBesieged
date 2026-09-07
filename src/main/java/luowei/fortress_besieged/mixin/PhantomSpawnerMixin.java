package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.PhantomSpawner;

/**
 * Disables vanilla phantom spawning.
 */
@Mixin(PhantomSpawner.class)
public class PhantomSpawnerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void fortressBesieged$disablePhantoms(ServerLevel level, boolean spawnEnemies, boolean spawnFriendlies, CallbackInfo ci) {
		ci.cancel();
	}
}
