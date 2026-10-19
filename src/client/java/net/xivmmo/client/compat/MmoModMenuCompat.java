package net.xivmmo.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu (20.0.3) entrypoint providing quick access to the XIV MMO Config & HUD Layout Editor.
 */
public final class MmoModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return YaclConfigScreenFactory::createConfigScreen;
	}
}
