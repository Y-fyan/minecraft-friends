package io.github.yfyan.minecraftfriends.test;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import io.netty.channel.embedded.EmbeddedChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

final class TestConnection extends Connection {
    private PacketListener listener;
    private boolean connected = true;
    private boolean restoreConflictNoticeSent;
    private final EmbeddedChannel channel;

    TestConnection() {
        super(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(this);
    }
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) { this.listener = listener; }
    @Override public void setupOutboundProtocol(ProtocolInfo<?> info) { }
    @Override public PacketListener getPacketListener() { return listener; }
    @Override public boolean isConnected() { return connected; }
    @Override public boolean isConnecting() { return false; }
    @Override public boolean isMemoryConnection() { return true; }
    @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }
    @Override public void send(Packet<?> packet) { record(packet); }
    @Override public void send(Packet<?> packet, PacketSendListener callback) { record(packet); }
    @Override public void send(Packet<?> packet, PacketSendListener callback, boolean flush) { record(packet); }
    @Override public void flushChannel() { }
    @Override public void setReadOnly() { }
    @Override public void tick() { }
    @Override public void disconnect(DisconnectionDetails details) { connected = false; channel.close(); }

    boolean restoreConflictNoticeSent() { return restoreConflictNoticeSent; }

    private void record(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket message
                && message.content().getString().contains("当前登录不受影响")) {
            restoreConflictNoticeSent = true;
        }
    }
}
