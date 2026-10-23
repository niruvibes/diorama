package dev.diorama.mixin;

import com.mojang.realmsclient.dto.RealmsServer;
import dev.diorama.WorldKey;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the permanent Realm identity with its server entry, before the temporary IP is used. */
@Mixin(RealmsServer.class)
public abstract class RealmsServerMixin {
	@Shadow public long id;
	@Shadow public int activeSlot;

	@Inject(method = "toServerData", at = @At("RETURN"))
	private void diorama$realmIdentity(String address, CallbackInfoReturnable<ServerData> ci) {
		WorldKey.realm(ci.getReturnValue(), this.id, this.activeSlot);
	}
}
