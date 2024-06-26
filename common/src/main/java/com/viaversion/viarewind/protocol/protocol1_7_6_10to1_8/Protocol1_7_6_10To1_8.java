/*
 * This file is part of ViaRewind - https://github.com/ViaVersion/ViaRewind
 * Copyright (C) 2018-2024 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8;

import com.viaversion.viabackwards.api.BackwardsProtocol;
import com.viaversion.viarewind.ViaRewind;
import com.viaversion.viarewind.api.data.RewindMappings;
import com.viaversion.viarewind.protocol.protocol1_7_2_5to1_7_6_10.ClientboundPackets1_7_2_5;
import com.viaversion.viarewind.protocol.protocol1_7_2_5to1_7_6_10.ServerboundPackets1_7_2_5;
import com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8.metadata.MetadataRewriter1_7_6_10To1_8;
import com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8.packets.*;
import com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8.provider.CompressionHandlerProvider;
import com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8.provider.compression.TrackingCompressionHandlerProvider;
import com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8.storage.*;
import com.viaversion.viarewind.protocol.protocol1_7_6_10to1_8.task.WorldBorderUpdateTask;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.ClientWorld;
import com.viaversion.viaversion.api.platform.providers.ViaProviders;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.base.ServerboundLoginPackets;
import com.viaversion.viaversion.protocols.protocol1_8.ClientboundPackets1_8;
import com.viaversion.viaversion.protocols.protocol1_8.ServerboundPackets1_8;

import java.util.concurrent.TimeUnit;

public class Protocol1_7_6_10To1_8 extends BackwardsProtocol<ClientboundPackets1_8, ClientboundPackets1_7_2_5, ServerboundPackets1_8, ServerboundPackets1_7_2_5> {

	public static final RewindMappings MAPPINGS = new RewindMappings("1.8", "1.7.10");

	private final BlockItemPackets1_8 itemRewriter = new BlockItemPackets1_8(this);
	private final MetadataRewriter1_7_6_10To1_8 metadataRewriter = new MetadataRewriter1_7_6_10To1_8(this);

	public Protocol1_7_6_10To1_8() {
		super(ClientboundPackets1_8.class, ClientboundPackets1_7_2_5.class, ServerboundPackets1_8.class, ServerboundPackets1_7_2_5.class);
	}

	@Override
	protected void registerPackets() {
		itemRewriter.register();
		metadataRewriter.register();

		EntityPackets1_8.register(this);
		PlayerPackets1_8.register(this);
		ScoreboardPackets1_8.register(this);
		WorldPackets1_8.register(this);

		this.registerClientbound(State.LOGIN, ClientboundLoginPackets.HELLO.getId(), ClientboundLoginPackets.HELLO.getId(), new PacketHandlers() {
			@Override
			public void register() {
				map(Type.STRING); // server hash
				map(Type.BYTE_ARRAY_PRIMITIVE, Type.SHORT_BYTE_ARRAY); // public key
				map(Type.BYTE_ARRAY_PRIMITIVE, Type.SHORT_BYTE_ARRAY); // verification token
			}
		});
		this.registerClientbound(State.LOGIN, ClientboundLoginPackets.LOGIN_COMPRESSION.getId(), ClientboundLoginPackets.LOGIN_COMPRESSION.getId(), new PacketHandlers() {
			@Override
			public void register() {
				handler(wrapper -> {
					final int threshold = wrapper.read(Type.VAR_INT);

					Via.getManager().getProviders().get(CompressionHandlerProvider.class).onHandleLoginCompressionPacket(wrapper.user(), threshold);
					wrapper.cancel();
				});
			}
		});
		this.cancelClientbound(ClientboundPackets1_8.SET_COMPRESSION); // unused
		this.registerClientbound(ClientboundPackets1_8.KEEP_ALIVE, new PacketHandlers() {
			@Override
			public void register() {
				map(Type.VAR_INT, Type.INT); // id
			}
		});

		this.registerServerbound(State.LOGIN, ServerboundLoginPackets.ENCRYPTION_KEY.getId(), ServerboundLoginPackets.ENCRYPTION_KEY.getId(), new PacketHandlers() {
			@Override
			public void register() {
				map(Type.SHORT_BYTE_ARRAY, Type.BYTE_ARRAY_PRIMITIVE); // shared secret
				map(Type.SHORT_BYTE_ARRAY, Type.BYTE_ARRAY_PRIMITIVE); // verification token
			}
		});

		this.registerServerbound(ServerboundPackets1_7_2_5.KEEP_ALIVE, new PacketHandlers() {
			@Override
			public void register() {
				map(Type.INT, Type.VAR_INT); // id
			}
		});
	}

	@Override
	public void transform(Direction direction, State state, PacketWrapper packetWrapper) throws Exception {
		Via.getManager().getProviders().get(CompressionHandlerProvider.class).onTransformPacket(packetWrapper.user());

		super.transform(direction, state, packetWrapper);
	}

	@Override
	public void init(UserConnection connection) {
		connection.addEntityTracker(this.getClass(), new EntityTracker1_8(connection));

		connection.put(new InventoryTracker(connection));
		connection.put(new PlayerSessionStorage(connection));
		connection.put(new GameProfileStorage(connection));
		connection.put(new Scoreboard(connection));
		connection.put(new CompressionStatusTracker(connection));
		connection.put(new WorldBorderEmulator(connection));

		if (!connection.has(ClientWorld.class)) {
			connection.put(new ClientWorld());
		}
	}

	@Override
	public void register(ViaProviders providers) {
		providers.register(CompressionHandlerProvider.class, new TrackingCompressionHandlerProvider());

		if (ViaRewind.getConfig().isEmulateWorldBorder()) {
			Via.getManager().getScheduler().scheduleRepeating(new WorldBorderUpdateTask(), 0L, 50L, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public RewindMappings getMappingData() {
		return MAPPINGS;
	}

	@Override
	public BlockItemPackets1_8 getItemRewriter() {
		return itemRewriter;
	}

	@Override
	public MetadataRewriter1_7_6_10To1_8 getEntityRewriter() {
		return metadataRewriter;
	}

	@Override
	public boolean hasMappingDataToLoad() {
		return true;
	}
}
