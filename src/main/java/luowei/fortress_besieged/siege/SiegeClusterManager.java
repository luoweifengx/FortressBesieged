package luowei.fortress_besieged.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * 内存态集群管理器：创建、tick、受击路由。
 */
public final class SiegeClusterManager {
	private static final Map<UUID, SiegeCluster> CLUSTERS = new HashMap<>();
	private static final Map<UUID, UUID> MOB_TO_CLUSTER = new HashMap<>();

	private SiegeClusterManager() {
	}

	/**
	 * 用给定怪物列表在指定锚点区块创建集群（起始 ASSEMBLE）。
	 *
	 * @return 新建集群，若 mobs 为空则返回 null
	 */
	public static SiegeCluster createCluster(ServerLevel level, ChunkPos anchorChunk, List<Mob> mobs) {
		List<Mob> eligible = new ArrayList<>();
		for (Mob mob : mobs) {
			if (mob == null || !mob.isAlive() || MOB_TO_CLUSTER.containsKey(mob.getUUID())) {
				continue;
			}
			eligible.add(mob);
		}
		if (eligible.isEmpty()) {
			return null;
		}
		SiegeCluster cluster = new SiegeCluster(level, anchorChunk, eligible);
		CLUSTERS.put(cluster.getId(), cluster);
		return cluster;
	}

	/**
	 * 收集锚点区块内（可选半径）的敌对怪物并创建测试集群。
	 */
	public static SiegeCluster createClusterFromNearbyMonsters(ServerLevel level, ChunkPos anchorChunk, int chunkRadius) {
		int minX = (anchorChunk.x - chunkRadius) << 4;
		int maxX = ((anchorChunk.x + chunkRadius) << 4) + 16;
		int minZ = (anchorChunk.z - chunkRadius) << 4;
		int maxZ = ((anchorChunk.z + chunkRadius) << 4) + 16;
		AABB box = new AABB(minX, level.getMinY(), minZ, maxX, level.getMaxY() + 1, maxZ);
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box, mob ->
				mob.isAlive()
						&& mob.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
						&& !MOB_TO_CLUSTER.containsKey(mob.getUUID()));
		return createCluster(level, anchorChunk, mobs);
	}

	public static void bindMob(Mob mob, SiegeCluster cluster) {
		MOB_TO_CLUSTER.put(mob.getUUID(), cluster.getId());
	}

	public static void unbindMob(Mob mob) {
		if (mob != null) {
			MOB_TO_CLUSTER.remove(mob.getUUID());
		}
	}

	public static void unbindMobId(UUID mobId) {
		MOB_TO_CLUSTER.remove(mobId);
	}

	public static SiegeCluster getClusterForMob(Mob mob) {
		UUID clusterId = MOB_TO_CLUSTER.get(mob.getUUID());
		return clusterId == null ? null : CLUSTERS.get(clusterId);
	}

	public static void onMobDamaged(Mob mob, Entity attacker) {
		SiegeCluster cluster = getClusterForMob(mob);
		if (cluster != null) {
			cluster.onMobDamaged(mob, attacker);
		}
	}

	public static void tickLevel(ServerLevel level) {
		Iterator<Map.Entry<UUID, SiegeCluster>> it = CLUSTERS.entrySet().iterator();
		while (it.hasNext()) {
			SiegeCluster cluster = it.next().getValue();
			if (cluster.isDisbanded()) {
				it.remove();
				continue;
			}
			if (cluster.getLevel() != level) {
				continue;
			}
			cluster.tick();
			if (cluster.isDisbanded()) {
				it.remove();
			}
		}
	}

	public static int clusterCount() {
		return CLUSTERS.size();
	}

	public static void clearLevel(ServerLevel level) {
		Iterator<Map.Entry<UUID, SiegeCluster>> it = CLUSTERS.entrySet().iterator();
		while (it.hasNext()) {
			SiegeCluster cluster = it.next().getValue();
			if (cluster.getLevel() != level) {
				continue;
			}
			cluster.forceDisband();
			UUID clusterId = cluster.getId();
			MOB_TO_CLUSTER.entrySet().removeIf(e -> clusterId.equals(e.getValue()));
			it.remove();
		}
	}

	public static void clearAll() {
		CLUSTERS.clear();
		MOB_TO_CLUSTER.clear();
	}
}
