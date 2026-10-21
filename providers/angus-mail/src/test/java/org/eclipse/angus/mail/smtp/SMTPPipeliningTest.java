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

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.event.TransportAdapter;
import jakarta.mail.event.TransportEvent;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Test;
import org.junit.Assume;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The server withholds replies until each expected command group arrives.
 * No latency threshold is needed to prove that commands were pipelined.
 */
public class SMTPPipeliningTest {
    @Test(timeout = 20000)
    public void testGroupsAndDataBoundary() throws Exception {
        try (Exchange exchange = new Exchange(70, 32, 32, 7)) {
            exchange.send();
            assertEquals(Arrays.asList(32, 32, 7), exchange.handler.groups);
            exchange.assertContent();
            exchange.assertNoReader();
        }
    }

    @Test(timeout = 20000)
    public void testBdatBoundaryAndContent() throws Exception {
        try (Exchange exchange = new Exchange(3, 4)) {
            exchange.properties.setProperty("mail.smtp.chunksize", "128");
            exchange.send();
            exchange.assertContent();
            assertTrue(exchange.handler.commands.stream().anyMatch(s -> s.startsWith("BDAT ")));
            exchange.assertNoReader();
        }
    }

    @Test(timeout = 20000)
    public void testDefaultOffAndMissingCapability() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            try (Exchange exchange = new Exchange(2, 1, 1, 1)) {
                if (mode == 0)
                    exchange.properties.remove("mail.smtp.pipelining");
                else
                    exchange.handler.extensions.remove("PIPELINING");
                exchange.connect(new ObservedTransport(exchange.session(), true));
                exchange.send();
                assertEquals(1, ((ObservedTransport) exchange.transport).legacyMail);
            }
        }
    }

    @Test(timeout = 20000)
    public void testLegacySubclassStaysSequential() throws Exception {
        try (Exchange exchange = new Exchange(2, 1, 1, 1)) {
            exchange.connect(new ObservedTransport(exchange.session(), false));
            exchange.send();
            assertEquals(1, ((ObservedTransport) exchange.transport).legacyMail);
        }
    }

    @Test(timeout = 20000)
    public void testOptedInSubclassCallbacksStayOnSender() throws Exception {
        try (Exchange exchange = new Exchange(2, 3)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            exchange.connect(transport);
            exchange.send();
            assertEquals(0, transport.legacyMail);
            assertEquals(Arrays.asList("MAIL", "RCPT", "RCPT"), transport.envelopeWrites);
            assertEquals(Arrays.asList(250, 250, 250), transport.envelopeReplies);
        }
    }

    @Test(timeout = 20000)
    public void testByteBoundAfterSubclassCustomization() throws Exception {
        try (Exchange exchange = new Exchange(2, 1, 1, 1)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            transport.padding = "x".repeat(33000);
            exchange.connect(transport);
            exchange.send();
            assertEquals(Arrays.asList(1, 1, 1), exchange.handler.groups);
        }
        try (Exchange exchange = new Exchange(3, 2, 1, 1)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            transport.padding = "x".repeat(18000);
            exchange.connect(transport);
            exchange.send();
            assertEquals(Arrays.asList(2, 1, 1), exchange.handler.groups);
        }
    }

    @Test(timeout = 20000)
    public void testRejectsInjectionAfterCustomization() throws Exception {
        try (Exchange exchange = new Exchange(1, 2)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            transport.padding = "bad\r\nDATA";
            exchange.connect(transport);
            try {
                exchange.send();
                fail("Command injection accepted");
            } catch (MessagingException expected) {
                assertTrue(expected.getMessage().contains("Invalid SMTP envelope"));
            }
            assertFalse(transport.isConnected());
            assertFalse(exchange.handler.commands.stream().anyMatch(s -> s.startsWith("MAIL ")));
            exchange.assertNoReader();
        }
    }

    @Test(timeout = 20000)
    public void testInvalidSubclassCallbacksCloseBeforeWriting() throws Exception {
        String[] failures = {"Envelope command was not queued",
                "More than one envelope command", "Reply read while queuing envelope"};
        for (int i = 0; i < failures.length; i++) {
            final int mode = i;
            try (Exchange exchange = new Exchange(1, 2)) {
                SMTPTransport transport = new SMTPTransport(exchange.session(),
                        new URLName("smtp://localhost")) {
                    @Override
                    protected boolean isPipeliningCompatible() {
                        return true;
                    }

                    @Override
                    protected void sendCommand(String cmd) throws MessagingException {
                        if (cmd.startsWith("MAIL ")) {
                            if (mode == 0)
                                return;
                            if (mode == 1) {
                                super.sendCommand(cmd);
                                super.sendCommand(cmd);
                                return;
                            }
                            readServerResponse();
                        }
                        super.sendCommand(cmd);
                    }
                };
                exchange.connect(transport);
                try {
                    exchange.send();
                    fail("Invalid subclass callback accepted");
                } catch (MessagingException expected) {
                    assertEquals(failures[i], expected.getMessage());
                }
                assertFalse(transport.isConnected());
                assertFalse(exchange.handler.commands.stream()
                        .anyMatch(command -> command.startsWith("MAIL ")));
                exchange.assertNoReader();
            }
        }
    }

    @Test(timeout = 20000)
    public void testSenderRejectionStopsBeforeWritingNextByteGroup() throws Exception {
        try (Exchange exchange = new Exchange(2, 1, 1, 1)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            transport.padding = "x".repeat(33000);
            exchange.handler.responsePlan.set(new String[]{
                    "550 sender rejected", "503 no sender", "503 no sender"});
            exchange.connect(transport);
            try {
                exchange.send();
                fail("Sender accepted");
            } catch (SMTPSendFailedException expected) {
                assertEquals(550, expected.getReturnCode());
                assertArrayEquals(exchange.addresses, expected.getValidUnsentAddresses());
            }
            // The next command is customized before its size forces a flush.
            assertEquals(Arrays.asList("MAIL", "RCPT"), transport.envelopeWrites);
            assertEquals(Arrays.asList(1), exchange.handler.groups);
            assertEquals(0, exchange.handler.commands.stream()
                    .filter(command -> command.startsWith("RCPT ")).count());
            assertTrue(transport.isConnected());
        }
    }

    @Test(timeout = 20000)
    public void testSenderRejectionDrainsBeforeResetAndReuse() throws Exception {
        try (Exchange exchange = new Exchange(2, 3)) {
            exchange.handler.responsePlan.set(new String[]{"550 sender rejected", "503 no sender", "503 no sender"});
            try {
                exchange.send();
                fail("Sender accepted");
            } catch (SMTPSendFailedException expected) {
                assertEquals(550, expected.getReturnCode());
                assertTrue(expected.getNextException() instanceof SMTPSenderFailedException);
                assertArrayEquals(exchange.addresses, expected.getValidUnsentAddresses());
                assertEquals(550, exchange.transport.getLastReturnCode());
            }
            assertTrue(exchange.handler.commands.contains("RSET"));
            exchange.handler.responsePlan.set(null);
            exchange.send();
            exchange.assertContent();
        }
    }

    @Test(timeout = 20000)
    public void testSenderRejectionRemainsPrimaryAfterTerminalReply() throws Exception {
        try (Exchange exchange = new Exchange(2, 3)) {
            exchange.handler.responsePlan.set(new String[]{"550 sender rejected", "421 shutting down", "503 no sender"});
            try {
                exchange.send();
                fail("Sender accepted");
            } catch (SMTPSendFailedException expected) {
                assertEquals(550, expected.getReturnCode());
                assertTrue(expected.getNextException() instanceof SMTPSenderFailedException);
                assertTrue(expected.getSuppressed().length > 0);
            }
            assertFalse(exchange.transport.isConnected());
            assertFalse(exchange.handler.commands.contains("RSET"));
        }
    }

    @Test(timeout = 20000)
    public void testFailedResetClosesWithoutReplacingSenderFailure() throws Exception {
        try (Exchange exchange = new Exchange(2, 3)) {
            exchange.handler.responsePlan.set(new String[]{"550 sender rejected", "503 no sender", "503 no sender"});
            exchange.handler.resetReply.set("550 cannot reset");
            try {
                exchange.send();
                fail("Sender accepted");
            } catch (SMTPSendFailedException expected) {
                assertEquals(550, expected.getReturnCode());
                assertTrue(expected.getMessage().contains("sender rejected"));
                assertTrue(expected.getSuppressed().length > 0);
            }
            assertFalse(exchange.transport.isConnected());
        }
    }

    @Test(timeout = 20000)
    public void testRecipientFailureAndPartialResults() throws Exception {
        for (boolean partial : new boolean[]{false, true}) {
            try (Exchange exchange = new Exchange(3, 4)) {
                exchange.properties.setProperty("mail.smtp.sendpartial", String.valueOf(partial));
                exchange.handler.responsePlan.set(new String[]{"250 sender", "250 accepted", "550 invalid", "450 busy"});
                exchange.connect(null);
                CountDownLatch event = new CountDownLatch(1);
                AtomicReference<TransportEvent> observed = new AtomicReference<>();
                exchange.transport.addTransportListener(new TransportAdapter() {
                    @Override
                    public void messageNotDelivered(TransportEvent value) {
                        observed.set(value);
                        event.countDown();
                    }

                    @Override
                    public void messagePartiallyDelivered(TransportEvent value) {
                        observed.set(value);
                        event.countDown();
                    }
                });
                try {
                    exchange.send();
                    fail("Missing recipient failure");
                } catch (SendFailedException expected) {
                    assertArrayEquals(new Address[]{exchange.addresses[1]}, expected.getInvalidAddresses());
                    assertArrayEquals(partial ? new Address[]{exchange.addresses[2]} :
                            new Address[]{exchange.addresses[0], exchange.addresses[2]},
                            expected.getValidUnsentAddresses());
                    if (partial)
                        assertArrayEquals(new Address[]{exchange.addresses[0]}, expected.getValidSentAddresses());
                    else
                        assertNull(expected.getValidSentAddresses());
                }
                assertTrue(event.await(3, TimeUnit.SECONDS));
                assertEquals(partial ? TransportEvent.MESSAGE_PARTIALLY_DELIVERED :
                        TransportEvent.MESSAGE_NOT_DELIVERED, observed.get().getType());
                assertEquals(partial, exchange.handler.message.get() != null);
                assertTrue(exchange.transport.isConnected());
                exchange.assertNoReader();
            }
        }
    }

    @Test(timeout = 20000)
    public void testReportSuccessAndMultilineReplies() throws Exception {
        try (Exchange exchange = new Exchange(2, 3)) {
            exchange.properties.setProperty("mail.smtp.reportsuccess", "true");
            exchange.handler.responsePlan.set(new String[]{"250 sender", "250-first\r\n250 accepted", "251 forwarded"});
            try {
                exchange.send();
                fail("Missing success report");
            } catch (SMTPSendFailedException expected) {
                assertArrayEquals(exchange.addresses, expected.getValidSentAddresses());
                assertEquals(0, expected.getInvalidAddresses().length);
                assertTrue(expected.getNextException() instanceof SMTPAddressSucceededException);
            }
            exchange.assertContent();
        }
    }

    @Test(timeout = 30000)
    public void testTerminalFailuresCloseWithoutResetOrReplay() throws Exception {
        for (String response : new String[]{"421 shutting down", "not SMTP", "250-first\r\n550 inconsistent", "EOF", "TIMEOUT"}) {
            try (Exchange exchange = new Exchange(2, 3)) {
                exchange.properties.setProperty("mail.smtp.timeout", "500");
                exchange.handler.responsePlan.set(new String[]{"250 sender", response, "250 accepted"});
                try {
                    exchange.send();
                    fail("Terminal failure ignored: " + response);
                } catch (MessagingException expected) {
                    assertFalse(exchange.transport.isConnected());
                }
                assertFalse(exchange.handler.commands.contains("RSET"));
                assertFalse(exchange.handler.commands.contains("DATA"));
                assertEquals(1, exchange.handler.commands.stream().filter(s -> s.startsWith("MAIL ")).count());
                exchange.assertNoReader();
            }
        }
    }

    @Test(timeout = 20000)
    public void testImplicitTlsUsesSmtpsProperty() throws Exception {
        try (Exchange exchange = new Exchange(2, true, 3)) {
            exchange.properties.remove("mail.smtp.pipelining");
            exchange.properties.setProperty("mail.smtps.pipelining", "true");
            exchange.send();
            exchange.assertContent();
        }
    }

    @Test(timeout = 30000)
    public void testTlsHostnameVerifierCannotBeBypassed() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (Exchange exchange = new Exchange(2, implicit, 3)) {
                String prefix = implicit ? "mail.smtps" : "mail.smtp";
                exchange.properties.setProperty(prefix + ".pipelining", "true");
                if (!implicit) {
                    exchange.handler.startTls = true;
                    exchange.properties.setProperty(prefix + ".starttls.required", "true");
                }
                exchange.properties.put(prefix + ".ssl.hostnameverifier",
                        (javax.net.ssl.HostnameVerifier) (host, session) -> false);
                try {
                    exchange.send();
                    fail("Hostname verifier rejection ignored");
                } catch (MessagingException expected) {
                    assertFalse(exchange.handler.commands.stream().anyMatch(s -> s.startsWith("MAIL ")));
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testAcknowledgedMessageSurvivesPeerClose() throws Exception {
        for (int mode : new int[]{0, 1, 2}) {
            try (Exchange exchange = new Exchange(2, mode == 2, 3)) {
                exchange.handler.closeAfterData = true;
                if (mode == 2)
                    exchange.properties.setProperty("mail.smtps.pipelining", "true");
                if (mode == 1) {
                    exchange.handler.startTls = true;
                    exchange.handler.abruptClose = true;
                    exchange.properties.setProperty("mail.smtp.starttls.required", "true");
                }
                exchange.send();
                exchange.assertContent();
                assertEquals(250, exchange.transport.getLastReturnCode());
                assertFalse(exchange.transport.isConnected());
                assertEquals(1, exchange.handler.commands.stream()
                        .filter(command -> command.startsWith("MAIL ")).count());
            }
        }
    }

    @Test(timeout = 30000)
    public void testStartTlsUsesFreshCapabilities() throws Exception {
        for (boolean advertise : new boolean[]{true, false}) {
            try (Exchange exchange = new Exchange(2, advertise ? new int[]{3} : new int[]{1, 1, 1})) {
                exchange.handler.startTls = true;
                exchange.handler.postTlsPipelining = advertise;
                exchange.properties.setProperty("mail.smtp.starttls.required", "true");
                exchange.send();
                assertEquals(advertise, exchange.transport.supportsExtension("PIPELINING"));
                exchange.assertContent();
            }
        }
    }

    @Test(timeout = 20000)
    public void testFailedEhloClearsCapabilities() throws Exception {
        try (Exchange exchange = new Exchange(2, 1, 1, 1)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            exchange.connect(transport);
            assertTrue(transport.supportsExtension("PIPELINING"));
            exchange.handler.rejectEhlo.set(true);
            synchronized (transport) {
                assertFalse(transport.ehlo("localhost"));
            }
            assertFalse(transport.supportsExtension("PIPELINING"));
            exchange.send();
            assertEquals(1, transport.legacyMail);
        }
    }

    @Test(timeout = 20000)
    public void testReconnectDoesNotReuseCapabilities() throws Exception {
        try (Exchange first = new Exchange(2, 3); Exchange second = new Exchange(2, 1, 1, 1)) {
            first.send();
            first.transport.close();
            assertFalse(first.transport.supportsExtension("PIPELINING"));
            second.handler.extensions.remove("PIPELINING");
            second.connect(first.transport);
            assertFalse(second.transport.supportsExtension("PIPELINING"));
            second.send();
        }
    }

    @Test(timeout = 20000)
    public void testInterruptedSenderJoinsReaderAndCloses() throws Exception {
        try (Exchange exchange = new Exchange(2, 3)) {
            exchange.handler.responsePlan.set(new String[]{"TIMEOUT", "250 ok", "250 ok"});
            exchange.connect(null);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread sender = new Thread(() -> {
                try {
                    exchange.send();
                } catch (Exception ex) {
                    failure.set(ex);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            sender.start();
            assertTrue(exchange.handler.envelopeReceived.await(3, TimeUnit.SECONDS));
            sender.interrupt();
            sender.join(3000);
            assertFalse(sender.isAlive());
            assertTrue(failure.get() instanceof MessagingException);
            assertTrue(interrupted.get());
            assertFalse(exchange.transport.isConnected());
            exchange.assertNoReader();
        }
    }

    @Test(timeout = 20000)
    public void testSenderRejectionStopsAtGroupBoundary() throws Exception {
        try (Exchange exchange = new Exchange(70, 32, 32, 7)) {
            ObservedTransport transport = new ObservedTransport(exchange.session(), true);
            exchange.connect(transport);
            String[] responses = new String[71];
            Arrays.fill(responses, "503 no sender");
            responses[0] = "550 sender rejected";
            exchange.handler.responsePlan.set(responses);
            try {
                exchange.send();
                fail("Sender accepted");
            } catch (SMTPSendFailedException expected) {
                assertEquals(550, expected.getReturnCode());
                assertArrayEquals(exchange.addresses, expected.getValidUnsentAddresses());
            }
            assertEquals(Arrays.asList(32), exchange.handler.groups);
            assertEquals(32, transport.envelopeWrites.size());
            assertEquals(31, exchange.handler.commands.stream().filter(s -> s.startsWith("RCPT ")).count());
            assertTrue(exchange.transport.isConnected());
        }
    }

    @Test(timeout = 120000)
    public void testOptionalLatencyComparison() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.benchmark"));
        for (int delay : new int[]{0, 10}) {
            for (int recipients : new int[]{1, 10, 100}) {
                for (boolean enabled : new boolean[]{false, true}) {
                    List<Integer> groups = new ArrayList<>();
                    for (int remaining = recipients + 1; remaining > 0;) {
                        int size = Math.min(remaining, enabled ? 32 : 1);
                        groups.add(size);
                        remaining -= size;
                    }
                    try (Exchange exchange = new Exchange(recipients,
                            groups.stream().mapToInt(Integer::intValue).toArray())) {
                        exchange.properties.setProperty("mail.smtp.pipelining", String.valueOf(enabled));
                        exchange.handler.replyDelay = delay;
                        exchange.connect(null);
                        long[] samples = new long[5];
                        for (int i = -2; i < samples.length; i++) {
                            long start = System.nanoTime();
                            exchange.send();
                            if (i >= 0)
                                samples[i] = System.nanoTime() - start;
                        }
                        Arrays.sort(samples);
                        System.out.printf(java.util.Locale.ROOT,
                                "PIPELINING-BENCH delay=%d recipients=%d enabled=%s median_ms=%.3f%n",
                                delay, recipients, enabled, samples[2] / 1000000.0);
                    }
                }
            }
        }
    }

    private static final class ObservedTransport extends SMTPTransport {
        final boolean optIn;
        final Thread sender = Thread.currentThread();
        final List<String> envelopeWrites = new ArrayList<>();
        final List<Integer> envelopeReplies = new ArrayList<>();
        int legacyMail;
        int pending;
        String padding;

        ObservedTransport(Session session, boolean optIn) {
            super(session, new URLName("smtp://localhost"));
            this.optIn = optIn;
        }

        @Override
        protected boolean isPipeliningCompatible() {
            return optIn || super.isPipeliningCompatible();
        }

        @Override
        protected void mailFrom() throws MessagingException {
            legacyMail++;
            super.mailFrom();
        }

        @Override
        protected void sendCommand(String cmd) throws MessagingException {
            assertSame(sender, Thread.currentThread());
            assertTrue(Thread.holdsLock(this));
            if (cmd.startsWith("MAIL ") || cmd.startsWith("RCPT ")) {
                envelopeWrites.add(cmd.substring(0, 4));
                pending++;
                if (padding != null && cmd.startsWith("RCPT "))
                    cmd += " XTEST=" + padding;
            }
            super.sendCommand(cmd);
        }

        @Override
        protected int readServerResponse() throws MessagingException {
            assertSame(sender, Thread.currentThread());
            assertTrue(Thread.holdsLock(this));
            int code = super.readServerResponse();
            if (pending > 0) {
                envelopeReplies.add(code);
                pending--;
            }
            return code;
        }
    }

    static final class Exchange implements AutoCloseable {
        final GatedHandler handler;
        final TestServer server;
        final Properties properties = new Properties();
        final Address[] addresses;
        final boolean ssl;
        SMTPTransport transport;
        MimeMessage message;

        Exchange(int recipients, int... groups) throws Exception {
            this(recipients, false, groups);
        }

        Exchange(int recipients, boolean ssl, int... groups) throws Exception {
            this.ssl = ssl;
            handler = new GatedHandler(recipients + 1, groups);
            server = ssl ? new TestServer(handler, handler.context) : new TestServer(handler);
            server.start();
            properties.setProperty("mail.smtp.pipelining", "true");
            properties.setProperty("mail.host", "localhost");
            for (String protocol : new String[]{"smtp", "smtps"}) {
                properties.setProperty("mail." + protocol + ".localhost", "localhost");
                properties.setProperty("mail." + protocol + ".timeout", "3000");
                properties.setProperty("mail." + protocol + ".connectiontimeout", "3000");
                properties.setProperty("mail." + protocol + ".socketFactory.fallback", "false");
                properties.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
                properties.put("mail." + protocol + ".ssl.socketFactory", handler.context.getSocketFactory());
                properties.put("mail." + protocol + ".ssl.engineFactory",
                        (java.util.function.BiFunction<String, Integer, SSLEngine>)
                                handler.context::createSSLEngine);
            }
            if (!ssl && Boolean.getBoolean("angus.pipelining.contract.starttls")) {
                handler.startTls = true;
                properties.setProperty("mail.smtp.starttls.required", "true");
            }
            addresses = new Address[recipients];
            for (int i = 0; i < recipients; i++)
                addresses[i] = new InternetAddress("recipient" + i + "@example.com");
        }

        Session session() {
            return Session.getInstance(properties);
        }

        void connect(SMTPTransport custom) throws Exception {
            Session session = session();
            transport = custom == null ? (SMTPTransport) session.getTransport(ssl ? "smtps" : "smtp") : custom;
            transport.connect("localhost", server.getPort(), null, null);
            message = new MimeMessage(session);
            message.setFrom(new InternetAddress("sender@example.com"));
            message.setText("first\r\n.leading dot\r\n.\r\nlast\r\n");
            message.saveChanges();
        }

        void send() throws Exception {
            if (transport == null)
                connect(null);
            transport.sendMessage(message, addresses);
            assertNull(handler.failure.get());
        }

        void assertContent() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            assertArrayEquals(bytes.toByteArray(), handler.message.get());
        }

        void assertNoReader() {
            assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread ->
                    thread.isAlive() && thread.getName().equals("Angus SMTP envelope reader")));
        }

        @Override
        public void close() throws MessagingException {
            try {
                if (transport != null)
                    transport.close();
            } finally {
                server.quit();
                try {
                    server.join(3000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Test server join interrupted", ex);
                }
                assertFalse(server.isAlive());
            }
        }
    }

    static final class GatedHandler extends SMTPHandler {
        final int total;
        final int[] expectedGroups;
        final SSLContext context;
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<Integer> groups = new CopyOnWriteArrayList<>();
        final AtomicReference<byte[]> message = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String[]> responsePlan = new AtomicReference<>();
        final AtomicReference<String> resetReply = new AtomicReference<>("250 reset");
        final AtomicBoolean rejectEhlo = new AtomicBoolean();
        final CountDownLatch envelopeReceived = new CountDownLatch(1);
        boolean startTls;
        boolean closeAfterData;
        boolean abruptClose;
        Socket beforeTls;
        boolean postTlsPipelining = true;
        boolean secure;
        int received;
        int pending;
        int group;
        int replyDelay;

        GatedHandler(int total, int[] groups) throws Exception {
            this.total = total;
            expectedGroups = groups;
            extensions.add("PIPELINING");
            extensions.add("CHUNKING");
            KeyStore store = KeyStore.getInstance("JKS");
            try (InputStream in = SMTPPipeliningTest.class.getResourceAsStream("pipelining.jks")) {
                store.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509");
            keys.init(store, "changeit".toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance("SunX509");
            trust.init(store);
            context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        }

        @Override
        protected String readLine() throws IOException {
            String line = super.readLine();
            if ("STARTTLS".equals(line)) {
                println("220 start TLS");
                beforeTls = clientSocket;
                SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(
                        clientSocket, "localhost", clientSocket.getPort(), true);
                socket.setUseClientMode(false);
                socket.startHandshake();
                setClientSocket(socket);
                secure = true;
                line = super.readLine();
            }
            if (line != null)
                commands.add(line);
            return line;
        }

        @Override
        public void ehlo() throws IOException {
            if (rejectEhlo.get()) {
                println("500 EHLO rejected");
                return;
            }
            println("250-localhost");
            if (extensions.contains("PIPELINING") && (!secure || postTlsPipelining))
                println("250-PIPELINING");
            if (startTls && !secure)
                println("250-STARTTLS");
            println("250 CHUNKING");
        }

        @Override
        public void mail(String line) throws IOException {
            received = pending = group = 0;
            envelope();
        }

        @Override
        public void rcpt(String line) throws IOException {
            envelope();
        }

        private void envelope() throws IOException {
            received++;
            pending++;
            if (pending == expectedGroups[group]) {
                groups.add(pending);
                envelopeReceived.countDown();
                if (replyDelay > 0) {
                    try {
                        Thread.sleep(replyDelay);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                }
                String[] responses = responsePlan.get();
                for (int i = received - pending; i < received; i++) {
                    String response = responses == null ? "250 accepted" : responses[i];
                    if (response.equals("EOF")) {
                        exit();
                        return;
                    }
                    if (response.equals("TIMEOUT"))
                        return;
                    println(response);
                }
                group++;
                pending = 0;
            }
        }

        private void boundary() throws IOException {
            if (received != total || pending != 0) {
                failure.set(new AssertionError("Body or reset before envelope drain"));
                throw new IOException("Envelope not complete");
            }
        }

        @Override
        public void data() throws IOException {
            boundary();
            super.data();
            if (closeAfterData) {
                if (abruptClose)
                    beforeTls.close();
                exit();
            }
        }

        @Override
        public void bdat(String line) throws IOException {
            boundary();
            super.bdat(line);
        }

        @Override
        public void rset() throws IOException {
            if (pending != 0)
                throw new IOException("Reset before group drain");
            println(resetReply.get());
        }

        @Override
        protected void setMessage(byte[] bytes) {
            message.set(bytes);
        }
    }
}
