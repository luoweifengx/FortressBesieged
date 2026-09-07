package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import luowei.fortress_besieged.siege.SiegeSpawnedMobs;

/**
 * 围城/野外凋灵骷髅不掉凋灵骷髅头（含闪电苦力怕击杀）。
 */
@Mixin(Entity.class)
public class SiegeWitherSkullDropMixin {
	@Inject(
			method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
			at = @At("HEAD"),
			cancellable = true)
	private void fortressBesieged$skipSiegeWitherSkull(
			ServerLevel level,
			ItemStack stack,
			float yOffset,
			CallbackInfoReturnable<ItemEntity> cir) {
		if (!((Object) this instanceof WitherSkeleton skeleton)) {
			return;
		}
		if (stack == null || !stack.is(Items.WITHER_SKELETON_SKULL)) {
			return;
		}
		if (SiegeSpawnedMobs.isModSpawned(skeleton)) {
			cir.setReturnValue(null);
		}
	}
}
