/*
 * Copyright (c) 2009, 2023 Oracle and/or its affiliates. All rights reserved.
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

import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.imap.IMAPHandler;
import org.eclipse.angus.mail.test.ReflectionUtil;
import org.eclipse.angus.mail.test.TestSSLSocketFactory;
import org.eclipse.angus.mail.test.TestServer;
import org.eclipse.angus.mail.test.TestSocketFactory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test that write timeouts work.
 */
public final class WriteTimeoutSocketTest {
    private TestServer testServer;
    private List<ScheduledExecutorService> scheduledExecutorServices = new ArrayList<>();
    private List<WriteTimeoutSocket> writeTimeoutSockets = new ArrayList<>();

    // timeout the test in case of deadlock
    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    private static final int TIMEOUT = 200;    // ms
    private static final String data =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @After
    public void tearDown() {
        close(testServer);
        scheduledExecutorServices.forEach(this::close);
        writeTimeoutSockets.forEach(this::close);
        scheduledExecutorServices.clear();
        writeTimeoutSockets.clear();
    }

    /**
     * Test write timeouts with plain sockets.
     */
    @Test
    public void test() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        test(properties, false);
    }

    /**
     * Test write timeouts with custom socket factory.
     */
    @Test
    public void testSocketFactory() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        TestSocketFactory sf = new TestSocketFactory();
        properties.put("mail.imap.socketFactory", sf);
        properties.setProperty("mail.imap.socketFactory.fallback", "false");
        test(properties, false);
        // make sure our socket factory was actually used
        assertTrue(sf.getSocketCreated());
    }

    @Test(expected = MessagingException.class)
    public void testSSLCheckserveridentityDefaultsTrue() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        properties.setProperty("mail.imap.ssl.enable", "true");
        properties.setProperty("mail.imap.ssl.trust", "localhost");
        test(properties, true);
    }

    /**
     * Test write timeouts with SSL sockets.
     */
    @Test
    public void testSSL() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.ssl.checkserveridentity", "false");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        properties.setProperty("mail.imap.ssl.enable", "true");
        properties.setProperty("mail.imap.ssl.trust", "localhost");
        test(properties, true);
    }

    /**
     * Test write timeouts with a custom SSL socket factory.
     */
    @Test
    public void testSSLSocketFactory() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.ssl.checkserveridentity", "false");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        properties.setProperty("mail.imap.ssl.enable", "true");
        // TestSSLSocketFactory always trusts "localhost"; setting
        // this property would cause MailSSLSocketFactory to be used instead
        // of TestSSLSocketFactory, which we don't want.
        //properties.setProperty("mail.imap.ssl.trust", "localhost");
        TestSSLSocketFactory sf = new TestSSLSocketFactory();
        properties.put("mail.imap.ssl.socketFactory", sf);
        // don't fall back to non-SSL
        properties.setProperty("mail.imap.socketFactory.fallback", "false");
        test(properties, true);
        // make sure our socket factory was actually used
        assertTrue(sf.getSocketWrapped() || sf.getSocketCreated());
    }

    /**
     * Test that WriteTimeoutSocket overrides all methods from Socket.
     * XXX - this is kind of hacky since it depends on Method.toString
     */
    @Test
    public void testOverrides() throws Exception {
        Set<String> socketMethods = new HashSet<>();
        Method[] m = java.net.Socket.class.getDeclaredMethods();
        String className = java.net.Socket.class.getName() + ".";
        for (int i = 0; i < m.length; i++) {
            if (Modifier.isPublic(m[i].getModifiers()) &&
                    !Modifier.isStatic(m[i].getModifiers())) {
                String name = m[i].toString().
                        replace("synchronized ", "").
                        replace(className, "");
                socketMethods.add(name);
            }
        }
        Set<String> wtsocketMethods = new HashSet<>();
        m = WriteTimeoutSocket.class.getDeclaredMethods();
        className = WriteTimeoutSocket.class.getName() + ".";
        for (int i = 0; i < m.length; i++) {
            if (Modifier.isPublic(m[i].getModifiers())) {
                String name = m[i].toString().
                        replace("synchronized ", "").
                        replace(className, "");
                socketMethods.remove(name);
            }
        }
        for (String s : socketMethods)
            System.out.println("WriteTimeoutSocket did not override: " + s);
        assertTrue(socketMethods.isEmpty());
    }

    private void test(Properties properties, boolean isSSL) throws Exception {
        TestServer server = null;
        try {
            final TimeoutHandler handler = new TimeoutHandler();
            server = new TestServer(handler, isSSL);
            server.start();

            properties.setProperty("mail.imap.port", String.valueOf(server.getPort()));
            final Session session = Session.getInstance(properties);
            //session.setDebug(true);

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom("test@example.com");
            msg.setSubject("test");
            final int size = 8192000;    // enough data to fill network buffers
            byte[] part = new byte[size];
            for (int i = 0; i < size; i++) {
                int j = i % 64;
                if (j == 62)
                    part[i] = (byte) '\r';
                else if (j == 63)
                    part[i] = (byte) '\n';
                else
                    part[i] = (byte) data.charAt((j + i / 64) % 62);
            }
            msg.setDataHandler(new DataHandler(
                    new ByteArrayDataSource(part, "text/plain")));
            msg.saveChanges();

            final Store store = session.getStore("imap");
            try {
                store.connect("test", "test");
                final Folder f = store.getFolder("test");
                f.appendMessages(new Message[]{msg});
                fail("No timeout");
            } catch (StoreClosedException scex) {
                // success!
            } finally {
                store.close();
            }
        } finally {
            if (server != null) {
                server.quit();
            }
        }
    }

    @Test
    public void testFileDescriptor$() throws Exception {
        try (PublicFileSocket ps = new PublicFileSocket()) {
            assertNotNull(ps.getFileDescriptor$());
        }

        testFileDescriptor$(new PublicFileSocket());
        testFileDescriptor$(new PublicFileSocket1of3());
        testFileDescriptor$(new PublicFileSocket2of3());
        testFileDescriptor$(new PublicFileSocket3of3());
    }

    @Test
    public void testExternalSesIsBeingUsed() throws Exception {
        final Properties properties = new Properties();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        scheduledExecutorServices.add(ses);
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        properties.put("mail.imap.executor.writetimeout", ses);

        test(properties, false);

        assertFalse(ses.isShutdownNowMethodCalled);
        assertTrue(ses.isScheduleMethodCalled);
    }

    @Test
    public void testRejectedExecutionException() throws Exception {
        final Properties properties = new Properties();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        scheduledExecutorServices.add(ses);
        ses.shutdownNow();
        properties.setProperty("mail.imap.host", "localhost");
        properties.setProperty("mail.imap.writetimeout", "" + TIMEOUT);
        properties.put("mail.imap.executor.writetimeout", ses);

        try {
            test(properties, false);
            fail("Expected IOException wasn't thrown ");
        } catch (MessagingException mex) {
            Throwable cause = mex.getCause();
            assertTrue(cause instanceof ConnectionException);
            assertTrue(cause.getMessage().contains("java.io.IOException: Write aborted due to timeout not enforced"));
        }
    }

    @Test
    public void testCloseOneSocketDoesntImpactAnother() throws Exception {
        WriteTimeoutSocket wts1 = new WriteTimeoutSocket(new Socket(), 10000);
        WriteTimeoutSocket wts2 = new WriteTimeoutSocket(new Socket(), 10000);
        writeTimeoutSockets.add(wts1);
        writeTimeoutSockets.add(wts2);

        ScheduledExecutorService ses1 =
                (ScheduledExecutorService) ReflectionUtil.getPrivateFieldValue(wts1, "ses");
        ScheduledExecutorService ses2 =
                (ScheduledExecutorService) ReflectionUtil.getPrivateFieldValue(wts2, "ses");
        scheduledExecutorServices.add(ses1);
        scheduledExecutorServices.add(ses2);

        assertFalse(ses1.isTerminated());
        assertFalse(ses2.isTerminated());

        wts1.close();
        assertTrue(ses1.isTerminated());
        assertFalse(ses2.isTerminated());
    }

    @Test
    public void testDefaultSesConstructor1() throws Exception {
        WriteTimeoutSocket writeTimeoutSocket = new WriteTimeoutSocket(new Socket(), 10000);
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertFalse((Boolean) isExternalSes);
    }

    @Test
    public void testDefaultSesConstructor2() throws Exception {
        WriteTimeoutSocket writeTimeoutSocket = new WriteTimeoutSocket(10000);
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertFalse((Boolean) isExternalSes);
    }

    @Test
    public void testDefaultSesConstructor3() throws Exception {
        testServer = getActiveTestServer(false);

        WriteTimeoutSocket writeTimeoutSocket =
                new WriteTimeoutSocket("localhost", testServer.getPort(), 10000);
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertFalse((Boolean) isExternalSes);
    }

    @Test
    public void testDefaultSesConstructor4() throws Exception {
        testServer = getActiveTestServer(false);
        WriteTimeoutSocket writeTimeoutSocket =
                new WriteTimeoutSocket(InetAddress.getLocalHost(), testServer.getPort(), 10000);
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertFalse((Boolean) isExternalSes);
    }

    @Test
    public void testDefaultSesConstructor5() throws Exception {
        testServer = getActiveTestServer(false);

        WriteTimeoutSocket writeTimeoutSocket =
                new WriteTimeoutSocket("localhost", testServer.getPort(),
                        (InetAddress) null, getRandomFreePort(), 10000);
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertFalse((Boolean) isExternalSes);
    }

    @Test
    public void testDefaultSesConstructor6() throws Exception {
        testServer = getActiveTestServer(false);

        WriteTimeoutSocket writeTimeoutSocket =
                new WriteTimeoutSocket(InetAddress.getByName("localhost"), testServer.getPort(),
                        (InetAddress) null, getRandomFreePort(), 10000);
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertFalse((Boolean) isExternalSes);
    }

    @Test
    public void testExternalSesConstructor7() throws Exception {
        WriteTimeoutSocket writeTimeoutSocket =
                new WriteTimeoutSocket(new Socket(), 10000, new ScheduledThreadPoolExecutor(1));
        writeTimeoutSockets.add(writeTimeoutSocket);

        Object isExternalSes = ReflectionUtil.getPrivateFieldValue(writeTimeoutSocket, "isExternalSes");
        assertTrue(isExternalSes instanceof Boolean);
        assertTrue((Boolean) isExternalSes);
    }

    @Test
    public void testExternalSesOnClose() throws Exception {
        Socket socket = new Socket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        WriteTimeoutSocket writeTimeoutSocket = new WriteTimeoutSocket(socket, 10000, ses);
        writeTimeoutSockets.add(writeTimeoutSocket);
        writeTimeoutSocket.close();

        assertFalse(ses.isShutdownNowMethodCalled);
    }

    @Test
    public void testDefaultSesOnClose() throws Exception {
        Socket socket = new Socket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        WriteTimeoutSocket writeTimeoutSocket = new WriteTimeoutSocket(socket, 10000);
        writeTimeoutSockets.add(writeTimeoutSocket);
        ReflectionUtil.setFieldValue(writeTimeoutSocket, "ses", ses);

        writeTimeoutSocket.close();

        assertTrue(ses.isShutdownNowMethodCalled);
    }

    private void testFileDescriptor$(Socket s) throws Exception {
        try (WriteTimeoutSocket ws = new WriteTimeoutSocket(s, 1000)) {
            assertNotNull(ws.getFileDescriptor$());
        } finally {
            s.close();
        }
    }

    private TestServer getActiveTestServer(boolean isSSL) {
        TestServer server = null;
        try {
            final TimeoutHandler handler = new TimeoutHandler();
            server = new TestServer(handler, isSSL);
            server.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return server;
    }

    private int getRandomFreePort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        int freePort = serverSocket.getLocalPort();
        serverSocket.close();

        return freePort;
    }

    private void close(ScheduledExecutorService ses) {
        if (ses.isTerminated()) {
            return;
        }

        try {
            ses.shutdownNow();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private void close(TestServer testServer) {
        if (testServer == null || !testServer.isAlive()) {
            return;
        }

        try {
            testServer.quit();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private void close(WriteTimeoutSocket writeTimeoutSocket) {
        if (writeTimeoutSocket.isClosed()) {
            return;
        }

        try {
            writeTimeoutSocket.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private static class PublicFileSocket extends Socket {
        public FileDescriptor getFileDescriptor$() {
            return new FileDescriptor();
        }
    }

    private static class PublicFileSocket1of3 extends PublicFileSocket {
    }

    private static class PublicFileSocket2of3 extends PublicFileSocket1of3 {
    }

    private static class PublicFileSocket3of3 extends PublicFileSocket2of3 {
    }

    /**
     * Custom handler.
     */
    private static final class TimeoutHandler extends IMAPHandler {
        @Override
        protected void collectMessage(int bytes) throws IOException {
            try {
                // allow plenty of time for even slow machines to time out
                Thread.sleep(TIMEOUT * 20);
            } catch (InterruptedException ex) {
            }
            super.collectMessage(bytes);
        }

        @Override
        public void list(String line) throws IOException {
            untagged("LIST () \"/\" test");
            ok();
        }
    }

    private static final class CustomScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        private boolean isShutdownNowMethodCalled;
        private boolean isScheduleMethodCalled;

        public CustomScheduledThreadPoolExecutor(int corePoolSize) {
            super(corePoolSize);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                               long delay, TimeUnit unit) {
            isScheduleMethodCalled = true;
            return super.schedule(callable, delay, unit);
        }

        @Override
        public List<Runnable> shutdownNow() {
            isShutdownNowMethodCalled = true;
            return super.shutdownNow();
        }
    }
}
