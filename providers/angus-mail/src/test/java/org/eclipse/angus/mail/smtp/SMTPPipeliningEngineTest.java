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
import org.eclipse.angus.mail.util.DuplexSocket;
import org.junit.Test;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Checks the engine hook and the deliberate legacy-factory fallback. */
public class SMTPPipeliningEngineTest {
    @Test(timeout = 30000)
    public void testTlsWorkerDoesNotInheritCallerThreadSettings() throws Exception {
        Thread caller = Thread.currentThread();
        ClassLoader previousLoader = caller.getContextClassLoader();
        int previousPriority = caller.getPriority();
        ClassLoader applicationLoader = new ClassLoader(previousLoader) { };
        for (boolean implicit : new boolean[] {false, true}) {
            for (int priority : new int[] {Thread.MIN_PRIORITY, Thread.MAX_PRIORITY}) {
                try (SMTPPipeliningTest.Exchange exchange =
                        new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                    String prefix = configure(exchange, implicit);
                    AtomicReference<Thread> worker = new AtomicReference<>();
                    SSLContext context = observingTlsContext(worker);
                    exchange.properties.put(prefix + ".ssl.engineFactory",
                            (BiFunction<String, Integer, SSLEngine>) context::createSSLEngine);
                    caller.setContextClassLoader(applicationLoader);
                    caller.setPriority(priority);
                    exchange.send();
                    assertNotNull(worker.get());
                    assertNotSame(caller, worker.get());
                    assertSame(DuplexSocket.class.getClassLoader(),
                            worker.get().getContextClassLoader());
                    assertEquals(Math.min(Thread.NORM_PRIORITY,
                            worker.get().getThreadGroup().getMaxPriority()),
                            worker.get().getPriority());
                    assertTrue(worker.get().isDaemon());
                    assertSame(applicationLoader, caller.getContextClassLoader());
                    assertEquals(Math.min(priority,
                            caller.getThreadGroup().getMaxPriority()),
                            caller.getPriority());
                    exchange.assertContent();
                } finally {
                    caller.setContextClassLoader(previousLoader);
                    caller.setPriority(previousPriority);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testConfiguredTlsProtocolsCiphersAndIdentity() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            for (String protocol : new String[]{"TLSv1.2", "TLSv1.3"}) {
                try (SMTPPipeliningTest.Exchange exchange =
                        new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                    String prefix = configure(exchange, implicit);
                    String cipher = protocol.equals("TLSv1.3") ? "TLS_AES_128_GCM_SHA256" :
                            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";
                    AtomicReference<SSLEngine> created = new AtomicReference<>();
                    exchange.properties.setProperty(prefix + ".ssl.protocols", protocol);
                    exchange.properties.setProperty(prefix + ".ssl.ciphersuites", cipher);
                    exchange.properties.put(prefix + ".ssl.engineFactory",
                            (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                                SSLEngine engine = exchange.handler.context.createSSLEngine(host, port);
                                created.set(engine);
                                return engine;
                            });
                    exchange.send();
                    assertArrayEquals(new String[]{protocol}, created.get().getEnabledProtocols());
                    assertEquals(protocol, created.get().getSession().getProtocol());
                    assertEquals(cipher, created.get().getSession().getCipherSuite());
                    assertEquals("LDAPS", created.get().getSSLParameters().getEndpointIdentificationAlgorithm());
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testDefaultContextAndMailTrustNeedNoEngineHook() throws Exception {
        SSLContext previous = SSLContext.getDefault();
        try {
            for (boolean implicit : new boolean[]{false, true}) {
                for (boolean mailTrust : new boolean[]{false, true}) {
                    try (SMTPPipeliningTest.Exchange exchange =
                            new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                        String prefix = configure(exchange, implicit);
                        exchange.properties.remove(prefix + ".ssl.socketFactory");
                        exchange.properties.remove(prefix + ".ssl.engineFactory");
                        if (mailTrust)
                            exchange.properties.setProperty(prefix + ".ssl.trust", "localhost");
                        else
                            SSLContext.setDefault(exchange.handler.context);
                        exchange.send();
                        exchange.assertContent();
                    }
                }
            }
        } finally {
            SSLContext.setDefault(previous);
        }
    }

    @Test(timeout = 30000)
    public void testExistingTlsFactoryKeepsSequentialSending() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 1, 1, 1)) {
                String prefix = configure(exchange, implicit);
                CountingFactory factory = new CountingFactory(exchange.handler.context.getSocketFactory());
                exchange.properties.remove(prefix + ".ssl.engineFactory");
                exchange.properties.put(prefix + ".ssl.socketFactory", factory);
                exchange.send();
                exchange.send();
                assertTrue(exchange.transport.isSSL());
                assertEquals(1, factory.calls.get());
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testEngineHookEnablesCustomContextAndReuse() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                CountingFactory factory = new CountingFactory(exchange.handler.context.getSocketFactory());
                AtomicInteger engines = new AtomicInteger();
                exchange.properties.put(prefix + ".ssl.socketFactory", factory);
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            assertEquals("localhost", host);
                            engines.incrementAndGet();
                            return exchange.handler.context.createSSLEngine(host, port);
                        });
                exchange.send();
                exchange.send();
                assertTrue(exchange.transport.isSSL());
                assertEquals(1, engines.get());
                assertEquals(0, factory.calls.get());
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testDisabledPipeliningDoesNotCallEngineHook() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 1, 1, 1)) {
                String prefix = configure(exchange, implicit);
                exchange.properties.setProperty(prefix + ".pipelining", "false");
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            throw new AssertionError("Disabled feature consulted engine hook");
                        });
                exchange.send();
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testBrokenEngineHookDoesNotFallBackToOtherTrust() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                CountingFactory factory = new CountingFactory(exchange.handler.context.getSocketFactory());
                AtomicInteger engines = new AtomicInteger();
                exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
                exchange.properties.put(prefix + ".ssl.socketFactory", factory);
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            engines.incrementAndGet();
                            return null;
                        });
                try {
                    exchange.send();
                    fail("Broken hook silently ignored");
                } catch (MessagingException expected) {
                    assertEquals(1, engines.get());
                    assertEquals(0, factory.calls.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testFailedEngineHookIsNotRetried() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = new AtomicInteger();
                IllegalStateException failure = new IllegalStateException("engine creation failed");
                exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            if (engines.incrementAndGet() == 1)
                                throw failure;
                            return exchange.handler.context.createSSLEngine(host, port);
                        });
                try {
                    exchange.send();
                    fail("Failed engine hook was retried");
                } catch (MessagingException expected) {
                    assertCause(expected, failure);
                    assertEquals(1, engines.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testEngineHookLinkageFailureClosesConnection() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = new AtomicInteger();
                NoClassDefFoundError failure = new NoClassDefFoundError("unavailable engine hook");
                exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            engines.incrementAndGet();
                            throw failure;
                        });
                try {
                    exchange.send();
                    fail("Engine hook linkage failure was ignored");
                } catch (MessagingException expected) {
                    assertCause(expected, failure);
                    assertEquals(1, engines.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testWrongPeerEngineIsNotRetried() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = new AtomicInteger();
                exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            engines.incrementAndGet();
                            return exchange.handler.context.createSSLEngine("wrong.example.invalid", port);
                        });
                try {
                    exchange.send();
                    fail("Engine for a different peer was accepted");
                } catch (MessagingException expected) {
                    assertEquals(1, engines.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testEngineConfigurationFailureIsNotRetried() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = new AtomicInteger();
                exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
                exchange.properties.setProperty(prefix + ".ssl.protocols", "invalid-protocol");
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            engines.incrementAndGet();
                            return exchange.handler.context.createSSLEngine(host, port);
                        });
                try {
                    exchange.send();
                    fail("Invalid engine configuration was accepted");
                } catch (MessagingException expected) {
                    Throwable cause = assertCause(expected, IllegalArgumentException.class);
                    assertTrue(cause.getMessage(), cause.getMessage().contains("invalid-protocol"));
                    assertEquals(1, engines.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testInvalidEngineHookTypeDoesNotRetryConnection() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
                exchange.properties.put(prefix + ".ssl.engineFactory", "not an engine factory");
                try {
                    exchange.send();
                    fail("Invalid engine factory type was accepted");
                } catch (MessagingException expected) {
                    assertCause(expected, IOException.class);
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testIgnoredIdentityCheckRequiresExplicitVerifier() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = configureIdentityIgnoringEngine(exchange, prefix);
                try {
                    exchange.send();
                    fail("Engine silently ignored server identity checking");
                } catch (MessagingException expected) {
                    assertCause(expected, SSLException.class);
                    assertEquals(1, engines.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testExplicitVerifierAllowsIdentityIgnoringEngineAndReuse() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = configureIdentityIgnoringEngine(exchange, prefix);
                AtomicInteger verifications = new AtomicInteger();
                exchange.properties.put(prefix + ".ssl.hostnameverifier",
                        (HostnameVerifier) (host, session) -> {
                            verifications.incrementAndGet();
                            assertEquals("localhost", host);
                            assertTrue(session.isValid());
                            return true;
                        });
                exchange.send();
                exchange.send();
                assertEquals(1, engines.get());
                assertEquals(1, verifications.get());
                assertEquals(1, exchange.server.clientCount());
                assertEquals(Arrays.asList(3, 3), exchange.handler.groups);
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testExplicitVerifierRejectsIdentityIgnoringEngine() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit);
                AtomicInteger engines = configureIdentityIgnoringEngine(exchange, prefix);
                AtomicInteger verifications = new AtomicInteger();
                exchange.properties.put(prefix + ".ssl.hostnameverifier",
                        (HostnameVerifier) (host, session) -> {
                            verifications.incrementAndGet();
                            return false;
                        });
                try {
                    exchange.send();
                    fail("Explicit verifier rejection was ignored");
                } catch (MessagingException expected) {
                    assertCause(expected, SSLException.class);
                    assertEquals(1, engines.get());
                    assertEquals(1, verifications.get());
                    assertSetupFailed(exchange);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testFactoryLookupFailureKeepsLegacyFallback() throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                new SMTPPipeliningTest.Exchange(2, true, 3)) {
            String prefix = configure(exchange, true);
            AtomicInteger engines = new AtomicInteger();
            exchange.properties.remove(prefix + ".ssl.socketFactory");
            exchange.properties.setProperty(prefix + ".ssl.socketFactory.class",
                    "org.eclipse.angus.mail.smtp.MissingSocketFactory");
            exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
            exchange.properties.put(prefix + ".ssl.engineFactory",
                    (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                        engines.incrementAndGet();
                        return exchange.handler.context.createSSLEngine(host, port);
                    });
            exchange.send();
            assertEquals(1, engines.get());
            assertEquals(1, exchange.server.clientCount());
            exchange.assertContent();
        }
    }

    private void assertSetupFailed(SMTPPipeliningTest.Exchange exchange) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (exchange.server.clientCount() < 1 && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertEquals(1, exchange.server.clientCount());
        assertFalse(exchange.transport.isConnected());
        assertFalse(exchange.handler.commands.stream().anyMatch(command ->
                command.startsWith("MAIL ") || command.equals("DATA") || command.startsWith("BDAT ")));
    }

    private AtomicInteger configureIdentityIgnoringEngine(
            SMTPPipeliningTest.Exchange exchange, String prefix) {
        AtomicInteger engines = new AtomicInteger();
        exchange.properties.setProperty(prefix + ".socketFactory.fallback", "true");
        exchange.properties.put(prefix + ".ssl.engineFactory",
                (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                    engines.incrementAndGet();
                    return new IdentityIgnoringEngine(exchange.handler.context.createSSLEngine(host, port));
                });
        return engines;
    }

    private void assertCause(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null && current != expected)
            current = current.getCause();
        assertTrue("Original engine failure was lost: " + failure, current == expected);
    }

    private Throwable assertCause(Throwable failure, Class<? extends Throwable> expected) {
        Throwable current = failure;
        while (current != null && !expected.isInstance(current))
            current = current.getCause();
        assertTrue("Original engine failure was lost: " + failure, current != null);
        return current;
    }

    private SSLContext observingTlsContext(AtomicReference<Thread> worker) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream input = SMTPPipeliningTest.class.getResourceAsStream("pipelining.jks")) {
            store.load(input, "changeit".toCharArray());
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance("SunX509");
        factory.init(store);
        X509TrustManager delegate = (X509TrustManager) factory.getTrustManagers()[0];
        X509TrustManager observer = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                worker.set(Thread.currentThread());
                delegate.checkServerTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return delegate.getAcceptedIssuers();
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {observer}, null);
        return context;
    }

    private String configure(SMTPPipeliningTest.Exchange exchange, boolean implicit) {
        String prefix = implicit ? "mail.smtps" : "mail.smtp";
        exchange.properties.setProperty(prefix + ".pipelining", "true");
        if (!implicit) {
            exchange.handler.startTls = true;
            exchange.properties.setProperty(prefix + ".starttls.required", "true");
        }
        return prefix;
    }

    private static final class IdentityIgnoringEngine extends SSLEngine {
        private final SSLEngine delegate;

        IdentityIgnoringEngine(SSLEngine delegate) {
            super(delegate.getPeerHost(), delegate.getPeerPort());
            this.delegate = delegate;
        }

        @Override
        public SSLParameters getSSLParameters() {
            SSLParameters parameters = delegate.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            return parameters;
        }

        @Override
        public void setSSLParameters(SSLParameters parameters) {
            String identity = parameters.getEndpointIdentificationAlgorithm();
            parameters.setEndpointIdentificationAlgorithm(null);
            try {
                delegate.setSSLParameters(parameters);
            } finally {
                parameters.setEndpointIdentificationAlgorithm(identity);
            }
        }

        @Override
        public SSLEngineResult wrap(ByteBuffer[] input, int offset, int length, ByteBuffer output)
                throws SSLException {
            return delegate.wrap(input, offset, length, output);
        }

        @Override
        public SSLEngineResult unwrap(ByteBuffer input, ByteBuffer[] output, int offset, int length)
                throws SSLException {
            return delegate.unwrap(input, output, offset, length);
        }

        @Override
        public Runnable getDelegatedTask() { return delegate.getDelegatedTask(); }
        @Override
        public void closeInbound() throws SSLException { delegate.closeInbound(); }
        @Override
        public boolean isInboundDone() { return delegate.isInboundDone(); }
        @Override
        public void closeOutbound() { delegate.closeOutbound(); }
        @Override
        public boolean isOutboundDone() { return delegate.isOutboundDone(); }
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
        public SSLSession getSession() { return delegate.getSession(); }
        @Override
        public SSLSession getHandshakeSession() { return delegate.getHandshakeSession(); }
        @Override
        public void beginHandshake() throws SSLException { delegate.beginHandshake(); }
        @Override
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() { return delegate.getHandshakeStatus(); }
        @Override
        public void setUseClientMode(boolean client) { delegate.setUseClientMode(client); }
        @Override
        public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        @Override
        public void setNeedClientAuth(boolean needed) { delegate.setNeedClientAuth(needed); }
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

    private static final class CountingFactory extends SSLSocketFactory {
        final SSLSocketFactory delegate;
        final AtomicInteger calls = new AtomicInteger();

        CountingFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean close)
                throws IOException {
            calls.incrementAndGet();
            return delegate.createSocket(socket, host, port, close);
        }

        @Override
        public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override
        public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
        @Override
        public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        @Override
        public Socket createSocket(InetAddress h, int p) { throw new UnsupportedOperationException(); }
        @Override
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
    }
}
