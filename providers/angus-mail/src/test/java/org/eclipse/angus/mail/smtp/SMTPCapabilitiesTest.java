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

package org.eclipse.angus.mail.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Capabilities belong to the current SMTP connection and TLS state.
 */
public final class SMTPCapabilitiesTest {
    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    @Test
    public void testFailedEhloClearsCapabilities() throws Exception {
        try (Connection connection = new Connection(false, false, false)) {
            connection.connect();
            assertInitialCapabilities(connection.transport);
            synchronized (connection.transport) {
                assertFalse(connection.transport.ehlo("localhost"));
            }
            assertNoCapabilities(connection.transport);
        }
    }

    @Test
    public void testSuccessfulEhloReplacesCapabilities() throws Exception {
        try (Connection connection = new Connection(false, true, false)) {
            connection.connect();
            assertInitialCapabilities(connection.transport);
            synchronized (connection.transport) {
                assertTrue(connection.transport.ehlo("localhost"));
            }
            assertRefreshedCapabilities(connection.transport);
        }
    }

    @Test
    public void testHeloClearsCapabilities() throws Exception {
        try (Connection connection = new Connection(false, false, false)) {
            connection.connect();
            assertInitialCapabilities(connection.transport);
            synchronized (connection.transport) {
                connection.transport.helo("localhost");
            }
            assertNoCapabilities(connection.transport);
        }
    }

    @Test
    public void testCloseClearsCapabilities() throws Exception {
        try (Connection connection = new Connection(false, false, false)) {
            connection.connect();
            assertInitialCapabilities(connection.transport);
            connection.transport.close();
            assertNoCapabilities(connection.transport);
        }
    }

    @Test
    public void testReconnectWithHeloFallback() throws Exception {
        checkReconnect(false);
    }

    @Test
    public void testReconnectWithEhloDisabled() throws Exception {
        checkReconnect(true);
    }

    private void checkReconnect(boolean disableEhlo) throws Exception {
        try (Connection first = new Connection(false, false, false);
             Connection second = new Connection(true, false, false)) {
            first.transport.connect("localhost", first.server.getPort(), "test", "test");
            assertInitialCapabilities(first.transport);
            assertEquals(1, first.handler.authCommands.get());
            first.transport.close();
            if (disableEhlo)
                first.properties.setProperty("mail.smtp.ehlo", "false");
            first.transport.connect("localhost", second.server.getPort(), "test", "test");
            assertNoCapabilities(first.transport);
            assertEquals(0, second.handler.authCommands.get());
            first.transport.close();
        }
    }

    @Test
    public void testStartTlsClearsCapabilitiesBeforeEhlo() throws Exception {
        try (Connection connection = new Connection(false, false, false)) {
            connection.connect();
            assertInitialCapabilities(connection.transport);
            synchronized (connection.transport) {
                connection.transport.startTLS();
            }
            assertTrue(connection.transport.isSSL());
            assertNoCapabilities(connection.transport);
        }
    }

    @Test
    public void testFailedPostTlsEhloDoesNotUseOldAuth() throws Exception {
        try (Connection connection = new Connection(false, false, false)) {
            connection.transport.setRequireStartTLS(true);
            connection.transport.connect("localhost", connection.server.getPort(), "test", "test");
            assertTrue(connection.transport.isSSL());
            assertNoCapabilities(connection.transport);
            assertEquals(0, connection.handler.authCommands.get());
        }
    }

    @Test
    public void testSuccessfulPostTlsEhloReplacesCapabilities() throws Exception {
        try (Connection connection = new Connection(false, true, false)) {
            connection.transport.setRequireStartTLS(true);
            connection.connect();
            assertTrue(connection.transport.isSSL());
            assertRefreshedCapabilities(connection.transport);
        }
    }

    @Test
    public void testRejectedStartTlsKeepsCapabilities() throws Exception {
        try (Connection connection = new Connection(false, false, true)) {
            connection.connect();
            synchronized (connection.transport) {
                try {
                    connection.transport.startTLS();
                    fail("STARTTLS should have been rejected");
                } catch (MessagingException expected) {
                    assertEquals(454, connection.transport.getLastReturnCode());
                }
            }
            assertFalse(connection.transport.isSSL());
            assertInitialCapabilities(connection.transport);
        }
    }

    private static void assertInitialCapabilities(SMTPTransport transport) {
        assertEquals("1024", transport.getExtensionParameter("SIZE"));
        assertEquals("PLAIN", transport.getExtensionParameter("AUTH"));
        assertTrue(transport.supportsExtension("CHUNKING"));
        assertTrue(transport.supportsExtension("STARTTLS"));
    }

    private static void assertNoCapabilities(SMTPTransport transport) {
        assertNull(transport.getExtensionParameter("SIZE"));
        assertNull(transport.getExtensionParameter("AUTH"));
        assertFalse(transport.supportsExtension("CHUNKING"));
        assertFalse(transport.supportsExtension("STARTTLS"));
    }

    private static void assertRefreshedCapabilities(SMTPTransport transport) {
        assertEquals("2048", transport.getExtensionParameter("SIZE"));
        assertTrue(transport.supportsExtension("8BITMIME"));
        assertNull(transport.getExtensionParameter("AUTH"));
        assertFalse(transport.supportsExtension("CHUNKING"));
        assertFalse(transport.supportsExtension("STARTTLS"));
    }

    private static final class Connection implements AutoCloseable {
        final Properties properties = new Properties();
        final CapabilityHandler handler;
        final TestServer server;
        final SMTPTransport transport;

        Connection(boolean rejectFirstEhlo, boolean refresh, boolean rejectTls)
                throws Exception {
            // Public localhost fixture, not a deployment key or trust store.
            KeyStore keys = KeyStore.getInstance("JKS");
            try (InputStream input = SMTPCapabilitiesTest.class
                    .getResourceAsStream("capabilities.jks")) {
                keys.load(input, "changeit".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, "changeit".toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keys);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            properties.setProperty("mail.smtp.localhost", "localhost");
            properties.setProperty("mail.smtp.connectiontimeout", "5000");
            properties.setProperty("mail.smtp.timeout", "5000");
            properties.setProperty("mail.smtp.writetimeout", "5000");
            properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            properties.put("mail.smtp.ssl.socketFactory", context.getSocketFactory());
            transport = (SMTPTransport) Session.getInstance(properties).getTransport("smtp");
            handler = new CapabilityHandler(context, rejectFirstEhlo, refresh, rejectTls);
            server = new TestServer(handler);
            server.start();
        }

        void connect() throws MessagingException {
            transport.connect("localhost", server.getPort(), null, null);
        }

        @Override
        public void close() throws Exception {
            try {
                transport.close();
            } finally {
                server.quit();
                for (Socket socket : handler.sockets)
                    socket.close();
                server.join(5000);
                assertFalse("Test server did not stop", server.isAlive());
            }
        }
    }

    private static final class CapabilityHandler extends SMTPHandler {
        final SSLContext context;
        final boolean rejectFirstEhlo;
        final boolean refresh;
        final boolean rejectTls;
        final AtomicInteger authCommands = new AtomicInteger();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        int greetings;
        boolean tls;

        CapabilityHandler(SSLContext context, boolean rejectFirstEhlo,
                boolean refresh, boolean rejectTls) {
            this.context = context;
            this.rejectFirstEhlo = rejectFirstEhlo;
            this.refresh = refresh;
            this.rejectTls = rejectTls;
        }

        @Override
        public void sendGreetings() throws IOException {
            sockets.add(clientSocket);
            clientSocket.setSoTimeout(5000);
            super.sendGreetings();
        }

        @Override
        public void handleCommand() throws IOException {
            String line = readLine();
            if (line == null)
                return;
            String command = line.split(" ", 2)[0];
            if (command.equals("EHLO")) {
                ehlo();
            } else if (command.equals("HELO")) {
                println("250 Hello");
            } else if (command.equals("AUTH")) {
                authCommands.incrementAndGet();
                println(rejectFirstEhlo || greetings > 1 || tls ?
                        "535 AUTH not advertised" : "235 Authorized");
            } else if (command.equals("STARTTLS")) {
                if (rejectTls) {
                    println("454 TLS unavailable");
                    return;
                }
                println("220 Ready for TLS");
                SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(
                        clientSocket, "localhost", clientSocket.getPort(), true);
                sockets.add(socket);
                socket.setUseClientMode(false);
                socket.setSoTimeout(5000);
                socket.startHandshake();
                setClientSocket(socket);
                tls = true;
            } else if (command.equals("QUIT")) {
                quit();
            } else if (command.equals("NOOP")) {
                noop();
            } else {
                throw new IOException("Unexpected command: " + line);
            }
        }

        @Override
        public void ehlo() throws IOException {
            if (++greetings == 1 && !rejectFirstEhlo) {
                println("250-localhost");
                println("250-SIZE 1024");
                println("250-STARTTLS");
                println("250-CHUNKING");
                println("250 AUTH PLAIN");
            } else if (refresh) {
                println("250-localhost");
                println("250-SIZE 2048");
                println("250 8BITMIME");
            } else {
                println("500 EHLO unavailable");
            }
        }
    }
}
