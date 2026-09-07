package luowei.fortress_besieged.siege;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 服务器级新玩家保护期（进入游戏天数 / {@code isTimeToBesiege}）。
 * 存于主世界 SavedData：{@code saves/&lt;world&gt;/data/fortress_besieged_player_protection.dat}。
 */
public final class SiegePlayerProtectionSavedData extends SavedData {
	public static final String ID = "fortress_besieged_player_protection";

	public record PlayerRecord(int daysInGame, boolean isTimeToBesiege) {
		public static final Codec<PlayerRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.optionalFieldOf("daysInGame", 0).forGetter(PlayerRecord::daysInGame),
				Codec.BOOL.optionalFieldOf("isTimeToBesiege", false).forGetter(PlayerRecord::isTimeToBesiege)
		).apply(instance, PlayerRecord::new));

		static PlayerRecord firstSeen() {
			return new PlayerRecord(0, false);
		}
	}

	private static final Codec<SiegePlayerProtectionSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, PlayerRecord.CODEC)
					.optionalFieldOf("players", Map.of())
					.forGetter(data -> Map.copyOf(data.players))
	).apply(instance, SiegePlayerProtectionSavedData::new));

	public static final SavedDataType<SiegePlayerProtectionSavedData> TYPE = new SavedDataType<>(
			ID,
			context -> new SiegePlayerProtectionSavedData(Map.of()),
			context -> CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<UUID, PlayerRecord> players;

	private SiegePlayerProtectionSavedData(Map<UUID, PlayerRecord> players) {
		this.players = new HashMap<>();
		if (players != null) {
			this.players.putAll(players);
		}
	}

	public static SiegePlayerProtectionSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public PlayerRecord ensure(UUID playerId) {
		PlayerRecord existing = players.get(playerId);
		if (existing != null) {
			return existing;
		}
		PlayerRecord created = PlayerRecord.firstSeen();
		players.put(playerId, created);
		setDirty();
		return created;
	}

	public PlayerRecord get(UUID playerId) {
		return players.get(playerId);
	}

	public void put(UUID playerId, PlayerRecord record) {
		players.put(playerId, record);
		setDirty();
	}

	/**
	 * 已记录玩家各 +1 游戏日；{@code isTimeToBesiege} 已为 true 则不再查天数。
	 */
	public void advanceDays(int protectionDays) {
		if (players.isEmpty()) {
			return;
		}
		boolean dirty = false;
		for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
			PlayerRecord prev = entry.getValue();
			int days = prev.daysInGame() + 1;
			boolean ready = prev.isTimeToBesiege();
			if (!ready && days >= protectionDays) {
				ready = true;
			}
			if (days != prev.daysInGame() || ready != prev.isTimeToBesiege()) {
				entry.setValue(new PlayerRecord(days, ready));
				dirty = true;
			}
		}
		if (dirty) {
			setDirty();
		}
	}
}
