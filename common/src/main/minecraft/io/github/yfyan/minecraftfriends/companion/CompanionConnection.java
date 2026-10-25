package io.github.yfyan.minecraftfriends.companion;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Local server entity connection. It has no socket, authentication, or remote client. */
final class CompanionConnection extends Connection {
    private PacketListener listener;
    private boolean connected = true;

    CompanionConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {
        this.listener = listener;
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> info) {
    }

    @Override
    public PacketListener getPacketListener() {
        return listener;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) {
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback, boolean flush) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void tick() {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        connected = false;
    }
}
