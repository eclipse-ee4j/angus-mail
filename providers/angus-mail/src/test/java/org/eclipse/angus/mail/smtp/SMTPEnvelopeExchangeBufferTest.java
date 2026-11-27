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

import org.eclipse.angus.mail.util.DuplexSocket;
import org.eclipse.angus.mail.util.NioSocketFetcher;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Supplies parser input directly; the socket only activates its normal callback. */
public class SMTPEnvelopeExchangeBufferTest {
    @Test(timeout = 20000)
    public void testReadyInputEOFIsTyped() throws Exception {
        InputStream ended = new InputStream() {
            @Override
            public int available() { return 1; }

            @Override
            public int read() { return -1; }
        };
        try (Connection connection = new Connection(ended, 1)) {
            EOFException failure = assertThrows(EOFException.class, connection.exchange::take);
            assertEquals("[EOF]", failure.getMessage());
            assertSame(failure, connection.exchange.getFailure());
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    @Test(timeout = 20000)
    public void testShutdownDuringWriteRetainsRepliesInOrder() throws Exception {
        try (Connection connection = new Connection("250 sender ok\r\n421 closing\r\n", 2)) {
            assertThrows(IOException.class, () -> {
                while (true)
                    connection.socket.getOutputStream().write(0);
            });
            assertFalse(connection.socket.getChannel().isOpen());
            assertEquals(250, connection.exchange.take().code);
            assertTrue(connection.exchange.hasReply());
            assertEquals(421, connection.exchange.take().code);
            assertFalse(connection.exchange.hasReply());
            assertSame(connection.exchange.getFailure(),
                    assertThrows(IOException.class, connection.exchange::take));
        }
    }

    @Test(timeout = 20000)
    public void testLargeValidMultilineReply() throws Exception {
        try (Connection connection = new Connection(reply(128 * 1024), 1)) {
            assertEquals(250, connection.exchange.take().code);
        }
    }

    @Test(timeout = 20000)
    public void testOneReplyCannotGrowWithoutLimit() throws Exception {
        try (Connection connection = new Connection(reply(1100 * 1024), 1)) {
            IOException failure = assertThrows(IOException.class, connection.exchange::take);
            assertTrue(failure.getMessage(), failure.getMessage().contains("reply limit"));
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    @Test(timeout = 20000)
    public void testQueuedReplyBytesAreBounded() throws Exception {
        String replies = reply(900 * 1024).repeat(5);
        try (Connection connection = new Connection(replies, 5)) {
            connection.exchange.take();
            assertNotNull(connection.exchange.getFailure());
            assertTrue(connection.exchange.getFailure().getMessage().contains("reply limit"));
            assertFalse(connection.socket.getChannel().isOpen());
        }
    }

    private static String reply(int bytes) {
        String line = "250-" + "x".repeat(496) + "\r\n";
        StringBuilder reply = new StringBuilder(bytes + line.length());
        while (reply.length() < bytes)
            reply.append(line);
        return reply.append("250 done\r\n").toString();
    }

    private static final class Connection implements AutoCloseable {
        final DuplexSocket socket;
        final Socket peer;
        final SMTPEnvelopeExchange exchange;

        Connection(String replies, int count) throws IOException {
            this(new ByteArrayInputStream(replies.getBytes(StandardCharsets.US_ASCII)), count);
        }

        Connection(InputStream input, int count) throws IOException {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
                server.setSoTimeout(3000);
                Properties properties = new Properties();
                properties.setProperty("mail.smtp.connectiontimeout", "3000");
                properties.setProperty("mail.smtp.timeout", "10000");
                socket = (DuplexSocket) NioSocketFetcher.getSocket(
                        loopback.getHostAddress(), server.getLocalPort(), properties, "mail.smtp", false);
                peer = server.accept();
                exchange = new SMTPEnvelopeExchange(socket, input);
                exchange.reserve(count);
                peer.getOutputStream().write(0);
            }
        }

        @Override
        public void close() throws IOException {
            exchange.finish(true, null);
            try {
                socket.close();
            } finally {
                peer.close();
            }
        }
    }
}
