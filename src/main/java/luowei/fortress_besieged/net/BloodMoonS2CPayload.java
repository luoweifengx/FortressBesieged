package luowei.fortress_besieged.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import luowei.fortress_besieged.FortressBesieged;

public record BloodMoonS2CPayload(boolean active) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BloodMoonS2CPayload> TYPE =
			new CustomPacketPayload.Type<>(FortressBesieged.id("blood_moon"));

	public static final StreamCodec<RegistryFriendlyByteBuf, BloodMoonS2CPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.BOOL,
					BloodMoonS2CPayload::active,
					BloodMoonS2CPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
