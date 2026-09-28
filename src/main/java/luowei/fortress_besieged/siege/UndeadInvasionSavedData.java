package luowei.fortress_besieged.siege;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Nether undead-invasion clock. Stored on the overworld:
 * {@code saves/<world>/data/fortress_besieged_undead_invasion.dat}.
 */
public final class UndeadInvasionSavedData extends SavedData {
	public static final String ID = "fortress_besieged_undead_invasion";

	private static final Codec<UndeadInvasionSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("active", false).forGetter(UndeadInvasionSavedData::active),
			Codec.STRING.optionalFieldOf("dimension", "").forGetter(UndeadInvasionSavedData::dimensionId),
			Codec.INT.optionalFieldOf("chunkX", 0).forGetter(UndeadInvasionSavedData::chunkX),
			Codec.INT.optionalFieldOf("chunkZ", 0).forGetter(UndeadInvasionSavedData::chunkZ),
			Codec.INT.optionalFieldOf("eventTicks", 0).forGetter(UndeadInvasionSavedData::eventTicks),
			Codec.LONG.optionalFieldOf("lastWaveGameTime", Long.MIN_VALUE).forGetter(UndeadInvasionSavedData::lastWaveGameTime),
			UUIDUtil.CODEC.optionalFieldOf("targetId").forGetter(UndeadInvasionSavedData::targetId)
	).apply(instance, UndeadInvasionSavedData::new));

	public static final SavedDataType<UndeadInvasionSavedData> TYPE = new SavedDataType<>(
			ID,
			context -> new UndeadInvasionSavedData(false, "", 0, 0, 0, Long.MIN_VALUE, Optional.empty()),
			context -> CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private boolean active;
	private String dimensionId;
	private int chunkX;
	private int chunkZ;
	private int eventTicks;
	private long lastWaveGameTime;
	private Optional<UUID> targetId;

	private UndeadInvasionSavedData(
			boolean active,
			String dimensionId,
			int chunkX,
			int chunkZ,
			int eventTicks,
			long lastWaveGameTime,
			Optional<UUID> targetId
	) {
		this.active = active;
		this.dimensionId = dimensionId == null ? "" : dimensionId;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.eventTicks = eventTicks;
		this.lastWaveGameTime = lastWaveGameTime;
		this.targetId = targetId == null ? Optional.empty() : targetId;
	}

	public static UndeadInvasionSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean active() {
		return active;
	}

	public String dimensionId() {
		return dimensionId;
	}

	public int chunkX() {
		return chunkX;
	}

	public int chunkZ() {
		return chunkZ;
	}

	public int eventTicks() {
		return eventTicks;
	}

	public long lastWaveGameTime() {
		return lastWaveGameTime;
	}

	public Optional<UUID> targetId() {
		return targetId;
	}

	public ChunkPos anchor() {
		return new ChunkPos(chunkX, chunkZ);
	}

	public Optional<ResourceLocation> dimension() {
		if (dimensionId == null || dimensionId.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(ResourceLocation.parse(dimensionId));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public void start(ResourceLocation dimension, ChunkPos anchor, UUID target, long gameTime) {
		this.active = true;
		this.dimensionId = dimension.toString();
		this.chunkX = anchor.x;
		this.chunkZ = anchor.z;
		this.eventTicks = 0;
		this.lastWaveGameTime = gameTime;
		this.targetId = Optional.ofNullable(target);
		setDirty();
	}

	public void stop() {
		if (!active && eventTicks == 0) {
			return;
		}
		this.active = false;
		setDirty();
	}

	public void setEventTicks(int eventTicks) {
		if (this.eventTicks == eventTicks) {
			return;
		}
		this.eventTicks = eventTicks;
		setDirty();
	}

	public void setLastWaveGameTime(long lastWaveGameTime) {
		if (this.lastWaveGameTime == lastWaveGameTime) {
			return;
		}
		this.lastWaveGameTime = lastWaveGameTime;
		setDirty();
	}
}
