package luowei.fortress_besieged.siege.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.siege.goal.SiegeAiHelper;
import luowei.fortress_besieged.siege.targeting.SiegeTargeting;

/**
 * In-memory wilderness packs. Not SavedData, not chunk tickets.
 * Unloaded / dead members drop out. A player may own several live packs.
 */
public final class WildernessPackTracker {
	private static final Map<UUID, Pack> PACKS = new HashMap<>();
	private static final Map<UUID, UUID> MOB_TO_PACK = new HashMap<>();
	private static final Map<UUID, Set<UUID>> PLAYER_TO_PACKS = new HashMap<>();
	private static final Map<UUID, UUID> FORCED_TARGET = new HashMap<>();

	private WildernessPackTracker() {
	}

	public static boolean isMember(Entity entity) {
		return entity != null && MOB_TO_PACK.containsKey(entity.getUUID());
	}

	public static boolean isMember(UUID mobId) {
		return mobId != null && MOB_TO_PACK.containsKey(mobId);
	}

	public static boolean hasLivePack(UUID playerId) {
		return livePackCount(playerId) > 0;
	}

	public static int livePackCount(UUID playerId) {
		if (playerId == null) {
			return 0;
		}
		Set<UUID> packIds = PLAYER_TO_PACKS.get(playerId);
		if (packIds == null || packIds.isEmpty()) {
			return 0;
		}
		int n = 0;
		for (UUID packId : packIds) {
			Pack pack = PACKS.get(packId);
			if (pack != null && !pack.members.isEmpty()) {
				n++;
			}
		}
		return n;
	}

	public static void register(ServerLevel level, UUID playerId, List<Mob> mobs) {
		if (level == null || playerId == null || mobs == null || mobs.isEmpty()) {
			return;
		}
		UUID packId = UUID.randomUUID();
		Pack pack = new Pack(packId, playerId, level.dimension());
		for (Mob mob : mobs) {
			if (mob == null || !mob.isAlive()) {
				continue;
			}
			UUID mobId = mob.getUUID();
			pack.members.add(mobId);
			MOB_TO_PACK.put(mobId, packId);
		}
		if (pack.members.isEmpty()) {
			return;
		}
		PACKS.put(packId, pack);
		PLAYER_TO_PACKS.computeIfAbsent(playerId, key -> new HashSet<>()).add(packId);
	}

	public static void onMobRemoved(UUID mobId) {
		if (mobId == null) {
			return;
		}
		FORCED_TARGET.remove(mobId);
		UUID packId = MOB_TO_PACK.remove(mobId);
		if (packId == null) {
			return;
		}
		SiegeAiHelper.discardVanillaBackup(mobId);
		Pack pack = PACKS.get(packId);
		if (pack == null) {
			return;
		}
		pack.members.remove(mobId);
		if (pack.members.isEmpty()) {
			PACKS.remove(packId);
			unbindPlayerPack(pack.playerId, packId);
		}
	}

	/**
	 * Drop members in this dimension that are dead or no longer loaded.
	 * Missing entities are treated as gone (wilderness packs are not persisted).
	 */
	public static void pruneLevel(ServerLevel level) {
		if (level == null) {
			return;
		}
		ResourceKey<Level> dim = level.dimension();
		List<UUID> gone = new ArrayList<>();
		for (Map.Entry<UUID, UUID> entry : MOB_TO_PACK.entrySet()) {
			UUID mobId = entry.getKey();
			Pack pack = PACKS.get(entry.getValue());
			if (pack == null) {
				gone.add(mobId);
				continue;
			}
			if (!pack.dimension.equals(dim)) {
				continue;
			}
			Entity entity = level.getEntity(mobId);
			if (entity instanceof Mob mob) {
				if (!mob.isAlive()) {
					gone.add(mobId);
				}
				continue;
			}
			gone.add(mobId);
		}
		for (UUID mobId : gone) {
			onMobRemoved(mobId);
		}
	}

	public static void clearLevel(ServerLevel level) {
		if (level == null) {
			return;
		}
		ResourceKey<Level> dim = level.dimension();
		Iterator<Map.Entry<UUID, Pack>> it = PACKS.entrySet().iterator();
		while (it.hasNext()) {
			Pack pack = it.next().getValue();
			if (!pack.dimension.equals(dim)) {
				continue;
			}
			for (UUID mobId : pack.members) {
				MOB_TO_PACK.remove(mobId);
				FORCED_TARGET.remove(mobId);
				SiegeAiHelper.discardVanillaBackup(mobId);
			}
			unbindPlayerPack(pack.playerId, pack.id);
			it.remove();
		}
	}

	public static void clearAll() {
		for (UUID mobId : MOB_TO_PACK.keySet()) {
			SiegeAiHelper.discardVanillaBackup(mobId);
		}
		PACKS.clear();
		MOB_TO_PACK.clear();
		PLAYER_TO_PACKS.clear();
		FORCED_TARGET.clear();
	}

	/** 受击强打断：记下攻击者，野外追击 Goal 优先打这个人。 */
	public static void onMobDamaged(Mob mob, Entity attacker) {
		if (mob == null || !isMember(mob)) {
			return;
		}
		if (!(attacker instanceof LivingEntity living) || !SiegeTargeting.isValidAttackTarget(living)) {
			return;
		}
		if (living.level() != mob.level()) {
			return;
		}
		FORCED_TARGET.put(mob.getUUID(), living.getUUID());
		mob.setTarget(living);
	}

	public static LivingEntity resolveForcedTarget(Mob mob) {
		if (mob == null || !(mob.level() instanceof ServerLevel level)) {
			return null;
		}
		UUID id = FORCED_TARGET.get(mob.getUUID());
		if (id == null) {
			return null;
		}
		Entity entity = level.getEntity(id);
		if (entity instanceof LivingEntity living && living.isAlive() && SiegeTargeting.isValidAttackTarget(living)) {
			if (mob.distanceToSqr(living) > HellSpawnConfig.AGGRO_LEASH_BLOCKS * HellSpawnConfig.AGGRO_LEASH_BLOCKS) {
				FORCED_TARGET.remove(mob.getUUID());
				return null;
			}
			return living;
		}
		FORCED_TARGET.remove(mob.getUUID());
		return null;
	}

	public static void clearForcedTarget(UUID mobId) {
		if (mobId != null) {
			FORCED_TARGET.remove(mobId);
		}
	}

	private static void unbindPlayerPack(UUID playerId, UUID packId) {
		Set<UUID> packIds = PLAYER_TO_PACKS.get(playerId);
		if (packIds == null) {
			return;
		}
		packIds.remove(packId);
		if (packIds.isEmpty()) {
			PLAYER_TO_PACKS.remove(playerId);
		}
	}

	private static final class Pack {
		final UUID id;
		final UUID playerId;
		final ResourceKey<Level> dimension;
		final List<UUID> members = new ArrayList<>();

		Pack(UUID id, UUID playerId, ResourceKey<Level> dimension) {
			this.id = id;
			this.playerId = playerId;
			this.dimension = dimension;
		}
	}
}
