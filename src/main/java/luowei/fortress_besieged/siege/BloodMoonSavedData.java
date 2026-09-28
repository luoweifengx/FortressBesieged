package luowei.fortress_besieged.siege;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Overworld blood-moon flag. Stored at
 * {@code saves/<world>/data/fortress_besieged_blood_moon.dat}.
 */
public final class BloodMoonSavedData extends SavedData {
	public static final String ID = "fortress_besieged_blood_moon";

	private static final Codec<BloodMoonSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("active", false).forGetter(BloodMoonSavedData::active),
			Codec.BOOL.optionalFieldOf("forced", false).forGetter(BloodMoonSavedData::forced),
			Codec.LONG.optionalFieldOf("lastRollDayIndex", -1L).forGetter(BloodMoonSavedData::lastRollDayIndex)
	).apply(instance, BloodMoonSavedData::new));

	public static final SavedDataType<BloodMoonSavedData> TYPE = new SavedDataType<>(
			ID,
			context -> new BloodMoonSavedData(false, false, -1L),
			context -> CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private boolean active;
	private boolean forced;
	private long lastRollDayIndex;

	private BloodMoonSavedData(boolean active, boolean forced, long lastRollDayIndex) {
		this.active = active;
		this.forced = forced;
		this.lastRollDayIndex = lastRollDayIndex;
	}

	public static BloodMoonSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean active() {
		return active;
	}

	public boolean forced() {
		return forced;
	}

	public long lastRollDayIndex() {
		return lastRollDayIndex;
	}

	public void setActive(boolean active) {
		if (this.active == active) {
			return;
		}
		this.active = active;
		setDirty();
	}

	public void setForced(boolean forced) {
		if (this.forced == forced) {
			return;
		}
		this.forced = forced;
		setDirty();
	}

	public void setLastRollDayIndex(long lastRollDayIndex) {
		if (this.lastRollDayIndex == lastRollDayIndex) {
			return;
		}
		this.lastRollDayIndex = lastRollDayIndex;
		setDirty();
	}
}
