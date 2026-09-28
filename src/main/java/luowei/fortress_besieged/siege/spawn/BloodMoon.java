package luowei.fortress_besieged.siege.spawn;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.net.BloodMoonS2CPayload;
import luowei.fortress_besieged.siege.BloodMoonSavedData;

/**
 * Blood moon: same duty window as every cluster type, but natural siege skips
 * the stage {@code chance} roll and always fires a wave.
 */
public final class BloodMoon {
	/** Rolled once per overworld day when the shared duty window first opens. */
	public static final float START_CHANCE = 0.125f;
	/** Blood-red chat for rise/end only. */
	public static final int CHAT_COLOR = 0xBB0A1E;

	private BloodMoon() {
	}

	public static boolean isActive(ServerLevel level) {
		if (level == null || level.getServer() == null) {
			return false;
		}
		return isActive(level.getServer());
	}

	public static boolean isActive(MinecraftServer server) {
		return server != null && BloodMoonSavedData.get(server).active();
	}

	public static void onWorldTick(ServerLevel level) {
		if (level == null || !level.dimension().equals(Level.OVERWORLD)) {
			return;
		}
		MinecraftServer server = level.getServer();
		BloodMoonSavedData data = BloodMoonSavedData.get(server);
		long timeOfDay = SiegeDayClock.timeOfDay(level);
		long dayIndex = SiegeDayClock.dayIndex(level);
		boolean inWindow = SiegeDayClock.inWindow(SiegeProgression.sharedDutyWindow(), timeOfDay);
		if (!inWindow) {
			if (data.active() && !data.forced()) {
				data.setActive(false);
				announce(server, false);
			}
			return;
		}
		if (data.lastRollDayIndex() == dayIndex) {
			return;
		}
		data.setLastRollDayIndex(dayIndex);
		if (data.forced()) {
			if (!data.active()) {
				data.setActive(true);
				announce(server, true);
			}
			return;
		}
		boolean start = level.random.nextFloat() < START_CHANCE;
		data.setActive(start);
		if (start) {
			announce(server, true);
		}
	}

	public static boolean start(MinecraftServer server) {
		BloodMoonSavedData data = BloodMoonSavedData.get(server);
		data.setForced(true);
		if (data.active()) {
			syncAll(server);
			return false;
		}
		data.setActive(true);
		announce(server, true);
		return true;
	}

	public static boolean stop(MinecraftServer server) {
		BloodMoonSavedData data = BloodMoonSavedData.get(server);
		data.setForced(false);
		if (!data.active()) {
			syncAll(server);
			return false;
		}
		data.setActive(false);
		announce(server, false);
		return true;
	}

	public static void syncTo(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ServerPlayNetworking.send(player, new BloodMoonS2CPayload(isActive(player.getServer())));
	}

	public static String describe(MinecraftServer server) {
		BloodMoonSavedData data = BloodMoonSavedData.get(server);
		return "bloodMoon active=" + data.active()
				+ " forced=" + data.forced()
				+ " lastRollDay=" + data.lastRollDayIndex()
				+ " startChance=" + START_CHANCE;
	}

	private static void announce(MinecraftServer server, boolean rising) {
		FortressBesieged.LOGGER.info("[siege] blood moon {}", rising ? "rise" : "end");
		server.getPlayerList().broadcastSystemMessage(chat(rising), false);
		syncAll(server);
		if (!rising) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.WEATHER, 0.8F, 1.0F);
		}
	}

	private static Component chat(boolean rising) {
		return Component.literal(rising ? "血月升起" : "血月结束")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(CHAT_COLOR)));
	}

	private static void syncAll(MinecraftServer server) {
		BloodMoonS2CPayload payload = new BloodMoonS2CPayload(isActive(server));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
