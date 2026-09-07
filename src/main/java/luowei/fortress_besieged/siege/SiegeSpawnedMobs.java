package luowei.fortress_besieged.siege;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import luowei.fortress_besieged.siege.goal.SiegeAiHelper;
import luowei.fortress_besieged.siege.spawn.WildernessPackTracker;

/**
 * True for siege-cluster members and wilderness-pack members (mod-spawned), not vanilla spawners.
 */
public final class SiegeSpawnedMobs {
	private SiegeSpawnedMobs() {
	}

	public static boolean isModSpawned(Entity entity) {
		if (entity == null) {
			return false;
		}
		if (entity instanceof Mob mob) {
			if (SiegeClusterManager.getClusterForMob(mob) != null) {
				return true;
			}
			if (SiegeAiHelper.hasSiegeGoals(mob) || SiegeAiHelper.hasWildernessGoals(mob)) {
				return true;
			}
		}
		return WildernessPackTracker.isMember(entity);
	}
}
