package luowei.fortress_besieged.siege.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla-like per-tick mining progress for siege block breaks (no haste / water / airborne).
 */
public final class SiegeMining {
	private SiegeMining() {
	}

	public static float tickProgress(Mob mob, BlockState state, Level level, BlockPos pos) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F) {
			return 0.0F;
		}
		if (hardness == 0.0F) {
			return 1.0F;
		}
		ItemStack stack = mob.getMainHandItem();
		float speed = stack.getDestroySpeed(state);
		if (speed > 1.0F) {
			int n = EnchantmentHelper.getEnchantmentLevel(
					mob.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY),
					mob);
			if (n > 0) {
				speed += n * n + 1;
			}
		}
		return speed / hardness / (stack.isCorrectToolForDrops(state) ? 30.0F : 100.0F);
	}

	public static int crackStage(float accumulated) {
		return Mth.clamp((int) (accumulated * 10.0F), 0, 9);
	}
}
