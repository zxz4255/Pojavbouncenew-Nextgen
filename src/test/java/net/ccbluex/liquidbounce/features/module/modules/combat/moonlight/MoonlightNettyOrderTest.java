/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.api.ViaFabricPlusAPI;
import com.viaversion.viafabricplus.api.protocoltranslator.Conversions;
import com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.debug.DebugHandler;
import com.viaversion.viaversion.api.minecraft.item.DataItem;
import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.MessageToMessageEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Netty LocalChannel, Minecraft Connection and Via UserConnection/PacketWrapper paths.
 * The MC-to-1.8 translator and ViaFabricPlus item conversion are deliberately stubbed: this
 * checks asynchronous interleaving, not real Via translation or a live 1.8 server.
 */
class MoonlightNettyOrderTest {
    private final LinkedBlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
    private final Connection minecraft = new Connection(PacketFlow.CLIENTBOUND);
    private EventLoopGroup group;
    private Channel server;
    private Channel client;
    private Field viaManagerField;
    private Field vfpApiField;
    private Object previousViaManager;
    private Object previousVfpApi;

    @BeforeEach
    void setUp() throws Exception {
        ProtocolManager protocolManager = stub(ProtocolManager.class, (self, method, args) -> {
            if (method.getName().equals("createPacketWrapper")) {
                return new PacketWrapperImpl((PacketType) args[0], (ByteBuf) args[1], (UserConnection) args[2]);
            }
            return defaultValue(method.getReturnType());
        });
        ViaInjector injector = stub(ViaInjector.class, (self, method, args) ->
            method.getName().equals("getEncoderName") ? "via-encoder" : defaultValue(method.getReturnType()));
        DebugHandler debug = stub(DebugHandler.class, (self, method, args) -> defaultValue(method.getReturnType()));
        ViaManager manager = stub(ViaManager.class, (self, method, args) -> switch (method.getName()) {
            case "getProtocolManager" -> protocolManager;
            case "getInjector" -> injector;
            case "debugHandler" -> debug;
            default -> defaultValue(method.getReturnType());
        });
        viaManagerField = Via.class.getDeclaredField("manager");
        viaManagerField.setAccessible(true);
        previousViaManager = viaManagerField.get(null);
        viaManagerField.set(null, manager);

        group = new DefaultEventLoopGroup(1);
        var address = new LocalAddress("moonlight-" + UUID.randomUUID());
        server = new ServerBootstrap().group(group).channel(LocalServerChannel.class)
            .childHandler(new ChannelInitializer<LocalChannel>() {
                @Override protected void initChannel(LocalChannel channel) {
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                            byte[] bytes = new byte[msg.readableBytes()];
                            msg.readBytes(bytes);
                            received.add(bytes);
                        }
                    });
                }
            }).bind(address).sync().channel();
        client = new Bootstrap().group(group).channel(LocalChannel.class)
            .handler(new ChannelInitializer<LocalChannel>() {
                @Override protected void initChannel(LocalChannel channel) {
                    // A raw Via packet starts immediately before this context. A Minecraft
                    // packet passes through the mock translator and then the same context.
                    channel.pipeline().addLast("via-encoder", new ChannelOutboundHandlerAdapter());
                    channel.pipeline().addLast("mock-mc-encoder", new MessageToMessageEncoder<Packet<?>>() {
                        @Override protected void encode(ChannelHandlerContext ctx, Packet<?> packet, List<Object> out) {
                            ByteBuf buf = ctx.alloc().buffer(1);
                            if (packet instanceof ServerboundMovePlayerPacket) buf.writeByte(0x03);
                            else if (packet instanceof ServerboundAttackPacket) buf.writeByte(0x02);
                            else throw new AssertionError("Unexpected packet " + packet);
                            out.add(buf);
                        }
                    });
                    channel.pipeline().addLast("minecraft-connection", minecraft);
                }
            }).connect(address).sync().channel();
        // LocalChannel can complete connect before it fires channelActive on the event loop.
        client.eventLoop().submit(() -> {}).sync();
        assertTrue(minecraft.isConnected(), "Minecraft Connection did not receive channelActive");

        var user = new UserConnectionImpl(client, true);
        user.getProtocolInfo().setServerState(State.PLAY);
        user.getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v1_8);
        ProtocolTranslation translation = stub(ProtocolTranslation.class, (self, method, args) ->
            method.getName().equals("targetVersion") ? ProtocolVersion.v1_8 : defaultValue(method.getReturnType()));
        Conversions conversions = stub(Conversions.class, (self, method, args) ->
            method.getName().equals("translateItem") ? new DataItem(322, (byte) 1, (short) 0, null) :
                defaultValue(method.getReturnType()));
        ViaFabricPlusAPI api = stub(ViaFabricPlusAPI.class, (self, method, args) -> switch (method.getName()) {
            case "userConnection" -> user;
            case "protocolTranslation" -> translation;
            case "conversions" -> conversions;
            default -> defaultValue(method.getReturnType());
        });
        vfpApiField = ViaFabricPlus.class.getDeclaredField("api");
        vfpApiField.setAccessible(true);
        previousVfpApi = vfpApiField.get(null);
        vfpApiField.set(null, api);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close().syncUninterruptibly();
        if (server != null) server.close().syncUninterruptibly();
        if (group != null) group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        if (vfpApiField != null) vfpApiField.set(null, previousVfpApi);
        if (viaManagerField != null) viaManagerField.set(null, previousViaManager);
    }

    private void submitBurst() {
        MoonlightLegacyPackets.sendBurst(null, 3, 6, () -> {
            for (int i = 0; i < 32; i++) {
                minecraft.send(new ServerboundMovePlayerPacket.StatusOnly(true, false), null);
            }
        });
        minecraft.send(new ServerboundAttackPacket(42), null);
    }

    @Test
    void rawViaAndMinecraftPacketsRemainOrderedFromGameThread() throws Exception {
        assertFalse(client.eventLoop().inEventLoop());
        submitBurst();
        checkReceivedBurst();
    }

    @Test
    void rawViaAndMinecraftPacketsRemainOrderedFromEventLoop() throws Exception {
        client.eventLoop().submit(this::submitBurst).sync();
        checkReceivedBurst();
    }

    private void checkReceivedBurst() throws InterruptedException {
        List<byte[]> wire = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            byte[] packet = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(packet, "Missing packet at position " + i);
            wire.add(packet);
        }
        assertArrayEquals(new byte[]{0x09, 0, 3}, wire.get(0));
        assertEquals(0x08, wire.get(1)[0] & 0xff);
        for (int i = 2; i < 34; i++) assertArrayEquals(new byte[]{0x03}, wire.get(i));
        assertArrayEquals(new byte[]{0x09, 0, 6}, wire.get(34));
        assertArrayEquals(new byte[]{0x02}, wire.get(35));
        assertNull(received.poll(100, TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        throw new AssertionError(type);
    }
}
