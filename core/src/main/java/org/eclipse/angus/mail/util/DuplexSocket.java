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
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private static final int MIN_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_TLS_STEPS = 64;
    private static final int SELECT_TIMEOUT_MILLIS = 20;
    private static final int CLOSE_TIMEOUT_MILLIS = 1000;
    private static final int TASK_SHUTDOWN_TIMEOUT_SECONDS = 3;

    private final Socket socket;
    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;
    private final int writeTimeout;
    private int readTimeout;
    private long inputDeadline = Long.MAX_VALUE;
    private final ArrayDeque<ByteBuffer> received = new ArrayDeque<>();
    private ByteBuffer networkInput = ByteBuffer.allocate(MIN_BUFFER_SIZE);
    private ByteBuffer networkOutput = ByteBuffer.allocate(MIN_BUFFER_SIZE);
    private ByteBuffer applicationInput = ByteBuffer.allocate(MIN_BUFFER_SIZE);
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

    /** A caller-thread check for the protocol consumer's next result. */
    @FunctionalInterface
    public interface InputCondition {
        /** @return whether the consumer has its next result */
        boolean isComplete();
    }

    DuplexSocket(Socket socket, int writeTimeout) throws IOException {
        this.socket = socket;
        this.channel = socket.getChannel();
        this.writeTimeout = writeTimeout;
        Selector opened = null;
        try {
            this.readTimeout = socket.getSoTimeout();
            opened = Selector.open();
            channel.configureBlocking(false);
            key = channel.register(opened, 0);
            selector = opened;
        } catch (IOException | RuntimeException | Error ex) {
            Throwable failure = opened == null ? ex : closeAfterFailure(opened, ex);
            failure = closeAfterFailure(socket, failure);
            failure = closeAfterFailure(channel, failure);
            if (failure instanceof Error)
                throw (Error) failure;
            throw ex;
        }
    }

    private static Throwable closeAfterFailure(Closeable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Throwable cleanup) {
            return addFailure(failure, cleanup);
        }
        return failure;
    }

    private static Throwable addFailure(Throwable failure, Throwable cleanup) {
        if (failure == null || failure == cleanup)
            return cleanup;
        if (cleanup instanceof Error && !(cleanup instanceof LinkageError)) {
            cleanup.addSuppressed(failure);
            return cleanup;
        }
        failure.addSuppressed(cleanup);
        return failure;
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
     * @param condition whether the consumer already has its result
     * @exception IOException for timeout, interruption, EOF or protocol failure
     */
    public void awaitInput(InputCondition condition) throws IOException {
        enter();
        try {
            long deadline = deadline(socket.getSoTimeout());
            while (!condition.isComplete()) {
                if (eof || tlsClosed)
                    throw new EOFException("[EOF]");
                pump(deadline);
            }
        } catch (IOException | RuntimeException ex) {
            abort(ex);
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
            networkInput = ByteBuffer.allocate(Math.max(MIN_BUFFER_SIZE, packet * 2));
            networkOutput = ByteBuffer.allocate(Math.max(MIN_BUFFER_SIZE, packet * 2));
            applicationInput = ByteBuffer.allocate(Math.max(MIN_BUFFER_SIZE,
                    engine.getSession().getApplicationBufferSize()));
            tasks = Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "Angus TLS task");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                thread.setContextClassLoader(DuplexSocket.class.getClassLoader());
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
            abort(ex);
            throw ex;
        } finally {
            owner = null;
        }
    }

    /**
     * Returns the untouched blocking socket for a legacy TLS factory.
     * Android socket-based TLS providers look up its native descriptor, so
     * they must receive the physical socket rather than this facade.
     */
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
                checkBounds(offset, length, bytes.length);
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
                checkBounds(offset, length, bytes.length);
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
                    abort(ex);
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

    private static void checkBounds(int offset, int length, int size) {
        if (offset < 0 || length < 0 || offset > size - length)
            throw new IndexOutOfBoundsException("offset " + offset +
                    ", length " + length + ", size " + size);
    }

    private static long deadline(int timeout) {
        return timeout <= 0 ? Long.MAX_VALUE :
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
    }

    private void pump(long deadline) throws IOException {
        pump(deadline, false);
    }

    private void pump(long deadline, boolean closing) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Duplex I/O interrupted");
        if (System.nanoTime() >= Math.min(deadline, inputDeadline))
            throw new SocketTimeoutException("Duplex I/O timed out");
        if (closed)
            throw new SocketException("Socket closed");
        boolean progress = writeNetwork();
        // Once our close alert is sent, no further peer input is required.
        if (closing && delegated == null && engine.isOutboundDone() &&
                networkOutput.position() == 0)
            return;
        progress |= readNetwork();
        if (engine == null) {
            progress |= processPlaintext();
        } else {
            progress |= processTLS();
            if (eof && delegated == null && !engine.isInboundDone() &&
                    engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP &&
                    !(closing && engine.isOutboundDone())) {
                engine.closeInbound();
                if (networkInput.position() != 0)
                    throw new EOFException("Truncated TLS record");
            }
        }
        progress |= writeNetwork();
        if (!progress)
            waitForNetwork();
    }

    /**
     * Delivers plaintext input and stages pending output without TLS wrapping.
     */
    private boolean processPlaintext() throws IOException {
        // Older Android versions lack ByteBuffer's covariant return methods.
        ((Buffer) networkInput).flip();
        boolean progress = networkInput.hasRemaining();
        deliver(networkInput);
        networkInput.compact();
        if (networkOutput.position() == 0 && applicationOutput.hasRemaining()) {
            int count = Math.min(networkOutput.remaining(), applicationOutput.remaining());
            int limit = applicationOutput.limit();
            ((Buffer) applicationOutput).limit(applicationOutput.position() + count);
            networkOutput.put(applicationOutput);
            ((Buffer) applicationOutput).limit(limit);
            progress = true;
        }
        return progress;
    }

    /**
     * Advances the engine on its owner thread, yielding regularly to channel I/O.
     */
    private boolean processTLS() throws IOException {
        boolean progress = false;
        for (int i = 0; i < MAX_TLS_STEPS; i++) {
            if (delegated != null) {
                if (!delegated.isDone())
                    break;
                completeDelegatedTasks();
                progress = true;
            }
            SSLEngineResult.HandshakeStatus state = engine.getHandshakeStatus();
            if (state == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                submitDelegatedTasks();
                return true;
            }
            boolean step;
            if (state == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                if (networkOutput.remaining() < engine.getSession().getPacketBufferSize())
                    break;
                step = wrapOutput(EMPTY, state);
            } else {
                step = unwrapInput(state);
                if (!step && engine.getHandshakeStatus() ==
                        SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                        applicationOutput.hasRemaining() &&
                        networkOutput.position() < APPLICATION_HIGH_WATER)
                    step = wrapOutput(applicationOutput, state);
            }
            progress |= step;
            if (!step)
                break;
        }
        return progress;
    }

    /**
     * Observes task completion before the owner calls the engine again.
     */
    private void completeDelegatedTasks() throws IOException {
        try {
            delegated.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("TLS task interrupted");
        } catch (ExecutionException ex) {
            throw new SSLException("TLS task failed", ex.getCause());
        }
        delegated = null;
    }

    /**
     * Hands off only JSSE's delegated work; the worker never performs I/O.
     */
    private void submitDelegatedTasks() throws SSLException {
        List<Runnable> work = new ArrayList<>();
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null)
            work.add(task);
        if (work.isEmpty())
            throw new SSLException("TLS engine requested an absent task");
        delegated = tasks.submit(() -> {
            for (Runnable delegatedTask : work)
                delegatedTask.run();
        });
    }

    /**
     * Wraps handshake or application output, reporting engine progress.
     */
    private boolean wrapOutput(ByteBuffer input,
            SSLEngineResult.HandshakeStatus previous) throws SSLException {
        SSLEngineResult result = engine.wrap(input, networkOutput);
        checkResult(result);
        return result.bytesConsumed() != 0 || result.bytesProduced() != 0 ||
                result.getHandshakeStatus() != previous;
    }

    /**
     * Unwraps available records and delivers their plaintext to the consumer.
     */
    private boolean unwrapInput(SSLEngineResult.HandshakeStatus previous)
            throws IOException {
        if (engine.isInboundDone() || (networkInput.position() == 0 &&
                !needsUnwrapWithoutInput(previous)))
            return false;
        ((Buffer) networkInput).flip();
        SSLEngineResult result = engine.unwrap(networkInput, applicationInput);
        networkInput.compact();
        checkResult(result);
        ((Buffer) applicationInput).flip();
        deliver(applicationInput);
        ((Buffer) applicationInput).clear();
        tlsClosed |= result.getStatus() == SSLEngineResult.Status.CLOSED;
        if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW &&
                !networkInput.hasRemaining())
            throw new SSLException("TLS record exceeds packet buffer");
        return result.bytesConsumed() != 0 || result.bytesProduced() != 0 ||
                result.getHandshakeStatus() != previous;
    }

    private static boolean needsUnwrapWithoutInput(
            SSLEngineResult.HandshakeStatus status) {
        // Some TLS runtimes do not declare this optional handshake status.
        return "NEED_UNWRAP_AGAIN".equals(status.name());
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

    /**
     * Reads without blocking and refreshes an active protocol input deadline.
     */
    private boolean readNetwork() throws IOException {
        if (eof || !networkInput.hasRemaining())
            return false;
        int count = channel.read(networkInput);
        if (count < 0)
            eof = true;
        if (count > 0 && handler != null)
            inputDeadline = deadline(readTimeout);
        return count != 0;
    }

    private boolean writeNetwork() throws IOException {
        if (networkOutput.position() == 0)
            return false;
        ((Buffer) networkOutput).flip();
        try {
            return channel.write(networkOutput) != 0;
        } finally {
            networkOutput.compact();
        }
    }

    /**
     * Waits for channel readiness while also observing tasks and interruption.
     */
    private void waitForNetwork() throws IOException {
        int interests = !eof && networkInput.hasRemaining() ? SelectionKey.OP_READ : 0;
        if (networkOutput.position() > 0)
            interests |= SelectionKey.OP_WRITE;
        key.interestOps(interests);
        selector.select(SELECT_TIMEOUT_MILLIS);
        selector.selectedKeys().clear();
    }

    /** Aborts physical I/O, including when invoked by a cancelling thread. */
    public void abort() {
        Throwable failure = closeResources(null);
        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;
        if (failure instanceof Error)
            throw (Error) failure;
        // Cancellation is best-effort. Ordinary close reports I/O failures.
    }

    /**
     * Aborts physical I/O, retaining cleanup errors on the original failure.
     * The caller remains responsible for reporting that failure.
     *
     * @param failure the original I/O or protocol failure
     */
    public void abort(Throwable failure) {
        if (failure == null) {
            abort();
            return;
        }
        Throwable result = closeResources(failure);
        if (result instanceof Error && result != failure)
            throw (Error) result;
    }

    private synchronized Throwable closeResources(Throwable failure) {
        if (closed)
            return failure;
        closed = true;
        failure = closeAfterFailure(socket, failure);
        // A custom socket can fail before closing its physical channel.
        failure = closeAfterFailure(channel, failure);
        failure = closeAfterFailure(selector, failure);
        if (tasks != null) {
            try {
                tasks.shutdownNow();
            } catch (RuntimeException | Error cleanup) {
                failure = addFailure(failure, cleanup);
            }
        }
        return failure;
    }

    private Throwable awaitTasks(Throwable failure) {
        if (tasks == null)
            return failure;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TASK_SHUTDOWN_TIMEOUT_SECONDS);
        try {
            while (!tasks.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return addFailure(failure, new IOException("TLS task did not stop"));
                try {
                    if (tasks.awaitTermination(remaining, TimeUnit.NANOSECONDS))
                        break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
        return failure;
    }

    /** Closes failed setup without starting a TLS shutdown exchange. */
    IOException closeOnFailure(IOException failure) {
        Throwable result = awaitTasks(closeResources(failure));
        if (result instanceof Error)
            throw (Error) result;
        return failure;
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
                long deadline = deadline(writeTimeout > 0 ?
                        Math.min(writeTimeout, CLOSE_TIMEOUT_MILLIS) : CLOSE_TIMEOUT_MILLIS);
                flushClose(deadline);
            }
        } catch (IOException | RuntimeException | Error ex) {
            failure = ex;
        } finally {
            failure = closeResources(failure);
            owner = null;
            failure = awaitTasks(failure);
        }
        if (failure instanceof IOException)
            throw (IOException) failure;
        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;
        if (failure instanceof Error)
            throw (Error) failure;
    }

    private void flushClose(long deadline) throws IOException {
        while (delegated != null || !engine.isOutboundDone() || networkOutput.position() > 0) {
            if (delegated == null) {
                SSLEngineResult.HandshakeStatus state = engine.getHandshakeStatus();
                // Some engines queue close output without requesting NEED_WRAP.
                if (state == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                        !engine.isOutboundDone() && networkOutput.remaining() >=
                        engine.getSession().getPacketBufferSize())
                    wrapOutput(EMPTY, state);
            }
            pump(deadline, true);
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

    /*
     * These Java SE 9 Socket methods are absent on older Android versions.
     * Match WriteTimeoutSocket: the providers do not use generic options,
     * and inherited methods would access this facade's unused SocketImpl.
     */
    //@Override
    public <T> Socket setOption(SocketOption<T> option, T value) throws IOException {
        throw new UnsupportedOperationException("DuplexSocket.setOption");
    }

    //@Override
    public <T> T getOption(SocketOption<T> option) throws IOException {
        throw new UnsupportedOperationException("DuplexSocket.getOption");
    }

    //@Override
    public Set<SocketOption<?>> supportedOptions() {
        return Collections.emptySet();
    }
}
