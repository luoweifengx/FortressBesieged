package luowei.fortress_besieged.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import luowei.fortress_besieged.FortressBesieged;

/**
 * Red-multiplied copy of the bound moon atlas. Shaders that sample the moon
 * draw see these pixels; phase UVs stay on the same atlas layout.
 */
public final class BloodMoonMoonMask {
	public static final ResourceLocation LOCATION =
			FortressBesieged.id("textures/environment/blood_moon_phases.png");
	private static final ResourceLocation SOURCE =
			ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

	private static boolean ready;
	private static boolean failed;

	private BloodMoonMoonMask() {
	}

	public static ResourceLocation texture() {
		if (!ready && !failed) {
			rebuild();
		}
		return ready ? LOCATION : null;
	}

	public static void invalidate() {
		ready = false;
		failed = false;
	}

	private static void rebuild() {
		Minecraft minecraft = Minecraft.getInstance();
		Optional<Resource> resource = minecraft.getResourceManager().getResource(SOURCE);
		if (resource.isEmpty()) {
			failed = true;
			FortressBesieged.LOGGER.warn("[siege] blood moon texture missing {}", SOURCE);
			return;
		}
		try (InputStream stream = resource.get().open()) {
			NativeImage image = NativeImage.read(stream);
			tint(image);
			DynamicTexture texture = new DynamicTexture(() -> "fortress-besieged blood moon", image);
			minecraft.getTextureManager().register(LOCATION, texture);
			ready = true;
		} catch (IOException | IllegalArgumentException exception) {
			failed = true;
			FortressBesieged.LOGGER.warn("[siege] blood moon texture failed", exception);
		}
	}

	private static void tint(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setPixel(x, y, ARGB.scaleRGB(image.getPixel(x, y), 1.0F, 0.18F, 0.18F));
			}
		}
	}
}
