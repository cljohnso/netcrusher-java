package org.netcrusher.tcp;

import org.netcrusher.NetCrusher;
import org.netcrusher.common.NioReactor;
import org.netcrusher.common.NioUtils;
import org.netcrusher.filter.ByteBufferFilterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * <p>TcpCrusher - a TCP proxy for test purposes. To create a new instance use TcpCrusherBuilder</p>
 *
 * <pre>
 * NioReactor reactor = new NioReactor();
 * TcpCrusher crusher = TcpCrusherBuilder.builder()
 *     .withReactor(reactor)
 *     .withLocalAddress("localhost", 10080)
 *     .withRemoteAddress("google.com", 80)
 *     .buildAndOpen();
 *
 * // do some test on localhost:10080
 * crusher.crush();
 * // do other test on localhost:10080
 *
 * crusher.close();
 * reactor.close();
 * </pre>
 *
 * @see TcpCrusherBuilder
 * @see NioReactor
 */
public class TcpCrusher implements NetCrusher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpCrusher.class);

    private final InetSocketAddress bindAddress;

    private final InetSocketAddress connectAddress;

    private final TcpCrusherSocketOptions socketOptions;

    private final NioReactor reactor;

    private final Map<InetSocketAddress, TcpPair> pairs;

    private final Consumer<TcpPair> creationListener;

    private final Consumer<TcpPair> deletionListener;

    private final int bufferCount;

    private final int bufferSize;

    private final ByteBufferFilterRepository filters;

    private ServerSocketChannel serverSocketChannel;

    private SelectionKey serverSelectionKey;

    private volatile boolean open;

    private volatile boolean frozen;

    public TcpCrusher(
            NioReactor reactor,
            InetSocketAddress bindAddress,
            InetSocketAddress connectAddress,
            TcpCrusherSocketOptions socketOptions,
            Consumer<TcpPair> creationListener,
            Consumer<TcpPair> deletionListener,
            int bufferCount,
            int bufferSize)
    {
        this.bindAddress = bindAddress;
        this.connectAddress = connectAddress;
        this.reactor = reactor;
        this.socketOptions = socketOptions;
        this.pairs = new ConcurrentHashMap<>(32);
        this.filters = new ByteBufferFilterRepository();
        this.bufferCount = bufferCount;
        this.bufferSize = bufferSize;
        this.creationListener = creationListener;
        this.deletionListener = deletionListener;
        this.open = false;
        this.frozen = true;
    }

    @Override
    public synchronized void open() throws IOException {
        if (open) {
            throw new IllegalStateException("TcpCrusher is already active");
        }

        LOGGER.debug("TcpCrusher <{}>-<{}> will be open", bindAddress, connectAddress);

        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.configureBlocking(false);
        this.serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        if (socketOptions.getBacklog() > 0) {
            this.serverSocketChannel.bind(bindAddress, socketOptions.getBacklog());
        } else {
            this.serverSocketChannel.bind(bindAddress);
        }

        serverSelectionKey = reactor.getSelector().register(serverSocketChannel, 0, (selectionKey) -> this.accept());

        LOGGER.info("TcpCrusher <{}>-<{}> is open", bindAddress, connectAddress);

        open = true;

        unfreeze();
    }

    @Override
    public synchronized void close() throws IOException {
        if (open) {
            LOGGER.debug("TcpCrusher <{}>-<{}> will be closed", bindAddress, connectAddress);

            freeze();

            closeAllPairs();

            serverSelectionKey.cancel();
            serverSelectionKey = null;

            NioUtils.closeChannel(serverSocketChannel);
            serverSocketChannel = null;

            reactor.getSelector().wakeup();

            LOGGER.info("TcpCrusher <{}>-<{}> is closed", bindAddress, connectAddress);

            open = false;
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /**
     * Close all pairs but keeps listening socket open
     */
    public synchronized void closeAllPairs() throws IOException {
        if (open) {
            for (TcpPair pair : getPairs()) {
                closePair(pair.getClientAddress());
            }
        } else {
            throw new IllegalStateException("Crusher is not open");
        }
    }

    /**
     * Close the pair by client address
     * @param clientAddress Client address
     * @throws IOException
     */
    public void closePair(InetSocketAddress clientAddress) throws IOException {
        TcpPair pair = pairs.remove(clientAddress);
        if (pair != null) {
            pair.closeExternal();
            if (deletionListener != null) {
                reactor.execute(() -> deletionListener.accept(pair));
            }
        }
    }

    @Override
    public synchronized void crush() throws IOException {
        if (open) {
            close();
            open();
        } else {
            throw new IllegalStateException("Crusher is not open");
        }
    }

    /**
     * Freezes crusher proxy. Call freeze() on all pairs and freezes the acceptor
     * @see TcpCrusher#freezeAllPairs()
     * @see TcpCrusher#unfreezeAllPairs()
     * @see TcpPair#freeze()
     * @see TcpPair#unfreeze()
     * @see TcpPair#isFrozen()
     * @throws IOException On IO error
     */
    @Override
    public synchronized void freeze() throws IOException {
        if (open) {
            if (!frozen) {
                reactor.getSelector().executeOp(() -> {
                    if (serverSelectionKey.isValid()) {
                        serverSelectionKey.interestOps(0);
                    }
                });
                frozen = true;
            }

            freezeAllPairs();

            LOGGER.debug("TcpCrusher <{}>-<{}> is frozen", bindAddress, connectAddress);
        } else {
            LOGGER.debug("Component is closed on freeze");
        }
    }

    /**
     * Freezes all TCP pairs
     * @throws IOException Throwed on IO error
     */
    public void freezeAllPairs() throws IOException {
        for (TcpPair pair : pairs.values()) {
            pair.freeze();
        }
    }

    /**
     * Unfreezes the crusher. Call unfreeze() on all pairs and unfreezes the acceptor
     * @see TcpCrusher#freezeAllPairs()
     * @see TcpCrusher#unfreezeAllPairs()
     * @see TcpPair#freeze()
     * @see TcpPair#unfreeze()
     * @see TcpPair#isFrozen()
     * @throws IOException On IO error
     */
    @Override
    public synchronized void unfreeze() throws IOException {
        if (open) {
            unfreezeAllPairs();

            if (frozen) {
                reactor.getSelector().executeOp(() -> serverSelectionKey.interestOps(SelectionKey.OP_ACCEPT));
                frozen = false;
            }

            LOGGER.debug("TcpCrusher <{}>-<{}> is unfrozen", bindAddress, connectAddress);
        } else {
            throw new IllegalStateException("Crusher is not open");
        }
    }

    @Override
    public synchronized boolean isFrozen() {
        if (open) {
            return frozen;
        } else {
            throw new IllegalStateException("Crusher is not open");
        }
    }

    /**
     * Unfreezes all TCP pairs
     * @throws IOException Throwed on IO error
     */
    public void unfreezeAllPairs() throws IOException {
        for (TcpPair pair : pairs.values()) {
            pair.unfreeze();
        }
    }

    private void accept() throws IOException {
        SocketChannel socketChannel1 = serverSocketChannel.accept();
        socketChannel1.configureBlocking(false);
        socketOptions.setupSocketChannel(socketChannel1);

        LOGGER.debug("Incoming connection is accepted on <{}>", bindAddress);

        SocketChannel socketChannel2 = SocketChannel.open();
        socketChannel2.configureBlocking(false);
        socketOptions.setupSocketChannel(socketChannel2);

        boolean connectedNow = socketChannel2.connect(connectAddress);
        if (!connectedNow) {
            final Future<?> connectCheck;
            if (socketOptions.getConnectionTimeoutMs() > 0) {
                connectCheck = reactor.schedule(socketOptions.getConnectionTimeoutMs(), () -> {
                    if (socketChannel2.isOpen() && !socketChannel2.isConnected()) {
                        LOGGER.warn("Fail to connect to <{}> in {}ms",
                            connectAddress, socketOptions.getConnectionTimeoutMs());
                        NioUtils.closeChannel(socketChannel1);
                        NioUtils.closeChannel(socketChannel2);
                    }
                });
            } else {
                connectCheck = CompletableFuture.completedFuture(null);
            }

            reactor.getSelector().register(socketChannel2, SelectionKey.OP_CONNECT, (selectionKey) -> {
                connectCheck.cancel(false);

                boolean connected;
                try {
                    connected = socketChannel2.finishConnect();
                } catch (ConnectException e) {
                    connected = false;
                }

                if (!connected) {
                    LOGGER.warn("Fail to finish outgoing connection to <{}>", connectAddress);
                    NioUtils.closeChannel(socketChannel1);
                    NioUtils.closeChannel(socketChannel2);
                    return;
                }

                appendPair(socketChannel1, socketChannel2);
            });
        } else {
            appendPair(socketChannel1, socketChannel2);
        }
    }

    private void appendPair(SocketChannel socketChannel1, SocketChannel socketChannel2) {
        try {
            TcpPair pair = new TcpPair(this, reactor, filters,
                socketChannel1, socketChannel2, bufferCount, bufferSize);
            pair.unfreeze();

            LOGGER.debug("Pair is created for <{}>", pair.getClientAddress());

            pairs.put(pair.getClientAddress(), pair);

            if (creationListener != null) {
                reactor.execute(() -> creationListener.accept(pair));
            }
        } catch (ClosedChannelException | CancelledKeyException e) {
            LOGGER.debug("One of the channels is already closed", e);
            NioUtils.closeChannel(socketChannel1);
            NioUtils.closeChannel(socketChannel2);
        } catch (IOException e) {
            LOGGER.error("Fail to create TcpCrusher TCP pair", e);
            NioUtils.closeChannel(socketChannel1);
            NioUtils.closeChannel(socketChannel2);
        }
    }

    @Override
    public InetSocketAddress getBindAddress() {
        return bindAddress;
    }

    @Override
    public InetSocketAddress getConnectAddress() {
        return connectAddress;
    }

    @Override
    public ByteBufferFilterRepository getFilters() {
        return filters;
    }

    /**
     * Get collection of active tranfer pairs
     * @return Collection of tranfer pairs
     */
    public Collection<TcpPair> getPairs() {
        return new ArrayList<>(this.pairs.values());
    }

}

