/*
 * Copyright (c) 2026 Benny Bottema (ai assisted). All rights reserved.
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

import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Checks stream contracts and caller-thread input callbacks. */
public class DuplexSocketCompatibilityTest {
    @Test(timeout = 10000)
    public void testReadBounds() throws Exception {
        try (Connection connection = new Connection()) {
            byte[] bytes = new byte[4];
            for (int[] range : invalidRanges()) {
                assertInvalidRange(() -> connection.socket.getInputStream()
                        .read(bytes, range[0], range[1]));
            }
            assertFalse(connection.socket.isClosed());
            connection.peer.getOutputStream().write(42);
            assertEquals(42, connection.socket.getInputStream().read());
        }
    }

    @Test(timeout = 10000)
    public void testWriteBounds() throws Exception {
        try (Connection connection = new Connection()) {
            byte[] bytes = new byte[4];
            for (int[] range : invalidRanges()) {
                assertInvalidRange(() -> connection.socket.getOutputStream()
                        .write(bytes, range[0], range[1]));
            }
            assertFalse(connection.socket.isClosed());
            connection.socket.getOutputStream().write(42);
            assertEquals(42, connection.peer.getInputStream().read());
        }
    }

    @Test(timeout = 10000)
    public void testNullBuffersAreRejectedBeforeIo() throws Exception {
        try (Connection connection = new Connection()) {
            try {
                connection.socket.getInputStream().read(null, 0, 0);
                fail("Null input buffer accepted");
            } catch (NullPointerException expected) {
            }
            try {
                connection.socket.getOutputStream().write(null, 0, 0);
                fail("Null output buffer accepted");
            } catch (NullPointerException expected) {
            }
            assertFalse(connection.socket.isClosed());
        }
    }

    @Test(timeout = 10000)
    public void testEmptyRangesAtBufferEnd() throws Exception {
        try (Connection connection = new Connection()) {
            byte[] bytes = new byte[4];
            assertEquals(0, connection.socket.getInputStream().read(bytes, 4, 0));
            connection.socket.getOutputStream().write(bytes, 4, 0);
            assertEquals(0, connection.peer.getInputStream().available());
            connection.peer.shutdownOutput();
            assertEquals(-1, connection.socket.getInputStream().read());
            assertEquals(0, connection.socket.getInputStream().read(bytes, 4, 0));
        }
    }

    @Test(timeout = 10000)
    public void testStreamSlicesPreserveContent() throws Exception {
        try (Connection connection = new Connection()) {
            connection.socket.getOutputStream().write(new byte[] {42, 3, 7, 11, 42}, 1, 3);
            byte[] expected = new byte[] {3, 7, 11};
            for (byte value : expected)
                assertEquals(value, connection.peer.getInputStream().read());

            connection.peer.getOutputStream().write(expected);
            byte[] received = new byte[] {42, 42, 42, 42, 42, 42};
            int count = 0;
            while (count < expected.length) {
                int next = connection.socket.getInputStream()
                        .read(received, 2 + count, expected.length - count);
                assertTrue(next > 0);
                count += next;
            }
            assertArrayEquals(new byte[] {42, 42, 3, 7, 11, 42}, received);
        }
    }

    @Test(timeout = 10000)
    public void testInputHandlerDrainsOnCallingThread() throws Exception {
        try (Connection connection = new Connection()) {
            byte[] expected = new byte[] {3, 7, 11, 19};
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            Thread caller = Thread.currentThread();
            connection.socket.setInputHandler(() -> {
                assertSame(caller, Thread.currentThread());
                byte[] bytes = new byte[connection.socket.getInputStream().available()];
                int count = connection.socket.getInputStream().read(bytes);
                received.write(bytes, 0, count);
            });
            try {
                connection.peer.getOutputStream().write(expected);
                connection.socket.awaitInput(() -> received.size() == expected.length);
                assertArrayEquals(expected, received.toByteArray());
            } finally {
                connection.socket.setInputHandler(null);
            }
            connection.socket.getOutputStream().write(42);
            assertEquals(42, connection.peer.getInputStream().read());
        }
    }

    @Test(timeout = 10000)
    public void testSatisfiedInputConditionNeedsNoNetworkData() throws Exception {
        try (Connection connection = new Connection()) {
            AtomicInteger calls = new AtomicInteger();
            connection.socket.awaitInput(() -> calls.incrementAndGet() == 1);
            assertEquals(1, calls.get());
            assertFalse(connection.socket.isClosed());
        }
    }

    @Test(timeout = 10000)
    public void testInputConditionFailureAbortsSocket() throws Exception {
        try (Connection connection = new Connection()) {
            IllegalStateException failure = new IllegalStateException("Input condition failed");
            try {
                connection.socket.awaitInput(() -> {
                    throw failure;
                });
                fail("Input condition failure ignored");
            } catch (IllegalStateException expected) {
                assertSame(failure, expected);
            }
            assertTrue(connection.socket.isClosed());
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    @Test(timeout = 10000)
    public void testConstructorPreservesFailureWhenSocketCloseFails() throws Exception {
        for (Throwable cleanup : new Throwable[] {new IOException("Socket close failed"),
                new IllegalStateException("Socket close failed"),
                new LinkageError("Socket close failed")}) {
            FailingCloseSocket failed = new FailingCloseSocket(cleanup);
            try {
                new DuplexSocket(failed, 2000);
                fail("Closed channel accepted");
            } catch (ClosedChannelException expected) {
                assertEquals(1, failed.closeCalls);
                assertArrayEquals(new Throwable[] {cleanup}, expected.getSuppressed());
            }
        }
    }

    @Test(timeout = 10000)
    public void testConstructorPropagatesFatalCleanupFailure() throws Exception {
        AssertionError fatal = new AssertionError("Fatal socket close failure");
        FailingCloseSocket failed = new FailingCloseSocket(fatal);
        try {
            new DuplexSocket(failed, 2000);
            fail("Closed channel accepted");
        } catch (AssertionError expected) {
            assertSame(fatal, expected);
            assertEquals(1, failed.closeCalls);
            assertEquals(1, expected.getSuppressed().length);
            assertTrue(expected.getSuppressed()[0] instanceof ClosedChannelException);
        }
    }

    @Test(timeout = 10000)
    public void testConstructorClosesChannelWhenTimeoutLookupFails() throws Exception {
        IOException cleanup = new IOException("Socket close failed");
        FailingCloseSocket raw = new FailingCloseSocket(cleanup, false);
        raw.timeoutFailure = new SocketException("Timeout lookup failed");
        try {
            try {
                new DuplexSocket(raw, 100);
                fail("Timeout lookup failure ignored");
            } catch (SocketException expected) {
                assertSame(raw.timeoutFailure, expected);
                assertArrayEquals(new Throwable[] {cleanup}, expected.getSuppressed());
            }
            assertFalse(raw.channel.isOpen());
            assertEquals(1, raw.closeCalls);
        } finally {
            raw.channel.close();
        }
    }

    @Test(timeout = 10000)
    public void testInterruptedClosePreservesInterrupt() throws Exception {
        try (Connection connection = new Connection()) {
            connection.socket.startTLS(new ClosingEngine(true));
            try {
                Thread.currentThread().interrupt();
                connection.socket.close();
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(connection.socket.getChannel().isOpen());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test(timeout = 10000)
    public void testInterruptedCloseWaitsForOwnedTask() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try (Connection connection = new Connection()) {
            connection.socket.startTLS(new ClosingEngine(true));
            java.lang.reflect.Field field = DuplexSocket.class.getDeclaredField("tasks");
            field.setAccessible(true);
            ExecutorService tasks = (ExecutorService) field.get(connection.socket);
            tasks.submit(() -> {
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    stopping.countDown();
                }
                try {
                    finish.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(3, TimeUnit.SECONDS));
            Future<Boolean> closed = closer.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    connection.socket.close();
                    return Thread.currentThread().isInterrupted();
                } finally {
                    Thread.interrupted();
                }
            });
            try {
                assertTrue(stopping.await(3, TimeUnit.SECONDS));
                try {
                    closed.get(100, TimeUnit.MILLISECONDS);
                    fail("Close returned before its task stopped");
                } catch (TimeoutException expected) {
                }
            } finally {
                finish.countDown();
            }
            assertTrue(closed.get(3, TimeUnit.SECONDS));
            assertTrue(tasks.isTerminated());
            assertFalse(connection.socket.getChannel().isOpen());
        } finally {
            finish.countDown();
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 10000)
    public void testCloseReportsFailureAndStillClosesChannel() throws Exception {
        for (Throwable cleanup : new Throwable[] {new IOException("Socket close failed"),
                new IllegalStateException("Socket close failed"),
                new LinkageError("Socket close failed"),
                new AssertionError("Socket close failed")}) {
            FailingCloseSocket raw = new FailingCloseSocket(cleanup, false);
            try {
                DuplexSocket socket = new DuplexSocket(raw, 100);
                Throwable failure = null;
                try {
                    socket.close();
                } catch (Throwable ex) {
                    failure = ex;
                }
                assertSame(cleanup, failure);
                assertTrue(socket.isClosed());
                assertFalse(raw.channel.isOpen());
                assertEquals(1, raw.closeCalls);
            } finally {
                raw.channel.close();
            }
        }
    }

    @Test(timeout = 10000)
    public void testConfigurationFailureRetainsCleanupFailures() throws Exception {
        for (Throwable cleanup : new Throwable[] {new IOException("Socket close failed"),
                new IllegalStateException("Socket close failed"),
                new LinkageError("Socket close failed")}) {
            FailingCloseSocket raw = new FailingCloseSocket(cleanup, false);
            try {
                DuplexSocket socket = new DuplexSocket(raw, 100);
                Properties properties = new Properties();
                properties.setProperty("mail.smtp.ssl.protocols", "invalid-protocol");
                try {
                    SocketFetcher.startTLS(socket, "localhost", properties, "mail.smtp");
                    fail("Invalid TLS configuration accepted");
                } catch (IOException expected) {
                    Throwable cause = expected;
                    while (cause != null && !(cause instanceof IllegalArgumentException))
                        cause = cause.getCause();
                    assertTrue("Configuration failure was lost", cause instanceof IllegalArgumentException);
                    assertArrayEquals(new Throwable[] {cleanup}, expected.getSuppressed());
                }
                assertFalse(raw.channel.isOpen());
                assertEquals(1, raw.closeCalls);
            } finally {
                raw.channel.close();
            }
        }
    }

    @Test(timeout = 10000)
    public void testInputFailureRetainsCleanupFailure() throws Exception {
        IllegalStateException failure = new IllegalStateException("Input condition failed");
        IOException cleanup = new IOException("Socket close failed");
        FailingCloseSocket raw = new FailingCloseSocket(cleanup, false);
        try {
            DuplexSocket socket = new DuplexSocket(raw, 100);
            try {
                socket.awaitInput(() -> { throw failure; });
                fail("Input failure ignored");
            } catch (IllegalStateException expected) {
                assertSame(failure, expected);
                assertArrayEquals(new Throwable[] {cleanup}, expected.getSuppressed());
            }
            assertFalse(raw.channel.isOpen());
        } finally {
            raw.channel.close();
        }
    }

    @Test(timeout = 10000)
    public void testCloseWrapsPendingAlertWithoutHandshake() throws Exception {
        try (Connection connection = new Connection()) {
            ClosingEngine engine = new ClosingEngine(true);
            connection.socket.startTLS(engine);
            connection.socket.close();
            assertEquals(1, engine.wrapCalls);
            assertTrue(engine.isOutboundDone());
            for (byte value : ClosingEngine.ALERT)
                assertEquals(value, connection.peer.getInputStream().read());
            assertEquals(-1, connection.peer.getInputStream().read());
        }
    }

    @Test(timeout = 10000)
    public void testCloseDoesNotReadAfterSendingLastAlert() throws Exception {
        for (boolean needWrap : new boolean[] {false, true}) {
            try (Connection connection = new Connection()) {
                ClosingEngine engine = new ClosingEngine(true, needWrap);
                connection.socket.startTLS(engine);
                connection.peer.shutdownOutput();
                connection.socket.close();
                assertTrue(engine.isOutboundDone());
                assertEquals(0, engine.inboundCloses);
                for (byte value : ClosingEngine.ALERT)
                    assertEquals(value, connection.peer.getInputStream().read());
                assertEquals(-1, connection.peer.getInputStream().read());
            }
        }
    }

    @Test(timeout = 10000)
    public void testCloseWithoutEngineProgressStillTimesOut() throws Exception {
        try (Connection connection = new Connection(100)) {
            ClosingEngine engine = new ClosingEngine(false);
            connection.socket.startTLS(engine);
            try {
                connection.socket.close();
                fail("Unfinished TLS closure accepted");
            } catch (SocketTimeoutException expected) {
                assertTrue(engine.wrapCalls > 0);
                assertTrue(connection.socket.isClosed());
                assertFalse(connection.socket.getChannel().isOpen());
            }
        }
    }

    private static int[][] invalidRanges() {
        return new int[][] {
            {-1, 1}, {0, -1}, {3, 2}, {5, 0},
            {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}
        };
    }

    private static void assertInvalidRange(IoOperation operation) throws IOException {
        try {
            operation.run();
            fail("Invalid buffer range accepted");
        } catch (IndexOutOfBoundsException expected) {
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    private static final class FailingCloseSocket extends Socket {
        final SocketChannel channel;
        final Throwable cleanup;
        int closeCalls;
        SocketException timeoutFailure;

        FailingCloseSocket(Throwable cleanup) throws IOException {
            this(cleanup, true);
        }

        FailingCloseSocket(Throwable cleanup, boolean closed) throws IOException {
            this.cleanup = cleanup;
            channel = SocketChannel.open();
            if (closed)
                channel.close();
        }

        @Override
        public SocketChannel getChannel() {
            return channel;
        }

        @Override
        public int getSoTimeout() throws SocketException {
            if (timeoutFailure != null)
                throw timeoutFailure;
            return 0;
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if (cleanup instanceof IOException)
                throw (IOException) cleanup;
            if (cleanup instanceof RuntimeException)
                throw (RuntimeException) cleanup;
            throw (Error) cleanup;
        }
    }

    /** Models engines that queue close output without changing handshake status. */
    private static final class ClosingEngine extends SSLEngine {
        static final byte[] ALERT = new byte[] {21, 3, 3};
        final SSLEngine delegate;
        final boolean makesProgress;
        final boolean needWrap;
        int wrapCalls;
        int inboundCloses;
        boolean closing;

        ClosingEngine(boolean makesProgress) throws Exception {
            this(makesProgress, false);
        }

        ClosingEngine(boolean makesProgress, boolean needWrap) throws Exception {
            delegate = SSLContext.getDefault().createSSLEngine();
            this.makesProgress = makesProgress;
            this.needWrap = needWrap;
        }

        @Override
        public SSLEngineResult wrap(ByteBuffer[] source, int offset, int length,
                ByteBuffer destination) throws SSLException {
            wrapCalls++;
            if (makesProgress) {
                destination.put(ALERT);
                return new SSLEngineResult(SSLEngineResult.Status.CLOSED,
                        getHandshakeStatus(), 0, ALERT.length);
            }
            return new SSLEngineResult(SSLEngineResult.Status.OK,
                    getHandshakeStatus(), 0, 0);
        }

        @Override
        public SSLEngineResult unwrap(ByteBuffer source, ByteBuffer[] destination,
                int offset, int length) throws SSLException {
            return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW,
                    getHandshakeStatus(), 0, 0);
        }

        @Override
        public Runnable getDelegatedTask() { return null; }
        @Override
        public void closeInbound() { inboundCloses++; }
        @Override
        public boolean isInboundDone() { return false; }
        @Override
        public void closeOutbound() { closing = true; }
        @Override
        public boolean isOutboundDone() { return closing && makesProgress && wrapCalls > 0; }
        @Override
        public SSLSession getSession() { return delegate.getSession(); }
        @Override
        public void beginHandshake() { }
        @Override
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
            return closing && needWrap && !isOutboundDone()
                    ? SSLEngineResult.HandshakeStatus.NEED_WRAP
                    : SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
        }
        @Override
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override
        public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
        @Override
        public void setEnabledCipherSuites(String[] suites) { delegate.setEnabledCipherSuites(suites); }
        @Override
        public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
        @Override
        public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
        @Override
        public void setEnabledProtocols(String[] protocols) { delegate.setEnabledProtocols(protocols); }
        @Override
        public void setUseClientMode(boolean client) { delegate.setUseClientMode(client); }
        @Override
        public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        @Override
        public void setNeedClientAuth(boolean required) { delegate.setNeedClientAuth(required); }
        @Override
        public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
        @Override
        public void setWantClientAuth(boolean wanted) { delegate.setWantClientAuth(wanted); }
        @Override
        public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
        @Override
        public void setEnableSessionCreation(boolean enabled) { delegate.setEnableSessionCreation(enabled); }
        @Override
        public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
    }

    private static final class Connection implements AutoCloseable {
        final DuplexSocket socket;
        final Socket peer;

        Connection() throws IOException {
            this(2000);
        }

        Connection(int writeTimeout) throws IOException {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
                server.setSoTimeout(2000);
                Socket client = SocketChannel.open().socket();
                Socket accepted = null;
                try {
                    client.connect(new InetSocketAddress(loopback, server.getLocalPort()), 2000);
                    client.setSoTimeout(2000);
                    accepted = server.accept();
                    accepted.setSoTimeout(2000);
                    socket = new DuplexSocket(client, writeTimeout);
                    peer = accepted;
                } catch (IOException | RuntimeException ex) {
                    try {
                        client.close();
                    } catch (IOException cleanup) {
                        ex.addSuppressed(cleanup);
                    }
                    if (accepted != null) {
                        try {
                            accepted.close();
                        } catch (IOException cleanup) {
                            ex.addSuppressed(cleanup);
                        }
                    }
                    throw ex;
                }
            }
        }

        @Override
        public void close() throws IOException {
            try {
                socket.close();
            } finally {
                peer.close();
            }
        }
    }
}
