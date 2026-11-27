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

import org.eclipse.angus.mail.test.TestSocketFactory;
import org.eclipse.angus.mail.util.LineInputStream;
import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.junit.Test;

import javax.net.SocketFactory;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Keeps physical proxy routing distinct from the TLS engine's destination. */
public class SMTPPipeliningProxyTest {
    @Test(timeout = 30000)
    public void testConnectProxyPreservesTheTlsDestinationAndReuse() throws Exception {
        for (boolean implicit : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange =
                    new SMTPPipeliningTest.Exchange(2, implicit, 3)) {
                try (ConnectProxy proxy = new ConnectProxy(exchange.server.getPort())) {
                    String prefix = implicit ? "mail.smtps" : "mail.smtp";
                    exchange.properties.setProperty(prefix + ".pipelining", "true");
                    exchange.properties.setProperty(prefix + ".proxy.host", "127.0.0.1");
                    exchange.properties.setProperty(prefix + ".proxy.port", Integer.toString(proxy.port()));
                    exchange.properties.setProperty(prefix + ".proxy.user", "fixture-user");
                    exchange.properties.setProperty(prefix + ".proxy.password", "fixture-password");
                    exchange.properties.setProperty(prefix + ".localaddress", "127.0.0.1");
                    if (!implicit) {
                        exchange.handler.startTls = true;
                        exchange.properties.setProperty(prefix + ".starttls.required", "true");
                    }
                    AtomicInteger engines = new AtomicInteger();
                    exchange.properties.put(prefix + ".ssl.engineFactory",
                            (BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                                assertEquals("localhost", host);
                                assertEquals(exchange.server.getPort(), port.intValue());
                                engines.incrementAndGet();
                                return exchange.handler.context.createSSLEngine(host, port);
                            });
                    exchange.send();
                    exchange.send();
                    assertTrue(exchange.transport.isSSL());
                    assertEquals(1, engines.get());
                    assertEquals(1, exchange.server.clientCount());
                    assertEquals("127.0.0.1", proxy.clientAddress.get());
                    assertTrue(proxy.authenticated);
                    exchange.assertContent();
                    exchange.transport.close();
                }
            }
        }
    }

    @Test(timeout = 15000)
    public void testStartTlsSocketFactoryUsesTunnelDestination() throws Exception {
        checkStartTlsSocketFactoryPort(true, false);
        checkStartTlsSocketFactoryPort(true, true);
        checkStartTlsSocketFactoryPort(false, true);
    }

    @Test(timeout = 15000)
    public void testStartTlsUsesPortSelectedByDirectSocket() throws Exception {
        for (boolean pipelining : new boolean[]{false, true}) {
            try (SMTPPipeliningTest.Exchange exchange = new SMTPPipeliningTest.Exchange(2, false, 1, 1, 1)) {
                exchange.handler.startTls = true;
                exchange.properties.setProperty("mail.smtp.pipelining", Boolean.toString(pipelining));
                exchange.properties.setProperty("mail.smtp.starttls.required", "true");
                exchange.properties.put("mail.smtp.socketFactory", new TestSocketFactory() {
                    @Override
                    public Socket createSocket() {
                        return new Socket() {
                            @Override
                            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                                InetSocketAddress requested = (InetSocketAddress) endpoint;
                                assertEquals(25, requested.getPort());
                                super.connect(new InetSocketAddress(requested.getAddress(), exchange.server.getPort()),
                                        timeout);
                            }
                        };
                    }
                });
                AtomicInteger peerPort = recordStartTlsPort(exchange);
                exchange.connect(null, 25);
                assertEquals(exchange.server.getPort(), peerPort.get());
            }
        }
    }

    private void checkStartTlsSocketFactoryPort(boolean pipelining, boolean plainTransport) throws Exception {
        try (SMTPPipeliningTest.Exchange exchange = new SMTPPipeliningTest.Exchange(2, false, 1, 1, 1);
             ConnectProxy proxy = new ConnectProxy(exchange.server.getPort())) {
            exchange.handler.startTls = true;
            exchange.properties.setProperty("mail.smtp.pipelining", Boolean.toString(pipelining));
            exchange.properties.setProperty("mail.smtp.starttls.required", "true");
            exchange.properties.setProperty("mail.smtp.proxy.host", "127.0.0.1");
            exchange.properties.setProperty("mail.smtp.proxy.port", Integer.toString(proxy.port()));
            exchange.properties.setProperty("mail.smtp.proxy.user", "fixture-user");
            exchange.properties.setProperty("mail.smtp.proxy.password", "fixture-password");
            AtomicInteger peerPort = recordStartTlsPort(exchange);
            if (plainTransport) {
                exchange.properties.put("mail.smtp.socketFactory.transport", SocketFactory.getDefault());
                exchange.properties.put("mail.smtp.socketFactory", SocketFactory.getDefault());
                exchange.properties.setProperty("mail.smtp.socketFactory.port",
                        Integer.toString(exchange.server.getPort()));
                exchange.connect(null, 1); // The factory override selects the tunnel destination.
            }
            exchange.send();
            assertEquals(exchange.server.getPort(), peerPort.get());
            exchange.transport.close();
        }
    }

    private static AtomicInteger recordStartTlsPort(SMTPPipeliningTest.Exchange exchange) throws Exception {
        exchange.properties.remove("mail.smtp.ssl.engineFactory");
        AtomicInteger peerPort = new AtomicInteger();
        exchange.properties.put("mail.smtp.ssl.socketFactory", new MailSSLSocketFactory() {
            @Override
            public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
                peerPort.set(port);
                return exchange.handler.context.getSocketFactory().createSocket(socket, host, port, autoClose);
            }
        });
        return peerPort;
    }

    private static final class ConnectProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final Future<?> forwarding;
        private final int destinationPort;
        private final AtomicReference<String> clientAddress = new AtomicReference<>();
        private volatile Socket client;
        private volatile Socket destination;
        private volatile boolean authenticated;
        private volatile boolean closed;

        ConnectProxy(int destinationPort) throws IOException {
            this.destinationPort = destinationPort;
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(5000);
            forwarding = workers.submit(() -> {
                try {
                    forward();
                } catch (IOException failure) {
                    if (!closed)
                        throw new IllegalStateException(failure);
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        private void forward() throws IOException {
            client = listener.accept();
            client.setSoTimeout(10000);
            clientAddress.set(client.getInetAddress().getHostAddress());
            LineInputStream input = new LineInputStream(client.getInputStream());
            assertEquals("CONNECT localhost:" + destinationPort + " HTTP/1.1", input.readLine());
            String expected = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(
                    "fixture-user:fixture-password".getBytes(StandardCharsets.US_ASCII));
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if (line.equals(expected))
                    authenticated = true;
            }
            assertTrue(authenticated);
            destination = new Socket("localhost", destinationPort);
            destination.setSoTimeout(10000);
            client.getOutputStream().write("HTTP/1.1 200 Connected\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            workers.submit(() -> {
                try {
                    relay(client, destination);
                } catch (IOException ignored) {
                    // Closing either end interrupts the opposite relay.
                }
            });
            relay(destination, client);
        }

        private static void relay(Socket source, Socket target) throws IOException {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.getInputStream().read(buffer)) >= 0) {
                target.getOutputStream().write(buffer, 0, count);
                target.getOutputStream().flush();
            }
            target.shutdownOutput();
        }

        @Override
        public void close() throws Exception {
            try {
                closed = true;
                listener.close();
                if (client != null)
                    client.close();
                if (destination != null)
                    destination.close();
                forwarding.get(3, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }
}
