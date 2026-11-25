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

import org.junit.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Checks the result contract shared by built-in and custom engine verifiers. */
public class NioSocketFetcherTest {
    @Test(timeout = 20000)
    public void testJvmSocksRouteIsPreserved() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ProxySelector original = ProxySelector.getDefault();
        try (ServerSocket proxy = new ServerSocket(0, 1, loopback);
             ServerSocket destination = new ServerSocket(0, 1, loopback)) {
            proxy.setSoTimeout(3000);
            Proxy socks = new Proxy(Proxy.Type.SOCKS, proxy.getLocalSocketAddress());
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return Collections.singletonList("socket".equals(uri.getScheme())
                            && uri.getPort() == destination.getLocalPort() ? socks : Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            });
            for (boolean useNio : new boolean[]{false, true}) {
                FutureTask<Integer> greeting = new FutureTask<>(() -> {
                    try (Socket client = proxy.accept()) {
                        client.setSoTimeout(3000);
                        int version = client.getInputStream().read();
                        client.getOutputStream().write(new byte[]{5, (byte) 255});
                        return version;
                    }
                });
                new Thread(greeting, "SOCKS test proxy").start();
                String host = loopback.getHostAddress();
                int port = destination.getLocalPort();
                Properties props = new Properties();
                props.setProperty("mail.smtp.connectiontimeout", "3000");
                assertThrows(IOException.class, () -> {
                    try (Socket ignored = useNio
                            ? NioSocketFetcher.getSocket(host, port, props, "mail.smtp", false)
                            : SocketFetcher.getSocket(host, port, props, "mail.smtp", false)) { }
                });
                assertEquals(5, greeting.get(5, TimeUnit.SECONDS).intValue());
            }
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    @Test(timeout = 10000)
    public void testProxyResponseTimeoutClosesConnection() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket proxy = new ServerSocket(0, 1, loopback)) {
            proxy.setSoTimeout(3000);
            Properties props = new Properties();
            props.setProperty("mail.smtp.proxy.host", loopback.getHostAddress());
            props.setProperty("mail.smtp.proxy.port", Integer.toString(proxy.getLocalPort()));
            props.setProperty("mail.smtp.timeout", "250");
            props.setProperty("mail.smtp.writetimeout", "3000");
            SocketConnectException failure = assertThrows(SocketConnectException.class,
                    () -> NioSocketFetcher.getSocket("localhost", 587, props, "mail.smtp", false));
            assertTrue(failure.getCause() instanceof SocketTimeoutException);
            try (Socket client = proxy.accept()) {
                client.setSoTimeout(2000);
                LineInputStream input = new LineInputStream(client.getInputStream());
                assertEquals("CONNECT localhost:587 HTTP/1.1", input.readLine());
                String header;
                do {
                    header = input.readLine();
                } while (header != null && !header.isEmpty());
                assertEquals("", header);
                assertEquals(-1, input.read());
            }
        }
    }

    @Test
    public void testRejectedVerifierIsPeerUnverified() throws Exception {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> verify((host, session) -> false));
        assertTrue(failure.getCause() instanceof SSLPeerUnverifiedException);
        assertTrue(failure.getCause().getMessage().contains("DENY"));
    }

    @Test
    public void testAcceptedVerifierIsCalled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        verify((host, session) -> {
            assertEquals("mail.example.test", host);
            calls.incrementAndGet();
            return true;
        });
        assertEquals(1, calls.get());
    }

    @Test
    public void testVerifierExceptionIsRetained() throws Exception {
        IllegalStateException original = new IllegalStateException("Verifier failed");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> verify((host, session) -> { throw original; }));
        assertSame(original, failure.getCause());
    }

    private static void verify(HostnameVerifier verifier) throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        java.lang.reflect.Method check = NioSocketFetcher.class.getDeclaredMethod(
                "checkServerIdentity", HostnameVerifier.class, String.class, SSLEngine.class);
        check.setAccessible(true);
        check.invoke(null, verifier, "mail.example.test", engine);
    }
}
