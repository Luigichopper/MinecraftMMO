package net.xivmmo.inventory;

import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.xivmmo.XivMmoMod;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * FFXIV-Inspired 140-Slot Inventory & Key Items/Crystals Container System.
 *
 * <p>Structure (matching FFXIV's 4-page 5x7 = 35-slot grid layout):
 * <ul>
 *   <li><b>Page 1 (Slots 0..34)</b>: Directly mirrors slots {@code 0..34} of the player's live Minecraft {@link Inventory}.</li>
 *   <li><b>Page 2 (Slots 35..69)</b>: Server-authoritative expanded FFXIV bag page 2 (35 slots).</li>
 *   <li><b>Page 3 (Slots 70..104)</b>: Server-authoritative expanded FFXIV bag page 3 (35 slots).</li>
 *   <li><b>Page 4 (Slots 105..139)</b>: Server-authoritative expanded FFXIV bag page 4 (35 slots).</li>
 *   <li><b>Key Items & Crystals (Slots 140..174)</b>: 35-slot dedicated tab for Job Soul Crystals, Aether Shards, and Key Items.</li>
 * </ul>
 */
public final class MmoInventoryState {
	public static final int SLOTS_PER_PAGE = 35; // 5 columns x 7 rows
	public static final int MAIN_PAGES = 4;
	public static final int TOTAL_MAIN_SLOTS = SLOTS_PER_PAGE * MAIN_PAGES; // 140 slots
	public static final int KEY_ITEM_SLOTS = SLOTS_PER_PAGE; // 35 slots (140..174)
	public static final int TOTAL_CONTAINER_SLOTS = TOTAL_MAIN_SLOTS + KEY_ITEM_SLOTS; // 175 slots

	public enum ItemCategory {
		GEAR_WEAPON(0, "Armoury & Gear", 0xFF4A90E2),
		CONSUMABLE_FOOD(1, "Medicines & Meals", 0xFF50C878),
		MATERIAL_CRYSTAL(2, "Materials & Crystals", 0xFFC878E6),
		BLOCK_MISC(3, "Furnishings & Misc", 0xFFE6A844);

		private final int sortOrder;
		private final String label;
		private final int borderColor;

		ItemCategory(int sortOrder, String label, int borderColor) {
			this.sortOrder = sortOrder;
			this.label = label;
			this.borderColor = borderColor;
		}

		public int sortOrder() {
			return this.sortOrder;
		}

		public String label() {
			return this.label;
		}

		public int borderColor() {
			return this.borderColor;
		}
	}

	/** Pages 2, 3, and 4 (slots 35..139 = 105 slots). */
	private final NonNullList<ItemStack> expandedBag = NonNullList.withSize(TOTAL_MAIN_SLOTS - SLOTS_PER_PAGE, ItemStack.EMPTY);

	/** Key Items & Crystals tab (slots 140..174 = 35 slots). */
	private final NonNullList<ItemStack> keyItemsAndCrystals = NonNullList.withSize(KEY_ITEM_SLOTS, ItemStack.EMPTY);
	private boolean keyItemsSeeded = false;

	public MmoInventoryState() {
	}

	private void ensureKeyItemsSeeded() {
		if (this.keyItemsSeeded) {
			return;
		}
		try {
			this.keyItemsAndCrystals.set(0, createSoulCrystal(Items.HEART_OF_THE_SEA, "Soul of the Vanguard", ChatFormatting.AQUA));
			this.keyItemsAndCrystals.set(1, createSoulCrystal(Items.EMERALD, "Soul of the Hierophant", ChatFormatting.GREEN));
			this.keyItemsAndCrystals.set(2, createSoulCrystal(Items.AMETHYST_SHARD, "Soul of the Lancer", ChatFormatting.RED));
			this.keyItemsAndCrystals.set(3, createSoulCrystal(Items.ECHO_SHARD, "Soul of the Arcanist", ChatFormatting.LIGHT_PURPLE));

			this.keyItemsAndCrystals.set(5, createNamedStack(Items.BLAZE_POWDER, 48, "Fire Shard", ChatFormatting.RED));
			this.keyItemsAndCrystals.set(6, createNamedStack(Items.PRISMARINE_CRYSTALS, 64, "Ice Shard", ChatFormatting.AQUA));
			this.keyItemsAndCrystals.set(7, createNamedStack(Items.FEATHER, 52, "Wind Shard", ChatFormatting.GREEN));
			this.keyItemsAndCrystals.set(8, createNamedStack(Items.RAW_GOLD, 36, "Earth Shard", ChatFormatting.GOLD));
			this.keyItemsAndCrystals.set(9, createNamedStack(Items.GLOWSTONE_DUST, 44, "Lightning Shard", ChatFormatting.LIGHT_PURPLE));
			this.keyItemsAndCrystals.set(10, createNamedStack(Items.LAPIS_LAZULI, 60, "Water Shard", ChatFormatting.BLUE));
			this.keyItemsSeeded = true;
		} catch (RuntimeException ignored) {
			// Item components not bound yet (early bootstrap); will retry when accessed in-world
		}
	}

	private static ItemStack createSoulCrystal(net.minecraft.world.item.Item item, String name, ChatFormatting color) {
		ItemStack stack = new ItemStack(item, 1);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
		return stack;
	}

	private static ItemStack createNamedStack(net.minecraft.world.item.Item item, int count, String name, ChatFormatting color) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
		return stack;
	}

	/**
	 * Classifies an {@link ItemStack} into one of the four FFXIV inventory categories
	 * (Weapons/Gear, Medicines/Meals, Materials/Crystals, Furnishings/Blocks).
	 */
	public static ItemCategory classifyItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return ItemCategory.BLOCK_MISC;
		}
		if (stack.has(DataComponents.EQUIPPABLE) || stack.has(DataComponents.MAX_DAMAGE)) {
			return ItemCategory.GEAR_WEAPON;
		}
		if (stack.has(DataComponents.FOOD)
			|| stack.is(Items.POTION)
			|| stack.is(Items.SPLASH_POTION)
			|| stack.is(Items.LINGERING_POTION)
			|| stack.is(Items.GOLDEN_APPLE)
			|| stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
			return ItemCategory.CONSUMABLE_FOOD;
		}
		if (stack.getItem() instanceof BlockItem) {
			return ItemCategory.BLOCK_MISC;
		}
		return ItemCategory.MATERIAL_CRYSTAL;
	}

	public ItemStack getSlot(@Nullable Player player, int ffxivSlotIndex) {
		if (ffxivSlotIndex < 0 || ffxivSlotIndex >= TOTAL_CONTAINER_SLOTS) {
			return ItemStack.EMPTY;
		}
		if (ffxivSlotIndex < SLOTS_PER_PAGE) {
			return player != null ? player.getInventory().getItem(ffxivSlotIndex) : ItemStack.EMPTY;
		}
		if (ffxivSlotIndex < TOTAL_MAIN_SLOTS) {
			return this.expandedBag.get(ffxivSlotIndex - SLOTS_PER_PAGE);
		}
		ensureKeyItemsSeeded();
		return this.keyItemsAndCrystals.get(ffxivSlotIndex - TOTAL_MAIN_SLOTS);
	}

	public void setSlot(@Nullable Player player, int ffxivSlotIndex, ItemStack stack) {
		ItemStack safe = stack == null ? ItemStack.EMPTY : stack;
		if (ffxivSlotIndex < 0 || ffxivSlotIndex >= TOTAL_CONTAINER_SLOTS) {
			return;
		}
		if (ffxivSlotIndex < SLOTS_PER_PAGE) {
			if (player != null) {
				player.getInventory().setItem(ffxivSlotIndex, safe);
			}
			return;
		}
		if (ffxivSlotIndex < TOTAL_MAIN_SLOTS) {
			this.expandedBag.set(ffxivSlotIndex - SLOTS_PER_PAGE, safe);
			return;
		}
		ensureKeyItemsSeeded();
		this.keyItemsAndCrystals.set(ffxivSlotIndex - TOTAL_MAIN_SLOTS, safe);
	}

	/**
	 * Counts how many of the 140 main FFXIV inventory slots are currently occupied.
	 */
	public int getOccupiedMainSlotCount(@Nullable Player player) {
		int occupied = 0;
		for (int i = 0; i < TOTAL_MAIN_SLOTS; i++) {
			if (!getSlot(player, i).isEmpty()) {
				occupied++;
			}
		}
		return occupied;
	}

	/**
	 * Swaps or merges two FFXIV inventory slots (0..174).
	 */
	public boolean swapOrMergeSlots(@Nullable Player player, int fromSlot, int toSlot) {
		if (fromSlot == toSlot
			|| fromSlot < 0 || fromSlot >= TOTAL_CONTAINER_SLOTS
			|| toSlot < 0 || toSlot >= TOTAL_CONTAINER_SLOTS) {
			return false;
		}
		ItemStack fromStack = getSlot(player, fromSlot);
		ItemStack toStack = getSlot(player, toSlot);
		if (fromStack.isEmpty() && toStack.isEmpty()) {
			return false;
		}

		// Merge partial stacks if identical item & components
		if (!fromStack.isEmpty() && !toStack.isEmpty() && ItemStack.isSameItemSameComponents(fromStack, toStack)) {
			int maxStack = toStack.getMaxStackSize();
			int space = maxStack - toStack.getCount();
			if (space > 0) {
				int transfer = Math.min(space, fromStack.getCount());
				ItemStack nextTo = toStack.copy();
				nextTo.setCount(toStack.getCount() + transfer);
				ItemStack nextFrom = fromStack.copy();
				nextFrom.setCount(fromStack.getCount() - transfer);
				setSlot(player, toSlot, nextTo);
				setSlot(player, fromSlot, nextFrom.getCount() <= 0 ? ItemStack.EMPTY : nextFrom);
				if (player != null) {
					player.getInventory().setChanged();
				}
				return true;
			}
		}

		setSlot(player, fromSlot, toStack.copy());
		setSlot(player, toSlot, fromStack.copy());
		if (player != null) {
			player.getInventory().setChanged();
		}
		return true;
	}

	/**
	 * Handles Right-Click on a slot in the FFXIV Inventory:
	 * - If the item is a Job Soul Crystal in Key Items, returns the Job ID to switch to.
	 * - If the item is equippable armor (`Equippable`), swaps it with the player's corresponding armor slot.
	 * - Otherwise moves the item between Page 1 (live inventory) and Pages 2-4 (expanded bag).
	 */
	public @Nullable Identifier handleRightClickSlot(Player player, int slotIndex) {
		ItemStack stack = getSlot(player, slotIndex);
		if (stack.isEmpty()) {
			return null;
		}

		// Check if it's a Job Soul Crystal
		String hoverName = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
		if (hoverName.contains("soul of the vanguard")) {
			return Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "vanguard");
		}
		if (hoverName.contains("soul of the hierophant")) {
			return Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "hierophant");
		}
		if (hoverName.contains("soul of the lancer")) {
			return Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "lancer");
		}
		if (hoverName.contains("soul of the arcanist")) {
			return Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "arcanist");
		}

		// Check if equippable armor/offhand
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable != null) {
			EquipmentSlot eqSlot = equippable.slot();
			if (eqSlot.isArmor() || eqSlot == EquipmentSlot.OFFHAND) {
				ItemStack currentEquipped = player.getItemBySlot(eqSlot);
				player.setItemSlot(eqSlot, stack.copy());
				setSlot(player, slotIndex, currentEquipped.copy());
				player.getInventory().setChanged();
				return null;
			}
		}

		// Otherwise quick-transfer between Page 1 (0..34) and Expanded Pages 2-4 (35..139)
		if (slotIndex < SLOTS_PER_PAGE) {
			for (int dest = SLOTS_PER_PAGE; dest < TOTAL_MAIN_SLOTS; dest++) {
				if (getSlot(player, dest).isEmpty()) {
					swapOrMergeSlots(player, slotIndex, dest);
					return null;
				}
			}
		} else if (slotIndex < TOTAL_MAIN_SLOTS) {
			for (int dest = 0; dest < SLOTS_PER_PAGE; dest++) {
				if (getSlot(player, dest).isEmpty()) {
					swapOrMergeSlots(player, slotIndex, dest);
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Performs FFXIV-style Inventory Sort & Auto-Stack across all 140 main inventory slots:
	 * 1. Consolidates partial stacks of identical items.
	 * 2. Sorts by {@link ItemCategory} (Gear/Weapons -> Consumables/Food -> Materials/Crystals -> Blocks/Misc),
	 *    then alphabetically by item name, then by stack count descending.
	 */
	public void sortMainInventory(@Nullable Player player) {
		List<ItemStack> collected = new ArrayList<>();
		for (int i = 0; i < TOTAL_MAIN_SLOTS; i++) {
			ItemStack s = getSlot(player, i);
			if (!s.isEmpty()) {
				mergeIntoList(collected, s.copy());
			}
		}

		collected.sort(Comparator
			.comparingInt((ItemStack s) -> classifyItem(s).sortOrder())
			.thenComparing(s -> s.getHoverName().getString())
			.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed())
		);

		for (int i = 0; i < TOTAL_MAIN_SLOTS; i++) {
			setSlot(player, i, i < collected.size() ? collected.get(i) : ItemStack.EMPTY);
		}
		if (player != null) {
			player.getInventory().setChanged();
		}
	}

	private static void mergeIntoList(List<ItemStack> list, ItemStack incoming) {
		for (ItemStack existing : list) {
			if (ItemStack.isSameItemSameComponents(existing, incoming)) {
				int space = existing.getMaxStackSize() - existing.getCount();
				if (space > 0) {
					int move = Math.min(space, incoming.getCount());
					existing.setCount(existing.getCount() + move);
					incoming.setCount(incoming.getCount() - move);
					if (incoming.getCount() <= 0) {
						return;
					}
				}
			}
		}
		if (incoming.getCount() > 0) {
			list.add(incoming);
		}
	}

	public List<ItemStack> getExpandedBagCopy() {
		List<ItemStack> copy = new ArrayList<>(this.expandedBag.size());
		for (ItemStack s : this.expandedBag) {
			copy.add(s.copy());
		}
		return copy;
	}

	public List<ItemStack> getKeyItemsCopy() {
		ensureKeyItemsSeeded();
		List<ItemStack> copy = new ArrayList<>(this.keyItemsAndCrystals.size());
		for (ItemStack s : this.keyItemsAndCrystals) {
			copy.add(s.copy());
		}
		return copy;
	}

	public void applyClientSync(List<ItemStack> syncedExpanded, List<ItemStack> syncedKeyItems) {
		this.keyItemsSeeded = true;
		for (int i = 0; i < this.expandedBag.size(); i++) {
			this.expandedBag.set(i, i < syncedExpanded.size() ? syncedExpanded.get(i) : ItemStack.EMPTY);
		}
		for (int i = 0; i < this.keyItemsAndCrystals.size(); i++) {
			this.keyItemsAndCrystals.set(i, i < syncedKeyItems.size() ? syncedKeyItems.get(i) : ItemStack.EMPTY);
		}
	}
}
