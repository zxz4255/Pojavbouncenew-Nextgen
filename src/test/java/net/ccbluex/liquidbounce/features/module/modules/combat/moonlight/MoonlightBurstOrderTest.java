/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.api.ViaFabricPlusAPI;
import com.viaversion.viafabricplus.api.protocoltranslator.Conversions;
import com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.DataItem;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ServerboundPackets1_8;
import io.netty.channel.Channel;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual adapter's ordering with stubbed Via transports, not a live server. */
class MoonlightBurstOrderTest {
    private final List<String> sent = new ArrayList<>();
    private ProtocolVersion target = ProtocolVersion.v1_8;
    private Object oldViaManager;
    private Object oldVfpApi;
    private Field viaManagerField;
    private Field vfpApiField;

    @BeforeEach
    void installStubTransport() throws Exception {
        Channel channel = stub(Channel.class, (self, method, args) -> switch (method.getName()) {
            case "isOpen" -> true;
            default -> defaultValue(method.getReturnType());
        });
        ProtocolInfo info = stub(ProtocolInfo.class, (self, method, args) -> switch (method.getName()) {
            case "getServerState" -> State.PLAY;
            case "serverProtocolVersion" -> target;
            default -> defaultValue(method.getReturnType());
        });
        UserConnection user = stub(UserConnection.class, (self, method, args) -> switch (method.getName()) {
            case "isClientSide", "isActive" -> true;
            case "isPendingDisconnect" -> false;
            case "getChannel" -> channel;
            case "getProtocolInfo" -> info;
            default -> defaultValue(method.getReturnType());
        });
        ProtocolTranslation translation = stub(ProtocolTranslation.class, (self, method, args) ->
            method.getName().equals("targetVersion") ? target : defaultValue(method.getReturnType()));
        Conversions conversions = stub(Conversions.class, (self, method, args) ->
            method.getName().equals("translateItem") ? new DataItem(322, (byte) 1, (short) 0, null) :
                defaultValue(method.getReturnType()));
        ViaFabricPlusAPI api = stub(ViaFabricPlusAPI.class, (self, method, args) -> switch (method.getName()) {
            case "userConnection" -> user;
            case "protocolTranslation" -> translation;
            case "conversions" -> conversions;
            default -> defaultValue(method.getReturnType());
        });
        ProtocolManager protocolManager = stub(ProtocolManager.class, (self, method, args) -> {
            if (method.getName().equals("createPacketWrapper")) {
                PacketType kind = (PacketType) args[0];
                int[] slot = {-1};
                return stub(PacketWrapper.class, (wrapper, called, values) -> switch (called.getName()) {
                    case "write" -> {
                        if (kind == ServerboundPackets1_8.SET_CARRIED_ITEM) slot[0] = ((Number) values[1]).intValue();
                        yield null;
                    }
                    case "sendToServerRaw" -> {
                        sent.add(kind == ServerboundPackets1_8.SET_CARRIED_ITEM ? "C09:" + slot[0] :
                            kind == ServerboundPackets1_8.INTERACT ? "C02" : "C08");
                        yield null;
                    }
                    default -> defaultValue(called.getReturnType());
                });
            }
            return defaultValue(method.getReturnType());
        });
        ViaManager manager = stub(ViaManager.class, (self, method, args) ->
            method.getName().equals("getProtocolManager") ? protocolManager : defaultValue(method.getReturnType()));

        // ViaFabricPlus is intentionally disabled in the unit-test loader; install and restore
        // isolated stubs so this does not need a game or mutate any real connection.
        viaManagerField = Via.class.getDeclaredField("manager");
        vfpApiField = ViaFabricPlus.class.getDeclaredField("api");
        viaManagerField.setAccessible(true);
        vfpApiField.setAccessible(true);
        oldViaManager = viaManagerField.get(null);
        oldVfpApi = vfpApiField.get(null);
        viaManagerField.set(null, manager);
        vfpApiField.set(null, api);
    }

    @AfterEach
    void restoreGlobalApis() throws Exception {
        if (viaManagerField != null) viaManagerField.set(null, oldViaManager);
        if (vfpApiField != null) vfpApiField.set(null, oldVfpApi);
    }

    @Test
    void switchesUsesReplaysAndRestoresInOrder() {
        assertTrue(MoonlightLegacyPackets.isAvailable());
        MoonlightLegacyPackets.sendBurst(null, 3, 6, () -> sent.add("replay"));
        assertEquals(List.of("C09:3", "C08", "replay", "C09:6"), sent);
    }

    @Test
    void skipsRedundantSlotChangesAndRestoresOnReplayFailure() {
        MoonlightLegacyPackets.sendBurst(null, 3, 3, () -> sent.add("replay"));
        assertEquals(List.of("C08", "replay"), sent);
        sent.clear();
        assertThrows(IllegalStateException.class, () -> MoonlightLegacyPackets.sendBurst(null, 1, 4, () -> {
            sent.add("replay");
            throw new IllegalStateException("test");
        }));
        assertEquals(List.of("C09:1", "C08", "replay", "C09:4"), sent);
    }

    @Test
    void swordBlockSendsInteractBeforeLegacyUse() {
        MoonlightLegacyPackets.sendSwordBlock(null, 12);
        assertEquals(List.of("C02", "C08"), sent);
        sent.clear();
        MoonlightLegacyPackets.sendSwordBlock(null, -1);
        assertEquals(List.of("C08"), sent);
    }

    @Test
    void hurtCombatActionsAreDeferredUntilAfterAppleSlotRestoration() {
        var punch = ServerboundPunchPacket.INSTANCE;
        var attack = new ServerboundAttackPacket(42);
        var interact = new MoonlightBufferedActions.Interact(42);
        var order = MoonlightBufferedActions.partitionForBurst(
            List.of("move-1", punch, attack, "move-2", interact));
        assertEquals(List.of("move-1", "move-2"), order.duringUse());
        assertEquals(List.of(punch, attack, interact), order.afterRestore());
        MoonlightLegacyPackets.sendBurst(null, 3, 6, () ->
            order.duringUse().forEach(packet -> sent.add(packet.toString())));
        order.afterRestore().forEach(packet -> sent.add(packet == punch ? "C0A" : packet == attack ? "C02-attack" : "C02-interact"));
        assertEquals(List.of("C09:3", "C08", "move-1", "move-2", "C09:6", "C0A", "C02-attack", "C02-interact"), sent);
    }

    @Test
    void refusesToWriteRawPacketsForAnotherProtocol() {
        target = ProtocolVersion.v1_9;
        assertFalse(MoonlightLegacyPackets.isAvailable());
        assertThrows(IllegalStateException.class, () ->
            MoonlightLegacyPackets.sendBurst(null, 1, 4, () -> sent.add("replay")));
        assertTrue(sent.isEmpty());
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
