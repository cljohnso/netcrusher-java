package org.netcrusher.datagram;

import org.netcrusher.common.NioReactor;
import org.netcrusher.tcp.TcpCrusherBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * <p>DatagramCrusher - a UDP proxy for test purposes. To create a new instance use DatagramCrusherBuilder</p>
 *
 * <pre>
 * NioReactor reactor = new NioReactor();
 * DatagramCrusher crusher = DatagramCrusherBuilder.builder()
 *     .withReactor(reactor)
 *     .withLocalAddress("localhost", 10081)
 *     .withRemoteAddress("time-nw.nist.gov", 37)
 *     .buildAndOpen();
 *
 * // do some test on localhost:10081
 * crusher.crush();
 * // do other test on localhost:10081
 *
 * crusher.close();
 * reactor.close();
 * </pre>
 *
 * @see TcpCrusherBuilder
 * @see NioReactor
 */
public class DatagramCrusher implements Closeable {

    private final NioReactor reactor;

    private final DatagramCrusherSocketOptions socketOptions;

    private final InetSocketAddress localAddress;

    private final InetSocketAddress remoteAddress;

    private final long maxIdleDurationMs;

    private DatagramInner inner;

    private volatile boolean opened;

    public DatagramCrusher(InetSocketAddress localAddress,
                           InetSocketAddress remoteAddress,
                           DatagramCrusherSocketOptions socketOptions,
                           NioReactor reactor,
                           long maxIdleDurationMs) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.socketOptions = socketOptions;
        this.reactor = reactor;
        this.maxIdleDurationMs = maxIdleDurationMs;
        this.opened = false;
    }

    /**
     * Opens the proxy. Listening socket will opened and binded.
     * @throws IOException On problem with opening/binding
     */
    public synchronized void open() throws IOException {
        if (opened) {
            throw new IllegalStateException("DatagramCrusher is already active");
        }

        this.inner = new DatagramInner(this, localAddress, remoteAddress, socketOptions, maxIdleDurationMs);
        this.inner.unfreeze();

        this.opened = true;
    }

    /**
     * Closes the proxy. Listening socket will be closed
     */
    @Override
    public synchronized void close() throws IOException {
        if (opened) {
            this.inner.close();
            this.inner = null;

            this.opened = false;
        }
    }

    /**
     * Reopens (closes and the opens again) crusher proxy
     */
    public synchronized void crush() throws IOException {
        if (opened) {
            close();
            open();
        }
    }

    /**
     * Freezes crusher proxy. Sockets are still open but packets are not sent
     * @see DatagramCrusher#unfreeze()
     * @throws IOException On IO error
     */
    public synchronized void freeze() throws IOException {
        if (opened) {
            inner.freeze();
        }
    }

    /**
     * Resumes crusher proxy after freezing
     * @see DatagramCrusher#freeze()
     * @throws IOException On IO error
     */
    public synchronized void unfreeze() throws IOException {
        if (opened) {
            inner.unfreeze();
        }
    }

    /**
     * Is the crusher freezed
     * @return Return true if freeze() on the crusher was called before
     * @see DatagramCrusher#unfreeze()
     * @see DatagramCrusher#freeze()
     */
    public boolean isFrozen() {
        return inner.isFrozen();
    }

    protected NioReactor getReactor() {
        return reactor;
    }

    protected synchronized void removeInner() {
        this.opened = false;
        this.inner = null;
    }

}
