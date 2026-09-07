package luowei.fortress_besieged.siege.spawn;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.siege.spawn.SiegeProgression.SpawnWindow;

/**
 * Schedule clock: nether uses overworld {@code dayTime} so quiet hours line up.
 * Nether sky time is fixed and is not used for windows.
 */
public final class SiegeDayClock {
	private SiegeDayClock() {
	}

	public static boolean isNether(ServerLevel level) {
		return level != null && Level.NETHER.equals(level.dimension());
	}

	/**
	 * Day-time used for siege windows (including nether siege, which reads overworld).
	 * Nether sky time is fixed and is not used for windows.
	 */
	public static long dayTime(ServerLevel level) {
		if (level == null) {
			return 0L;
		}
		if (isNether(level)) {
			return level.getServer().overworld().getDayTime();
		}
		return level.getDayTime();
	}

	public static long timeOfDay(ServerLevel level) {
		return Math.floorMod(dayTime(level), 24000L);
	}

	public static long dayIndex(ServerLevel level) {
		return Math.floorDiv(dayTime(level), 24000L);
	}

	public static boolean inWindow(SpawnWindow window, long timeOfDay) {
		if (window == null) {
			return false;
		}
		return timeOfDay >= window.windowStart() && timeOfDay < window.windowEnd();
	}

	public static SpawnWindow defaultNetherDueWindow(float chance) {
		return new SpawnWindow(0, 24000, chance);
	}
}
