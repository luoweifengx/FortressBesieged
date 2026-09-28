package luowei.fortress_besieged.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.resources.ResourceLocation;

import luowei.fortress_besieged.client.BloodMoonClient;
import luowei.fortress_besieged.client.BloodMoonMoonMask;

/**
 * Binds a red-multiplied copy of the moon atlas while a blood moon is active.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
	@Redirect(
			method = "renderMoon",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/RenderType;celestial(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"
			)
	)
	private RenderType fortressBesieged$bloodMoon(ResourceLocation texture) {
		if (BloodMoonClient.isActive()) {
			ResourceLocation masked = BloodMoonMoonMask.texture();
			if (masked != null) {
				texture = masked;
			}
		}
		return RenderType.celestial(texture);
	}
}
