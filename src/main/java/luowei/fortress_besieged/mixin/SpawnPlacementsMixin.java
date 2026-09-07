package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * Blocks zombie reinforcement spawns ({@link EntitySpawnReason#REINFORCEMENT} only).
 */
@Mixin(SpawnPlacements.class)
public class SpawnPlacementsMixin {
	@Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
	private static void fortressBesieged$disableReinforcements(
			EntityType<?> type,
			ServerLevelAccessor level,
			EntitySpawnReason reason,
			BlockPos pos,
			RandomSource random,
			CallbackInfoReturnable<Boolean> cir
	) {
		if (reason == EntitySpawnReason.REINFORCEMENT) {
			cir.setReturnValue(false);
		}
	}
}
