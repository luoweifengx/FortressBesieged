package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Stops NaturalSpawner MONSTER replenishment, including structure spawn_overrides.
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {
	@Inject(
			method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private static void fortressBesieged$disableMonsterCategory(
			MobCategory category,
			ServerLevel level,
			ChunkAccess chunk,
			BlockPos pos,
			NaturalSpawner.SpawnPredicate predicate,
			NaturalSpawner.AfterSpawnCallback callback,
			CallbackInfo ci
	) {
		if (category == MobCategory.MONSTER) {
			ci.cancel();
		}
	}
}
