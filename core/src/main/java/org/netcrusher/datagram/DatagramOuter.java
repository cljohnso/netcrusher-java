package org.netcrusher.datagram;

import org.netcrusher.common.NioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.LinkedList;
import java.util.Queue;

public class DatagramOuter implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatagramOuter.class);

    private static final int PENDING_LIMIT = 64 * 1024;

    private final DatagramInner inner;

    private final InetSocketAddress clientAddress;

    private final InetSocketAddress remoteAddress;

    private final Queue<ByteBuffer> incoming;

    private final DatagramChannel channel;

    private final SelectionKey selectionKey;

    private final ByteBuffer bb;

    private long lastOperationTimestamp;

    public DatagramOuter(DatagramInner inner,
                         InetSocketAddress clientAddress,
                         InetSocketAddress remoteAddress,
                         DatagramCrusherSocketOptions socketOptions) throws IOException {
        this.inner = inner;
        this.clientAddress = clientAddress;
        this.remoteAddress = remoteAddress;
        this.incoming = new LinkedList<>();
        this.lastOperationTimestamp = System.currentTimeMillis();

        this.channel = DatagramChannel.open(socketOptions.getProtocolFamily());
        this.channel.configureBlocking(true);
        socketOptions.setupSocketChannel(this.channel);
        this.channel.connect(remoteAddress);
        this.channel.configureBlocking(false);

        this.bb = ByteBuffer.allocate(channel.socket().getReceiveBufferSize());

        this.selectionKey = inner.getReactor().register(channel, SelectionKey.OP_READ, this::callback);

        LOGGER.debug("Outer for <{}> to <{}> is started", clientAddress, remoteAddress);
    }

    @Override
    public void close() {
        NioUtils.closeChannel(channel);

        inner.removeOuter(clientAddress);

        LOGGER.debug("Outer for <{}> to <{}> is closed", clientAddress, remoteAddress);
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public InetSocketAddress getClientAddress() {
        return clientAddress;
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

        ByteBuffer bb = incoming.peek();
        if (bb != null) {
            int written = channel.write(bb);
            LOGGER.trace("Written {} bytes to outer", written);

            if (!bb.hasRemaining()) {
                incoming.poll();
            }

            lastOperationTimestamp = System.currentTimeMillis();
        }

        if (incoming.isEmpty()) {
            NioUtils.clearInterestOps(selectionKey, SelectionKey.OP_WRITE);
        }
    }

    private void handleReadable(SelectionKey selectionKey) throws IOException {
        DatagramChannel channel = (DatagramChannel) selectionKey.channel();

        bb.clear();
        int read = channel.read(bb);
        LOGGER.trace("Read {} bytes from outer", read);

        ByteBuffer data = ByteBuffer.allocate(bb.limit());
        bb.flip();
        data.put(bb);
        data.flip();

        inner.send(new DatagramMessage(clientAddress, data));

        lastOperationTimestamp = System.currentTimeMillis();
    }

    protected void send(ByteBuffer bb) {
        if (incoming.size() < PENDING_LIMIT) {
            incoming.add(bb);
            NioUtils.setupInterestOps(selectionKey, SelectionKey.OP_WRITE);
        } else {
            LOGGER.debug("Pending limit is exceeded. Packet is dropped");
        }
    }

    protected long getIdleDurationMs() {
        return System.currentTimeMillis() - lastOperationTimestamp;
    }
}
