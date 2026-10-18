/*
 * Copyright (c) 2026 Benny Bottema (ai-assisted). All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.eclipse.angus.mail.util;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A synchronous socket facade over a nonblocking channel. One calling thread
 * owns I/O and the engine; delegated TLS tasks alone run on an owned worker.
 * In particular, a blocked write never prevents reading TLS control records.
 * Protocol consumers may drain replies during writes without another reader.
 * This internal utility is public for use by the separate provider modules.
 */
public final class DuplexSocket extends Socket {
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    private static final int APPLICATION_HIGH_WATER = 8192;

    private final Socket socket;
    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;
    private final int writeTimeout;
    private int readTimeout;
    private long inputDeadline = Long.MAX_VALUE;
    private final ArrayDeque<ByteBuffer> received = new ArrayDeque<>();
    private ByteBuffer networkInput = ByteBuffer.allocate(65536);
    private ByteBuffer networkOutput = ByteBuffer.allocate(65536);
    private ByteBuffer applicationInput = ByteBuffer.allocate(65536);
    private ByteBuffer applicationOutput = EMPTY;
    private SSLEngine engine;
    private ExecutorService tasks;
    private Future<?> delegated;
    private InputHandler handler;
    private int available;
    private boolean eof;
    private boolean tlsClosed;
    private volatile boolean closed;
    private volatile Thread owner;

    /** A caller-thread notification; read only bytes already available. */
    @FunctionalInterface
    public interface InputHandler {
        /** @exception IOException when protocol input is invalid */
        void available() throws IOException;
    }

    DuplexSocket(Socket socket, int writeTimeout) throws IOException {
        this.socket = socket;
        this.channel = socket.getChannel();
        this.writeTimeout = writeTimeout;
        this.readTimeout = socket.getSoTimeout();
        Selector opened = Selector.open();
        try {
            channel.configureBlocking(false);
            key = channel.register(opened, 0);
            selector = opened;
        } catch (IOException | RuntimeException ex) {
            opened.close();
            socket.close();
            throw ex;
        }
    }

    /** @return whether TLS has been established on this channel */
    public boolean isSSL() {
        return engine != null;
    }

    /**
     * Installs a protocol input handler. It runs on the calling I/O thread,
     * including during writes, and must not perform a blocking read.
     *
     * @param handler the input handler, or null to detach it
     */
    public void setInputHandler(InputHandler handler) {
        this.handler = handler;
        inputDeadline = handler == null ? Long.MAX_VALUE : deadline(readTimeout);
    }

    /**
     * Processes incoming data until a protocol consumer has its next result.
     * The configured read timeout bounds this wait. Failure aborts the socket.
     *
     * @param complete whether the consumer already has its result
     * @exception IOException for timeout, interruption, EOF or protocol failure
     */
    public void awaitInput(BooleanSupplier complete) throws IOException {
        enter();
        try {
            long deadline = deadline(socket.getSoTimeout());
            while (!complete.getAsBoolean()) {
                if (eof || tlsClosed)
                    throw new EOFException("[EOF]");
                pump(deadline);
            }
        } catch (IOException | RuntimeException ex) {
            abort();
            throw ex;
        } finally {
            owner = null;
        }
    }

    void startTLS(SSLEngine engine) throws IOException {
        if (this.engine != null || available != 0 || networkInput.position() != 0)
            throw new IOException("Unexpected input at TLS boundary");
        enter();
        try {
            this.engine = engine;
            int packet = engine.getSession().getPacketBufferSize();
            networkInput = ByteBuffer.allocate(Math.max(65536, packet * 2));
            networkOutput = ByteBuffer.allocate(Math.max(65536, packet * 2));
            applicationInput = ByteBuffer.allocate(Math.max(65536,
                    engine.getSession().getApplicationBufferSize()));
            tasks = Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "Angus TLS task");
                thread.setDaemon(true);
                return thread;
            });
            engine.beginHandshake();
            long deadline = deadline(socket.getSoTimeout());
            while (engine.getHandshakeStatus() !=
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
                    networkOutput.position() != 0 || delegated != null) {
                pump(deadline);
                if (eof || tlsClosed)
                    throw new EOFException("Peer closed during TLS handshake");
            }
        } catch (IOException | RuntimeException ex) {
            abort();
            throw ex;
        } finally {
            owner = null;
        }
    }

    /** Returns the untouched blocking socket for a legacy TLS factory. */
    Socket release() throws IOException {
        if (engine != null || available != 0 || networkInput.position() != 0 ||
                networkOutput.position() != 0 || owner != null)
            throw new IOException("Cannot hand off active duplex socket");
        key.cancel();
        selector.close();
        channel.configureBlocking(true);
        return socket;
    }

    @Override
    public InputStream getInputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0)
                    return 0;
                // A protocol handler drains only available bytes, so it does
                // not recursively enter the pump or change I/O ownership.
                if (available == 0) {
                    if (eof || tlsClosed)
                        return -1;
                    awaitInput(() -> available != 0 || eof || tlsClosed);
                    if (available == 0)
                        return -1;
                }
                ByteBuffer next = received.peek();
                int count = Math.min(length, next.remaining());
                next.get(bytes, offset, count);
                available -= count;
                if (!next.hasRemaining())
                    received.remove();
                return count;
            }

            @Override
            public int available() {
                return available;
            }

            @Override
            public void close() throws IOException {
                DuplexSocket.this.close();
            }
        };
    }

    @Override
    public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value});
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                enter();
                try {
                    long deadline = deadline(writeTimeout);
                    applicationOutput = ByteBuffer.wrap(bytes, offset, length);
                    while (applicationOutput.hasRemaining() || networkOutput.position() != 0) {
                        if (eof || tlsClosed)
                            throw new EOFException("Peer closed during write");
                        pump(deadline);
                    }
                } catch (IOException | RuntimeException ex) {
                    abort();
                    throw ex;
                } finally {
                    applicationOutput = EMPTY;
                    owner = null;
                }
            }

            @Override
            public void close() throws IOException {
                DuplexSocket.this.close();
            }
        };
    }

    private void enter() throws IOException {
        if (closed)
            throw new SocketException("Socket closed");
        if (owner != null)
            throw new IOException("Concurrent or recursive duplex I/O");
        owner = Thread.currentThread();
    }

    private static long deadline(int timeout) {
        return timeout <= 0 ? Long.MAX_VALUE :
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    }

    private void pump(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Duplex I/O interrupted");
        if (System.nanoTime() >= Math.min(deadline, inputDeadline))
            throw new SocketTimeoutException("Duplex I/O timed out");
        if (closed)
            throw new SocketException("Socket closed");
        boolean progress = writeNetwork();
        if (!eof && networkInput.hasRemaining()) {
            int count = channel.read(networkInput);
            if (count < 0)
                eof = true;
            if (count > 0 && handler != null)
                inputDeadline = deadline(readTimeout);
            progress |= count != 0;
        }
        if (engine == null) {
            networkInput.flip();
            progress |= networkInput.hasRemaining();
            deliver(networkInput);
            networkInput.compact();
            if (networkOutput.position() == 0 && applicationOutput.hasRemaining()) {
                int count = Math.min(networkOutput.remaining(), applicationOutput.remaining());
                int limit = applicationOutput.limit();
                applicationOutput.limit(applicationOutput.position() + count);
                networkOutput.put(applicationOutput);
                applicationOutput.limit(limit);
                progress = true;
            }
        } else {
            progress |= processTLS();
            if (eof && delegated == null && !engine.isInboundDone() &&
                    engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                engine.closeInbound();
                if (networkInput.position() != 0)
                    throw new EOFException("Truncated TLS record");
            }
        }
        progress |= writeNetwork();
        if (!progress) {
            int interests = !eof && networkInput.hasRemaining() ? SelectionKey.OP_READ : 0;
            if (networkOutput.position() > 0)
                interests |= SelectionKey.OP_WRITE;
            key.interestOps(interests);
            // Polling also observes delegated-task completion and interruption.
            selector.select(20);
            selector.selectedKeys().clear();
        }
    }

    private boolean processTLS() throws IOException {
        boolean progress = false;
        for (int i = 0; i < 64; i++) {
            if (delegated != null) {
                if (!delegated.isDone())
                    break;
                try {
                    delegated.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("TLS task interrupted");
                } catch (ExecutionException ex) {
                    throw new SSLException("TLS task failed", ex.getCause());
                }
                delegated = null;
                progress = true;
            }
            SSLEngineResult.HandshakeStatus state = engine.getHandshakeStatus();
            if (state == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                List<Runnable> work = new ArrayList<>();
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null)
                    work.add(task);
                if (work.isEmpty())
                    throw new SSLException("TLS engine requested an absent task");
                delegated = tasks.submit(() -> work.forEach(Runnable::run));
                return true;
            }
            boolean step = false;
            if (state == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                if (networkOutput.remaining() < engine.getSession().getPacketBufferSize())
                    break;
                SSLEngineResult result = engine.wrap(EMPTY, networkOutput);
                checkResult(result);
                step = result.bytesProduced() != 0 || result.getHandshakeStatus() != state;
            } else {
                if (!engine.isInboundDone() && (networkInput.position() > 0 ||
                        state == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN)) {
                    networkInput.flip();
                    SSLEngineResult result = engine.unwrap(networkInput, applicationInput);
                    networkInput.compact();
                    checkResult(result);
                    applicationInput.flip();
                    deliver(applicationInput);
                    applicationInput.clear();
                    tlsClosed |= result.getStatus() == SSLEngineResult.Status.CLOSED;
                    step = result.bytesConsumed() != 0 || result.bytesProduced() != 0 ||
                            result.getHandshakeStatus() != state;
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW &&
                            !networkInput.hasRemaining())
                        throw new SSLException("TLS record exceeds packet buffer");
                }
                if (!step && engine.getHandshakeStatus() ==
                        SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                        applicationOutput.hasRemaining() &&
                        networkOutput.position() < APPLICATION_HIGH_WATER) {
                    SSLEngineResult result = engine.wrap(applicationOutput, networkOutput);
                    checkResult(result);
                    step = result.bytesConsumed() != 0 || result.bytesProduced() != 0 ||
                            result.getHandshakeStatus() != state;
                }
            }
            progress |= step;
            if (!step)
                break;
        }
        return progress;
    }

    private void checkResult(SSLEngineResult result) throws SSLException {
        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
            throw new SSLException("TLS engine buffer overflow");
    }

    private void deliver(ByteBuffer input) throws IOException {
        if (!input.hasRemaining())
            return;
        byte[] bytes = new byte[input.remaining()];
        input.get(bytes);
        received.add(ByteBuffer.wrap(bytes));
        available += bytes.length;
        if (handler != null)
            handler.available();
    }

    private boolean writeNetwork() throws IOException {
        if (networkOutput.position() == 0)
            return false;
        networkOutput.flip();
        try {
            return channel.write(networkOutput) != 0;
        } finally {
            networkOutput.compact();
        }
    }

    /** Aborts physical I/O, including when invoked by a cancelling thread. */
    public void abort() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Preserve the I/O or protocol failure that prompted the abort.
        } finally {
            try {
                selector.close();
            } catch (IOException ignored) {
            }
            if (tasks != null)
                tasks.shutdownNow();
        }
    }

    @Override
    public void close() throws IOException {
        Throwable failure = null;
        try {
            // Never call the engine concurrently with a write or delegated
            // task. Cancellation closes the raw channel instead of waiting.
            if (!closed && owner == null && engine != null && delegated == null &&
                    !Thread.currentThread().isInterrupted()) {
                enter();
                engine.closeOutbound();
                long deadline = deadline(writeTimeout > 0 ? Math.min(writeTimeout, 1000) : 1000);
                while (!engine.isOutboundDone() || networkOutput.position() > 0)
                    pump(deadline);
            }
        } catch (IOException | RuntimeException ex) {
            failure = ex;
            throw ex;
        } finally {
            abort();
            owner = null;
            if (tasks != null) {
                try {
                    if (!tasks.awaitTermination(3, TimeUnit.SECONDS)) {
                        IOException cleanup = new IOException("TLS task did not stop");
                        if (failure == null)
                            throw cleanup;
                        failure.addSuppressed(cleanup);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public SocketChannel getChannel() {
        return channel;
    }

    @Override
    public InetAddress getInetAddress() {
        return socket.getInetAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }

    @Override
    public int getPort() {
        return socket.getPort();
    }

    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return socket.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return socket.getLocalSocketAddress();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        socket.setSoTimeout(timeout);
        readTimeout = timeout;
        if (handler != null)
            inputDeadline = deadline(timeout);
    }
    @Override
    public int getSoTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    @Override
    public void setSoLinger(boolean on, int linger) throws SocketException {
        socket.setSoLinger(on, linger);
    }

    @Override
    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    @Override
    public boolean isConnected() {
        return socket.isConnected();
    }

    @Override
    public boolean isBound() {
        return socket.isBound();
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        socket.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return socket.getTcpNoDelay();
    }

    @Override
    public void setSendBufferSize(int size) throws SocketException {
        socket.setSendBufferSize(size);
    }

    @Override
    public int getSendBufferSize() throws SocketException {
        return socket.getSendBufferSize();
    }

    @Override
    public void setReceiveBufferSize(int size) throws SocketException {
        socket.setReceiveBufferSize(size);
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        return socket.getReceiveBufferSize();
    }
}
