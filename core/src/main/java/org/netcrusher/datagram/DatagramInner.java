package org.netcrusher.datagram;

import org.netcrusher.common.NioReactor;
import org.netcrusher.common.NioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class DatagramInner implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatagramInner.class);

    private static final int PENDING_LIMIT = 64 * 1024;

    private final NioReactor reactor;

    private final DatagramCrusherSocketOptions socketOptions;

    private final InetSocketAddress localAddress;

    private final InetSocketAddress remoteAddress;

    private final DatagramChannel channel;

    private final SelectionKey selectionKey;

    private final ByteBuffer bb;

    private final Map<InetSocketAddress, DatagramOuter> outers;

    private final Queue<DatagramMessage> incoming;

    private final long maxIdleDurationMs;

    public DatagramInner(NioReactor reactor,
                         InetSocketAddress localAddress,
                         InetSocketAddress remoteAddress,
                         DatagramCrusherSocketOptions socketOptions,
                         long maxIdleDurationMs) throws IOException {
        this.reactor = reactor;
        this.socketOptions = socketOptions;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.outers = new HashMap<>(32);
        this.incoming = new LinkedList<>();
        this.maxIdleDurationMs = maxIdleDurationMs;

        this.channel = DatagramChannel.open(socketOptions.getProtocolFamily());
        this.channel.configureBlocking(true);
        socketOptions.setupSocketChannel(this.channel);
        this.channel.bind(localAddress);
        this.channel.configureBlocking(false);

        this.bb = ByteBuffer.allocate(channel.socket().getReceiveBufferSize());

        this.selectionKey = reactor.register(channel, SelectionKey.OP_READ, this::callback);

        LOGGER.debug("Inner on <{}> is started", localAddress);
    }

    @Override
    public void close() {
        NioUtils.closeChannel(channel);

        for (DatagramOuter outer : outers.values()) {
            outer.close();
        }

        outers.clear();

        LOGGER.debug("Inner on <{}> is closed", localAddress);
    }

    private void callback(SelectionKey selectionKey) throws IOException {
        if (!selectionKey.isValid()) {
            throw new IOException("Selection key is not valid");
        }

        if (selectionKey.isReadable()) {
            handleReadable(selectionKey);
        }

        if (selectionKey.isWritable()) {
            handleWritable(selectionKey);
        }
    }

    private void handleWritable(SelectionKey selectionKey) throws IOException {
        DatagramChannel channel = (DatagramChannel) selectionKey.channel();

        DatagramMessage dm = incoming.peek();
        if (dm != null) {
            int written = channel.send(dm.getBuffer(), dm.getAddress());
            LOGGER.trace("Send {} bytes from inner <{}>", written, dm.getAddress());

            if (!dm.getBuffer().hasRemaining()) {
                incoming.poll();
            }
        }

        if (incoming.isEmpty()) {
            NioUtils.clearInterestOps(selectionKey, SelectionKey.OP_WRITE);
        }
    }

    private void handleReadable(SelectionKey selectionKey) throws IOException {
        DatagramChannel channel = (DatagramChannel) selectionKey.channel();

        bb.clear();
        InetSocketAddress address = (InetSocketAddress) channel.receive(bb);

        if (address != null) {
            DatagramOuter outer = requestOuter(address);

            ByteBuffer data = ByteBuffer.allocate(bb.limit());
            bb.flip();
            data.put(bb);
            data.flip();

            outer.send(data);

            LOGGER.trace("Received {} bytes from inner <{}>", data.limit(), address);
        }
    }

    private DatagramOuter requestOuter(InetSocketAddress address) throws IOException {
        DatagramOuter outer = outers.get(address);

        if (outer == null) {
            if (maxIdleDurationMs > 0) {
                clearOuters(maxIdleDurationMs);
            }

            outer = new DatagramOuter(this, address, remoteAddress, socketOptions);
            outers.put(address, outer);
        }

        return outer;
    }

    private void clearOuters(long maxIdleDurationMs) {
        int countBefore = outers.size();
        if (countBefore > 0) {
            Iterator<DatagramOuter> outerIterator = outers.values().iterator();

            while (outerIterator.hasNext()) {
                DatagramOuter outer = outerIterator.next();

                if (outer.getIdleDurationMs() > maxIdleDurationMs) {
                    outer.close();
                    outerIterator.remove();
                }
            }

            int countAfter = outers.size();
            LOGGER.debug("Outer connections are cleared {} -> {}", countBefore, countAfter);
        }
    }

    protected void send(DatagramMessage message) {
        if (incoming.size() < PENDING_LIMIT) {
            incoming.add(message);
            NioUtils.setupInterestOps(selectionKey, SelectionKey.OP_WRITE);
        } else {
            LOGGER.debug("Pending limit is exceeded. Packet is dropped");
        }
    }

    protected NioReactor getReactor() {
        return reactor;
    }

    protected void removeOuter(InetSocketAddress clientAddress) {
        outers.remove(clientAddress);
    }

}