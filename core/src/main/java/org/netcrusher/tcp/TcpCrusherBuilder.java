package org.netcrusher.tcp;

import org.netcrusher.common.NioReactor;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.util.function.Consumer;

/**
 * Builder for TcpCrusher instance
 */
public final class TcpCrusherBuilder {

    private InetSocketAddress localAddress;

    private InetSocketAddress remoteAddress;

    private NioReactor reactor;

    private TcpCrusherSocketOptions socketOptions;

    private Consumer<TcpPair> creationListener;

    private Consumer<TcpPair> deletionListener;

    private int bufferCount;

    private int bufferSize;

    private TcpCrusherBuilder() {
        this.socketOptions = new TcpCrusherSocketOptions();
        this.bufferCount = 16;
        this.bufferSize = 16 * 1024;
    }

    /**
     * Creates a new builder
     * @return A new builder instance
     */
    public static TcpCrusherBuilder builder() {
        return new TcpCrusherBuilder();
    }

    /**
     * Set local address for proxy (where to bind a listening socket)
     * @param address Inet address
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withLocalAddress(InetSocketAddress address) {
        this.localAddress = address;
        return this;
    }

    /**
     * Set local address for proxy (where to bind a listening socket)
     * @param hostname Host name or interface address
     * @param port Port number
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withLocalAddress(String hostname, int port) {
        this.localAddress = new InetSocketAddress(hostname, port);
        return this;
    }

    /**
     * Set remote address for proxy (where to connect)
     * @param address Inet address
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withRemoteAddress(InetSocketAddress address) {
        this.remoteAddress = address;
        return this;
    }

    /**
     * Set remote address for proxy (where to connect)
     * @param hostname Remote host name or IP address of remote host
     * @param port Port number
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withRemoteAddress(String hostname, int port) {
        this.remoteAddress = new InetSocketAddress(hostname, port);
        return this;
    }

    /**
     * Set reactor instance for this proxy
     * @param reactor Reactor
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withReactor(NioReactor reactor) {
        this.reactor = reactor;
        return this;
    }

    /**
     * Set a listener for a new proxy connection
     * @param creationListener Listener implementation
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withCreationListener(Consumer<TcpPair> creationListener) {
        this.creationListener = creationListener;
        return this;
    }

    /**
     * Set a listener for a proxy connection to be deleted
     * @param deletionListener Listener implementation
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withDeletionListener(Consumer<TcpPair> deletionListener) {
        this.deletionListener = deletionListener;
        return this;
    }

    /**
     * Set a backlog size for a listening socket. If not set the default backlog size will be used
     * @param backlog Backlog size
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withBacklog(int backlog) {
        this.socketOptions.setBacklog(backlog);
        return this;
    }

    /**
     * Set whether or not both sockets would use SO_KEEPALIVE feature
     * @param keepAlive SO_KEEPALIVE flag value
     * @see StandardSocketOptions#SO_KEEPALIVE
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withKeepAlive(boolean keepAlive) {
        this.socketOptions.setKeepAlive(keepAlive);
        return this;
    }

    /**
     * Set whether or not both sockets would use TCP_NODELAY feature
     * @param tcpNoDelay TCP_NODELAY flag value
     * @see StandardSocketOptions#TCP_NODELAY
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withTcpNoDelay(boolean tcpNoDelay) {
        this.socketOptions.setTcpNoDelay(tcpNoDelay);
        return this;
    }

    /**
     * Set socket buffer size for receiving, If not set the default size will be used.
     * @param bufferSize Size in bytes
     * @see StandardSocketOptions#SO_RCVBUF
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withRcvBufferSize(int bufferSize) {
        this.socketOptions.setRcvBufferSize(bufferSize);
        return this;
    }

    /**
     * Set socket buffer size for sending, If not set the default size will be used.
     * @param bufferSize Size in bytes
     * @see StandardSocketOptions#SO_SNDBUF
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withSndBufferSize(int bufferSize) {
        this.socketOptions.setSndBufferSize(bufferSize);
        return this;
    }

    /**
     * Connection timeout for remote connection. If set to 0 the timeout will be not timeout at all
     * @param timeoutMs Timeout in milliseconds
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withConnectionTimeoutMs(long timeoutMs) {
        this.socketOptions.setConnectionTimeoutMs(timeoutMs);
        return this;
    }

    /**
     * Set how many buffer instances will be in queue between two sockets in a proxy pair
     * @param bufferCount Count of buffer
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withBufferCount(int bufferCount) {
        this.bufferCount = bufferCount;
        return this;
    }

    /**
     * Set the size of each buffer in queue between two sockets in a proxy pair
     * @param bufferSize Size of buffer in bytes
     * @return This builder instance to chain with other methods
     */
    public TcpCrusherBuilder withBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        return this;
    }

    /**
     * Builds a new TcpCrusher instance
     * @return TcpCrusher instance
     */
    public TcpCrusher build() {
        if (localAddress == null) {
            throw new IllegalArgumentException("Local address is not set");
        }

        if (remoteAddress == null) {
            throw new IllegalArgumentException("Remote address is not set");
        }

        if (reactor == null) {
            throw new IllegalArgumentException("Context is not set");
        }

        return new TcpCrusher(localAddress, remoteAddress, socketOptions.copy(), reactor,
            creationListener, deletionListener, bufferCount, bufferSize);
    }
}