package luowei.fortress_besieged.siege.targeting;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;

/**
 * 围城「敌对/可攻击」判定：对玩家友善的单位。
 *
 * <p>选用显式白名单 + 玩家归属宠物，避免把中立动物全部算进去：
 * Player、IronGolem、SnowGolem、Villager、已驯服且主人是玩家的 {@link OwnableEntity}
 *（含 Wolf）。{@link MobCategory#MONSTER} 永不视为友善目标。
 */
public final class SiegeTargeting {
	private SiegeTargeting() {
	}

	public static boolean isPlayerFriendly(LivingEntity entity) {
		if (entity == null || !entity.isAlive()) {
			return false;
		}
		if (entity instanceof Player) {
			return true;
		}
		if (entity.getType().getCategory() == MobCategory.MONSTER) {
			return false;
		}
		if (entity instanceof IronGolem || entity instanceof SnowGolem || entity instanceof Villager) {
			return true;
		}
		if (entity instanceof Wolf wolf) {
			return wolf.isTame() && wolf.getOwner() instanceof Player;
		}
		if (entity instanceof OwnableEntity ownable) {
			return ownable.getOwner() instanceof Player;
		}
		return false;
	}

	/**
	 * 可作为围城/野外攻击目标：玩家阵营，且跳过创造、旁观。
	 */
	public static boolean isValidAttackTarget(LivingEntity entity) {
		if (!isPlayerFriendly(entity)) {
			return false;
		}
		if (entity instanceof Player player) {
			return !player.isSpectator() && !player.isCreative();
		}
		return true;
	}
}
