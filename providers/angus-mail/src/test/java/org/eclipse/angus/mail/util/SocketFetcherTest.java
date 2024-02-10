/*
 * Copyright (c) 2009, 2024 Oracle and/or its affiliates. All rights reserved.
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

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.test.ProtocolHandler;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.function.ThrowingRunnable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import org.eclipse.angus.mail.imap.IMAPHandler;
import org.eclipse.angus.mail.test.TestSSLSocketFactory;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Test SocketFetcher.
 */
public final class SocketFetcherTest {

    // timeout the test in case of deadlock
    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    /**
     * Test connecting with proxy host and port.
     */
    @Test
    public void testProxyHostPort() {
        assertTrue("proxy host, port", testProxy("proxy", "localhost", "PPPP"));
    }

    /**
     * Test connecting with proxy host and port and user name and password.
     */
    @Test
    public void testProxyHostPortUserPassword() {
        assertTrue("proxy host, port, user, password",
                testProxyUserPassword("proxy", "localhost", "PPPP", "user", "pwd"));
    }

    /**
     * Test connecting with proxy host:port.
     */
    @Test
    public void testProxyHostColonPort() {
        assertTrue("proxy host:port", testProxy("proxy", "localhost:PPPP", null));
    }

    /**
     * Test connecting with socks host and port.
     */
    @Test
    public void testSocksHostPort() {
        assertTrue("socks host, port", testProxy("socks", "localhost", "PPPP"));
    }

    /**
     * Test connecting with socks host:port.
     */
    @Test
    public void testSocksHostColonPort() {
        assertTrue("socks host:port", testProxy("socks", "localhost:PPPP", null));
    }

    /**
     * Test connecting with no proxy.
     */
    @Test
    public void testNoProxy() {
        assertFalse("no proxy", testProxy("none", "localhost", null));
    }

    /**
     * HTTP response and IMAP response together.
     * This test verifies the IMAP response will not be read when reading the proxy response.
     */
    @Test
    public void issue45Success() throws IOException {
        String imapResponse = "* OK NAME IMAP4rev1 Server  Server 1ece50b148c8 is ready.";
        StringBuilder message = new StringBuilder();
        message.append("HTTP/1.0 200 Connection established\r\n");
        message.append("More things\r\n");
        message.append("\r\n");
        message.append(imapResponse).append("\r\n");
        InputStream proxyResponse = new ByteArrayInputStream(message.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(SocketFetcher.readProxyResponse(proxyResponse, new StringBuilder()));
        LineInputStream r = new LineInputStream(proxyResponse, true);
        /* IMAP response was not read yet.
         * Next line would fail if SocketFetcher.readProxyResponse uses a BufferedInputStream
         * because all the input is read and buffered.
         */
        assertEquals(imapResponse, r.readLine());
    }

    @Test
    public void issue45Failure() throws IOException {
        String errorMessage = "HTTP/1.0 403 Error";
        StringBuilder message = new StringBuilder();
        message.append(errorMessage).append("\r\n");
        message.append("More things\r\n");
        message.append("\r\n");
        InputStream proxyResponse = new ByteArrayInputStream(message.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder error = new StringBuilder();
        assertFalse(SocketFetcher.readProxyResponse(proxyResponse, error));
        assertEquals(errorMessage, error.toString());
    }

    @Test
    public void testSSLHostnameVerifierAcceptsConnections() throws Exception {
        testSSLHostnameVerifier(true);
    }

    /**
     * Test connecting (IMAP) with SSL using a custom hostname verifier which will
     * reject all connections.
     *
     * @throws Exception
     */
    @Test
    public void testSSLHostnameVerifierRejectsConnections() throws Exception {
        testSSLHostnameVerifier(false);
    }

    @Test
    public void testSSLVerifierInstantiatedByString() throws Exception {
        testSSLHostnameVerifierByName();
    }

    /**
     * Utility method for testing a custom {@link HostnameVerifier}.
     *
     * @param acceptConnections Whether the {@link HostnameVerifier} should accept or reject connections.
     * @throws Exception
     */
    private void testSSLHostnameVerifier(boolean acceptConnections) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.ssl.enable", "true");

        TestSSLSocketFactory sf = new TestSSLSocketFactory();
        properties.put("mail.imap.ssl.socketFactory", sf);

        // don't fall back to non-SSL
        properties.setProperty("mail.imap.socketFactory.fallback", "false");

        TestHostnameVerifier hnv = new TestHostnameVerifier(acceptConnections);
        properties.put("mail.imap.ssl.hostnameverifier", hnv);
        properties.setProperty("mail.imap.ssl.checkserveridentity", "false");

        ThrowingRunnable runnable = () -> {
            TestServer server = null;
            try {
                server = new TestServer(new IMAPHandler(), true);
                server.start();

                properties.setProperty("mail.imap.port",
                        Integer.toString(server.getPort()));
                final Session session = Session.getInstance(properties);

                try (Store store = session.getStore("imap")) {
                    store.connect("test", "test");
                }
            } finally {
                if (server != null) {
                    server.quit();
                }
            }
        };

        if (!acceptConnections) {
            // When the hostname verifier refuses a connection, a MessagingException will be thrown.
            assertNotNull(assertThrows(MessagingException.class, runnable));
        } else {
            // When the hostname verifier is not set to refuse connections, no exception should be thrown.
            synchronized (TestHostnameVerifier.class) {
                try {
                    runnable.run();
                } catch(Throwable t){
                    throw new AssertionError(t);
                } finally{
                    TestHostnameVerifier.reset();
                }
            }
        }

        // Ensure the custom hostname verifier was actually used.
        assertTrue("Hostname verifier was not used.", hnv.hasInvokedVerify());
    }

    private void testSSLHostnameVerifierByName() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.ssl.enable", "true");

        TestSSLSocketFactory sf = new TestSSLSocketFactory();
        properties.put("mail.imap.ssl.socketFactory", sf);

        // don't fall back to non-SSL
        properties.setProperty("mail.imap.socketFactory.fallback", "false");

        properties.setProperty("mail.imap.ssl.hostnameverifier.class", TestHostnameVerifier.class.getName());
        properties.setProperty("mail.imap.ssl.checkserveridentity", "false");

        ThrowingRunnable runnable = () -> {
            TestServer server = null;
            try {
                server = new TestServer(new IMAPHandler(), true);
                server.start();

                properties.setProperty("mail.imap.port",
                        Integer.toString(server.getPort()));
                final Session session = Session.getInstance(properties);

                try (Store store = session.getStore("imap")) {
                    store.connect("test", "test");
                }
            } finally {
                if (server != null) {
                    server.quit();
                }
            }
        };

        synchronized (TestHostnameVerifier.class) {
            try {
                runnable.run();
                assertEquals("Expected the Default Constructor of the HostnameVerifier class to be invoked once.", 1, TestHostnameVerifier.getDefaultConstructorCount());
            } catch (Throwable t) {
                throw new AssertionError(t);
            } finally {
                TestHostnameVerifier.reset();
            }
        }
    }

    @Test
    public void testSSLEndpointIdentityCheckEmptyString() throws Throwable {
        testSSLEndpointIdentityCheck("");
    }

    @Test
    public void testSSLEndpointIdentityCheckLdaps() {
        try {
            testSSLEndpointIdentityCheck("LDAPS");
            throw new AssertionError();
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (MessagingException me) {
            Throwable cause = me.getCause();
            assertTrue(String.valueOf(cause),
                    cause instanceof SSLHandshakeException);
            assertTrue(me.toString(), isFromTrustManager(me));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    @Test
    public void testSSLEndpointIdentityCheckHttps() {
        try {
            testSSLEndpointIdentityCheck("HTTPS");
            throw new AssertionError();
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (MessagingException me) {
            Throwable cause = me.getCause();
            assertTrue(String.valueOf(cause),
                    cause instanceof SSLHandshakeException);
            assertTrue(me.toString(), isFromTrustManager(me));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private boolean isFromTrustManager(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            for (StackTraceElement s : t.getStackTrace()) {
                if ("checkServerTrusted".equals(s.getMethodName())
                        && s.getClassName().contains("TrustManager")) {
                    return true;
                }
            }
        }
        return false;
    }


    private void testSSLEndpointIdentityCheck(String algo) throws Throwable {
        final Properties props = new Properties();
        //String host = InetAddress.getLocalHost().getCanonicalHostName();
        //properties.setProperty("mail.imap.host", host);
        props.setProperty("mail.imap.host", "localhost");
        props.setProperty("mail.imap.ssl.enable", "true");

        TestSSLSocketFactory sf = new TestSSLSocketFactory();
        props.put("mail.imap.ssl.socketFactory", sf);

        // don't fall back to non-SSL
        props.setProperty("mail.imap.socketFactory.fallback", "false");

        props.setProperty("mail.imap.ssl.checkserveridentity", "false");
        props.setProperty("mail.imap.ssl.endpointidentitycheck", algo);

        TestServer server = null;
        try {
            server = new TestServer(new IMAPHandler(), true);
            server.start();

            props.setProperty("mail.imap.port",
                    Integer.toString(server.getPort()));
            final Session session = Session.getInstance(props);

            try (Store store = session.getStore("imap")) {
                store.connect("test", "test");
            }
        } finally {
            if (server != null) {
                server.quit();
            }
        }
    }

    /**
     *
     */
    public boolean testProxy(String type, String host, String port) {
        return testProxyUserPassword(type, host, port, null, null);
    }

    /**
     *
     */
    public boolean testProxyUserPassword(String type, String host, String port,
                                         String user, String pwd) {
        TestServer server = null;
        try {
            ProxyHandler handler = new ProxyHandler(type.equals("proxy"));
            server = new TestServer(handler);
            server.start();
            String sport = String.valueOf(server.getPort());

            //System.setProperty("mail.socket.debug", "true");
            Properties properties = new Properties();
            properties.setProperty("mail.test.host", "localhost");
            properties.setProperty("mail.test.port", "2");
            properties.setProperty("mail.test." + type + ".host",
                    host.replace("PPPP", sport));
            if (port != null)
                properties.setProperty("mail.test." + type + ".port",
                        port.replace("PPPP", sport));
            if (user != null)
                properties.setProperty("mail.test." + type + ".user", user);
            if (pwd != null)
                properties.setProperty("mail.test." + type + ".password", pwd);

            Socket s = null;
            try {
                s = SocketFetcher.getSocket("localhost", 2,
                        properties, "mail.test", false);
            } catch (Exception ex) {
                // ignore failure, which is expected
                //System.out.println(ex);
                //ex.printStackTrace();
            } finally {
                if (s != null)
                    s.close();
            }
            if (!handler.getConnected())
                return false;
            if (user != null && pwd != null)
                return (user + ":" + pwd).equals(handler.getUserPassword());
            else
                return true;

        } catch (final Exception e) {
            //e.printStackTrace();
            fail(e.getMessage());
            return false;    // XXX - doesn't matter
        } finally {
            if (server != null) {
                server.quit();
            }
        }
    }

    public static class TestHostnameVerifier implements HostnameVerifier {
        /*
         * This is based on an assumption that the hostname verifier is instantiated
         * by its default constructor in a managed way.
         *
         * Unit tests that check this property should impose their own thread safety.
         * For example, when executing code expected to be using the TestHostnameVerifier,
         * the unit test may synchronize on the TestHostnameVerifier class and call the
         * static "reset" method prior to de-synchronizing.
         */
        private static final AtomicInteger defaultConstructorCount = new AtomicInteger();
        private boolean acceptConnections = true;
        private boolean verified = false;

        public TestHostnameVerifier() {
            defaultConstructorCount.getAndIncrement();
        }

        public TestHostnameVerifier(boolean acceptConnections) {
            this.acceptConnections = acceptConnections;
        }

        @Override
        public boolean verify(String hostname, SSLSession session) {
            verified = true;
            return acceptConnections;
        }

        /**
         * Indicates whether the hostname verifier has been used.
         * @return true if
         */
        public boolean hasInvokedVerify() {
            return verified;
        }

        public static int getDefaultConstructorCount() {
            return defaultConstructorCount.get();
        }

        /**
         * Used to reset static values.
         */
        public static void reset() {
            defaultConstructorCount.set(0);
        }
    }

    /**
     * Custom handler.  Remember whether any data was sent
     * and save user/password string;
     */
    private static class ProxyHandler extends ProtocolHandler {
        private final boolean http;

        // must be static because handler is cloned for each connection
        private static volatile boolean connected;
        private static volatile String userPassword;

        public ProxyHandler(boolean http) {
            this.http = http;
            connected = false;
        }

        @Override
        public void handleCommand() throws IOException {
            if (!http) {
                int c = in.read();
                if (c >= 0) {
                    // any data means a real client connected
                    connected = true;
                }
                exit();
            }

            // else, http...
            String line;
            while ((line = readLine()) != null) {
                // any data means a real client connected
                connected = true;
                if (line.length() == 0)
                    break;
                if (line.startsWith("Proxy-Authorization:")) {
                    int i = line.indexOf("Basic ") + 6;
                    String up = line.substring(i);
                    userPassword = new String(Base64.getDecoder().decode(
                            up.getBytes(StandardCharsets.US_ASCII)),
                            StandardCharsets.UTF_8);
                }
            }
            exit();
        }

        public boolean getConnected() {
            return connected;
        }

        public String getUserPassword() {
            return userPassword;
        }
    }
}
