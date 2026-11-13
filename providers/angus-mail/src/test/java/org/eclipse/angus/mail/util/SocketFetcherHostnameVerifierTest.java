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

import jakarta.mail.MessagingException;
import jakarta.mail.Service;
import jakarta.mail.Session;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Custom hostname verification after a trusted TLS handshake, across providers.
 */
@RunWith(Parameterized.class)
public class SocketFetcherHostnameVerifierTest {
    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    private final String protocol;
    private final boolean verifierClass;
    private static RecordingVerifier latestVerifier;

    /**
     * Return each provider's STARTTLS and implicit TLS configurations.
     */
    @Parameterized.Parameters(name = "{0}, verifier class={1}")
    public static Collection<Object[]> configurations() {
        List<Object[]> configurations = new ArrayList<>();
        for (String protocol : new String[]{"smtp", "smtps", "imap",
                "imaps", "pop3", "pop3s"}) {
            configurations.add(new Object[]{protocol, false});
            configurations.add(new Object[]{protocol, true});
        }
        return configurations;
    }

    /**
     * Select the provider and verifier configuration mechanism.
     */
    public SocketFetcherHostnameVerifierTest(String protocol,
                                            boolean verifierClass) {
        this.protocol = protocol;
        this.verifierClass = verifierClass;
    }

    /**
     * Do not retain a verifier from a preceding connection.
     */
    @Before
    public void resetVerifier() {
        latestVerifier = null;
    }

    /**
     * A normal false result identifies the unverified peer and closes TLS.
     */
    @Test
    public void rejectionRetainsPeerVerificationType() throws Exception {
        try (TlsPeer peer = new TlsPeer(protocol, false)) {
            TrackingFactory factory = new TrackingFactory(tlsContext());
            Properties properties = properties(factory, RejectingVerifier.class);
            try (Service service = service(properties)) {
                MessagingException failure = assertThrows(
                        MessagingException.class,
                        () -> service.connect("localhost", peer.port(),
                                "user", "password"));
                Throwable identityFailure = findCause(failure,
                        SSLPeerUnverifiedException.class);
                assertNotNull(failure.toString(), identityFailure);
                assertEquals("Server identity does not match authentication "
                        + "scheme: rejecting verifier DENY",
                        identityFailure.getMessage());
                assertNull(identityFailure.getCause());
                Throwable wrapper = findIdentityWrapper(failure);
                assertNotNull(failure.toString(), wrapper);
                assertSame(identityFailure, wrapper.getCause());
                assertEquals(0, wrapper.getSuppressed().length);
                assertVerifiedAndClosed(factory);
                peer.awaitClosed();
            }
        }
    }

    /**
     * A true result keeps the connection usable after identity verification.
     */
    @Test
    public void acceptanceKeepsTheConnectionUsable() throws Exception {
        try (TlsPeer peer = new TlsPeer(protocol, false)) {
            TrackingFactory factory = new TrackingFactory(tlsContext());
            Properties properties = properties(factory, AcceptingVerifier.class);
            try (Service service = service(properties)) {
                service.connect("localhost", peer.port(), "user", "password");
                assertTrue(service.isConnected());
                assertEquals(1, latestVerifier.calls);
                assertFalse(factory.socket.isClosed());
            }
            assertVerifiedAndClosed(factory);
            peer.awaitClosed();
        }
    }

    /**
     * Callback faults retain the original instance and are not rejections.
     */
    @Test
    public void callbackExceptionIsNotPeerRejection() throws Exception {
        assertCallbackFailure(ThrowingVerifier.class);
    }

    /**
     * Linkage failures retain the existing wrapping and cleanup behavior.
     */
    @Test
    public void callbackLinkageErrorIsNotPeerRejection() throws Exception {
        assertCallbackFailure(LinkageFailingVerifier.class);
    }

    /**
     * Protocol negotiation failure happens before the verifier is invoked.
     */
    @Test
    public void handshakeFailureIsNotPeerRejection() throws Exception {
        try (TlsPeer peer = new TlsPeer(protocol, true)) {
            TrackingFactory factory = new TrackingFactory(tlsContext());
            Properties properties = properties(factory, RejectingVerifier.class);
            properties.setProperty(prefix() + ".ssl.protocols", "TLSv1.2");
            try (Service service = service(properties)) {
                MessagingException failure = assertThrows(
                        MessagingException.class,
                        () -> service.connect("localhost", peer.port(),
                                "user", "password"));
                assertNotNull(findCause(failure, SSLException.class));
                assertNoPeerRejection(failure);
                assertTrue(latestVerifier == null || latestVerifier.calls == 0);
                assertNotNull(factory.socket);
                assertTrue(factory.socket.isClosed());
                peer.awaitClosed();
            }
        }
    }

    /**
     * Construction errors occur after TLS and retain their original cause.
     */
    @Test
    public void missingVerifierClassIsNotPeerRejection() throws Exception {
        try (TlsPeer peer = new TlsPeer(protocol, false)) {
            TrackingFactory factory = new TrackingFactory(tlsContext());
            Properties properties = properties(factory, AcceptingVerifier.class);
            properties.remove(prefix() + ".ssl.hostnameverifier");
            properties.setProperty(prefix() + ".ssl.hostnameverifier.class",
                    "org.eclipse.angus.mail.test.MissingHostnameVerifier");
            try (Service service = service(properties)) {
                MessagingException failure = assertThrows(
                        MessagingException.class,
                        () -> service.connect("localhost", peer.port(),
                                "user", "password"));
                assertNotNull(findCause(failure, ClassNotFoundException.class));
                assertNoPeerRejection(failure);
                assertTrue(factory.socket.isClosed());
                peer.awaitClosed();
            }
        }
    }

    /**
     * An object still takes precedence over a configured class name.
     */
    @Test
    public void verifierObjectTakesPrecedence() throws Exception {
        try (TlsPeer peer = new TlsPeer(protocol, false)) {
            TrackingFactory factory = new TrackingFactory(tlsContext());
            Properties properties = properties(factory, AcceptingVerifier.class);
            AcceptingVerifier verifier = new AcceptingVerifier();
            properties.put(prefix() + ".ssl.hostnameverifier", verifier);
            properties.setProperty(prefix() + ".ssl.hostnameverifier.class",
                    "org.eclipse.angus.mail.test.MissingHostnameVerifier");
            try (Service service = service(properties)) {
                service.connect("localhost", peer.port(), "user", "password");
                assertEquals(1, verifier.calls);
            }
            assertVerifiedAndClosed(factory);
            peer.awaitClosed();
        }
    }

    /**
     * Assert callback identity without depending on provider exception text.
     */
    private void assertCallbackFailure(
            Class<? extends RecordingVerifier> verifierType) throws Exception {
        try (TlsPeer peer = new TlsPeer(protocol, false)) {
            TrackingFactory factory = new TrackingFactory(tlsContext());
            Properties properties = properties(factory, verifierType);
            try (Service service = service(properties)) {
                MessagingException failure = assertThrows(
                        MessagingException.class,
                        () -> service.connect("localhost", peer.port(),
                                "user", "password"));
                assertNotNull(latestVerifier);
                assertSame(latestVerifier.failure,
                        findCause(failure, latestVerifier.failure.getClass()));
                assertNoPeerRejection(failure);
                assertVerifiedAndClosed(factory);
                peer.awaitClosed();
            }
        }
    }

    /**
     * Configure TLS without bypassing certificate or endpoint validation.
     */
    private Properties properties(TrackingFactory factory,
            Class<? extends RecordingVerifier> verifierType) throws Exception {
        Properties properties = new Properties();
        properties.put(prefix() + ".ssl.socketFactory", factory);
        properties.setProperty(prefix() + ".socketFactory.fallback", "false");
        properties.setProperty(prefix() + ".ssl.checkserveridentity", "true");
        properties.setProperty(prefix() + ".connectiontimeout", "5000");
        properties.setProperty(prefix() + ".timeout", "5000");
        properties.setProperty(prefix() + ".localhost", "localhost");
        if (!protocol.endsWith("s")) {
            properties.setProperty(prefix() + ".starttls.enable", "true");
            properties.setProperty(prefix() + ".starttls.required", "true");
        }
        properties.setProperty(prefix() + ".auth.mechanisms", "LOGIN");
        if (verifierClass) {
            properties.setProperty(prefix() + ".ssl.hostnameverifier.class",
                    verifierType.getName());
        } else {
            properties.put(prefix() + ".ssl.hostnameverifier",
                    verifierType.getConstructor().newInstance());
        }
        return properties;
    }

    /**
     * Select a real Transport or Store, retaining each provider's prefix.
     */
    private Service service(Properties properties) throws Exception {
        Session session = Session.getInstance(properties);
        return protocol.startsWith("smtp") ? session.getTransport(protocol)
                : session.getStore(protocol);
    }

    /**
     * Return the property prefix for the selected provider.
     */
    private String prefix() {
        return "mail." + protocol;
    }

    /**
     * Require both the callback and closure of the actual factory socket.
     */
    private static void assertVerifiedAndClosed(TrackingFactory factory) {
        assertNotNull(latestVerifier);
        assertEquals(1, latestVerifier.calls);
        assertNotNull(factory.socket);
        assertEquals("LDAPS", factory.socket.getSSLParameters()
                .getEndpointIdentificationAlgorithm());
        assertTrue("Rejected or retired TLS socket remains open",
                factory.socket.isClosed());
    }

    /**
     * Check types rather than interpreting diagnostic messages.
     */
    private static void assertNoPeerRejection(Throwable failure) {
        assertNull(findCause(failure, SSLPeerUnverifiedException.class));
        assertNull(findCause(failure, CertificateException.class));
    }

    /**
     * Find the existing SocketFetcher wrapper in the provider cause chain.
     */
    private static Throwable findIdentityWrapper(Throwable failure) {
        for (Throwable current = failure; current != null;
                current = current.getCause()) {
            if (current.getClass() == IOException.class &&
                    "Unable to check server identity for: localhost"
                            .equals(current.getMessage())) {
                return current;
            }
        }
        return null;
    }

    /**
     * Find a typed cause without replacing the original exception.
     */
    private static Throwable findCause(Throwable failure, Class<?> type) {
        for (Throwable current = failure; current != null;
                current = current.getCause()) {
            if (type.isInstance(current))
                return current;
        }
        return null;
    }

    /**
     * Trust only the valid localhost certificate supplied by this fixture.
     */
    private static SSLContext tlsContext() throws Exception {
        KeyStore keys = KeyStore.getInstance("JKS");
        try (InputStream input = SocketFetcherHostnameVerifierTest.class
                .getResourceAsStream("hostname-verifier.jks")) {
            assertNotNull(input);
            keys.load(input, "changeit".toCharArray());
        }
        ((X509Certificate) keys.getCertificate("localhost")).checkValidity();
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "changeit".toCharArray());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(),
                null);
        return context;
    }

    /**
     * Record that verification runs after a successful, authenticated handshake.
     */
    public abstract static class RecordingVerifier implements HostnameVerifier {
        int calls;
        final Throwable failure;

        /**
         * Retain the instance created by the verifier class property.
         */
        RecordingVerifier(Throwable failure) {
            this.failure = failure;
            latestVerifier = this;
        }

        /**
         * Verify the fixture's TLS session before returning a decision.
         */
        @Override
        public boolean verify(String host, SSLSession session) {
            calls++;
            assertEquals("localhost", host);
            assertTrue(session.isValid());
            try {
                ((X509Certificate) session.getPeerCertificates()[0])
                        .checkValidity();
            } catch (CertificateException | SSLPeerUnverifiedException ex) {
                throw new AssertionError(ex);
            }
            if (failure instanceof RuntimeException)
                throw (RuntimeException) failure;
            if (failure instanceof Error)
                throw (Error) failure;
            return this instanceof AcceptingVerifier;
        }
    }

    /**
     * Public constructor for the class-name configuration path.
     */
    public static class AcceptingVerifier extends RecordingVerifier {
        public AcceptingVerifier() { super(null); }
    }

    /**
     * A normal false result, with a stable diagnostic name.
     */
    public static class RejectingVerifier extends RecordingVerifier {
        public RejectingVerifier() { super(null); }
        @Override
        public String toString() { return "rejecting verifier"; }
    }

    /**
     * An application callback fault, rather than an identity decision.
     */
    public static class ThrowingVerifier extends RecordingVerifier {
        public ThrowingVerifier() {
            super(new IllegalStateException("callback failed"));
        }
    }

    /**
     * A callback linkage error, retaining Angus's existing error boundary.
     */
    public static class LinkageFailingVerifier extends RecordingVerifier {
        public LinkageFailingVerifier() {
            super(new NoClassDefFoundError("callback dependency missing"));
        }
    }

    /**
     * Track the actual JSSE socket, without changing its behavior.
     */
    private static class TrackingFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        SSLSocket socket;

        TrackingFactory(SSLContext context) {
            delegate = context.getSocketFactory();
        }

        @Override
        public Socket createSocket(Socket plain, String host, int port,
                                   boolean autoClose) throws IOException {
            socket = (SSLSocket) delegate.createSocket(plain, host, port, autoClose);
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new AssertionError("Expected layered TLS socket");
        }

        @Override
        public Socket createSocket(String host, int port,
                                   InetAddress local, int localPort) {
            throw new AssertionError("Expected layered TLS socket");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new AssertionError("Expected layered TLS socket");
        }

        @Override
        public Socket createSocket(InetAddress host, int port,
                                   InetAddress local, int localPort) {
            throw new AssertionError("Expected layered TLS socket");
        }
    }

    /**
     * A single owned connection, supporting each provider's TLS negotiation.
     */
    private static class TlsPeer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final String protocol;
        private final boolean handshakeFailure;
        private final SSLContext context;
        private volatile Socket socket;
        private volatile Throwable failure;
        private BufferedReader reader;
        private PrintWriter writer;
        private boolean tls;

        TlsPeer(String protocol, boolean handshakeFailure) throws Exception {
            this.protocol = protocol;
            this.handshakeFailure = handshakeFailure;
            context = tlsContext();
            listener = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
            listener.setSoTimeout(5000);
            worker = new Thread(this::serve, "hostname-verifier-peer");
            worker.start();
        }

        /**
         * Return the loopback listener's allocated port.
         */
        int port() {
            return listener.getLocalPort();
        }

        /**
         * Serve a minimal provider conversation until the client closes.
         */
        private void serve() {
            try (Socket accepted = listener.accept()) {
                socket = accepted;
                accepted.setSoTimeout(5000);
                if (protocol.endsWith("s"))
                    upgradeToTls();
                else
                    streams();
                reply(protocol.startsWith("smtp") ? "220 localhost ready"
                        : protocol.startsWith("imap") ? "* OK localhost ready"
                        : "+OK localhost ready");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!respond(line))
                        return;
                }
            } catch (SSLException ex) {
                if (!handshakeFailure)
                    failure = ex;
            } catch (SocketException ex) {
                // A client closing after verification can reset the connection.
            } catch (Throwable ex) {
                failure = ex;
            } finally {
                closed.countDown();
            }
        }

        /**
         * Respond to only the commands used while connecting and closing.
         */
        private boolean respond(String line) throws Exception {
            String[] parts = line.split(" ", 3);
            String tag = protocol.startsWith("imap") ? parts[0] + " " : "";
            String command = (tag.isEmpty() ? parts[0] : parts[1])
                    .toUpperCase(Locale.ROOT);
            switch (command) {
                case "EHLO":
                    reply(tls ? "250 localhost" : "250-localhost\r\n250 STARTTLS");
                    break;
                case "CAPABILITY":
                    reply("* CAPABILITY IMAP4rev1" + (tls ? "" : " STARTTLS")
                            + "\r\n" + tag + "OK capability");
                    break;
                case "CAPA":
                    reply("+OK\r\nUSER\r\n" + (tls ? "" : "STLS\r\n") + ".");
                    break;
                case "STARTTLS":
                case "STLS":
                    reply(protocol.startsWith("smtp") ? "220 begin TLS"
                            : tag.isEmpty() ? "+OK begin TLS" : tag + "OK begin TLS");
                    upgradeToTls();
                    break;
                case "LOGIN":
                    reply(tag + "OK logged in");
                    break;
                case "USER":
                case "PASS":
                    reply("+OK");
                    break;
                case "STAT":
                    reply("+OK 0 0");
                    break;
                case "NOOP":
                    reply(protocol.startsWith("smtp") ? "250 OK"
                            : tag.isEmpty() ? "+OK" : tag + "OK");
                    break;
                case "LOGOUT":
                    reply("* BYE\r\n" + tag + "OK logged out");
                    return false;
                case "QUIT":
                    reply(protocol.startsWith("smtp") ? "221 goodbye" : "+OK goodbye");
                    return false;
                default:
                    throw new AssertionError("Unexpected command: " + line);
            }
            return true;
        }

        /**
         * Complete TLS before permitting the provider conversation to proceed.
         */
        private void upgradeToTls() throws IOException {
            SSLSocket secure = (SSLSocket) context.getSocketFactory().createSocket(
                    socket, "localhost", port(), true);
            socket = secure;
            secure.setUseClientMode(false);
            secure.setSoTimeout(5000);
            if (handshakeFailure)
                secure.setEnabledProtocols(new String[]{"TLSv1.3"});
            secure.startHandshake();
            tls = true;
            streams();
        }

        /**
         * Replace streams only after a TLS upgrade has completed.
         */
        private void streams() throws IOException {
            reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII), true);
        }

        /**
         * Send complete protocol lines.
         */
        private void reply(String response) {
            writer.print(response + "\r\n");
            writer.flush();
        }

        /**
         * Require peer-observed closure, not just a client-side socket flag.
         */
        void awaitClosed() throws InterruptedException {
            assertTrue("TLS peer did not observe closure",
                    closed.await(5, TimeUnit.SECONDS));
            assertNull("TLS peer failed: " + failure, failure);
        }

        /**
         * Close owned sockets and join the peer even after an assertion fails.
         */
        @Override
        public void close() throws Exception {
            listener.close();
            Socket connection = socket;
            if (connection != null)
                connection.close();
            worker.join(5000);
            assertFalse("TLS peer remains alive", worker.isAlive());
        }
    }
}
