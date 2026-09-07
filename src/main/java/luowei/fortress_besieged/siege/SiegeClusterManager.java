package luowei.fortress_besieged.siege;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeClusterSavedData.ClusterSnapshot;
import luowei.fortress_besieged.siege.spawn.SiegeClusterConfig;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate;
import luowei.fortress_besieged.siege.spawn.SiegeClusterTemplate.LootSpec;

/**
 * 集群管理器：创建、tick、受击路由、维度存档读写。
 */
public final class SiegeClusterManager {
	private static final Map<UUID, SiegeCluster> CLUSTERS = new HashMap<>();
	private static final Map<UUID, UUID> MOB_TO_CLUSTER = new HashMap<>();
	private static final Set<ResourceKey<Level>> LOADED_LEVELS = new HashSet<>();

	private SiegeClusterManager() {
	}

	/**
	 * 用给定怪物列表在指定锚点区块创建集群（起始 ASSEMBLE）。
	 *
	 * @return 新建集群，若 mobs 为空则返回 null
	 */
	public static SiegeCluster createCluster(ServerLevel level, ChunkPos anchorChunk, List<Mob> mobs) {
		ensureLoaded(level);
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
		syncSave(level);
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

	public static void onMobLoaded(Mob mob) {
		if (mob == null) {
			return;
		}
		UUID clusterId = MOB_TO_CLUSTER.get(mob.getUUID());
		SiegeCluster cluster = clusterId == null ? null : CLUSTERS.get(clusterId);
		if (cluster == null) {
			for (SiegeCluster candidate : CLUSTERS.values()) {
				if (candidate.getLevel() == mob.level() && candidate.getUnit(mob.getUUID()) != null) {
					cluster = candidate;
					break;
				}
			}
		}
		if (cluster != null) {
			cluster.onUnitEntityLoaded(mob);
		}
	}

	public static void onMobDeath(Mob mob) {
		if (mob == null) {
			return;
		}
		SiegeCluster cluster = getClusterForMob(mob);
		if (cluster == null) {
			return;
		}
		dropClusterLoot(mob, cluster);
		cluster.removeDeadUnit(mob.getUUID());
		syncSave(cluster.getLevel());
	}

	private static void dropClusterLoot(Mob mob, SiegeCluster cluster) {
		String templateId = cluster.getTemplateId();
		if (templateId == null || templateId.isBlank()) {
			return;
		}
		SiegeClusterTemplate template = SiegeClusterConfig.siege().getTemplate(templateId).orElse(null);
		if (template == null) {
			return;
		}
		LootSpec loot = template.loot();
		if (loot == null || !loot.enabled()) {
			return;
		}
		if (mob.getRandom().nextDouble() >= loot.chance()) {
			return;
		}
		int min = loot.min();
		int max = loot.max();
		int count = min + mob.getRandom().nextInt(max - min + 1);
		if (count <= 0) {
			return;
		}
		Item item = resolveLootItem(loot.item());
		if (item == Items.AIR) {
			return;
		}
		if (!(mob.level() instanceof ServerLevel level)) {
			return;
		}
		int remaining = count;
		int maxStack = Math.max(1, new ItemStack(item).getMaxStackSize());
		while (remaining > 0) {
			int n = Math.min(remaining, maxStack);
			mob.spawnAtLocation(level, new ItemStack(item, n));
			remaining -= n;
		}
	}

	private static Item resolveLootItem(String raw) {
		if (raw == null || raw.isBlank()) {
			return Items.AIR;
		}
		String id = raw.contains(":") ? raw : "minecraft:" + raw;
		ResourceLocation loc = ResourceLocation.tryParse(id);
		if (loc == null) {
			return Items.AIR;
		}
		var holder = BuiltInRegistries.ITEM.get(loc);
		return holder.isEmpty() ? Items.AIR : holder.get().value();
	}

	public static void tickLevel(ServerLevel level) {
		ensureLoaded(level);
		Iterator<Map.Entry<UUID, SiegeCluster>> it = CLUSTERS.entrySet().iterator();
		boolean changed = false;
		while (it.hasNext()) {
			SiegeCluster cluster = it.next().getValue();
			if (cluster.isDisbanded()) {
				it.remove();
				changed = true;
				continue;
			}
			if (cluster.getLevel() != level) {
				continue;
			}
			cluster.tick();
			if (cluster.isDisbanded()) {
				it.remove();
				changed = true;
			}
		}
		if (changed || hasClusterIn(level)) {
			syncSave(level);
		}
	}

	public static int clusterCount() {
		return CLUSTERS.size();
	}

	public static Collection<SiegeCluster> clusters() {
		return List.copyOf(CLUSTERS.values());
	}

	/** 命令解散：卸 Goal、摘票、从存档删除。 */
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
		syncSave(level);
	}

	/** 维度/服务器就绪后读档，恢复集群并重新上 7×7 票。 */
	public static void loadLevel(ServerLevel level) {
		if (!LOADED_LEVELS.add(level.dimension())) {
			return;
		}
		SiegeClusterSavedData data = SiegeClusterSavedData.get(level);
		int restored = 0;
		for (ClusterSnapshot snapshot : data.clusters()) {
			if (CLUSTERS.containsKey(snapshot.id())) {
				continue;
			}
			if (snapshot.dimension() != null && !snapshot.dimension().equals(level.dimension().location())) {
				FortressBesieged.LOGGER.warn(
						"[siege] skip saved cluster {} dimension mismatch saved={} level={}",
						snapshot.id(),
						snapshot.dimension(),
						level.dimension().location());
				continue;
			}
			SiegeCluster cluster = new SiegeCluster(level, snapshot);
			CLUSTERS.put(cluster.getId(), cluster);
			for (UUID mobId : cluster.unitIds()) {
				MOB_TO_CLUSTER.put(mobId, cluster.getId());
			}
			cluster.tryBindLoadedUnits();
			restored++;
		}
		if (restored > 0) {
			FortressBesieged.LOGGER.info(
					"[siege] restored {} cluster(s) in {}",
					restored,
					level.dimension().location());
		}
	}

	/**
	 * 维度卸载 / 停服：先把当前状态写入 SavedData，再摘会话票并清内存。
	 * 不 {@link SiegeCluster#forceDisband}，以免把存档抹空。
	 */
	public static void unloadLevel(ServerLevel level) {
		// 内存里还有集群才写档；二次 unload（STOPPING 后再 UNLOAD）不得用空表覆盖已保存快照。
		if (hasClusterIn(level)) {
			syncSave(level);
		}
		Iterator<Map.Entry<UUID, SiegeCluster>> it = CLUSTERS.entrySet().iterator();
		while (it.hasNext()) {
			SiegeCluster cluster = it.next().getValue();
			if (cluster.getLevel() != level) {
				continue;
			}
			cluster.dropTickets();
			UUID clusterId = cluster.getId();
			MOB_TO_CLUSTER.entrySet().removeIf(e -> clusterId.equals(e.getValue()));
			it.remove();
		}
		SiegeChunkTickets.releaseAll(level);
		LOADED_LEVELS.remove(level.dimension());
	}

	public static void syncSave(ServerLevel level) {
		if (level == null) {
			return;
		}
		List<ClusterSnapshot> snaps = new ArrayList<>();
		for (SiegeCluster cluster : CLUSTERS.values()) {
			if (cluster.getLevel() != level || cluster.isDisbanded()) {
				continue;
			}
			snaps.add(cluster.toSnapshot());
		}
		SiegeClusterSavedData.get(level).replace(snaps);
	}

	public static void clearAll() {
		SiegeChunkTickets.releaseAllLevels();
		CLUSTERS.clear();
		MOB_TO_CLUSTER.clear();
		LOADED_LEVELS.clear();
	}

	private static void ensureLoaded(ServerLevel level) {
		if (!LOADED_LEVELS.contains(level.dimension())) {
			loadLevel(level);
		}
	}

	private static boolean hasClusterIn(ServerLevel level) {
		for (SiegeCluster cluster : CLUSTERS.values()) {
			if (cluster.getLevel() == level && !cluster.isDisbanded()) {
				return true;
			}
		}
		return false;
	}
}
