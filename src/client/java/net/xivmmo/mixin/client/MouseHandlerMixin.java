package net.xivmmo.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.xivmmo.client.camera.MmoCameraController;
import net.xivmmo.client.input.MmoInputHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overhauls MouseHandler for Free-Cursor MMO Mode:
 * - Releases GLFW cursor during gameplay unless RMB-orbiting or in Build Mode
 * - Routes LMB to entity targeting selection (or LMB+RMB forward movement)
 * - Routes RMB + drag to camera orbit with cursor position lock & restore
 * - Routes scroll wheel to camera zoom distance (unless Alt is held)
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private boolean isLeftPressed;

	@Shadow
	private boolean isRightPressed;

	@Shadow
	private double xpos;

	@Shadow
	private double ypos;

	@Shadow
	private boolean ignoreFirstMove;

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Shadow
	private boolean mouseGrabbed;

	@Shadow
	protected abstract MouseButtonInfo simulateRightClick(MouseButtonInfo info, boolean pressed);

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void xivmmo$onButton(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
		Window window = this.minecraft.getWindow();
		if (handle != window.handle()) {
			return;
		}
		if (this.minecraft.gui.screen() != null || this.minecraft.gui.overlay() != null || this.minecraft.level == null) {
			return;
		}
		if (!MmoInputHandler.isFreeCursorMode()) {
			return;
		}

		boolean pressed = action == 1;
		MouseButtonInfo buttonInfo = this.simulateRightClick(rawButtonInfo, pressed);
		int button = buttonInfo.button();
		MmoInputHandler.setMouseButtonState(button, pressed);

		// When Alt (Block Interact Modifier) is held in Free-Cursor Mode, allow vanilla block/item actions
		// while keeping the cursor free (grabMouse is guarded below).
		if (MmoInputHandler.isBlockInteractionAllowed()) {
			return;
		}

		this.minecraft.getFramerateLimitTracker().onInputReceived();

		if (button == 1) {
			// Right Mouse Button: Orbit camera while held, restore cursor to saved position on release
			this.isRightPressed = pressed;
			if (pressed) {
				MmoInputHandler.startRightMouseOrbit(this.xpos, this.ypos);
				this.mouseGrabbed = true;
				this.ignoreFirstMove = true;
				InputConstants.grabOrReleaseMouse(window, 212995, this.xpos, this.ypos);
			} else if (MmoInputHandler.isRightMouseOrbiting()) {
				MmoInputHandler.stopRightMouseOrbit();
				this.mouseGrabbed = false;
				this.xpos = MmoInputHandler.getSavedOrbitCursorX();
				this.ypos = MmoInputHandler.getSavedOrbitCursorY();
				this.ignoreFirstMove = true;
				InputConstants.grabOrReleaseMouse(window, 212993, this.xpos, this.ypos);
			}
			ci.cancel();
		} else if (button == 0) {
			// Left Mouse Button: Select/target entity under cursor (or combine with RMB to move forward)
			this.isLeftPressed = pressed;
			if (pressed && !MmoInputHandler.isRightMouseDown()) {
				MmoInputHandler.onLeftClickSelect(this.xpos, this.ypos);
			}
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void xivmmo$onScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (handle != this.minecraft.getWindow().handle()) {
			return;
		}
		if (this.minecraft.gui.screen() != null || this.minecraft.gui.overlay() != null || this.minecraft.player == null) {
			return;
		}
		// In MMO Mode (unless holding Alt to scroll vanilla hotbar), scroll wheel controls camera zoom
		if (MmoInputHandler.isMmoModeActive() && !MmoInputHandler.isBlockInteractModifierDown()) {
			this.minecraft.getFramerateLimitTracker().onInputReceived();
			boolean discreteScroll = this.minecraft.options.discreteMouseScroll().get();
			double scrollSensitivity = this.minecraft.options.mouseWheelSensitivity().get();
			double scaledYOffset = (discreteScroll ? Math.signum(yoffset) : yoffset) * scrollSensitivity;
			MmoCameraController.get().onScrollZoom(scaledYOffset);
			ci.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void xivmmo$turnPlayer(double mousea, CallbackInfo ci) {
		if (!MmoInputHandler.isFreeCursorMode() || this.minecraft.player == null) {
			return;
		}
		if (MmoInputHandler.isRightMouseOrbiting()) {
			double ss = this.minecraft.options.sensitivity().get() * 0.6F + 0.2F;
			double sensitivityMod = ss * ss * ss;
			double sens = sensitivityMod * 8.0;
			double xo = this.accumulatedDX * sens;
			double yo = this.accumulatedDY * sens;
			if (this.minecraft.options.invertMouseX().get()) {
				xo = -xo;
			}
			if (this.minecraft.options.invertMouseY().get()) {
				yo = -yo;
			}
			MmoCameraController.get().onOrbitDelta(xo * 0.15, yo * 0.15);
		}
		ci.cancel();
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void xivmmo$grabMouse(CallbackInfo ci) {
		if (MmoInputHandler.isFreeCursorMode() && !MmoInputHandler.isRightMouseOrbiting()) {
			if (this.minecraft.isWindowActive()) {
				this.mouseGrabbed = false;
				if (this.xpos <= 0.0 && this.ypos <= 0.0) {
					this.xpos = this.minecraft.getWindow().getScreenWidth() / 2.0;
					this.ypos = this.minecraft.getWindow().getScreenHeight() / 2.0;
				}
				InputConstants.grabOrReleaseMouse(this.minecraft.getWindow(), 212993, this.xpos, this.ypos);
				if (this.minecraft.gui.screen() != null) {
					this.minecraft.gui.setScreen(null);
				}
				this.minecraft.missTime = 10000;
				this.ignoreFirstMove = true;
			}
			ci.cancel();
		}
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private void xivmmo$releaseMouse(CallbackInfo ci) {
		if (MmoInputHandler.isRightMouseOrbiting()) {
			this.mouseGrabbed = false;
			this.xpos = MmoInputHandler.getSavedOrbitCursorX();
			this.ypos = MmoInputHandler.getSavedOrbitCursorY();
			MmoInputHandler.resetMouseStates();
			InputConstants.grabOrReleaseMouse(this.minecraft.getWindow(), 212993, this.xpos, this.ypos);
			ci.cancel();
		} else {
			MmoInputHandler.resetMouseStates();
		}
	}
}
