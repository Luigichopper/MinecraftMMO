package net.xivmmo.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.xivmmo.XivMmoMod;
import net.xivmmo.combat.ability.AbilityDefinition;
import net.xivmmo.combat.telegraph.TelegraphShapes;
import net.xivmmo.progression.JobProgressionState;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines and registers all C2S and S2C networking payloads for targeting, ability execution,
 * status/enmity HUD synchronization, world telegraphs, FFXIV Job progression, and the 140-slot FFXIV Inventory.
 */
public final class MmoNetwork {
	private static boolean registered = false;

	private MmoNetwork() {
	}

	public static synchronized void registerPayloadTypes() {
		if (registered) {
			return;
		}
		registered = true;

		PayloadTypeRegistry.serverboundPlay().register(C2SSetTargetPayload.TYPE, C2SSetTargetPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SUseAbilityPayload.TYPE, C2SUseAbilityPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SSpawnTestTelegraphPayload.TYPE, C2SSpawnTestTelegraphPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SSelectJobPayload.TYPE, C2SSelectJobPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SSpawnArenaBossPayload.TYPE, C2SSpawnArenaBossPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SInventoryActionPayload.TYPE, C2SInventoryActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SLevelCommandPayload.TYPE, C2SLevelCommandPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SChatChannelPayload.TYPE, C2SChatChannelPayload.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(S2CAbilityResponsePayload.TYPE, S2CAbilityResponsePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CCastStatusPayload.TYPE, S2CCastStatusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CTargetOfTargetPayload.TYPE, S2CTargetOfTargetPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CTelegraphSyncPayload.TYPE, S2CTelegraphSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CCombatHudSyncPayload.TYPE, S2CCombatHudSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CCombatTextPayload.TYPE, S2CCombatTextPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CPartySyncPayload.TYPE, S2CPartySyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CProgressionSyncPayload.TYPE, S2CProgressionSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CInventorySyncPayload.TYPE, S2CInventorySyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CChatChannelPayload.TYPE, S2CChatChannelPayload.CODEC);
	}

	public record C2SSetTargetPayload(int hardTargetEntityId, int focusTargetEntityId) implements CustomPacketPayload {
		public static final Type<C2SSetTargetPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_set_target")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SSetTargetPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.hardTargetEntityId());
				buf.writeVarInt(p.focusTargetEntityId());
			},
			buf -> new C2SSetTargetPayload(buf.readVarInt(), buf.readVarInt())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SUseAbilityPayload(
		int sequenceId,
		Identifier abilityId,
		int targetEntityId,
		double groundX,
		double groundY,
		double groundZ
	) implements CustomPacketPayload {
		public static final Type<C2SUseAbilityPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_use_ability")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SUseAbilityPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.sequenceId());
				buf.writeIdentifier(p.abilityId());
				buf.writeVarInt(p.targetEntityId());
				buf.writeDouble(p.groundX());
				buf.writeDouble(p.groundY());
				buf.writeDouble(p.groundZ());
			},
			buf -> new C2SUseAbilityPayload(
				buf.readVarInt(),
				buf.readIdentifier(),
				buf.readVarInt(),
				buf.readDouble(),
				buf.readDouble(),
				buf.readDouble()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SSpawnTestTelegraphPayload(
		AbilityDefinition.AoeShape shape,
		float radius,
		float innerRadius,
		float coneAngleDegrees,
		int durationTicks
	) implements CustomPacketPayload {
		public static final Type<C2SSpawnTestTelegraphPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_spawn_test_telegraph")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SSpawnTestTelegraphPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeEnum(p.shape());
				buf.writeFloat(p.radius());
				buf.writeFloat(p.innerRadius());
				buf.writeFloat(p.coneAngleDegrees());
				buf.writeVarInt(p.durationTicks());
			},
			buf -> new C2SSpawnTestTelegraphPayload(
				buf.readEnum(AbilityDefinition.AoeShape.class),
				buf.readFloat(),
				buf.readFloat(),
				buf.readFloat(),
				buf.readVarInt()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CAbilityResponsePayload(
		int sequenceId,
		Identifier abilityId,
		boolean accepted,
		String statusMessage,
		int gcdRemainingTicks,
		int animLockRemainingTicks,
		int castTotalTicks,
		int chargesRemaining,
		int rechargeRemainingTicks,
		int currentMana
	) implements CustomPacketPayload {
		public static final Type<S2CAbilityResponsePayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_ability_response")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CAbilityResponsePayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.sequenceId());
				buf.writeIdentifier(p.abilityId());
				buf.writeBoolean(p.accepted());
				buf.writeUtf(p.statusMessage());
				buf.writeVarInt(p.gcdRemainingTicks());
				buf.writeVarInt(p.animLockRemainingTicks());
				buf.writeVarInt(p.castTotalTicks());
				buf.writeVarInt(p.chargesRemaining());
				buf.writeVarInt(p.rechargeRemainingTicks());
				buf.writeVarInt(p.currentMana());
			},
			buf -> new S2CAbilityResponsePayload(
				buf.readVarInt(),
				buf.readIdentifier(),
				buf.readBoolean(),
				buf.readUtf(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CCastStatusPayload(
		int casterEntityId,
		Identifier abilityId,
		boolean completed,
		boolean interrupted,
		String reason
	) implements CustomPacketPayload {
		public static final Type<S2CCastStatusPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_cast_status")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CCastStatusPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.casterEntityId());
				buf.writeIdentifier(p.abilityId());
				buf.writeBoolean(p.completed());
				buf.writeBoolean(p.interrupted());
				buf.writeUtf(p.reason());
			},
			buf -> new S2CCastStatusPayload(
				buf.readVarInt(),
				buf.readIdentifier(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readUtf()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CTargetOfTargetPayload(int entityId, int targetOfTargetEntityId) implements CustomPacketPayload {
		public static final Type<S2CTargetOfTargetPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_target_of_target")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CTargetOfTargetPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId());
				buf.writeVarInt(p.targetOfTargetEntityId());
			},
			buf -> new S2CTargetOfTargetPayload(buf.readVarInt(), buf.readVarInt())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CTelegraphSyncPayload(List<TelegraphShapes.ActiveTelegraph> telegraphs) implements CustomPacketPayload {
		public static final Type<S2CTelegraphSyncPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_telegraph_sync")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CTelegraphSyncPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.telegraphs().size());
				for (TelegraphShapes.ActiveTelegraph t : p.telegraphs()) {
					TelegraphShapes.ActiveTelegraph.STREAM_CODEC.encode(buf, t);
				}
			},
			buf -> {
				int count = buf.readVarInt();
				List<TelegraphShapes.ActiveTelegraph> list = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					list.add(TelegraphShapes.ActiveTelegraph.STREAM_CODEC.decode(buf));
				}
				return new S2CTelegraphSyncPayload(list);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record StatusBadge(String displayName, boolean buff, int remainingSeconds, int stacks) {
		public static final StreamCodec<FriendlyByteBuf, StatusBadge> STREAM_CODEC = StreamCodec.of(
			(buf, b) -> {
				buf.writeUtf(b.displayName());
				buf.writeBoolean(b.buff());
				buf.writeVarInt(b.remainingSeconds());
				buf.writeVarInt(b.stacks());
			},
			buf -> new StatusBadge(buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt())
		);
	}

	public record S2CCombatHudSyncPayload(
		int enmityRank,
		float enmityRatio,
		List<StatusBadge> playerStatuses,
		List<StatusBadge> targetStatuses
	) implements CustomPacketPayload {
		public static final Type<S2CCombatHudSyncPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_combat_hud_sync")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CCombatHudSyncPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.enmityRank());
				buf.writeFloat(p.enmityRatio());
				buf.writeVarInt(p.playerStatuses().size());
				for (StatusBadge b : p.playerStatuses()) {
					StatusBadge.STREAM_CODEC.encode(buf, b);
				}
				buf.writeVarInt(p.targetStatuses().size());
				for (StatusBadge b : p.targetStatuses()) {
					StatusBadge.STREAM_CODEC.encode(buf, b);
				}
			},
			buf -> {
				int rank = buf.readVarInt();
				float ratio = buf.readFloat();
				int pCount = buf.readVarInt();
				List<StatusBadge> pList = new ArrayList<>(pCount);
				for (int i = 0; i < pCount; i++) {
					pList.add(StatusBadge.STREAM_CODEC.decode(buf));
				}
				int tCount = buf.readVarInt();
				List<StatusBadge> tList = new ArrayList<>(tCount);
				for (int i = 0; i < tCount; i++) {
					tList.add(StatusBadge.STREAM_CODEC.decode(buf));
				}
				return new S2CCombatHudSyncPayload(rank, ratio, pList, tList);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SSelectJobPayload(Identifier jobId) implements CustomPacketPayload {
		public static final Type<C2SSelectJobPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_select_job")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SSelectJobPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeIdentifier(p.jobId()),
			buf -> new C2SSelectJobPayload(buf.readIdentifier())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SSpawnArenaBossPayload(String arenaId) implements CustomPacketPayload {
		public static final Type<C2SSpawnArenaBossPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_spawn_arena_boss")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SSpawnArenaBossPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeUtf(p.arenaId()),
			buf -> new C2SSpawnArenaBossPayload(buf.readUtf())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CCombatTextPayload(
		int targetEntityId,
		String text,
		int color,
		boolean emphasize
	) implements CustomPacketPayload {
		public static final Type<S2CCombatTextPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_combat_text")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CCombatTextPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.targetEntityId());
				buf.writeUtf(p.text());
				buf.writeInt(p.color());
				buf.writeBoolean(p.emphasize());
			},
			buf -> new S2CCombatTextPayload(
				buf.readVarInt(),
				buf.readUtf(),
				buf.readInt(),
				buf.readBoolean()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record PartyMemberEntry(
		int entityId,
		String name,
		Identifier jobId,
		String role,
		int level,
		float hp,
		float maxHp,
		int mana,
		int maxMana,
		int enmityRank,
		String topStatus
	) {
		public static final StreamCodec<FriendlyByteBuf, PartyMemberEntry> STREAM_CODEC = StreamCodec.of(
			(buf, m) -> {
				buf.writeVarInt(m.entityId());
				buf.writeUtf(m.name());
				buf.writeIdentifier(m.jobId());
				buf.writeUtf(m.role());
				buf.writeVarInt(m.level());
				buf.writeFloat(m.hp());
				buf.writeFloat(m.maxHp());
				buf.writeVarInt(m.mana());
				buf.writeVarInt(m.maxMana());
				buf.writeVarInt(m.enmityRank());
				buf.writeUtf(m.topStatus());
			},
			buf -> new PartyMemberEntry(
				buf.readVarInt(),
				buf.readUtf(),
				buf.readIdentifier(),
				buf.readUtf(),
				buf.readVarInt(),
				buf.readFloat(),
				buf.readFloat(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readUtf()
			)
		);
	}

	public record S2CPartySyncPayload(
		Identifier activeJobId,
		List<PartyMemberEntry> members
	) implements CustomPacketPayload {
		public static final Type<S2CPartySyncPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_party_sync")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CPartySyncPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeIdentifier(p.activeJobId());
				buf.writeVarInt(p.members().size());
				for (PartyMemberEntry m : p.members()) {
					PartyMemberEntry.STREAM_CODEC.encode(buf, m);
				}
			},
			buf -> {
				Identifier jobId = buf.readIdentifier();
				int count = buf.readVarInt();
				List<PartyMemberEntry> list = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					list.add(PartyMemberEntry.STREAM_CODEC.decode(buf));
				}
				return new S2CPartySyncPayload(jobId, list);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CProgressionSyncPayload(
		Identifier activeJobId,
		List<JobProgressionState.JobLevelEntry> jobs,
		int armouryBonusPercent,
		long gil
	) implements CustomPacketPayload {
		public static final Type<S2CProgressionSyncPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_progression_sync")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CProgressionSyncPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeIdentifier(p.activeJobId());
				buf.writeVarInt(p.jobs().size());
				for (JobProgressionState.JobLevelEntry e : p.jobs()) {
					JobProgressionState.JobLevelEntry.STREAM_CODEC.encode(buf, e);
				}
				buf.writeVarInt(p.armouryBonusPercent());
				buf.writeVarLong(p.gil());
			},
			buf -> {
				Identifier activeId = buf.readIdentifier();
				int count = buf.readVarInt();
				List<JobProgressionState.JobLevelEntry> list = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					list.add(JobProgressionState.JobLevelEntry.STREAM_CODEC.decode(buf));
				}
				int bonus = buf.readVarInt();
				long gilVal = buf.readVarLong();
				return new S2CProgressionSyncPayload(activeId, list, bonus, gilVal);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public enum InventoryActionType {
		SWAP_SLOTS,
		RIGHT_CLICK_SLOT,
		SORT_INVENTORY,
		REQUEST_SYNC
	}

	public record C2SInventoryActionPayload(
		InventoryActionType actionType,
		int firstSlot,
		int secondSlot
	) implements CustomPacketPayload {
		public static final Type<C2SInventoryActionPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_inventory_action")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SInventoryActionPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeEnum(p.actionType());
				buf.writeVarInt(p.firstSlot());
				buf.writeVarInt(p.secondSlot());
			},
			buf -> new C2SInventoryActionPayload(
				buf.readEnum(InventoryActionType.class),
				buf.readVarInt(),
				buf.readVarInt()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CInventorySyncPayload(
		List<ItemStack> expandedBag,
		List<ItemStack> keyItemsAndCrystals,
		long gil
	) implements CustomPacketPayload {
		public static final Type<S2CInventorySyncPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_inventory_sync")
		);

		public static final StreamCodec<RegistryFriendlyByteBuf, S2CInventorySyncPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.expandedBag());
				ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.keyItemsAndCrystals());
				buf.writeVarLong(p.gil());
			},
			buf -> new S2CInventorySyncPayload(
				ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf),
				ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf),
				buf.readVarLong()
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SLevelCommandPayload(
		boolean isSetLevel,
		int value
	) implements CustomPacketPayload {
		public static final Type<C2SLevelCommandPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_level_command")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SLevelCommandPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.isSetLevel());
				buf.writeVarInt(p.value());
			},
			buf -> new C2SLevelCommandPayload(buf.readBoolean(), buf.readVarInt())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SChatChannelPayload(
		String channelName,
		String targetPlayer,
		String messageBody
	) implements CustomPacketPayload {
		public static final Type<C2SChatChannelPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "c2s_chat_channel")
		);

		public static final StreamCodec<FriendlyByteBuf, C2SChatChannelPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.channelName());
				buf.writeUtf(p.targetPlayer());
				buf.writeUtf(p.messageBody());
			},
			buf -> new C2SChatChannelPayload(
				buf.readUtf(32),
				buf.readUtf(64),
				buf.readUtf(512)
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CChatChannelPayload(
		String channelName,
		String senderName,
		String formattedText
	) implements CustomPacketPayload {
		public static final Type<S2CChatChannelPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(XivMmoMod.MOD_ID, "s2c_chat_channel")
		);

		public static final StreamCodec<FriendlyByteBuf, S2CChatChannelPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.channelName());
				buf.writeUtf(p.senderName());
				buf.writeUtf(p.formattedText());
			},
			buf -> new S2CChatChannelPayload(
				buf.readUtf(32),
				buf.readUtf(64),
				buf.readUtf(512)
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
