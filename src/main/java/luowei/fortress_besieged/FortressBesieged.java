package luowei.fortress_besieged;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import luowei.fortress_besieged.siege.SiegeClusterManager;
import luowei.fortress_besieged.siege.SiegeCommands;
import luowei.fortress_besieged.siege.path.SiegePathCostConfig;
import luowei.fortress_besieged.siege.spawn.SiegeSpawnScheduler;

public class FortressBesieged implements ModInitializer {
	public static final String MOD_ID = "fortress-besieged";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SiegePathCostConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				SiegeCommands.register(dispatcher));

		ServerTickEvents.END_WORLD_TICK.register(world -> {
			if (world instanceof ServerLevel serverLevel) {
				SiegeClusterManager.tickLevel(serverLevel);
				SiegeSpawnScheduler.onWorldTick(serverLevel);
			}
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (entity instanceof Mob mob) {
				SiegeClusterManager.onMobDamaged(mob, source.getEntity());
			}
		});

		ServerWorldEvents.UNLOAD.register((server, world) -> {
			if (world instanceof ServerLevel serverLevel) {
				SiegeClusterManager.clearLevel(serverLevel);
			}
		});

		LOGGER.info("Fortress Besieged siege cluster system initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
