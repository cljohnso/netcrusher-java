package org.netcrusher.datagram;

import org.netcrusher.common.NioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedList;

public class DatagramQueue implements Serializable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatagramQueue.class);

    private static final int LIMIT_COUNT = 64 * 1024;

    private static final int LIMIT_SIZE = 64 * 1024 * 1024;

    private final Deque<Entry> entries;

    private long remaining;

    public DatagramQueue() {
        this.entries = new LinkedList<>();
        this.remaining = 0;
    }

    public long remaining() {
        return remaining;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean add(InetSocketAddress address, ByteBuffer bbToCopy) {
        ByteBuffer bb = NioUtils.copy(bbToCopy);

        Entry entry = new Entry(address, bb);

        return add(entry);
    }

    public boolean add(Entry entry) {
        if (entries.size() > LIMIT_COUNT) {
            LOGGER.warn("Pending limit is exceeded ({} datagrams). Packet is dropped", entries.size());
            return false;
        }

        if (remaining > LIMIT_SIZE) {
            LOGGER.warn("Pending limit is exceeded ({} bytes). Packet is dropped", remaining);
            return false;
        }

        if (entry.buffer.hasRemaining()) {
            entries.addLast(entry);
            remaining += entry.buffer.remaining();
            return true;
        } else {
            release(entry);
            return false;
        }
    }

    public boolean retry(Entry entry) {
        if (entry.buffer.hasRemaining()) {
            entries.addFirst(entry);
            remaining += entry.buffer.remaining();
            return true;
        } else {
            release(entry);
            return false;
        }
    }

    public Entry request() {
        Entry entry = entries.pollFirst();
        if (entry != null) {
            remaining -= entry.buffer.remaining();
        }
        return entry;
    }

    public void release(Entry entry) {
        // nothing to do yet
    }

    public static final class Entry implements Serializable {

        private final InetSocketAddress address;

        private final ByteBuffer buffer;

        public Entry(InetSocketAddress address, ByteBuffer buffer) {
            this.address = address;
            this.buffer = buffer;
        }

        public InetSocketAddress getAddress() {
            return address;
        }

        public ByteBuffer getBuffer() {
            return buffer;
        }
    }
}
