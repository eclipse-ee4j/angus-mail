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
import org.junit.Test;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Checks the engine hook and the deliberate legacy-factory fallback. */
public class SMTPPipeliningEngineTest {
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
                exchange.properties.put(prefix + ".ssl.socketFactory", factory);
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> null);
                try {
                    exchange.send();
                    fail("Broken hook silently ignored");
                } catch (MessagingException expected) {
                    assertEquals(0, factory.calls.get());
                }
            }
        }
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
