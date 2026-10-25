package io.github.yfyan.probe;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** Server-owned entity, not an authenticated or protocol client. Drops outbound packets. */
public final class ProbeConnection extends Connection {
    private PacketListener listener;
    private boolean connected = true;
    private int packets;

    public ProbeConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {
        this.listener = listener;
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> info) {
        // No codec/socket: explicitly no claim to test serialization or client acknowledgements.
    }

    @Override
    public PacketListener getPacketListener() { return listener; }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public boolean isConnecting() { return false; }

    @Override
    public boolean isMemoryConnection() { return true; }

    @Override
    public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }

    @Override
    public void send(Packet<?> packet) { packets++; }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) { packets++; }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback, boolean flush) { packets++; }

    @Override
    public void flushChannel() { }

    @Override
    public void setReadOnly() { }

    @Override
    public void tick() { }

    @Override
    public void disconnect(DisconnectionDetails details) { connected = false; }

    public int discardedPackets() { return packets; }
}
