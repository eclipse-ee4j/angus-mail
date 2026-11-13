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
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Exercises provider buffer changes without depending on encrypted record sizes. */
public class DuplexSocketBufferTest {
    @Test(timeout = 15000)
    public void testFatalHandshakeFailureStillClosesTheConnection() throws Exception {
        try (Connection connection = new Connection()) {
            BufferEngine engine = new BufferEngine(Mode.FATAL);
            assertSame(engine.fatal, assertThrows(AssertionError.class,
                    () -> connection.socket.startTLS(engine)));
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    @Test(timeout = 15000)
    public void testWrapRetriesAfterPacketBufferGrowth() throws Exception {
        try (Connection connection = new Connection()) {
            BufferEngine engine = new BufferEngine(Mode.WRAP);
            connection.socket.startTLS(engine);
            connection.socket.getOutputStream().write(42);
            assertEquals(42, connection.peer.getInputStream().read());
            assertTrue(engine.wrapCalls > 1);
        }
    }

    @Test(timeout = 15000)
    public void testUnwrapRetriesAfterApplicationBufferGrowth() throws Exception {
        try (Connection connection = new Connection()) {
            BufferEngine engine = new BufferEngine(Mode.UNWRAP);
            connection.socket.startTLS(engine);
            connection.peer.getOutputStream().write(42);
            assertEquals(42, connection.socket.getInputStream().read());
            assertTrue(engine.unwrapCalls > 1);
        }
    }

    @Test(timeout = 15000)
    public void testFullInputUnderflowRetainsThePartialRecord() throws Exception {
        try (Connection connection = new Connection()) {
            BufferEngine engine = new BufferEngine(Mode.RECORD);
            connection.socket.startTLS(engine);
            ExecutorService writer = Executors.newSingleThreadExecutor();
            try {
                Future<?> write = writer.submit(() -> {
                    byte[] record = new byte[128 * 1024];
                    record[0] = 42;
                    try {
                        connection.peer.getOutputStream().write(record);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                assertEquals(42, connection.socket.getInputStream().read());
                write.get(3, TimeUnit.SECONDS);
                assertTrue(engine.unwrapCalls > 1);
            } finally {
                writer.shutdownNow();
            }
        }
    }

    @Test(timeout = 15000)
    public void testExcessiveProviderBufferRequestFailsBeforeAllocation() throws Exception {
        try (Connection connection = new Connection()) {
            IOException failure = assertThrows(IOException.class,
                    () -> connection.socket.startTLS(new BufferEngine(Mode.HUGE)));
            assertTrue(failure.getMessage(), failure.getMessage().contains("buffer"));
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    @Test(timeout = 15000)
    public void testClosedHandshakeIsNotEstablished() throws Exception {
        try (Connection connection = new Connection()) {
            assertThrows(IOException.class,
                    () -> connection.socket.startTLS(new BufferEngine(Mode.CLOSED)));
            assertFalse(connection.socket.isSSL());
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    @Test(timeout = 15000)
    public void testUnconsumedPlaintextHasAnInputLimit() throws Exception {
        try (Connection connection = new Connection()) {
            ExecutorService writer = Executors.newSingleThreadExecutor();
            try {
                Future<?> write = writer.submit(() -> {
                    try {
                        connection.peer.getOutputStream().write(new byte[5 * 1024 * 1024]);
                        connection.peer.shutdownOutput();
                    } catch (IOException expected) {
                        // The client closes the connection on exceeding its input limit.
                    }
                });
                IOException failure = assertThrows(IOException.class,
                        () -> connection.socket.awaitInput(() -> false));
                assertTrue(failure.getMessage(), failure.getMessage().contains("input limit"));
                assertFalse(connection.socket.getChannel().isOpen());
                write.get(3, TimeUnit.SECONDS);
            } finally {
                writer.shutdownNow();
            }
        }
    }

    private enum Mode { WRAP, UNWRAP, RECORD, HUGE, CLOSED, FATAL }

    /** Copies bytes only; the real-provider tests cover TLS encryption itself. */
    private static final class BufferEngine extends SSLEngine {
        private static final int REQUIRED = 128 * 1024;
        private final SSLEngine settings;
        private final SSLSession session;
        private final Mode mode;
        private final AssertionError fatal = new AssertionError("TLS provider setup failed");
        private boolean closed;
        private int packet = 1024;
        private int application = 1024;
        private int wrapCalls;
        private int unwrapCalls;

        BufferEngine(Mode mode) throws Exception {
            this.mode = mode;
            settings = SSLContext.getDefault().createSSLEngine();
            SSLSession original = settings.getSession();
            session = (SSLSession) Proxy.newProxyInstance(SSLSession.class.getClassLoader(),
                    new Class<?>[]{SSLSession.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getPacketBufferSize"))
                            return mode == Mode.HUGE ? Integer.MAX_VALUE : packet;
                        if (method.getName().equals("getApplicationBufferSize"))
                            return application;
                        return method.invoke(original, args);
                    });
        }

        @Override
        public SSLEngineResult wrap(ByteBuffer[] sources, int offset, int length,
                ByteBuffer destination) {
            wrapCalls++;
            if (mode == Mode.WRAP && destination.remaining() < REQUIRED) {
                packet = REQUIRED;
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0);
            }
            int count = 0;
            for (int i = offset; i < offset + length; i++) {
                while (sources[i].hasRemaining() && destination.hasRemaining()) {
                    destination.put(sources[i].get());
                    count++;
                }
            }
            return result(SSLEngineResult.Status.OK, count, count);
        }

        @Override
        public SSLEngineResult unwrap(ByteBuffer source, ByteBuffer[] destinations,
                int offset, int length) {
            unwrapCalls++;
            ByteBuffer destination = destinations[offset];
            if (mode == Mode.UNWRAP && destination.remaining() < REQUIRED) {
                application = REQUIRED;
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0);
            }
            if (mode == Mode.RECORD) {
                packet = REQUIRED;
                if (source.remaining() < REQUIRED)
                    return result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0);
                destination.put(source.get());
                source.position(source.position() + REQUIRED - 1);
                return result(SSLEngineResult.Status.OK, REQUIRED, 1);
            }
            int count = Math.min(source.remaining(), destination.remaining());
            for (int i = 0; i < count; i++)
                destination.put(source.get());
            return result(SSLEngineResult.Status.OK, count, count);
        }

        private SSLEngineResult result(SSLEngineResult.Status status, int read, int written) {
            return new SSLEngineResult(status, getHandshakeStatus(), read, written);
        }

        @Override
        public Runnable getDelegatedTask() { return null; }
        @Override
        public void closeInbound() { }
        @Override
        public boolean isInboundDone() { return closed; }
        @Override
        public void closeOutbound() { closed = true; }
        @Override
        public boolean isOutboundDone() { return closed; }
        @Override
        public SSLSession getSession() { return session; }
        @Override
        public void beginHandshake() {
            if (mode == Mode.FATAL)
                throw fatal;
            closed = mode == Mode.CLOSED;
        }
        @Override
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
            return SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
        }
        @Override
        public String[] getSupportedCipherSuites() { return settings.getSupportedCipherSuites(); }
        @Override
        public String[] getEnabledCipherSuites() { return settings.getEnabledCipherSuites(); }
        @Override
        public void setEnabledCipherSuites(String[] value) { settings.setEnabledCipherSuites(value); }
        @Override
        public String[] getSupportedProtocols() { return settings.getSupportedProtocols(); }
        @Override
        public String[] getEnabledProtocols() { return settings.getEnabledProtocols(); }
        @Override
        public void setEnabledProtocols(String[] value) { settings.setEnabledProtocols(value); }
        @Override
        public void setUseClientMode(boolean value) { settings.setUseClientMode(value); }
        @Override
        public boolean getUseClientMode() { return settings.getUseClientMode(); }
        @Override
        public void setNeedClientAuth(boolean value) { settings.setNeedClientAuth(value); }
        @Override
        public boolean getNeedClientAuth() { return settings.getNeedClientAuth(); }
        @Override
        public void setWantClientAuth(boolean value) { settings.setWantClientAuth(value); }
        @Override
        public boolean getWantClientAuth() { return settings.getWantClientAuth(); }
        @Override
        public void setEnableSessionCreation(boolean value) { settings.setEnableSessionCreation(value); }
        @Override
        public boolean getEnableSessionCreation() { return settings.getEnableSessionCreation(); }
    }

    private static final class Connection implements AutoCloseable {
        final DuplexSocket socket;
        final Socket peer;

        Connection() throws IOException {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
                server.setSoTimeout(3000);
                Socket client = SocketChannel.open().socket();
                client.connect(new InetSocketAddress(loopback, server.getLocalPort()), 3000);
                client.setSoTimeout(5000);
                peer = server.accept();
                peer.setSoTimeout(5000);
                socket = new DuplexSocket(client, 5000);
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
