package luowei.fortress_besieged.siege.goal;

import net.minecraft.core.BlockPos;

/**
 * 接近队列中的单步：走到一格、破坏一格、或放置圆石。
 */
public record BreachAction(Type type, BlockPos pos) {
	public enum Type {
		WALK, BREAK, PLACE
	}

	public BreachAction {
		pos = pos.immutable();
	}
}
