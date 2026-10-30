/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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

import org.eclipse.angus.mail.test.ProtocolHandler;
import org.eclipse.angus.mail.test.TestServer;
import org.eclipse.angus.mail.test.TestSSLSocketFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests composition of transport creation with Angus socket and TLS policy.
 */
public final class SocketFetcherRawFactoryTest {

    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    @Test
    public void rawTransportComposesWithSslFactoryForAllProtocols()
            throws Exception {
        for (String protocol : new String[]{"smtp", "imap", "pop3"})
            connectWithTlsFactory("mail." + protocol, true);
    }

    @Test
    public void rawTransportComposesWithGeneralTlsFactoryForAllProtocols()
            throws Exception {
        for (String protocol : new String[]{"smtp", "imap", "pop3"})
            connectWithTlsFactory("mail." + protocol, false);
    }

    private void connectWithTlsFactory(String prefix, boolean sslSlot)
            throws Exception {
        TestServer server = new TestServer(new EchoHandler(), true);
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingTlsFactory tls = new RecordingTlsFactory();
        try (RecordingSocket raw = new RecordingSocket()) {
            TestFactory factory = new TestFactory(raw);
            Properties props = properties(prefix, factory);
            String factoryKey = sslSlot ? ".ssl.socketFactory" : ".socketFactory";
            props.put(prefix + factoryKey, tls);
            props.setProperty(prefix + factoryKey + ".port",
                    Integer.toString(server.getPort()));
            // An explicitly supplied SSL factory owns trust even when this is set.
            props.setProperty(prefix + ".ssl.trust", "untrusted.invalid");
            props.setProperty(prefix + ".ssl.protocols", "TLSv1.2");
            props.setProperty(prefix + ".localaddress", "127.0.0.1");
            props.setProperty(prefix + ".localport", "0");
            props.setProperty(prefix + ".writetimeout", "5000");
            props.put(prefix + ".executor.writetimeout", scheduler);
            server.start();

            // The legacy factory's port selection must also apply to the raw socket.
            try (Socket socket = SocketFetcher.getSocket("localhost", 2,
                    props, prefix, sslSlot)) {
                assertEquals(prefix, 1, factory.calls);
                assertTrue(prefix, socket instanceof SSLSocket);
                assertEquals(1, tls.wrapCalls);
                assertTrue(tls.transport instanceof WriteTimeoutSocket);
                assertEquals(raw.getLocalSocketAddress(),
                        tls.transport.getLocalSocketAddress());
                assertEquals("localhost", tls.host);
                assertEquals(server.getPort(), tls.port);
                assertTrue(tls.autoClose);
                assertEquals(new InetSocketAddress("localhost", server.getPort()),
                        raw.endpoint);
                assertEquals(1000, raw.connectTimeout);
                assertEquals(new InetSocketAddress("127.0.0.1", 0), raw.bound);
                assertEquals(Arrays.asList("timeout", "bind", "connect"),
                        raw.events.subList(0, 3));
                assertEquals(2000, socket.getSoTimeout());
                assertEquals("TLSv1.2", ((SSLSocket) socket).getSession().getProtocol());
                exchangeByte(socket);
                assertTrue("Native write timeout must cover TLS writes",
                        scheduler.schedules > 0);
            }
            assertTrue(raw.isClosed());
            assertFalse("Angus must leave a caller-owned scheduler running",
                    scheduler.isShutdown());
        } finally {
            scheduler.shutdownNow();
            server.quit();
        }
    }

    @Test
    public void rawTransportUsesDefaultTlsAndSslTrust() throws Exception {
        TestServer server = new TestServer(new EchoHandler(), true);
        try (RecordingSocket raw = new RecordingSocket()) {
            TestFactory factory = new TestFactory(raw);
            Properties props = properties("mail.smtp", factory);
            props.setProperty("mail.smtp.ssl.trust", "localhost");
            server.start();
            try (Socket socket = SocketFetcher.getSocket("localhost", server.getPort(),
                    props, "mail.smtp", true)) {
                assertTrue(socket instanceof SSLSocket);
                assertEquals(1, factory.calls);
                assertEquals(server.getPort(), raw.getPort());
                exchangeByte(socket);
            }
            assertTrue(raw.isClosed());
        } finally {
            server.quit();
        }
    }

    @Test
    public void startTlsWrapsExistingRawTransportWithoutCreatingAnother()
            throws Exception {
        TestServer server = new TestServer(new EchoHandler(), true);
        RecordingTlsFactory tls = new RecordingTlsFactory();
        try (RecordingSocket raw = new RecordingSocket()) {
            TestFactory factory = new TestFactory(raw);
            Properties props = properties("mail.imap", factory);
            props.put("mail.imap.ssl.socketFactory", tls);
            server.start();
            try (Socket plain = SocketFetcher.getSocket("localhost", server.getPort(),
                    props, "mail.imap", false);
                 Socket secure = SocketFetcher.startTLS(plain, "localhost", props,
                         "mail.imap")) {
                assertSame(raw, plain);
                assertTrue(secure instanceof SSLSocket);
                assertSame(raw, tls.transport);
                assertEquals(1, factory.calls);
                assertEquals(1, tls.wrapCalls);
                exchangeByte(secure);
            }
        } finally {
            server.quit();
        }
    }

    @Test
    public void originalMailSslFactoryRetainsTrustCallbackAndRejection()
            throws Exception {
        for (boolean accept : new boolean[]{true, false}) {
            TestServer server = new TestServer(new EchoHandler(), true);
            TrustCheckingFactory tls = new TrustCheckingFactory(accept);
            try (RecordingSocket raw = new RecordingSocket()) {
                TestFactory factory = new TestFactory(raw);
                Properties props = properties("mail.pop3", factory);
                props.put("mail.pop3.ssl.socketFactory", tls);
                props.setProperty("mail.pop3.ssl.trust", "*");
                server.start();
                if (accept) {
                    try (Socket socket = SocketFetcher.getSocket("localhost",
                            server.getPort(), props, "mail.pop3", true)) {
                        assertSame(socket, tls.checkedSocket);
                        exchangeByte(socket);
                    }
                } else {
                    IOException failure = assertThrows(IOException.class, () ->
                            SocketFetcher.getSocket("localhost", server.getPort(),
                                    props, "mail.pop3", true));
                    assertTrue(failure.getMessage(),
                            failure.getMessage().contains("not trusted"));
                    assertTrue(raw.isClosed());
                    assertTrue(tls.checkedSocket.isClosed());
                }
                assertEquals(1, factory.calls);
                assertEquals(1, tls.trustChecks);
                assertEquals("localhost", tls.checkedHost);
                assertNotNull(tls.checkedSocket);
            } finally {
                server.quit();
            }
        }
    }

    @Test
    public void withoutRawHookPreservesRegularFactoryAndItsPort() throws Exception {
        try (FiniteSocket socket = new FiniteSocket()) {
            TestFactory factory = new TestFactory(socket);
            Properties props = properties("mail.smtp", null);
            props.put("mail.smtp.socketFactory", factory);
            props.setProperty("mail.smtp.socketFactory.port", "2465");
            assertSame(socket, SocketFetcher.getSocket("127.0.0.1", 25,
                    props, "mail.smtp", false));
            assertEquals(1, factory.calls);
            assertEquals(new InetSocketAddress("127.0.0.1", 2465), socket.endpoint);
            assertEquals(2000, socket.getSoTimeout());
        }
    }

    @Test
    public void instanceTakesPrecedenceOverRawClass() throws Exception {
        synchronized (DefaultFactory.class) {
            DefaultFactory.resolutions = 0;
            DefaultFactory.factory = null;
            try (FiniteSocket raw = new FiniteSocket()) {
                TestFactory factory = new TestFactory(raw);
                Properties props = properties("mail.imap", factory);
                props.setProperty("mail.imap.socketFactory.raw.class",
                        DefaultFactory.class.getName());
                assertSame(raw, SocketFetcher.getSocket("127.0.0.1", 143,
                        props, "mail.imap", false));
                assertEquals(1, factory.calls);
                assertEquals(0, DefaultFactory.resolutions);
            }
        }
    }

    @Test
    public void rawClassIsResolvedOncePerConnection() throws Exception {
        synchronized (DefaultFactory.class) {
            DefaultFactory.resolutions = 0;
            Properties props = properties("mail.pop3", null);
            props.setProperty("mail.pop3.socketFactory.raw.class",
                    DefaultFactory.class.getName());
            try {
                for (int connection = 1; connection <= 2; connection++) {
                    try (FiniteSocket raw = new FiniteSocket()) {
                        TestFactory factory = new TestFactory(raw);
                        DefaultFactory.factory = factory;
                        assertSame(raw, SocketFetcher.getSocket("127.0.0.1", 110,
                                props, "mail.pop3", false));
                        assertEquals(1, factory.calls);
                        assertEquals(connection, DefaultFactory.resolutions);
                    }
                }
            } finally {
                DefaultFactory.factory = null;
            }
        }
    }

    @Test
    public void invalidRawClassCannotUseLegacyFactoryFallback() throws Exception {
        for (String className : new String[]{"no.such.RawSocketFactory",
                WrongDefaultFactory.class.getName()}) {
            try (FiniteSocket legacySocket = new FiniteSocket()) {
                TestFactory legacy = new TestFactory(legacySocket);
                Properties props = properties("mail.smtp", null);
                props.setProperty("mail.smtp.socketFactory.raw.class", className);
                props.put("mail.smtp.socketFactory", legacy);
                IOException failure = assertThrows(IOException.class, () ->
                        SocketFetcher.getSocket("127.0.0.1", 25,
                                props, "mail.smtp", false));
                assertTrue(failure.getMessage(),
                        failure.getMessage().contains("socketFactory.raw.class"));
                assertNotNull(failure.getCause());
                assertEquals(0, legacy.calls);
            }
        }
    }

    @Test
    public void nullClassFactoryCannotUseLegacyFactoryFallback() throws Exception {
        synchronized (DefaultFactory.class) {
            DefaultFactory.resolutions = 0;
            DefaultFactory.factory = null;
            try (FiniteSocket legacySocket = new FiniteSocket()) {
                TestFactory legacy = new TestFactory(legacySocket);
                Properties props = properties("mail.smtp", null);
                props.setProperty("mail.smtp.socketFactory.raw.class",
                        DefaultFactory.class.getName());
                props.put("mail.smtp.socketFactory", legacy);
                assertThrows(IOException.class, () -> SocketFetcher.getSocket(
                        "127.0.0.1", 25, props, "mail.smtp", false));
                assertEquals(1, DefaultFactory.resolutions);
                assertEquals(0, legacy.calls);
            }
        }
    }

    @Test
    public void nullRawSocketCannotUseLegacyFactoryFallback() throws Exception {
        try (FiniteSocket legacySocket = new FiniteSocket()) {
            TestFactory raw = new TestFactory(null);
            TestFactory legacy = new TestFactory(legacySocket);
            Properties props = properties("mail.imap", raw);
            props.put("mail.imap.socketFactory", legacy);
            assertThrows(IOException.class, () -> SocketFetcher.getSocket(
                    "127.0.0.1", 143, props, "mail.imap", false));
            assertEquals(1, raw.calls);
            assertEquals(0, legacy.calls);
        }
    }

    @Test
    public void rawCreationFailureIsNotRetriedOrBypassed() throws Exception {
        try (FiniteSocket legacySocket = new FiniteSocket()) {
            IOException expected = new IOException("raw transport creation failed");
            TestFactory raw = new TestFactory(null) {
                @Override
                public Socket createSocket() throws IOException {
                    calls++;
                    throw expected;
                }
            };
            TestFactory legacy = new TestFactory(legacySocket);
            Properties props = properties("mail.pop3", raw);
            props.put("mail.pop3.socketFactory", legacy);
            assertSame(expected, assertThrows(IOException.class, () ->
                    SocketFetcher.getSocket("127.0.0.1", 110,
                            props, "mail.pop3", false)));
            assertEquals(1, raw.calls);
            assertEquals(0, legacy.calls);
        }
    }

    @Test
    public void closedConnectedAndSslRawSocketsAreRejected() throws Exception {
        FiniteSocket closed = new FiniteSocket();
        closed.close();
        FiniteSocket connected = new FiniteSocket();
        connected.connected = true;
        try (Socket ssl = TestServer.createSSLContext().getSocketFactory().createSocket();
             FiniteSocket legacySocket = new FiniteSocket()) {
            for (Socket invalid : new Socket[]{closed, connected, ssl}) {
                TestFactory raw = new TestFactory(invalid);
                TestFactory legacy = new TestFactory(legacySocket);
                Properties props = properties("mail.smtp", raw);
                props.put("mail.smtp.socketFactory", legacy);
                assertThrows(IOException.class, () -> SocketFetcher.getSocket(
                        "127.0.0.1", 25, props, "mail.smtp", false));
                assertEquals(1, raw.calls);
                assertEquals(0, legacy.calls);
                assertTrue(invalid.isClosed());
            }
        } finally {
            closed.close();
            connected.close();
        }
    }

    @Test
    public void legacyClassResolutionMayFallBackBeforeRawCreation()
            throws Exception {
        try (FiniteSocket rawSocket = new FiniteSocket()) {
            TestFactory raw = new TestFactory(rawSocket);
            Properties props = properties("mail.imap", raw);
            props.setProperty("mail.imap.socketFactory.class",
                    "no.such.LegacySocketFactory");
            assertSame(rawSocket, SocketFetcher.getSocket("127.0.0.1", 143,
                    props, "mail.imap", false));
            assertEquals(1, raw.calls);
            assertEquals(new InetSocketAddress("127.0.0.1", 143), rawSocket.endpoint);
        }
    }

    @Test
    public void connectFailureClosesRawAndRetainsSuppressedCloseFailure()
            throws Exception {
        IOException connectFailure = new IOException("connect failed");
        IOException closeFailure = new IOException("close failed");
        FiniteSocket rawSocket = new FiniteSocket();
        rawSocket.connectFailure = connectFailure;
        rawSocket.closeFailure = closeFailure;
        try (FiniteSocket legacySocket = new FiniteSocket()) {
            TestFactory raw = new TestFactory(rawSocket);
            TestFactory legacy = new TestFactory(legacySocket);
            Properties props = properties("mail.smtp", raw);
            props.put("mail.smtp.socketFactory", legacy);
            IOException failure = assertThrows(IOException.class, () ->
                    SocketFetcher.getSocket("127.0.0.1", 25,
                            props, "mail.smtp", false));
            assertSame(connectFailure, failure.getCause());
            assertEquals(1, failure.getSuppressed().length);
            assertSame(closeFailure, failure.getSuppressed()[0]);
            assertTrue(rawSocket.isClosed());
            assertEquals(1, raw.calls);
            assertEquals(0, legacy.calls);
        }
    }

    @Test
    public void tlsFactoryFailureClosesRawWithoutRetry() throws Exception {
        IOException expected = new IOException("TLS wrapping failed");
        RecordingTlsFactory tls = new RecordingTlsFactory() {
            @Override
            public Socket createSocket(Socket socket, String host, int port,
                                       boolean autoClose) throws IOException {
                wrapCalls++;
                throw expected;
            }
        };
        try (FiniteSocket rawSocket = new FiniteSocket()) {
            TestFactory raw = new TestFactory(rawSocket);
            Properties props = properties("mail.imap", raw);
            props.put("mail.imap.ssl.socketFactory", tls);
            assertSame(expected, assertThrows(IOException.class, () ->
                    SocketFetcher.getSocket("127.0.0.1", 0,
                            props, "mail.imap", true)));
            assertTrue(rawSocket.isClosed());
            assertEquals(1, raw.calls);
            assertEquals(1, tls.wrapCalls);
        }
    }

    @Test
    public void handshakeFailureClosesRawAndTlsSockets() throws Exception {
        TestServer server = new TestServer(new NonTlsHandler());
        RecordingTlsFactory tls = new RecordingTlsFactory();
        try (RecordingSocket rawSocket = new RecordingSocket()) {
            TestFactory raw = new TestFactory(rawSocket);
            Properties props = properties("mail.pop3", raw);
            props.put("mail.pop3.ssl.socketFactory", tls);
            server.start();
            assertThrows(IOException.class, () -> SocketFetcher.getSocket(
                    "localhost", server.getPort(), props, "mail.pop3", true));
            assertEquals(1, raw.calls);
            assertEquals(1, tls.wrapCalls);
            assertTrue(rawSocket.isClosed());
            assertNotNull(tls.layered);
            assertTrue(tls.layered.isClosed());
        } finally {
            server.quit();
        }
    }

    private static Properties properties(String prefix, SocketFactory raw) {
        Properties props = new Properties();
        if (raw != null)
            props.put(prefix + ".socketFactory.raw", raw);
        props.setProperty(prefix + ".socketFactory.fallback", "true");
        props.setProperty(prefix + ".connectiontimeout", "1000");
        props.setProperty(prefix + ".timeout", "2000");
        props.setProperty(prefix + ".ssl.checkserveridentity", "false");
        return props;
    }

    private static void exchangeByte(Socket socket) throws IOException {
        socket.getOutputStream().write(42);
        socket.getOutputStream().flush();
        assertEquals(42, socket.getInputStream().read());
    }

    private static final class EchoHandler extends ProtocolHandler {
        @Override
        public void handleCommand() throws IOException {
            int b = in.read();
            if (b >= 0) {
                clientSocket.getOutputStream().write(b);
                clientSocket.getOutputStream().flush();
            }
            exit();
        }
    }

    private static final class NonTlsHandler extends ProtocolHandler {
        @Override
        public void sendGreetings() {
            writer.println("This peer does not speak TLS");
            writer.flush();
            exit();
        }

        @Override
        public void handleCommand() {
            exit();
        }
    }

    private static class TestFactory extends SocketFactory {
        private final Socket socket;
        protected int calls;

        TestFactory(Socket socket) {
            this.socket = socket;
        }

        @Override
        public Socket createSocket() throws IOException {
            calls++;
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new AssertionError("Expected unconnected socket creation");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new AssertionError("Expected unconnected socket creation");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) {
            throw new AssertionError("Expected unconnected socket creation");
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
            throw new AssertionError("Expected unconnected socket creation");
        }
    }

    /** Public static getDefault matches the native socket factory class contract. */
    public static final class DefaultFactory {
        private static SocketFactory factory;
        private static int resolutions;

        public static SocketFactory getDefault() {
            resolutions++;
            return factory;
        }
    }

    public static final class WrongDefaultFactory {
        public static Object getDefault() {
            return "not a SocketFactory";
        }
    }

    private static class FiniteSocket extends Socket {
        private SocketAddress endpoint;
        private boolean connected;
        private boolean closed;
        private IOException connectFailure;
        private IOException closeFailure;

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            this.endpoint = endpoint;
            if (connectFailure != null)
                throw connectFailure;
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
            if (closeFailure != null)
                throw closeFailure;
        }
    }

    private static final class RecordingSocket extends Socket {
        private final List<String> events = new ArrayList<>();
        private SocketAddress endpoint;
        private SocketAddress bound;
        private int connectTimeout;

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            events.add("timeout");
            super.setSoTimeout(timeout);
        }

        @Override
        public void bind(SocketAddress address) throws IOException {
            events.add("bind");
            bound = address;
            super.bind(address);
        }

        @Override
        public void connect(SocketAddress address, int timeout) throws IOException {
            events.add("connect");
            endpoint = address;
            connectTimeout = timeout;
            super.connect(address, timeout);
        }
    }

    private static class RecordingTlsFactory extends TestSSLSocketFactory {
        protected int wrapCalls;
        private Socket transport;
        private Socket layered;
        private String host;
        private int port;
        private boolean autoClose;

        RecordingTlsFactory() throws GeneralSecurityException {
        }

        @Override
        public synchronized Socket createSocket(Socket socket, String host, int port,
                                                boolean autoClose) throws IOException {
            wrapCalls++;
            transport = socket;
            this.host = host;
            this.port = port;
            this.autoClose = autoClose;
            layered = super.createSocket(socket, host, port, autoClose);
            return layered;
        }

        @Override
        public Socket createSocket() {
            throw new AssertionError("TLS factory must wrap the selected transport");
        }
    }

    private static final class TrustCheckingFactory extends MailSSLSocketFactory {
        private final boolean accept;
        private int trustChecks;
        private String checkedHost;
        private SSLSocket checkedSocket;

        TrustCheckingFactory(boolean accept) throws GeneralSecurityException {
            this.accept = accept;
            setTrustAllHosts(true);
        }

        @Override
        public synchronized boolean isServerTrusted(String server, SSLSocket socket) {
            trustChecks++;
            checkedHost = server;
            checkedSocket = socket;
            return accept;
        }
    }

    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        private int schedules;

        RecordingScheduler() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay,
                                              TimeUnit unit) {
            schedules++;
            return super.schedule(callable, delay, unit);
        }
    }
}
