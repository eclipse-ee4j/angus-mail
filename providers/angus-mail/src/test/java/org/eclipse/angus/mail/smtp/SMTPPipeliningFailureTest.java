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
import jakarta.mail.URLName;
import jakarta.mail.event.TransportAdapter;
import jakarta.mail.event.TransportEvent;
import org.eclipse.angus.mail.test.ReflectionUtil;
import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Recipient results must survive a later failure in the same envelope.
 */
public class SMTPPipeliningFailureTest {
    @Test(timeout = 30000)
    public void testShutdownPreservesRecipientResults() throws Exception {
        for (boolean partial : new boolean[]{false, true}) {
            for (boolean success : new boolean[]{false, true}) {
                try (SMTPPipeliningTest.Exchange exchange =
                             new SMTPPipeliningTest.Exchange(3, 4)) {
                    exchange.properties.setProperty("mail.smtp.sendpartial",
                            Boolean.toString(partial));
                    exchange.properties.setProperty("mail.smtp.reportsuccess",
                            Boolean.toString(success));
                    checkShutdown(exchange, success);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void testShutdownPreservesRecipientResultsOverTls() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                         new SMTPPipeliningTest.Exchange(3, implicit, 4)) {
                String prefix = implicit ? "mail.smtps" : "mail.smtp";
                exchange.properties.setProperty(prefix + ".pipelining", "true");
                exchange.properties.setProperty(prefix + ".sendpartial", "true");
                exchange.properties.setProperty(prefix + ".reportsuccess", "true");
                if (!implicit) {
                    exchange.handler.startTls = true;
                    exchange.properties.setProperty(prefix + ".starttls.required",
                            "true");
                }
                checkShutdown(exchange, true);
            }
        }
    }

    @Test(timeout = 20000)
    public void testSequentialShutdownPreservesRecipientResults()
            throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                     new SMTPPipeliningTest.Exchange(3, 1, 1, 1, 1)) {
            exchange.properties.setProperty("mail.smtp.pipelining", "false");
            exchange.handler.responsePlan.set(new String[]{
                    "250 sender accepted", "550 invalid recipient",
                    "250 recipient accepted", "421 shutting down"});
            Failure result = sendFailure(exchange);
            assertResults(exchange, result, new int[]{0}, new int[]{1, 2});
            assertReports(result.exception, exchange, new int[]{0, 2},
                    new int[]{550, 421});
            assertNull(exchange.handler.message.get());
        }
    }

    @Test(timeout = 20000)
    public void testShutdownIncludesUnansweredRecipients() throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                     new SMTPPipeliningTest.Exchange(5, 6)) {
            exchange.handler.responsePlan.set(new String[]{
                    "250 sender accepted", "550 invalid recipient",
                    "450 recipient busy", "250 recipient accepted",
                    "421 shutting down", "250 recipient accepted"});
            Failure result = sendFailure(exchange);
            assertResults(exchange, result, new int[]{0}, new int[]{2, 1, 3, 4});
            assertReports(result.exception, exchange, new int[]{0, 1, 3},
                    new int[]{550, 450, 421});
            assertNotNull(findIOException(result.exception));
            assertClosedWithoutDelivery(exchange);
        }
    }

    @Test(timeout = 20000)
    public void testShutdownRetainsEarlierGroupResults() throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                     new SMTPPipeliningTest.Exchange(70, 32, 32, 7)) {
            String[] replies = new String[71];
            Arrays.fill(replies, "250 recipient accepted");
            replies[0] = "250 sender accepted";
            replies[1] = "550 invalid recipient";
            replies[33] = "421 shutting down";
            exchange.handler.responsePlan.set(replies);
            Failure result = sendFailure(exchange);
            int[] unsent = new int[69];
            for (int i = 0; i < unsent.length; i++)
                unsent[i] = i + 1;
            assertResults(exchange, result, new int[]{0}, unsent);
            assertReports(result.exception, exchange, new int[]{0, 32},
                    new int[]{550, 421});
            assertEquals(Arrays.asList(32, 32), exchange.handler.groups);
            assertEquals(63, exchange.handler.commands.stream()
                    .filter(command -> command.startsWith("RCPT ")).count());
            assertClosedWithoutDelivery(exchange);
        }
    }

    @Test(timeout = 30000)
    public void testReadFailureRetainsEarlierRecipientReports() throws Exception {
        for (String reply : new String[]{"EOF", "not SMTP",
                "250-first\r\n550 inconsistent", "TIMEOUT"}) {
            try (SMTPPipeliningTest.Exchange exchange =
                         new SMTPPipeliningTest.Exchange(4, 5)) {
                exchange.properties.setProperty("mail.smtp.timeout", "500");
                exchange.properties.setProperty("mail.smtp.reportsuccess", "true");
                exchange.handler.responsePlan.set(new String[]{
                        "250 sender accepted", "550 invalid recipient",
                        "250 recipient accepted", reply, "250 recipient accepted"});
                Failure result = sendFailure(exchange);
                assertResults(exchange, result, new int[]{0}, new int[]{1, 2, 3});
                assertReports(result.exception, exchange, new int[]{0, 1},
                        new int[]{550, 250});
                assertNotNull("Missing I/O cause for " + reply,
                        findIOException(result.exception));
                assertClosedWithoutDelivery(exchange);
            }
        }
    }

    @Test(timeout = 20000)
    public void testUnexpectedReplyRetainsEarlierRecipientReports()
            throws Exception {
        for (boolean shutdown : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                         new SMTPPipeliningTest.Exchange(4, 5)) {
                exchange.properties.setProperty("mail.smtp.reportsuccess", "true");
                exchange.handler.responsePlan.set(new String[]{
                        "250 sender accepted", "550 invalid recipient",
                        "250 recipient accepted", "354 unexpected continuation",
                        shutdown ? "421 shutting down" : "250 recipient accepted"});
                Failure result = sendFailure(exchange);
                assertResults(exchange, result, new int[]{0}, new int[]{1, 2, 3});
                assertTrue(result.exception instanceof SMTPAddressFailedException);
                SMTPAddressFailedException failure =
                        (SMTPAddressFailedException) result.exception;
                assertEquals(354, failure.getReturnCode());
                assertEquals(exchange.addresses[2], failure.getAddress());
                assertReports(failure, exchange, new int[]{0, 1},
                        new int[]{550, 250});
                if (shutdown) {
                    assertNotNull(findIOException(failure));
                    assertClosedWithoutDelivery(exchange);
                } else {
                    assertTrue(exchange.handler.commands.contains("RSET"));
                    assertTrue(exchange.transport.isConnected());
                    assertNull(exchange.handler.message.get());
                    exchange.assertNoReader();
                    exchange.handler.responsePlan.set(null);
                    exchange.transport.setReportSuccess(false);
                    exchange.send();
                    exchange.assertContent();
                }
            }
        }
    }

    @Test(timeout = 20000)
    public void testCompleteRejectionKeepsEachRecipientReportOnce()
            throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                     new SMTPPipeliningTest.Exchange(3, 4)) {
            exchange.properties.setProperty("mail.smtp.reportsuccess", "true");
            exchange.handler.responsePlan.set(new String[]{
                    "250 sender accepted", "250 recipient accepted",
                    "550 invalid recipient", "450 recipient busy"});
            Failure result = sendFailure(exchange);
            assertResults(exchange, result, new int[]{1}, new int[]{0, 2});
            assertReports(result.exception, exchange, new int[]{0, 1, 2},
                    new int[]{250, 550, 450});
            assertTrue(exchange.transport.isConnected());
            assertTrue(exchange.handler.commands.contains("RSET"));
            assertNull(exchange.handler.message.get());
            exchange.assertNoReader();
        }
    }

    @Test(timeout = 30000)
    public void testInterruptedSenderRetainsEarlierGroupResults()
            throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                     new SMTPPipeliningTest.Exchange(40, 32, 9)) {
            exchange.properties.setProperty("mail.smtp.timeout", "10000");
            String[] replies = new String[41];
            Arrays.fill(replies, "250 recipient accepted");
            replies[0] = "250 sender accepted";
            replies[1] = "550 invalid recipient";
            replies[32] = "TIMEOUT";
            exchange.handler.responsePlan.set(replies);
            AtomicReference<Failure> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread sender = new Thread(() -> {
                try {
                    result.set(sendFailure(exchange));
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            sender.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (exchange.handler.groups.size() < 2 && sender.isAlive()
                        && System.nanoTime() < deadline)
                    Thread.sleep(10);
                assertEquals(Arrays.asList(32, 9), exchange.handler.groups);
                sender.interrupt();
                sender.join(5000);
                assertFalse("Interrupted sender did not finish", sender.isAlive());
            } finally {
                if (sender.isAlive()) {
                    sender.interrupt();
                    sender.join(12000);
                }
            }
            assertNull(failure.get());
            assertTrue("Sender interrupt was cleared", interrupted.get());
            assertNotNull(result.get());
            int[] unsent = new int[39];
            for (int i = 0; i < unsent.length; i++)
                unsent[i] = i + 1;
            assertResults(exchange, result.get(), new int[]{0}, unsent);
            assertReports(result.get().exception, exchange, new int[]{0},
                    new int[]{550});
            assertTrue("Missing interruption cause",
                    findCause(result.get().exception, InterruptedException.class)
                            != null ||
                    findCause(result.get().exception, InterruptedIOException.class)
                            != null);
            assertClosedWithoutDelivery(exchange);
        }
    }

    @Test(timeout = 20000)
    public void testWriteFailureRetainsEarlierGroupResults() throws Exception {
        try (SMTPPipeliningTest.Exchange exchange =
                     new SMTPPipeliningTest.Exchange(40, 32, 9)) {
            IOException writeFailure = new IOException("Test envelope write failed");
            OutputStream output = new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw writeFailure;
                }
            };
            SMTPTransport transport = new SMTPTransport(exchange.session(),
                    new URLName("smtp://localhost")) {
                private boolean envelope;
                private int replies;

                @Override
                protected boolean isPipeliningCompatible() {
                    return true;
                }

                @Override
                protected void sendEnvelope() throws MessagingException {
                    envelope = true;
                    try {
                        super.sendEnvelope();
                    } finally {
                        envelope = false;
                    }
                }

                @Override
                protected int readServerResponse() throws MessagingException {
                    int code = super.readServerResponse();
                    if (envelope && ++replies == 32) {
                        try {
                            ReflectionUtil.setFieldValue(this, "serverOutput",
                                    output);
                        } catch (ReflectiveOperationException ex) {
                            throw new MessagingException(
                                    "Could not install failing test output", ex);
                        }
                    }
                    return code;
                }
            };
            String[] replies = new String[41];
            Arrays.fill(replies, "250 recipient accepted");
            replies[0] = "250 sender accepted";
            replies[1] = "550 invalid recipient";
            exchange.handler.responsePlan.set(replies);
            exchange.connect(transport);
            Failure result = sendFailure(exchange);
            int[] unsent = new int[39];
            for (int i = 0; i < unsent.length; i++)
                unsent[i] = i + 1;
            assertResults(exchange, result, new int[]{0}, unsent);
            assertReports(result.exception, exchange, new int[]{0},
                    new int[]{550});
            assertSame(writeFailure, findIOException(result.exception));
            assertEquals(Arrays.asList(32), exchange.handler.groups);
            assertEquals(31, exchange.handler.commands.stream()
                    .filter(command -> command.startsWith("RCPT ")).count());
            assertClosedWithoutDelivery(exchange);
        }
    }

    @Test(timeout = 20000)
    public void testSenderRejectionRemainsPrimaryAfterReadFailure()
            throws Exception {
        for (String reply : new String[]{"421 shutting down", "EOF",
                "not SMTP"}) {
            try (SMTPPipeliningTest.Exchange exchange =
                         new SMTPPipeliningTest.Exchange(2, 3)) {
                exchange.handler.responsePlan.set(new String[]{
                        "550 sender rejected", reply, "503 no sender"});
                Failure result = sendFailure(exchange);
                assertTrue(result.exception instanceof SMTPSendFailedException);
                SMTPSendFailedException failure =
                        (SMTPSendFailedException) result.exception;
                assertEquals(550, failure.getReturnCode());
                assertTrue(failure.getCommand().startsWith("MAIL FROM:"));
                assertTrue(failure.getNextException()
                        instanceof SMTPSenderFailedException);
                assertArrayEquals(exchange.addresses,
                        failure.getValidUnsentAddresses());
                assertNull(failure.getInvalidAddresses());
                assertNull(result.event.getInvalidAddresses());
                assertArrayEquals(exchange.addresses,
                        result.event.getValidUnsentAddresses());
                assertNotNull(findIOException(failure));
                assertEquals(550, exchange.transport.getLastReturnCode());
                assertClosedWithoutDelivery(exchange);
            }
        }
    }

    private void checkShutdown(SMTPPipeliningTest.Exchange exchange,
                               boolean success) throws Exception {
        exchange.handler.responsePlan.set(new String[]{
                "250 sender accepted", "550 invalid recipient",
                "250 recipient accepted", "421 shutting down"});
        Failure result = sendFailure(exchange);
        assertResults(exchange, result, new int[]{0}, new int[]{1, 2});
        assertTrue(result.exception instanceof SMTPSendFailedException);
        SMTPSendFailedException failure =
                (SMTPSendFailedException) result.exception;
        assertEquals(421, failure.getReturnCode());
        assertEquals("RCPT TO:<" + exchange.addresses[2] + ">",
                failure.getCommand());
        assertReports(failure, exchange, success ? new int[]{0, 1, 2} :
                new int[]{0, 2}, success ? new int[]{550, 250, 421} :
                new int[]{550, 421});
        IOException cause = findIOException(failure);
        assertNotNull(cause);
        assertTrue(cause.getMessage().contains("421 shutting down"));
        assertEquals(421, exchange.transport.getLastReturnCode());
        assertClosedWithoutDelivery(exchange);
    }

    private Failure sendFailure(SMTPPipeliningTest.Exchange exchange)
            throws Exception {
        if (exchange.transport == null)
            exchange.connect(null);
        CountDownLatch eventReady = new CountDownLatch(1);
        AtomicReference<TransportEvent> event = new AtomicReference<>();
        exchange.transport.addTransportListener(new TransportAdapter() {
            @Override
            public void messageNotDelivered(TransportEvent value) {
                event.set(value);
                eventReady.countDown();
            }
        });
        MessagingException failure = null;
        try {
            exchange.send();
            fail("Envelope failure was ignored");
        } catch (MessagingException expected) {
            failure = expected;
        }
        boolean interrupted = Thread.interrupted();
        try {
            assertTrue("Missing transport event",
                    eventReady.await(3, TimeUnit.SECONDS));
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
        assertSame(exchange.message, event.get().getMessage());
        return new Failure(failure, event.get());
    }

    private void assertResults(SMTPPipeliningTest.Exchange exchange,
                               Failure result, int[] invalid, int[] unsent) {
        assertEquals(TransportEvent.MESSAGE_NOT_DELIVERED,
                result.event.getType());
        Address[] expectedInvalid = addresses(exchange, invalid);
        Address[] expectedUnsent = addresses(exchange, unsent);
        assertArrayEquals(expectedInvalid, result.event.getInvalidAddresses());
        assertArrayEquals(expectedUnsent, result.event.getValidUnsentAddresses());
        assertNull(result.event.getValidSentAddresses());
        if (result.exception instanceof SendFailedException &&
                !(result.exception instanceof SMTPAddressFailedException)) {
            SendFailedException failure = (SendFailedException) result.exception;
            assertArrayEquals(expectedInvalid, failure.getInvalidAddresses());
            assertArrayEquals(expectedUnsent, failure.getValidUnsentAddresses());
            assertNull(failure.getValidSentAddresses());
        }
    }

    private Address[] addresses(SMTPPipeliningTest.Exchange exchange,
                                int[] indices) {
        Address[] result = new Address[indices.length];
        for (int i = 0; i < indices.length; i++)
            result[i] = exchange.addresses[indices[i]];
        return result;
    }

    private void assertReports(MessagingException failure,
                               SMTPPipeliningTest.Exchange exchange,
                               int[] recipients, int[] codes) {
        List<Address> addresses = new ArrayList<>();
        List<Integer> replies = new ArrayList<>();
        Set<Exception> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Exception next = failure.getNextException();
        while (next != null) {
            assertTrue("Cycle in exception chain", seen.add(next));
            if (next instanceof SMTPAddressFailedException) {
                SMTPAddressFailedException report =
                        (SMTPAddressFailedException) next;
                addresses.add(report.getAddress());
                replies.add(report.getReturnCode());
            } else if (next instanceof SMTPAddressSucceededException) {
                SMTPAddressSucceededException report =
                        (SMTPAddressSucceededException) next;
                addresses.add(report.getAddress());
                replies.add(report.getReturnCode());
            }
            next = next instanceof MessagingException ?
                    ((MessagingException) next).getNextException() : null;
        }
        assertArrayEquals(addresses(exchange, recipients), addresses.toArray());
        assertEquals(codes.length, replies.size());
        for (int i = 0; i < codes.length; i++)
            assertEquals(codes[i], replies.get(i).intValue());
    }

    private IOException findIOException(Throwable failure) {
        return findCause(failure, IOException.class);
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        return findCause(failure, type,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type,
                                             Set<Throwable> seen) {
        if (failure == null || !seen.add(failure))
            return null;
        if (type.isInstance(failure))
            return type.cast(failure);
        T result = findCause(failure.getCause(), type, seen);
        if (result != null)
            return result;
        for (Throwable suppressed : failure.getSuppressed()) {
            result = findCause(suppressed, type, seen);
            if (result != null)
                return result;
        }
        return null;
    }

    private void assertClosedWithoutDelivery(SMTPPipeliningTest.Exchange exchange) {
        assertFalse(exchange.transport.isConnected());
        assertFalse(exchange.handler.commands.contains("RSET"));
        assertFalse(exchange.handler.commands.contains("DATA"));
        assertFalse(exchange.handler.commands.stream()
                .anyMatch(command -> command.startsWith("BDAT ")));
        assertEquals(1, exchange.handler.commands.stream()
                .filter(command -> command.startsWith("MAIL ")).count());
        assertNull(exchange.handler.message.get());
        exchange.assertNoReader();
    }

    private static final class Failure {
        final MessagingException exception;
        final TransportEvent event;

        Failure(MessagingException exception, TransportEvent event) {
            this.exception = exception;
            this.event = event;
        }
    }
}
