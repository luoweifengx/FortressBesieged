package luowei.fortress_besieged.siege;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import luowei.fortress_besieged.FortressBesieged;

/**
 * 会话内 7×7 实体 tick 区块票。不是 {@code /forceload} / {@link TicketType#FORCED}，
 * 不写入 {@code chunks.dat}；崩溃或漏卸也不会变成永久强加载。
 *
 * <p>1.21.5 {@code addTicketWithRadius} 的票等级为 {@code 33 - radius}
 *（{@code ChunkLevel.byStatus(FULL) - radius}）。实体 tick 要求等级 {@code <= 31}，
 * 且票会按切比雪夫距离 +1 扩散，因此实体 tick 半径 = {@code radius - 2}。
 * 要盖住中心 7×7（切比雪夫 3）须 {@code radius = 5}；外围会多一圈弱加载，可接受。
 */
public final class SiegeChunkTickets {
	/** 目标实体 tick 切比雪夫半径（7×7）。 */
	public static final int ENTITY_TICK_CHEBYSHEV = 3;
	/**
	 * 交给 {@code addTicketWithRadius} 的半径：33 - 5 = 28，距离 3 处为 31（仍实体 tick）。
	 */
	public static final int TICKET_RADIUS = ENTITY_TICK_CHEBYSHEV + 2;

	/**
	 * 非持久、无超时、加载+模拟。与 {@link TicketType#FORCED}（persist=true）不同。
	 * 注册失败时仍用同一实例做 add/remove（原版按引用匹配票）。
	 */
	public static final TicketType TYPE = createType();

	/** 每个维度：票中心区块 → 集群引用计数（重叠集群卸一个不能拆另一个）。 */
	private static final Map<ServerLevel, Map<ChunkPos, Integer>> REFS = new IdentityHashMap<>();

	private SiegeChunkTickets() {
	}

	public static void bootstrap() {
		// 触发 TYPE 静态初始化
	}

	public static void retain(ServerLevel level, ChunkPos center) {
		if (level == null || center == null) {
			return;
		}
		Map<ChunkPos, Integer> map = REFS.computeIfAbsent(level, l -> new HashMap<>());
		int next = map.merge(center, 1, Integer::sum);
		if (next == 1) {
			level.getChunkSource().addTicketWithRadius(TYPE, center, TICKET_RADIUS);
			FortressBesieged.LOGGER.debug(
					"[siege] add 7x7 ticket center={} radius={} (entity-tick chebyshev {})",
					center,
					TICKET_RADIUS,
					ENTITY_TICK_CHEBYSHEV);
		}
	}

	public static void release(ServerLevel level, ChunkPos center) {
		if (level == null || center == null) {
			return;
		}
		Map<ChunkPos, Integer> map = REFS.get(level);
		if (map == null) {
			return;
		}
		Integer current = map.get(center);
		if (current == null) {
			return;
		}
		if (current <= 1) {
			map.remove(center);
			level.getChunkSource().removeTicketWithRadius(TYPE, center, TICKET_RADIUS);
			FortressBesieged.LOGGER.debug("[siege] remove 7x7 ticket center={}", center);
		} else {
			map.put(center, current - 1);
		}
		if (map.isEmpty()) {
			REFS.remove(level);
		}
	}

	/** 维度卸载 / 停服：摘掉本维度全部会话票。 */
	public static void releaseAll(ServerLevel level) {
		if (level == null) {
			return;
		}
		Map<ChunkPos, Integer> map = REFS.remove(level);
		if (map == null) {
			return;
		}
		for (ChunkPos center : map.keySet()) {
			level.getChunkSource().removeTicketWithRadius(TYPE, center, TICKET_RADIUS);
		}
	}

	public static void releaseAllLevels() {
		Iterator<ServerLevel> it = REFS.keySet().iterator();
		while (it.hasNext()) {
			ServerLevel level = it.next();
			Map<ChunkPos, Integer> map = REFS.get(level);
			if (map != null) {
				for (ChunkPos center : map.keySet()) {
					level.getChunkSource().removeTicketWithRadius(TYPE, center, TICKET_RADIUS);
				}
			}
			it.remove();
		}
	}

	private static TicketType createType() {
		TicketType created = new TicketType(
				TicketType.NO_TIMEOUT,
				false,
				TicketType.TicketUse.LOADING_AND_SIMULATION);
		try {
			return Registry.register(
					BuiltInRegistries.TICKET_TYPE,
					FortressBesieged.id("cluster"),
					created);
		} catch (RuntimeException e) {
			FortressBesieged.LOGGER.warn(
					"[siege] TicketType registry frozen; using session-only ticket instance ({})",
					e.getMessage());
			return created;
		}
	}
}
