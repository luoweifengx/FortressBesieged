package luowei.fortress_besieged.siege.spawn;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

/**
 * Vanilla world difficulty as the global siege/wilderness dial.
 * Peaceful: no natural rolls. Easy/Normal share delayed unlocks; Hard keeps
 * current unlocks except host (一群) which needs iron armor.
 */
public final class SiegeDifficulty {
	private SiegeDifficulty() {
	}

	public static Difficulty of(ServerLevel level) {
		return level == null ? Difficulty.HARD : level.getDifficulty();
	}

	public static boolean isPeaceful(ServerLevel level) {
		return of(level) == Difficulty.PEACEFUL;
	}

	public static boolean isHard(Difficulty difficulty) {
		return difficulty == Difficulty.HARD;
	}

	/** Easy and Normal: small at iron, host at diamond, legion at 终末. */
	public static boolean usesRelaxedUnlocks(Difficulty difficulty) {
		return difficulty == Difficulty.EASY || difficulty == Difficulty.NORMAL;
	}

	public static int intervalMultiplier(Difficulty difficulty) {
		return switch (difficulty == null ? Difficulty.HARD : difficulty) {
			case PEACEFUL -> 0;
			case EASY -> 3;
			case NORMAL -> 2;
			case HARD -> 1;
		};
	}

	public static int scaledInterval(int baseTicks, Difficulty difficulty) {
		int multiplier = intervalMultiplier(difficulty);
		if (multiplier <= 0) {
			return Integer.MAX_VALUE;
		}
		return Math.max(1, baseTicks * multiplier);
	}

	public static float equipmentDropMultiplier(Difficulty difficulty) {
		return switch (difficulty == null ? Difficulty.HARD : difficulty) {
			case PEACEFUL -> 0.0F;
			case EASY -> 0.3F;
			case NORMAL -> 0.4F;
			case HARD -> 0.8F;
		};
	}
}
