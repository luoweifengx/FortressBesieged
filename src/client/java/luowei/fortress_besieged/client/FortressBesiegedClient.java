package luowei.fortress_besieged.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import luowei.fortress_besieged.FortressBesieged;
import luowei.fortress_besieged.net.BloodMoonS2CPayload;

public class FortressBesiegedClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(BloodMoonS2CPayload.TYPE, (payload, context) -> {
			boolean active = payload.active();
			context.client().execute(() -> BloodMoonClient.setActive(active));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> BloodMoonClient.setActive(false)));
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return FortressBesieged.id("blood_moon_moon");
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				BloodMoonMoonMask.invalidate();
			}
		});
	}
}
