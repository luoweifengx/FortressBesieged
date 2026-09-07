package luowei.fortress_besieged.siege.spawn;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.SiegeCluster;
import luowei.fortress_besieged.siege.SiegeClusterState;

/**
 * Structured spawn / cluster-lifecycle logs.
 * INFO = cluster spawn, state change, disband; DEBUG = pick attempts, timings, skip reasons.
 * Logger name: {@code fortress-besieged}.
 */
public final class SiegeSpawnMonitor {
	private static final Logger LOGGER = FortressBesieged.LOGGER;
	/** Cycle totals at or above this (ms) are promoted to WARN. */
	public static final long SLOW_CYCLE_MS = 50L;

	private SiegeSpawnMonitor() {
	}

	public static long nanos() {
		return System.nanoTime();
	}

	public static long msSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	public static boolean isDebug() {
		return LOGGER.isDebugEnabled();
	}

	public static void info(String format, Object... args) {
		LOGGER.info("[siege spawn] " + format, args);
	}

	public static void debug(String format, Object... args) {
		LOGGER.debug("[siege spawn] " + format, args);
	}

	public static void warn(String format, Object... args) {
		LOGGER.warn("[siege spawn] " + format, args);
	}

	public static void timing(String stage, long startNanos) {
		LOGGER.debug("[siege spawn] timing stage={} ms={}", stage, msSince(startNanos));
	}

	public static void timing(String stage, long startNanos, String extraFmt, Object... extra) {
		if (!LOGGER.isDebugEnabled()) {
			return;
		}
		Object[] args = new Object[extra.length + 2];
		args[0] = stage;
		args[1] = msSince(startNanos);
		System.arraycopy(extra, 0, args, 2, extra.length);
		LOGGER.debug("[siege spawn] timing stage={} ms={} " + extraFmt, args);
	}

	public static void cycle(boolean spawned, long startNanos, String extraFmt, Object... extra) {
		long ms = msSince(startNanos);
		Object[] args = new Object[extra.length + 1];
		args[0] = ms;
		System.arraycopy(extra, 0, args, 1, extra.length);
		String format = "[siege spawn] cycle ms={} " + extraFmt;
		if (ms >= SLOW_CYCLE_MS) {
			LOGGER.warn(format, args);
		} else {
			LOGGER.debug(format, args);
		}
	}

	public static String target(MinecraftServer server, UUID id) {
		if (id == null) {
			return "null";
		}
		String name = resolveName(server, id);
		return name == null ? id.toString() : name + "/" + id;
	}

	public static String target(ServerLevel level, UUID id) {
		return target(level == null ? null : level.getServer(), id);
	}

	public static String chunk(ChunkPos pos) {
		return pos == null ? "null" : pos.x + "," + pos.z;
	}

	public static String blockPos(ServerLevel level, ChunkPos chunk) {
		if (level == null || chunk == null) {
			return "null";
		}
		int x = (chunk.x << 4) + 8;
		int z = (chunk.z << 4) + 8;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return x + "," + y + "," + z;
	}

	public static String dim(ServerLevel level) {
		return level == null ? "null" : level.dimension().location().toString();
	}

	/**
	 * 8-way compass from {@code from} toward {@code to} in chunk space.
	 * Minecraft: +X east, +Z south.
	 */
	public static String attackDir(ChunkPos from, ChunkPos to) {
		if (from == null || to == null) {
			return "none";
		}
		int dx = to.x - from.x;
		int dz = to.z - from.z;
		if (dx == 0 && dz == 0) {
			return "same dx=0 dz=0";
		}
		String ns = dz < 0 ? "N" : dz > 0 ? "S" : "";
		String ew = dx > 0 ? "E" : dx < 0 ? "W" : "";
		return ns + ew + " dx=" + dx + " dz=" + dz;
	}

	/**
	 * Chinese 8-way from {@code from} toward {@code to} (EW then NS: 东南, not 南东).
	 * Minecraft: +X east, +Z south.
	 */
	public static String compassCn(ChunkPos from, ChunkPos to) {
		if (from == null || to == null) {
			return "附近";
		}
		int dx = to.x - from.x;
		int dz = to.z - from.z;
		if (dx == 0 && dz == 0) {
			return "附近";
		}
		String ew = dx > 0 ? "东" : dx < 0 ? "西" : "";
		String ns = dz > 0 ? "南" : dz < 0 ? "北" : "";
		return ew + ns;
	}

	public static void spawnedCluster(
			ServerLevel level,
			UUID targetId,
			SiegeCluster cluster,
			String toward,
			String template
	) {
		ChunkPos anchor = cluster.getAnchorChunk();
		ChunkPos assault = cluster.getTargetChunk();
		LOGGER.info(
				"[siege spawn] spawned cluster={} player={} dim={} pos={} chunk={} gameTime={} dayTime={} dir={} toward={} targetChunk={} state={} template={} living={}",
				cluster.getId(),
				target(level, targetId),
				dim(level),
				blockPos(level, anchor),
				chunk(anchor),
				level.getGameTime(),
				level.getDayTime(),
				attackDir(anchor, assault),
				toward,
				chunk(assault),
				cluster.getState(),
				template,
				cluster.getLivingCount());
	}

	public static void state(
			UUID clusterId,
			SiegeClusterState from,
			String to,
			String reason,
			ServerLevel level,
			int tick,
			ChunkPos anchor,
			ChunkPos target,
			int living
	) {
		LOGGER.info(
				"[siege] state cluster={} {}→{} reason={} dim={} gameTime={} tick={} anchor={} target={} dir={} living={}",
				clusterId,
				from,
				to,
				reason,
				dim(level),
				level == null ? -1L : level.getGameTime(),
				tick,
				chunk(anchor),
				chunk(target),
				attackDir(anchor, target),
				living);
	}

	/** Wipe, ≤2 left, or {@code /siege disband}. Always INFO. */
	public static void disbanded(SiegeCluster cluster, SiegeClusterState from, String reason, int tick, int living) {
		if (cluster == null) {
			return;
		}
		ServerLevel level = cluster.getLevel();
		ChunkPos anchor = cluster.getAnchorChunk();
		ChunkPos target = cluster.getTargetChunk();
		LOGGER.info(
				"[siege] disbanded cluster={} {}→DISBAND reason={} dim={} gameTime={} tick={} pos={} chunk={} targetChunk={} dir={} living={} template={} hell={} targetId={}",
				cluster.getId(),
				from,
				reason,
				dim(level),
				level == null ? -1L : level.getGameTime(),
				tick,
				blockPos(level, anchor),
				chunk(anchor),
				chunk(target),
				attackDir(anchor, target),
				living,
				cluster.describeTemplate(),
				cluster.isHell(),
				target(level, cluster.getSiegeTargetId()));
	}

	private static String resolveName(MinecraftServer server, UUID id) {
		if (server == null || id == null) {
			return null;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player != null) {
			return player.getGameProfile().getName();
		}
		return server.getProfileCache().get(id).map(GameProfile::getName).orElse(null);
	}
}
