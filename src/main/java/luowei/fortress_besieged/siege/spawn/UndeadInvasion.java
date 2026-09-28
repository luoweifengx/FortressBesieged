package luowei.fortress_besieged.siege.spawn;

import java.util.Random;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.siege.UndeadInvasionSavedData;
import luowei.fortress_besieged.siege.pbs.PbsTerritoryAdapter;
import luowei.player_block_status.lib.chunk.RegionManager;

/**
 * Nether undead invasion. Structure shape is not bound yet: the anchor is the
 * chunk where the event was started. Speed stays at 1 until auxiliary structures exist.
 */
public final class UndeadInvasion {
	public static final int DURATION_TICKS = 24000;
	public static final int SPEED = 1;
	public static final int WAVE_INTERVAL_TICKS = 200;
	public static final int INFECT_PASSES = 10;
	public static final int CHAT_COLOR = 0x6B2D8B;

	private static ServerBossEvent bar;

	private UndeadInvasion() {
	}

	public record StartResult(boolean started, int infectChanged, int hostileAround, String message) {
	}

	public static boolean isActive(MinecraftServer server) {
		return server != null && UndeadInvasionSavedData.get(server).active();
	}

	public static void onWorldTick(ServerLevel level) {
		if (level == null || level.getServer() == null) {
			return;
		}
		MinecraftServer server = level.getServer();
		UndeadInvasionSavedData data = UndeadInvasionSavedData.get(server);
		if (!data.active()) {
			clearBar();
			return;
		}
		ResourceLocation dimension = data.dimension().orElse(null);
		if (dimension == null || !dimension.equals(level.dimension().location())) {
			return;
		}
		ensureBar(server);
		int ticks = Math.min(DURATION_TICKS, data.eventTicks() + SPEED);
		data.setEventTicks(ticks);
		bar.setProgress(ticks / (float) DURATION_TICKS);
		if (ticks >= DURATION_TICKS) {
			finish(server, true);
			return;
		}
		long gameTime = level.getGameTime();
		if (data.lastWaveGameTime() != Long.MIN_VALUE
				&& gameTime - data.lastWaveGameTime() < WAVE_INTERVAL_TICKS) {
			return;
		}
		data.setLastWaveGameTime(gameTime);
		UndeadInvasionSpawner.spawnWave(
				level,
				data.anchor(),
				data.targetId().orElse(null),
				new Random(level.random.nextLong()));
	}

	public static void syncTo(ServerPlayer player) {
		if (player == null || bar == null) {
			return;
		}
		if (isActive(player.getServer())) {
			bar.addPlayer(player);
		}
	}

	public static StartResult start(ServerPlayer player) {
		if (player == null) {
			return new StartResult(false, 0, 0, "Player required");
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return new StartResult(false, 0, 0, "Player required");
		}
		MinecraftServer server = level.getServer();
		if (!SiegeDayClock.isNether(level)) {
			return new StartResult(false, 0, 0, "Undead invasion starts in the nether");
		}
		UndeadInvasionSavedData data = UndeadInvasionSavedData.get(server);
		if (data.active()) {
			return new StartResult(false, 0, 0, "Undead invasion already active");
		}
		ChunkPos anchor = player.chunkPosition();
		int changed = 0;
		for (int i = 0; i < INFECT_PASSES; i++) {
			changed += RegionManager.forceInfection(level);
		}
		int hostile = UndeadInvasionSpawner.countHostileAround(level, anchor);
		UUID targetId = PbsTerritoryAdapter.resolvePollTargetForPlayer(server, player.getUUID());
		data.start(level.dimension().location(), anchor, targetId, level.getGameTime());
		ensureBar(server);
		bar.setProgress(0.0F);
		announce(server, "亡灵入侵开始");
		playStart(server);
		UndeadInvasionSpawner.spawnWave(level, anchor, targetId, new Random(level.random.nextLong()));
		String message = "Undead invasion started at " + anchor
				+ " infectPasses=" + INFECT_PASSES
				+ " infectChanged=" + changed
				+ " hostileAround=" + hostile
				+ " radius=" + UndeadInvasionSpawner.HOSTILE_RADIUS;
		FortressBesieged.LOGGER.info("[siege] {}", message);
		return new StartResult(true, changed, hostile, message);
	}

	public static boolean stop(MinecraftServer server) {
		UndeadInvasionSavedData data = UndeadInvasionSavedData.get(server);
		if (!data.active()) {
			clearBar();
			return false;
		}
		finish(server, false);
		return true;
	}

	public static String describe(MinecraftServer server) {
		UndeadInvasionSavedData data = UndeadInvasionSavedData.get(server);
		return "undead active=" + data.active()
				+ " dim=" + data.dimensionId()
				+ " anchor=" + data.chunkX() + "," + data.chunkZ()
				+ " eventTicks=" + data.eventTicks() + "/" + DURATION_TICKS
				+ " speed=" + SPEED
				+ " waveInterval=" + WAVE_INTERVAL_TICKS;
	}

	private static void finish(MinecraftServer server, boolean victory) {
		UndeadInvasionSavedData.get(server).stop();
		clearBar();
		announce(server, victory ? "亡灵入侵结束" : "亡灵入侵中止");
		FortressBesieged.LOGGER.info("[siege] undead invasion {}", victory ? "victory" : "stopped");
	}

	private static void ensureBar(MinecraftServer server) {
		if (bar == null) {
			bar = new ServerBossEvent(
					UndeadInvasionBar.title(),
					BossEvent.BossBarColor.PURPLE,
					BossEvent.BossBarOverlay.PROGRESS);
			bar.setProgress(0.0F);
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bar.addPlayer(player);
		}
	}

	private static void clearBar() {
		if (bar == null) {
			return;
		}
		bar.removeAllPlayers();
		bar = null;
	}

	private static void announce(MinecraftServer server, String text) {
		server.getPlayerList().broadcastSystemMessage(
				Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(CHAT_COLOR))),
				false);
	}

	private static void playStart(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.7F);
		}
	}
}
