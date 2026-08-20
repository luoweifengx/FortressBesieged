package luowei.fortress_besieged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

@Mixin(Mob.class)
public interface MobGoalSelectorAccessor {
	@Accessor("goalSelector")
	GoalSelector fortressBesieged$getGoalSelector();

	@Accessor("targetSelector")
	GoalSelector fortressBesieged$getTargetSelector();
}
