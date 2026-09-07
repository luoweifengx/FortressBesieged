package luowei.fortress_besieged.siege.goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.mixin.MobGoalSelectorAccessor;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * 为集群怪物安装专用 Goal：卸掉原版索敌/近战，保留围城 Goal + 游荡。
 */
public final class SiegeAiHelper {
	/** 发光时长：尽可能长（近似无限）；解散时清除。 */
	private static final int GLOWING_DURATION_TICKS = MobEffectInstance.INFINITE_DURATION;

	private static final Map<UUID, List<SavedVanillaGoal>> VANILLA_BACKUPS = new HashMap<>();

	private record SavedVanillaGoal(boolean fromTargetSelector, int priority, Goal goal) {
	}

	private SiegeAiHelper() {
	}

	public static boolean hasSiegeGoals(Mob mob) {
		if (mob == null) {
			return false;
		}
		for (WrappedGoal wrapped : goals(mob).getAvailableGoals()) {
			if (isSiegeGoalInstance(wrapped.getGoal())) {
				return true;
			}
		}
		return false;
	}

	public static void install(Mob mob, SiegeCluster cluster) {
		if (hasSiegeGoals(mob)) {
			applyGlowing(mob);
			SiegeClusterManager.bindMob(mob, cluster);
			angerZombifiedPiglin(mob);
			return;
		}
		GoalSelector goals = goals(mob);
		GoalSelector targets = targets(mob);
		backupAndStripVanillaCombat(mob, goals, targets);
		// 卸掉原有游荡后按固定优先级加回，避免与围城 Goal 抢优先级
		removeWanderGoals(goals);
		boolean ghast = isGhast(mob);
		if (!ghast) {
			goals.addGoal(1, new SiegeDestroyBlockGoal(mob, cluster));
		}
		goals.addGoal(2, new SiegeAttackGoal(mob, cluster));
		if (!ghast) {
			goals.addGoal(3, new SiegeIdleReturnGoal(mob, cluster));
		}
		goals.addGoal(4, new SiegeIdleDecideGoal(mob, cluster));
		if (mob instanceof PathfinderMob pathfinder) {
			goals.addGoal(7, new WaterAvoidingRandomStrollGoal(pathfinder, 1.0D));
		}
		mob.setTarget(null);
		applyGlowing(mob);
		SiegeClusterManager.bindMob(mob, cluster);
		angerZombifiedPiglin(mob);
		FortressBesieged.LOGGER.debug(
				"[siege] install goals+glowing on {} uuid={} cluster={}",
				mob.getType().getDescriptionId(),
				mob.getUUID(),
				cluster.getId());
	}

	/**
	 * Wilderness packs: chase player-side targets or wander. No DESTROY / ASSAULT /
	 * idle-dice-to-mine, no glowing, no cluster bind.
	 */
	public static void installWilderness(Mob mob) {
		if (mob == null || hasWildernessGoals(mob) || hasSiegeGoals(mob)) {
			return;
		}
		GoalSelector goals = goals(mob);
		GoalSelector targets = targets(mob);
		backupAndStripVanillaCombat(mob, goals, targets);
		removeWanderGoals(goals);
		goals.addGoal(2, new WildernessChaseGoal(mob));
		if (mob instanceof PathfinderMob pathfinder) {
			goals.addGoal(7, new WaterAvoidingRandomStrollGoal(pathfinder, 1.0D));
		}
		mob.setTarget(null);
		angerZombifiedPiglin(mob);
	}

	public static boolean hasWildernessGoals(Mob mob) {
		if (mob == null) {
			return false;
		}
		for (WrappedGoal wrapped : goals(mob).getAvailableGoals()) {
			if (wrapped.getGoal() instanceof WildernessChaseGoal) {
				return true;
			}
		}
		return false;
	}

	public static void uninstall(Mob mob) {
		if (mob == null) {
			return;
		}
		goals(mob).removeAllGoals(SiegeAiHelper::isSiegeGoalInstance);
		restoreVanillaCombat(mob);
		clearGlowing(mob);
		SiegeClusterManager.unbindMob(mob);
	}

	/** 解散后保留攻击倾向：清掉集群 Goal，并恢复原版近战/索敌。 */
	public static void switchToVanillaAttack(Mob mob) {
		uninstall(mob);
	}

	/** 单位已死、无需恢复 Goal 时丢掉备份。 */
	public static void discardVanillaBackup(UUID mobId) {
		if (mobId != null) {
			VANILLA_BACKUPS.remove(mobId);
		}
	}

	public static void applyGlowing(Mob mob) {
		if (mob == null) {
			return;
		}
		mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOWING_DURATION_TICKS, 0, false, false));
	}

	public static void clearGlowing(Mob mob) {
		if (mob == null) {
			return;
		}
		mob.removeEffect(MobEffects.GLOWING);
	}

	private static GoalSelector goals(Mob mob) {
		return ((MobGoalSelectorAccessor) mob).fortressBesieged$getGoalSelector();
	}

	private static GoalSelector targets(Mob mob) {
		return ((MobGoalSelectorAccessor) mob).fortressBesieged$getTargetSelector();
	}

	private static void backupAndStripVanillaCombat(Mob mob, GoalSelector goals, GoalSelector targets) {
		List<SavedVanillaGoal> saved = new ArrayList<>();
		saved.addAll(takeGoals(targets, goal -> true, true));
		saved.addAll(takeGoals(goals, goal -> goal instanceof MeleeAttackGoal, false));
		VANILLA_BACKUPS.put(mob.getUUID(), saved);
	}

	private static void restoreVanillaCombat(Mob mob) {
		List<SavedVanillaGoal> saved = VANILLA_BACKUPS.remove(mob.getUUID());
		if (saved == null || saved.isEmpty()) {
			return;
		}
		GoalSelector goals = goals(mob);
		GoalSelector targets = targets(mob);
		for (SavedVanillaGoal entry : saved) {
			(entry.fromTargetSelector() ? targets : goals).addGoal(entry.priority(), entry.goal());
		}
	}

	private static List<SavedVanillaGoal> takeGoals(
			GoalSelector selector, Predicate<Goal> predicate, boolean fromTargetSelector) {
		List<SavedVanillaGoal> saved = new ArrayList<>();
		for (WrappedGoal wrapped : List.copyOf(selector.getAvailableGoals())) {
			Goal goal = wrapped.getGoal();
			if (predicate.test(goal)) {
				saved.add(new SavedVanillaGoal(fromTargetSelector, wrapped.getPriority(), goal));
			}
		}
		selector.removeAllGoals(predicate);
		return saved;
	}

	private static void removeWanderGoals(GoalSelector goals) {
		goals.removeAllGoals(goal -> goal instanceof RandomStrollGoal);
	}

	static boolean isGhast(Mob mob) {
		return mob instanceof Ghast;
	}

	/**
	 * 持弓骷髅（含流浪者/沼骸，不含凋灵骷髅）：不抢 MOVE/LOOK，保留原版 {@code RangedBowAttackGoal}。
	 */
	static boolean isBowSkeleton(Mob mob) {
		return mob instanceof AbstractSkeleton
				&& !(mob instanceof WitherSkeleton)
				&& mob.isHolding(Items.BOW);
	}

	/** 恶魂火球 / 持弓骷髅射箭：本模组 Goal 只负责 {@code setTarget}。 */
	static boolean leaveVanillaRanged(Mob mob) {
		return isGhast(mob) || isBowSkeleton(mob);
	}

	/**
	 * 本模组刷出的僵尸猪灵立刻进入持久仇恨，不必先挨打。
	 */
	public static void angerZombifiedPiglin(Mob mob) {
		if (!(mob instanceof ZombifiedPiglin piglin) || !(mob.level() instanceof ServerLevel level)) {
			return;
		}
		piglin.startPersistentAngerTimer();
		piglin.setRemainingPersistentAngerTime(Integer.MAX_VALUE);
		Player nearest = level.getNearestPlayer(piglin, 128.0D);
		if (nearest != null && SiegeTargeting.isValidAttackTarget(nearest)) {
			piglin.setPersistentAngerTarget(nearest.getUUID());
			piglin.setTarget(nearest);
		}
	}

	private static boolean isSiegeGoalInstance(Goal goal) {
		return goal instanceof SiegeDestroyBlockGoal
				|| goal instanceof SiegeAttackGoal
				|| goal instanceof SiegeIdleReturnGoal
				|| goal instanceof SiegeIdleDecideGoal;
	}

	static EnumSet<Goal.Flag> moveLookFlags() {
		return EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK);
	}

	/** 通知该怪 DESTROY 执行队列：路上 BREAK pos 改成 WALK。 */
	public static boolean rewriteDestroyRouteBreakToWalk(Mob mob, BlockPos pos) {
		if (mob == null || pos == null) {
			return false;
		}
		for (WrappedGoal wrapped : goals(mob).getAvailableGoals()) {
			if (wrapped.getGoal() instanceof SiegeDestroyBlockGoal destroyGoal) {
				return destroyGoal.rewriteBreakToWalk(pos);
			}
		}
		return false;
	}
}
