package luowei.fortress_besieged.siege.spawn;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

/**
 * Vanilla world difficulty as the global siege/wilderness dial.
 * Peaceful: no natural rolls. Siege cadence and wilderness away-timer
 * are unscaled. Pack counts use template {@code countByDifficulty}.
 * Stage / tier unlocks use the Normal rules on every non-peaceful difficulty.
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

	/** All non-peaceful: small at iron, host at diamond, legion at diamond or demon. */
	public static boolean usesRelaxedUnlocks(Difficulty difficulty) {
		return difficulty != Difficulty.PEACEFUL;
	}

	/** Legacy interval scale; siege and wilderness cadences are unscaled. */
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
			case EASY -> 0.6F;
			case NORMAL -> 0.7F;
			case HARD -> 0.9F;
		};
	}
}
