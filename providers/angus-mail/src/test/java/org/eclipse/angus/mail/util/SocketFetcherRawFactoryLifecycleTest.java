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

import org.junit.Test;
import javax.net.SocketFactory;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Tests raw factory input and transport resource ownership. */
public class SocketFetcherRawFactoryLifecycleTest {
    @Test(timeout = 10000)
    public void usesRawSocketForOrdinaryTransport() throws Exception {
        try (RecordingSocket transport = new RecordingSocket()) {
            SingleSocketFactory factory = new SingleSocketFactory(transport);
            Properties properties = new Properties();
            properties.put("mail.smtp.socketFactory.raw", factory);
            properties.setProperty("mail.smtp.connectiontimeout", "200");
            properties.setProperty("mail.smtp.timeout", "456");
            Socket actual = null;
            IOException failure = null;
            try {
                actual = SocketFetcher.getSocket("127.0.0.1", 0, properties, "mail.smtp", false);
            } catch (IOException ex) {
                failure = ex;
            }
            assertEquals("The configured transport factory must create the socket", 1, factory.calls);
            assertNull(failure);
            assertSame(transport, actual);
            assertEquals(new InetSocketAddress("127.0.0.1", 0), transport.endpoint);
            assertEquals(200, transport.timeout);
            assertEquals(456, actual.getSoTimeout());
        }
    }

    @Test
    public void invalidRawFactoryDoesNotFallThroughToOrdinarySocketCreation() {
        Properties properties = new Properties();
        properties.put("mail.smtp.socketFactory.raw", "not a SocketFactory");
        properties.setProperty("mail.smtp.socketFactory.fallback", "true");
        IOException failure = assertThrows(IOException.class, () ->
                SocketFetcher.getSocket("127.0.0.1", -1, properties, "mail.smtp", false));
        assertTrue(failure.getMessage(), failure.getMessage().contains("socketFactory.raw"));
    }

    @Test
    public void rawClassMustBeNonemptyText() {
        for (Object invalid : new Object[]{42, ""}) {
            Properties properties = new Properties();
            properties.put("mail.smtp.socketFactory.raw.class", invalid);
            IOException failure = assertThrows(IOException.class, () ->
                    SocketFetcher.getSocket("127.0.0.1", -1, properties, "mail.smtp", false));
            assertTrue(failure.getMessage(), failure.getMessage().contains("socketFactory.raw.class"));
        }
    }

    @Test
    public void validInstanceIgnoresInvalidClassDeclaration() throws Exception {
        for (Object ignored : new Object[]{42, ""}) {
            try (RecordingSocket transport = new RecordingSocket()) {
                Properties properties = new Properties();
                properties.put("mail.smtp.socketFactory.raw", new SingleSocketFactory(transport));
                properties.put("mail.smtp.socketFactory.raw.class", ignored);
                assertSame(transport, SocketFetcher.getSocket("127.0.0.1", 2525,
                        properties, "mail.smtp", false));
            }
        }
    }

    @Test
    public void rawFactoryReplacesNativeSocketChoices() throws Exception {
        for (boolean socks : new boolean[]{false, true}) {
            try (RecordingSocket transport = new RecordingSocket()) {
                Properties properties = new Properties();
                properties.put("mail.smtp.socketFactory.raw", new SingleSocketFactory(transport));
                properties.setProperty("mail.smtp.usesocketchannels", "true");
                if (socks) {
                    properties.setProperty("mail.smtp.socks.host", "127.0.0.1");
                    properties.setProperty("mail.smtp.socks.port", "1");
                }
                assertSame(transport, SocketFetcher.getSocket("127.0.0.1", 2525,
                        properties, "mail.smtp", false));
                assertNull(transport.getChannel());
                assertEquals(new InetSocketAddress("127.0.0.1", 2525), transport.endpoint);
            }
        }
    }

    @Test
    public void rawConnectFailureDoesNotClaimBypassedNativeSocksRouting() throws Exception {
        try (RecordingSocket transport = new RecordingSocket() {
            @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                throw new IOException("transport refused connection");
            }
        }) {
            Properties properties = new Properties();
            properties.put("mail.smtp.socketFactory.raw", new SingleSocketFactory(transport));
            properties.setProperty("mail.smtp.socks.host", "127.0.0.1");
            properties.setProperty("mail.smtp.socks.port", "1");
            IOException failure = assertThrows(IOException.class, () ->
                    SocketFetcher.getSocket("127.0.0.1", 2525, properties, "mail.smtp", false));
            String message = failure.getMessage();
            assertFalse(message, message != null && message.contains("Using SOCKS"));
            assertEquals("transport refused connection", failure.getCause().getMessage());
        }
    }

    @Test(timeout = 10000)
    public void failedTlsSetupRetiresNativeWriteTimeoutResources() throws Exception {
        AtomicReference<Socket> wrapped = new AtomicReference<>();
        IOException failure = new IOException("TLS factory rejected wrapping");
        RecordingSocket transport = new RecordingSocket() {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        };
        MailSSLSocketFactory tls = new MailSSLSocketFactory() {
            @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
                wrapped.set(socket);
                throw failure;
            }
        };
        Properties properties = new Properties();
        properties.put("mail.smtps.socketFactory.raw", new SingleSocketFactory(transport));
        properties.put("mail.smtps.ssl.socketFactory", tls);
        properties.setProperty("mail.smtps.proxy.host", "127.0.0.1");
        properties.setProperty("mail.smtps.writetimeout", "5000");
        try {
            assertSame(failure, assertThrows(IOException.class, () ->
                    SocketFetcher.getSocket("127.0.0.1", 2525, properties, "mail.smtps", true)));
            assertTrue(transport.isClosed());
            assertTrue(wrapped.get() instanceof WriteTimeoutSocket);
            Field executor = WriteTimeoutSocket.class.getDeclaredField("ses");
            executor.setAccessible(true);
            assertTrue("The native timeout scheduler must retire after failed TLS setup",
                    ((ScheduledExecutorService) executor.get(wrapped.get())).isShutdown());
        } finally {
            if (wrapped.get() != null)
                wrapped.get().close();
            transport.close();
        }
    }

    private static final class SingleSocketFactory extends SocketFactory {
        private final Socket socket;
        private int calls;
        SingleSocketFactory(Socket socket) { this.socket = socket; }
        @Override public Socket createSocket() { calls++; return socket; }
        @Override public Socket createSocket(String host, int port) {
            throw new AssertionError("Expected unconnected socket creation");
        }
        @Override public Socket createSocket(InetAddress host, int port) {
            throw new AssertionError("Expected unconnected socket creation");
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) {
            throw new AssertionError("Expected unconnected socket creation");
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
            throw new AssertionError("Expected unconnected socket creation");
        }
    }

    private static class RecordingSocket extends Socket {
        private SocketAddress endpoint;
        private int timeout;
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            this.endpoint = endpoint;
            this.timeout = timeout;
        }
    }
}
