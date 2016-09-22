package org.netcrusher.tcp;

import org.netcrusher.common.NioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.UUID;

public class TcpPair implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpPair.class);

    private final String key;

    private final AbstractSelectableChannel inner;

    private final SelectionKey innerKey;

    private final AbstractSelectableChannel outer;

    private final SelectionKey outerKey;

    private final TcpTransfer innerTransfer;

    private final TcpTransfer outerTransfer;

    private final TcpCrusher crusher;

    private final InetSocketAddress innerClientAddr;

    private final InetSocketAddress innerListenAddr;

    private final InetSocketAddress outerClientAddr;

    private final InetSocketAddress outerListenAddr;

    public TcpPair(TcpCrusher crusher, SocketChannel inner, SocketChannel outer,
                   int bufferCount, int bufferSize) throws IOException {
        this.key = UUID.randomUUID().toString();
        this.crusher = crusher;

        this.inner = inner;
        this.outer = outer;

        this.innerClientAddr = (InetSocketAddress) inner.getRemoteAddress();
        this.innerListenAddr = (InetSocketAddress) inner.getLocalAddress();

        this.outerClientAddr = (InetSocketAddress) outer.getLocalAddress();
        this.outerListenAddr = (InetSocketAddress) outer.getRemoteAddress();

        this.innerKey = crusher.getReactor().register(inner, 0, this::innerCallback);
        this.outerKey = crusher.getReactor().register(outer, 0, this::outerCallback);

        TcpTransferQueue innerToOuter = new TcpTransferQueue(bufferCount, bufferSize);
        TcpTransferQueue outerToInner = new TcpTransferQueue(bufferCount, bufferSize);

        this.innerTransfer = new TcpTransfer("INNER", this.outerKey, outerToInner, innerToOuter);
        this.outerTransfer = new TcpTransfer("OUTER", this.innerKey, innerToOuter, outerToInner);
    }

    /**
     * Unique identifier for this pair
     * @return Identifier string
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns client address for 'inner' connection
     * @return Socket address
     */
    public InetSocketAddress getInnerClientAddr() {
        return innerClientAddr;
    }

    /**
     * Returns listening address for 'inner' connection
     * @return Socket address
     */
    public InetSocketAddress getInnerListenAddr() {
        return innerListenAddr;
    }

    /**
     * Returns client address for 'outer' connection
     * @return Socket address
     */
    public InetSocketAddress getOuterClientAddr() {
        return outerClientAddr;
    }

    /**
     * Return listening address for 'outer' connection
     * @return Socket address
     */
    public InetSocketAddress getOuterListenAddr() {
        return outerListenAddr;
    }

    /**
     * Start transfer after pair is created
     */
    protected void init() {
        NioUtils.setupInterestOps(innerKey, SelectionKey.OP_READ);
        NioUtils.setupInterestOps(outerKey, SelectionKey.OP_READ);

        crusher.getReactor().reload();
    }

    /**
     * Closes this paired connection
     */
    public void close() {
        NioUtils.closeChannel(inner);
        NioUtils.closeChannel(outer);

        crusher.removePair(this.getKey());

        LOGGER.debug("Pair '{}' is closed", this.getKey());
    }

    private void callback(SelectionKey selectionKey,
                          AbstractSelectableChannel thisChannel,
                          TcpTransfer thisTransfer,
                          AbstractSelectableChannel thatChannel)
    {
        try {
            thisTransfer.handleEvent(selectionKey);
        } catch (EOFException e) {
            LOGGER.trace("EOF on transfer {}", thisTransfer.getName());
            if (thisTransfer.getOutgoing().pending() == 0) {
                close();
            } else {
                NioUtils.closeChannel(thisChannel);
            }
        } catch (IOException e) {
            LOGGER.error("Fail to handle event for socket channel", e);
            close();
        }

        if (thisChannel.isOpen() && !thatChannel.isOpen() && thisTransfer.getIncoming().pending() == 0) {
            close();
        }
    }

    private void innerCallback(SelectionKey selectionKey) {
        callback(selectionKey, inner, innerTransfer, outer);
    }

    private void outerCallback(SelectionKey selectionKey) {
        callback(selectionKey, outer, outerTransfer, inner);
    }

}

