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

import org.junit.Test;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Preserve identity, callback, handshake and cleanup evidence independently.
 */
public class SocketFetcherIdentityCleanupTest {
    /**
     * An ordinary close failure remains suppressed on the identity wrapper.
     */
    @Test
    public void rejectionRetainsCloseFailure() throws Exception {
        IOException closeFailure = new IOException("close failed");
        StubSocket socket = new StubSocket(closeFailure, null);
        IOException failure = assertThrows(IOException.class,
                () -> startTls(socket, (host, session) -> false));
        assertEquals("Unable to check server identity for: localhost",
                failure.getMessage());
        assertTrue(failure.getCause() instanceof SSLPeerUnverifiedException);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, socket.closeCalls);
    }

    /**
     * A failed callback never acquires the identity-rejection type.
     */
    @Test
    public void callbackFailureRetainsCloseFailure() throws Exception {
        IOException closeFailure = new IOException("close failed");
        RuntimeException callbackFailure = new IllegalStateException("callback failed");
        StubSocket socket = new StubSocket(closeFailure, null);
        IOException failure = assertThrows(IOException.class,
                () -> startTls(socket, (host, session) -> {
                    throw callbackFailure;
                }));
        assertSame(callbackFailure, failure.getCause());
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, socket.closeCalls);
    }

    /**
     * A generic TLS failure is retained by identity, without reclassification.
     */
    @Test
    public void handshakeFailureRetainsCloseFailure() throws Exception {
        IOException closeFailure = new IOException("close failed");
        SSLException handshakeFailure = new SSLException("TLS transport failed");
        StubSocket socket = new StubSocket(closeFailure, handshakeFailure);
        IOException failure = assertThrows(IOException.class,
                () -> startTls(socket, (host, session) -> {
                    throw new AssertionError("Verifier ran before handshake completed");
                }));
        assertSame(handshakeFailure, failure);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertNull(failure.getCause());
        assertEquals(1, socket.closeCalls);
    }

    /**
     * A fatal cleanup error still dominates, with the original failure retained.
     */
    @Test
    public void fatalCloseFailureRetainsRejectionAsSuppressed() throws Exception {
        AssertionError closeFailure = new AssertionError("fatal close failure");
        StubSocket socket = new StubSocket(closeFailure, null);
        AssertionError failure = assertThrows(AssertionError.class,
                () -> startTls(socket, (host, session) -> false));
        assertSame(closeFailure, failure);
        assertTrue(failure.getSuppressed()[0].getCause()
                instanceof SSLPeerUnverifiedException);
        assertEquals(1, socket.closeCalls);
    }

    /**
     * A fatal callback error retains Angus's existing propagation boundary.
     */
    @Test
    public void fatalCallbackErrorIsNotReclassified() throws Exception {
        AssertionError callbackFailure = new AssertionError("fatal callback failure");
        StubSocket socket = new StubSocket(null, null);
        AssertionError failure = assertThrows(AssertionError.class,
                () -> startTls(socket, (host, session) -> {
                    throw callbackFailure;
                }));
        assertSame(callbackFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        // This Error is outside configureSSLSocket's existing cleanup boundary.
        assertEquals(0, socket.closeCalls);
        socket.close();
    }

    /**
     * Exercise the public TLS boundary with a deterministic socket double.
     */
    private static void startTls(StubSocket socket, HostnameVerifier verifier)
            throws IOException {
        Properties properties = new Properties();
        properties.put("mail.smtp.ssl.socketFactory", new StubFactory(socket));
        properties.put("mail.smtp.ssl.hostnameverifier", verifier);
        try (Socket plain = new Socket()) {
            SocketFetcher.startTLS(plain, "localhost", properties, "mail.smtp");
        }
    }

    /**
     * Inject handshake and cleanup failures without opening a connection.
     */
    private static class StubSocket extends SSLSocket {
        private final Throwable closeFailure;
        private final IOException handshakeFailure;
        int closeCalls;

        StubSocket(Throwable closeFailure, IOException handshakeFailure) {
            this.closeFailure = closeFailure;
            this.handshakeFailure = handshakeFailure;
        }

        @Override
        public void startHandshake() throws IOException {
            if (handshakeFailure != null)
                throw handshakeFailure;
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailure instanceof IOException)
                throw (IOException) closeFailure;
            if (closeFailure instanceof Error)
                throw (Error) closeFailure;
            super.close();
        }

        @Override
        public SSLParameters getSSLParameters() { return new SSLParameters(); }
        @Override
        public void setSSLParameters(SSLParameters parameters) { }
        @Override
        public SSLSession getSession() { return null; }
        @Override
        public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override
        public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override
        public void setEnabledCipherSuites(String[] suites) { }
        @Override
        public String[] getSupportedProtocols() { return new String[]{"TLSv1.2"}; }
        @Override
        public String[] getEnabledProtocols() { return getSupportedProtocols(); }
        @Override
        public void setEnabledProtocols(String[] protocols) { }
        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override
        public void setUseClientMode(boolean mode) { }
        @Override
        public boolean getUseClientMode() { return true; }
        @Override
        public void setNeedClientAuth(boolean need) { }
        @Override
        public boolean getNeedClientAuth() { return false; }
        @Override
        public void setWantClientAuth(boolean want) { }
        @Override
        public boolean getWantClientAuth() { return false; }
        @Override
        public void setEnableSessionCreation(boolean enabled) { }
        @Override
        public boolean getEnableSessionCreation() { return true; }
    }

    /**
     * Return the injected SSL socket through SocketFetcher's normal factory path.
     */
    private static class StubFactory extends SSLSocketFactory {
        private final SSLSocket socket;

        StubFactory(SSLSocket socket) { this.socket = socket; }

        @Override
        public Socket createSocket(Socket plain, String host, int port,
                                   boolean autoClose) { return socket; }
        @Override
        public String[] getDefaultCipherSuites() { return new String[0]; }
        @Override
        public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override
        public Socket createSocket(String host, int port) { throw new AssertionError(); }
        @Override
        public Socket createSocket(String host, int port, InetAddress local,
                                   int localPort) { throw new AssertionError(); }
        @Override
        public Socket createSocket(InetAddress host, int port) { throw new AssertionError(); }
        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local,
                                   int localPort) { throw new AssertionError(); }
    }
}
