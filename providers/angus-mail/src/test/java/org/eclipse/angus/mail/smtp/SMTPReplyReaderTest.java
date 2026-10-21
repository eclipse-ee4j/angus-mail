/*
 * Copyright (c) 2026 Benny Bottema. All rights reserved.
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

import org.eclipse.angus.mail.test.TestServer;
import org.eclipse.angus.mail.util.LineInputStream;
import org.eclipse.angus.mail.util.WriteTimeoutSocket;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Standalone reader stress: both peers write more than their socket buffers can
 * hold. A reader must drain replies while the sending thread is blocked.
 */
public class SMTPReplyReaderTest {
    private static final int BYTES = 4 * 1024 * 1024;
    // Still much smaller than the payload, without tiny TCP windows on Linux.
    private static final int SOCKET_BUFFER_SIZE = 64 * 1024;

    @Test(timeout = 60000)
    public void testPlainBackpressure() throws Exception {
        backpressure(0, false);
    }

    @Test(timeout = 60000)
    public void testStartTlsBackpressure() throws Exception {
        backpressure(1, false);
    }

    @Test(timeout = 60000)
    public void testImplicitTlsBackpressure() throws Exception {
        backpressure(2, false);
    }

    @Test(timeout = 60000)
    public void testPlainAbortUnblocksWrite() throws Exception {
        backpressure(0, true);
    }

    @Test(timeout = 60000)
    public void testStartTlsAbortUnblocksWrite() throws Exception {
        backpressure(1, true);
    }

    @Test(timeout = 60000)
    public void testImplicitTlsAbortUnblocksWrite() throws Exception {
        backpressure(2, true);
    }

    /**
     * Places a write-timeout wrapper outside the connected socket. With TLS
     * this differs from SocketFetcher's normal wrapper order; transport-level
     * timeout behavior is covered separately by SMTPPipeliningTlsTest.
     * TLS uses the existing test keystore, not a production trust override.
     */
    private void backpressure(int mode, boolean abort) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SMTPReplyReader reader = null;
        try (Pair pair = new Pair(mode, executor)) {
            Socket client = pair.client;
            byte[] command = new byte[BYTES];
            Arrays.fill(command, (byte) 'x');
            CountDownLatch serverWriting = new CountDownLatch(1);
            CountDownLatch clientFinished = new CountDownLatch(1);
            Future<?> server = executor.submit(() -> {
                try {
                    assertEquals('x', pair.server.getInputStream().read());
                    serverWriting.countDown();
                    if (abort) {
                        pair.server.getOutputStream().write(
                                "421 shutting down\r\n".getBytes(StandardCharsets.US_ASCII));
                        pair.server.getOutputStream().flush();
                        // Do not read the rest of the client's blocked write.
                        assertTrue(clientFinished.await(20, TimeUnit.SECONDS));
                    } else {
                        byte[] line = ("250-" + "r".repeat(480) + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII);
                        for (int i = 0; i < BYTES / line.length; i++)
                            pair.server.getOutputStream().write(line);
                        pair.server.getOutputStream().write(
                                "250 done\r\n".getBytes(StandardCharsets.US_ASCII));
                        pair.server.getOutputStream().flush();
                        int remaining = BYTES - 1;
                        byte[] buffer = new byte[8192];
                        while (remaining > 0) {
                            int n = pair.server.getInputStream().read(buffer, 0,
                                    Math.min(buffer.length, remaining));
                            if (n == -1)
                                throw new IOException("Truncated command");
                            remaining -= n;
                        }
                    }
                    return null;
                } catch (IOException ex) {
                    if (!abort)
                        throw ex;
                    return null;
                } finally {
                    pair.server.close();
                }
            });
            Future<IOException> writer = executor.submit(() -> {
                try {
                    client.getOutputStream().write(command);
                    return null;
                } catch (IOException ex) {
                    return ex;
                }
            });
            assertTrue(serverWriting.await(5, TimeUnit.SECONDS));
            // Establish the blocked-write control before starting the reader.
            try {
                writer.get(150, TimeUnit.MILLISECONDS);
                fail("Write completed without a concurrent reader");
            } catch (TimeoutException expected) {
                // Both peers cannot progress until replies are drained.
            }
            reader = new SMTPReplyReader(client, new LineInputStream(
                    new BufferedInputStream(client.getInputStream())));
            reader.reserve(1);
            if (abort) {
                try {
                    reader.take();
                    fail("421 accepted");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("421 shutting down"));
                    assertSame(expected, reader.getFailure());
                }
                assertNotNull(writer.get(10, TimeUnit.SECONDS));
                clientFinished.countDown();
            } else {
                SMTPReplyReader.Reply reply = reader.take();
                assertEquals(250, reply.code);
                assertTrue(reply.text.endsWith("250 done\n"));
                assertNull(writer.get(15, TimeUnit.SECONDS));
            }
            reader.finish(!abort);
            reader = null;
            client.close();
            server.get(10, TimeUnit.SECONDS);
        } finally {
            if (reader != null)
                reader.finish(false);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * Real connected sockets with deliberately small buffers. In STARTTLS
     * mode a plaintext command/reply precedes wrapping and the handshake.
     * Owns the client's write-timeout wrapper, including its timer on failure.
     */
    private static final class Pair implements AutoCloseable {
        final Socket client;
        final Socket server;

        Pair(int mode, ExecutorService executor) throws Exception {
            SSLContext context = sslContext();
            try (ServerSocket listener = mode == 2 ?
                    context.getServerSocketFactory().createServerSocket(0) :
                    new ServerSocket(0)) {
                listener.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
                Future<Socket> accept = executor.submit(() -> {
                    Socket socket = listener.accept();
                    configure(socket);
                    if (mode == 1) {
                        assertEquals("STARTTLS", new LineInputStream(socket.getInputStream()).readLine());
                        socket.getOutputStream().write("220 ready\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket = context.getSocketFactory().createSocket(socket,
                                "localhost", socket.getPort(), true);
                        ((SSLSocket) socket).setUseClientMode(false);
                    }
                    if (mode != 0)
                        ((SSLSocket) socket).startHandshake();
                    return socket;
                });
                Socket socket = mode == 2 ? context.getSocketFactory().createSocket() : new Socket();
                configure(socket);
                socket.connect(new java.net.InetSocketAddress("localhost", listener.getLocalPort()));
                if (mode == 1) {
                    socket.getOutputStream().write("STARTTLS\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("220 ready", new LineInputStream(socket.getInputStream()).readLine());
                    socket = context.getSocketFactory().createSocket(socket,
                            "localhost", listener.getLocalPort(), true);
                }
                if (mode != 0)
                    ((SSLSocket) socket).startHandshake();
                server = accept.get(10, TimeUnit.SECONDS);
                client = new WriteTimeoutSocket(socket, 30000);
            }
        }

        private static void configure(Socket socket) throws IOException {
            socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
            socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
            socket.setSoTimeout(20000);
            socket.setTcpNoDelay(true);
        }

        private static SSLContext sslContext() throws Exception {
            KeyStore store = KeyStore.getInstance("JKS");
            try (InputStream in = TestServer.class.getResourceAsStream("keystore.jks")) {
                store.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509");
            keys.init(store, "changeit".toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance("SunX509");
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        }

        @Override
        public void close() throws IOException {
            try {
                if (!client.isClosed()) {
                    client.setSoLinger(true, 0);
                }
                client.close();
            } finally {
                if (!server.isClosed()) {
                    server.setSoLinger(true, 0);
                    server.close();
                }
            }
        }
    }
}
