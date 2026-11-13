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

package org.eclipse.angus.mail.smtp;

import jakarta.mail.MessagingException;
import org.junit.Assume;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the optional Conscrypt provider through the real SMTP transport. */
public class SMTPPipeliningConscryptTest {
    @Test(timeout = 30000)
    public void testMatchingEnginePipelinesAndReusesConnection() throws Exception {
        SSLContext context = conscryptContext();
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit, context, true, true);
                AtomicInteger engines = new AtomicInteger();
                AtomicReference<SSLEngine> created = new AtomicReference<>();
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            engines.incrementAndGet();
                            SSLEngine engine = context.createSSLEngine(host, port);
                            created.set(engine);
                            return engine;
                        });
                exchange.send();
                exchange.send();
                assertEquals(Arrays.asList(3, 3), exchange.handler.groups);
                assertEquals(1, engines.get());
                assertEquals(1, exchange.server.clientCount());
                assertEquals("HTTPS", created.get().getSSLParameters().getEndpointIdentificationAlgorithm());
                assertTrue(exchange.transport.isSSL());
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testFactoryWithoutEngineKeepsSequentialSending() throws Exception {
        SSLContext context = conscryptContext();
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 1, 1, 1)) {
                configure(exchange, implicit, context, true, false);
                exchange.send();
                exchange.send();
                assertEquals(Arrays.asList(1, 1, 1, 1, 1, 1), exchange.handler.groups);
                assertEquals(1, exchange.server.clientCount());
                assertTrue(exchange.transport.isSSL());
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testDisabledPipeliningDoesNotUseEngine() throws Exception {
        SSLContext context = conscryptContext();
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 1, 1, 1)) {
                String prefix = configure(exchange, implicit, context, false, false);
                exchange.properties.put(prefix + ".ssl.engineFactory",
                        (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                            throw new AssertionError("Disabled feature consulted engine hook");
                        });
                exchange.send();
                assertEquals(Arrays.asList(1, 1, 1), exchange.handler.groups);
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testFactoryAndEngineKeepTheirTrustConfiguration() throws Exception {
        SSLContext context = conscryptContext();
        SSLContext previous = SSLContext.getDefault();
        try {
            SSLContext.setDefault(untrustedContext());
            for (boolean implicit : new boolean[]{false, true}) {
                for (boolean engine : new boolean[]{false, true}) {
                    int[] groups = engine ? new int[]{3} : new int[]{1, 1, 1};
                    try (SMTPPipeliningTest.Exchange exchange =
                            new SMTPPipeliningTest.Exchange(2, implicit, groups)) {
                        configure(exchange, implicit, context, true, engine);
                        exchange.send();
                        assertEquals(1, exchange.server.clientCount());
                        exchange.assertContent();
                    }
                }
            }
        } finally {
            SSLContext.setDefault(previous);
        }
    }

    @Test(timeout = 30000)
    public void testWrongPeerCertificateIsRejectedBeforeEnvelope() throws Exception {
        SSLContext context = conscryptContext();
        for (boolean implicit : new boolean[]{false, true}) {
            for (boolean engine : new boolean[]{false, true}) {
                int[] groups = engine ? new int[]{3} : new int[]{1, 1, 1};
                try (SMTPPipeliningTest.Exchange exchange =
                        new SMTPPipeliningTest.Exchange(2, implicit, groups)) {
                    configure(exchange, implicit, context, true, engine);
                    exchange.transport = (SMTPTransport) exchange.session().getTransport(implicit ? "smtps" : "smtp");
                    try {
                        // The fixture trusts localhost and 127.0.0.1, not this loopback address.
                        exchange.transport.connect("127.0.0.2", exchange.server.getPort(), null, null);
                        fail("Certificate for a different peer was accepted");
                    } catch (MessagingException expected) {
                        assertTrue("Certificate failure was lost: " + expected,
                                hasCause(expected, CertificateException.class));
                        assertFalse(exchange.transport.isConnected());
                        assertFalse(exchange.handler.commands.stream().anyMatch(command ->
                                command.startsWith("MAIL ") || command.equals("DATA") || command.startsWith("BDAT ")));
                        assertNull(exchange.handler.message.get());
                    }
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testMixedProvidersCannotBypassHostnameChecking() throws Exception {
        SSLContext context = conscryptContext(true);
        for (boolean implicit : new boolean[] {false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                configure(exchange, implicit, context, true, true);
                exchange.transport = (SMTPTransport) exchange.session().getTransport(implicit ? "smtps" : "smtp");
                try {
                    exchange.transport.connect("127.0.0.2", exchange.server.getPort(), null, null);
                    fail("Mixed providers bypassed hostname checking");
                } catch (MessagingException expected) {
                    assertTrue("Certificate failure was lost: " + expected,
                            hasCause(expected, CertificateException.class));
                    assertFalse(exchange.transport.isConnected());
                    assertFalse(exchange.handler.commands.stream().anyMatch(command ->
                            command.startsWith("MAIL ") || command.equals("DATA") || command.startsWith("BDAT ")));
                    assertNull(exchange.handler.message.get());
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testMixedProvidersAllowMatchingPeerAndReuse() throws Exception {
        SSLContext context = conscryptContext(true);
        for (boolean implicit : new boolean[] {false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                configure(exchange, implicit, context, true, true);
                exchange.send();
                exchange.send();
                assertEquals(Arrays.asList(3, 3), exchange.handler.groups);
                assertEquals(1, exchange.server.clientCount());
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 30000)
    public void testMixedProvidersRespectDisabledIdentityChecking() throws Exception {
        SSLContext context = conscryptContext(true);
        for (boolean implicit : new boolean[] {false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                String prefix = configure(exchange, implicit, context, true, true);
                exchange.properties.setProperty(prefix + ".ssl.checkserveridentity", "false");
                exchange.transport = (SMTPTransport) exchange.session().getTransport(implicit ? "smtps" : "smtp");
                exchange.transport.connect("127.0.0.2", exchange.server.getPort(), null, null);
                assertTrue(exchange.transport.isConnected());
            }
        }
    }

    private String configure(SMTPPipeliningTest.Exchange exchange, boolean implicit,
            SSLContext context, boolean pipelining, boolean engine) {
        String prefix = implicit ? "mail.smtps" : "mail.smtp";
        exchange.properties.setProperty(prefix + ".pipelining", Boolean.toString(pipelining));
        exchange.properties.put(prefix + ".ssl.socketFactory", context.getSocketFactory());
        if (engine)
            exchange.properties.put(prefix + ".ssl.engineFactory",
                    (BiFunction<String, Integer, SSLEngine>) context::createSSLEngine);
        else
            exchange.properties.remove(prefix + ".ssl.engineFactory");
        if (!implicit) {
            exchange.handler.startTls = true;
            exchange.properties.setProperty(prefix + ".starttls.required", "true");
        }
        return prefix;
    }

    private SSLContext conscryptContext() throws Exception {
        return conscryptContext(false);
    }

    private SSLContext conscryptContext(boolean mixed) throws Exception {
        Class<?> conscrypt;
        try {
            conscrypt = Class.forName("org.conscrypt.Conscrypt");
        } catch (ClassNotFoundException ex) {
            if (!Boolean.getBoolean("angus.test.conscrypt.required"))
                Assume.assumeNoException("Enable the conscrypt-tests profile to exercise Conscrypt", ex);
            throw ex;
        }
        Provider provider = (Provider) conscrypt.getMethod("newProvider").invoke(null);
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = SMTPPipeliningTest.class.getResourceAsStream("pipelining.jks")) {
            store.load(in, "changeit".toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX", provider);
        trust.init(store);
        SSLContext context = mixed ? SSLContext.getInstance("TLS", "SunJSSE") :
                SSLContext.getInstance("TLS", provider);
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private SSLContext untrustedContext() throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        store.load(null, null);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null && !type.isInstance(current))
            current = current.getCause();
        return current != null;
    }
}
