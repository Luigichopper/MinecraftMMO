package net.xivmmo;

import net.fabricmc.api.ModInitializer;
import net.xivmmo.combat.engine.ServerCombatEngine;
import net.xivmmo.data.MmoDataRegistry;
import net.xivmmo.network.MmoNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XivMmoMod implements ModInitializer {
	public static final String MOD_ID = "xivmmo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing XIV MMO Combat & Camera Overhaul ({})", MOD_ID);
		MmoDataRegistry.registerReloadListeners();
		MmoNetwork.registerPayloadTypes();
		ServerCombatEngine.init();
	}
}
