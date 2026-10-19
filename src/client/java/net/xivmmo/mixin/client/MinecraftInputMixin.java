package net.xivmmo.mixin.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.xivmmo.client.hud.MmoHudManager;
import net.xivmmo.client.input.MmoInputHandler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overhauls vanilla hotbar and mouse/item mechanics in MMO Combat Mode:
 * - Disables vanilla hotbar slot switching (1-9, save/load hotbar activator), item dropping (Q),
 *   offhand swapping (F), block picking (Middle Click), and vanilla held-item attack/use when
 *   {@link MmoHudManager#shouldDisableVanillaHotbarMechanics()} is active.
 * - Allows continuous block mining ({@code continueAttack}) when holding the Block Interact Modifier (Alt)
 *   or Build Mode (B) in Free-Cursor MMO Mode even though {@link MouseHandler#isMouseGrabbed()} is false.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInputMixin {
	@Shadow
	@Final
	public Options options;

	@Shadow
	public @Nullable LocalPlayer player;

	@Shadow
	public @Nullable MultiPlayerGameMode gameMode;

	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void xivmmo$disableVanillaHotbarMechanicsInMmoMode(CallbackInfo ci) {
		if (!MmoHudManager.shouldDisableVanillaHotbarMechanics()) {
			return;
		}

		// 1. Drain 1-9 hotbar slot keys so pressing 1-8 for MMO abilities (or 9) never switches the vanilla hotbar slot
		for (KeyMapping hotbarKey : this.options.keyHotbarSlots) {
			while (hotbarKey.consumeClick()) {
				// drained
			}
			hotbarKey.setDown(false);
		}
		while (this.options.keySaveHotbarActivator.consumeClick()) {
			// drained
		}
		while (this.options.keyLoadHotbarActivator.consumeClick()) {
			// drained
		}

		// 2. Drain hotbar item manipulation keys (Drop Q, Swap Offhand F, Pick Block Middle-Click)
		while (this.options.keyDrop.consumeClick()) {
			// drained
		}
		this.options.keyDrop.setDown(false);

		while (this.options.keySwapOffhand.consumeClick()) {
			// drained
		}
		this.options.keySwapOffhand.setDown(false);

		while (this.options.keyPickItem.consumeClick()) {
			// drained
		}
		this.options.keyPickItem.setDown(false);

		// 3. Drain vanilla held-item Attack / Use keys so hidden hotbar items cannot be swung, eaten, or placed outside Build/Alt mode
		while (this.options.keyAttack.consumeClick()) {
			// drained
		}
		this.options.keyAttack.setDown(false);

		while (this.options.keyUse.consumeClick()) {
			// drained
		}
		this.options.keyUse.setDown(false);

		if (this.player != null && this.player.isUsingItem() && this.gameMode != null) {
			this.gameMode.releaseUsingItem(this.player);
		}
	}

	@Redirect(
		method = "handleKeybinds",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"
		)
	)
	private boolean xivmmo$allowContinueAttackInAltBlockMode(MouseHandler mouseHandler) {
		if (MmoInputHandler.isFreeCursorMode() && MmoInputHandler.isBlockInteractionAllowed()) {
			return true;
		}
		return mouseHandler.isMouseGrabbed();
	}
}
