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

import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.CertificateException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNoException;

/** Checks provider-specific identity checking with real TLS connections. */
public final class SocketFetcherConscryptTest {
    private static final String MATCHING_CERTIFICATE = "conscrypt-hostname.jks";
    private static final String WRONG_CERTIFICATE = "conscrypt-wrong-hostname.jks";
    private static Provider conscrypt;

    @BeforeClass
    public static void loadConscrypt() throws Exception {
        try {
            conscrypt = (Provider) Class.forName("org.conscrypt.Conscrypt")
                    .getMethod("newProvider").invoke(null);
        } catch (ClassNotFoundException missing) {
            if (Boolean.getBoolean("angus.test.conscrypt.required"))
                throw missing;
            assumeNoException("Enable the conscrypt-tests profile", missing);
        }
    }

    @Test(timeout = 30000)
    public void testImplicitTlsRejectsWrongHostname() throws Exception {
        try (TlsServer server = new TlsServer(WRONG_CERTIFICATE)) {
            TrackingFactory factory = factory(WRONG_CERTIFICATE, conscrypt);
            Properties properties = properties(factory, "mail.smtps");
            IOException failure = assertThrows(IOException.class,
                    () -> SocketFetcher.getSocket("localhost", server.port(),
                            properties, "mail.smtps", true));
            assertIdentityFailure(failure, factory);
        }
    }

    @Test(timeout = 30000)
    public void testStartTlsRejectsWrongHostname() throws Exception {
        try (TlsServer server = new TlsServer(MATCHING_CERTIFICATE)) {
            TrackingFactory factory = factory(MATCHING_CERTIFICATE, conscrypt);
            Properties properties = properties(factory, "mail.smtp");
            try (Socket plain = new Socket("localhost", server.port())) {
                IOException failure = assertThrows(IOException.class,
                        () -> SocketFetcher.startTLS(plain,
                                "different.invalid", properties, "mail.smtp"));
                assertIdentityFailure(failure, factory);
            }
        }
    }

    @Test(timeout = 30000)
    public void testMatchingDnsAndIpNames() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            for (String host : new String[]{"localhost", "127.0.0.1"}) {
                verifyExchange(MATCHING_CERTIFICATE, host, implicit, conscrypt,
                        true, null, "HTTPS");
            }
        }
    }

    @Test(timeout = 30000)
    public void testWildcardMatchesOneLabel() throws Exception {
        verifyExchange(MATCHING_CERTIFICATE, "mail.example.invalid", false,
                conscrypt, true, null, "HTTPS");
        for (String host : new String[]{"a.b.example.invalid", "example.invalid"}) {
            try (TlsServer server = new TlsServer(MATCHING_CERTIFICATE)) {
                TrackingFactory factory = factory(MATCHING_CERTIFICATE, conscrypt);
                Properties properties = properties(factory, "mail.smtp");
                try (Socket plain = new Socket("localhost", server.port())) {
                    IOException failure = assertThrows(IOException.class,
                            () -> SocketFetcher.startTLS(plain, host,
                                    properties, "mail.smtp"));
                    assertIdentityFailure(failure, factory);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testDisabledIdentityChecking() throws Exception {
        for (boolean implicit : new boolean[]{false, true})
            verifyExchange(WRONG_CERTIFICATE, "localhost", implicit,
                    conscrypt, false, null, null);
    }

    @Test(timeout = 30000)
    public void testExplicitVerifierStillRunsWhenIdentityCheckingIsDisabled()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HostnameVerifier verifier = (host, session) -> {
            calls.incrementAndGet();
            return true;
        };
        for (boolean implicit : new boolean[]{false, true})
            verifyExchange(WRONG_CERTIFICATE, "localhost", implicit,
                    conscrypt, false, verifier, null);
        assertEquals(2, calls.get());
    }

    @Test(timeout = 30000)
    public void testExplicitVerifierCanRejectAConnection() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (TlsServer server = new TlsServer(MATCHING_CERTIFICATE)) {
                TrackingFactory factory = factory(MATCHING_CERTIFICATE, conscrypt);
                String prefix = implicit ? "mail.smtps" : "mail.smtp";
                Properties properties = properties(factory, prefix);
                AtomicInteger calls = new AtomicInteger();
                properties.setProperty(prefix + ".ssl.checkserveridentity", "false");
                properties.put(prefix + ".ssl.hostnameverifier",
                        (HostnameVerifier) (host, session) -> {
                            calls.incrementAndGet();
                            return false;
                        });
                IOException failure = assertThrows(IOException.class,
                        () -> connect(server, "localhost", implicit, properties));
                assertTrue(failure.toString(), failure.getMessage().contains("identity"));
                assertEquals(1, calls.get());
                assertTrue(factory.created.isClosed());
            }
        }
    }

    @Test(timeout = 30000)
    public void testSunJsseKeepsLdapIdentityChecking() throws Exception {
        for (boolean implicit : new boolean[]{false, true})
            verifyExchange(MATCHING_CERTIFICATE, "localhost", implicit,
                    null, true, null, "LDAPS");
    }

    @Test(timeout = 30000)
    public void testMailVerifierChecksNumericIpNames() throws Exception {
        for (String host : new String[] {"127.0.0.1", "::1", "[0:0:0:0:0:0:0:1]"}) {
            try (TlsServer server = new TlsServer(MATCHING_CERTIFICATE)) {
                TrackingFactory factory = factory(MATCHING_CERTIFICATE, null);
                Properties properties = properties(factory, "mail.smtp");
                properties.setProperty("mail.smtp.ssl.checkserveridentity", "false");
                properties.setProperty("mail.smtp.ssl.hostnameverifier.class", "MailHostnameVerifier");
                try (SSLSocket socket = connect(server, host, false, properties)) {
                    socket.getOutputStream().write(42);
                    socket.getOutputStream().flush();
                    assertEquals(42, socket.getInputStream().read());
                }
            }
        }
        for (String host : new String[] {"127.0.0.2", "::2"}) {
            try (TlsServer server = new TlsServer(MATCHING_CERTIFICATE)) {
                TrackingFactory factory = factory(MATCHING_CERTIFICATE, null);
                Properties properties = properties(factory, "mail.smtp");
                properties.setProperty("mail.smtp.ssl.checkserveridentity", "false");
                properties.setProperty("mail.smtp.ssl.hostnameverifier.class", "MailHostnameVerifier");
                assertThrows(IOException.class, () -> connect(server, host, false, properties));
                assertTrue(factory.created.isClosed());
            }
        }
    }

    private static void verifyExchange(String certificate, String host,
            boolean implicit, Provider provider, boolean checkIdentity,
            HostnameVerifier verifier, String algorithm) throws Exception {
        try (TlsServer server = new TlsServer(certificate)) {
            TrackingFactory factory = factory(certificate, provider);
            String prefix = implicit ? "mail.smtps" : "mail.smtp";
            Properties properties = properties(factory, prefix);
            properties.setProperty(prefix + ".ssl.checkserveridentity",
                    Boolean.toString(checkIdentity));
            if (verifier != null)
                properties.put(prefix + ".ssl.hostnameverifier", verifier);
            try (SSLSocket socket = connect(server, host, implicit, properties)) {
                assertEquals(algorithm,
                        socket.getSSLParameters().getEndpointIdentificationAlgorithm());
                socket.getOutputStream().write(42);
                socket.getOutputStream().flush();
                assertEquals(42, socket.getInputStream().read());
            }
        }
    }

    private static SSLSocket connect(TlsServer server, String host,
            boolean implicit, Properties properties) throws IOException {
        if (implicit)
            return (SSLSocket) SocketFetcher.getSocket(host, server.port(),
                    properties, "mail.smtps", true);
        Socket plain = new Socket("localhost", server.port());
        try {
            return (SSLSocket) SocketFetcher.startTLS(plain, host,
                    properties, "mail.smtp");
        } catch (IOException | RuntimeException failure) {
            plain.close();
            throw failure;
        }
    }

    private static Properties properties(TrackingFactory factory, String prefix) {
        Properties properties = new Properties();
        properties.put(prefix + ".ssl.socketFactory", factory);
        properties.setProperty(prefix + ".socketFactory.fallback", "false");
        properties.setProperty(prefix + ".connectiontimeout", "5000");
        properties.setProperty(prefix + ".timeout", "5000");
        properties.setProperty(prefix + ".ssl.protocols", "TLSv1.2");
        return properties;
    }

    private static void assertIdentityFailure(IOException failure,
            TrackingFactory factory) {
        assertTrue(failure.toString(), failure instanceof SSLHandshakeException);
        boolean certificateCause = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            certificateCause |= cause instanceof CertificateException;
        assertTrue("Missing certificate failure in exception chain", certificateCause);
        assertTrue("Rejected socket remains open", factory.created.isClosed());
    }

    private static TrackingFactory factory(String certificate, Provider provider)
            throws Exception {
        KeyStore store = keyStore(certificate);
        TrustManagerFactory trust = provider == null ?
                TrustManagerFactory.getInstance("PKIX", "SunJSSE") :
                TrustManagerFactory.getInstance("PKIX", provider);
        trust.init(store);
        SSLContext context = provider == null ? SSLContext.getInstance("TLS", "SunJSSE") :
                SSLContext.getInstance("TLS", provider);
        context.init(null, trust.getTrustManagers(), null);
        return new TrackingFactory(context.getSocketFactory());
    }

    private static KeyStore keyStore(String certificate) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream input = SocketFetcherConscryptTest.class
                .getResourceAsStream(certificate)) {
            store.load(input, "changeit".toCharArray());
        }
        return store;
    }

    private static final class TrackingFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private SSLSocket created;

        TrackingFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port,
                boolean close) throws IOException {
            created = (SSLSocket) delegate.createSocket(socket, host, port, close);
            return created;
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
        public Socket createSocket(String host, int port) throws IOException {
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local,
                int localPort) throws IOException {
            return delegate.createSocket(host, port, local, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return delegate.createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local,
                int localPort) throws IOException {
            return delegate.createSocket(host, port, local, localPort);
        }
    }

    private static final class TlsServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private volatile Socket client;

        TlsServer(String certificate) throws Exception {
            KeyStore store = keyStore(certificate);
            KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509");
            keys.init(store, "changeit".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS", "SunJSSE");
            context.init(keys.getKeyManagers(), null, null);
            listener = context.getServerSocketFactory().createServerSocket(0, 1,
                    InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::exchange, "Conscrypt test server");
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void exchange() {
            try (Socket accepted = listener.accept()) {
                client = accepted;
                accepted.setSoTimeout(5000);
                int value = accepted.getInputStream().read();
                if (value != -1) {
                    accepted.getOutputStream().write(value);
                    accepted.getOutputStream().flush();
                }
            } catch (IOException expected) {
                // A rejected client can end the handshake before sending data.
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            if (client != null)
                client.close();
            worker.join(5000);
            assertFalse("TLS server did not stop", worker.isAlive());
        }
    }
}
