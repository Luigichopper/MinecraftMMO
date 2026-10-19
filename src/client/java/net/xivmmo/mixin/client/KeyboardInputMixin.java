package net.xivmmo.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.xivmmo.client.input.MmoInputHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements FFXIV-style LMB+RMB simultaneous hold to move forward.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void xivmmo$onTick(CallbackInfo ci) {
		if (MmoInputHandler.isBothMouseHeld() && !this.keyPresses.forward()) {
			this.keyPresses = new Input(
				true,
				this.keyPresses.backward(),
				this.keyPresses.left(),
				this.keyPresses.right(),
				this.keyPresses.jump(),
				this.keyPresses.shift(),
				this.keyPresses.sprint()
			);
			float forwardImpulse = this.keyPresses.forward() == this.keyPresses.backward()
				? 0.0F
				: (this.keyPresses.forward() ? 1.0F : -1.0F);
			float leftImpulse = this.keyPresses.left() == this.keyPresses.right()
				? 0.0F
				: (this.keyPresses.left() ? 1.0F : -1.0F);
			this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
		}
	}
}
