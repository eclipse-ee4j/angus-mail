/*
 * Copyright (c) 2025 Oracle and/or its affiliates. All rights reserved.
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


package org.eclipse.angus.mail.smtps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

import org.eclipse.angus.mail.test.TestServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;

public class SmtpStartTlsTrustTest {

    private static final int PORT = 16025;
    private static final String HOST_FILE_KEY = "jdk.net.hosts.file";
    private static String hostsFile;
    private ExecutorService es;

    @BeforeClass
    public static void beforeClass() {
        hostsFile = System.getProperty(HOST_FILE_KEY);
        if (hostsFile != null) {
            System.clearProperty(HOST_FILE_KEY);
        }
        System.setProperty(HOST_FILE_KEY, SmtpStartTlsTrustTest.class.getResource("/test-hosts").getPath());
    }

    @AfterClass
    public static void afterClass() {
        if (hostsFile != null) {
            System.setProperty(HOST_FILE_KEY, hostsFile);
        }
    }

    @Before
    public void before() throws Exception {
        es = Executors.newSingleThreadExecutor();
        assertEquals("127.0.0.1", InetAddress.getByName("mailtest.local").getHostAddress());
        es.execute(() -> {
            try {
                KeyStore ks = KeyStore.getInstance("JKS");
                ks.load(TestServer.class.getResourceAsStream("keystore.jks"), "changeit".toCharArray());

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, "changeit".toCharArray());

                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(kmf.getKeyManagers(), null, new SecureRandom());

                SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
                try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT)) {
                    serverSocket.setNeedClientAuth(false);
                    serverSocket.setWantClientAuth(false);
                    try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                        socket.startHandshake();
                        BufferedWriter out = new BufferedWriter(
                                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        out.write("220 mailtest.local Simple SMTP Ready\r\n");
                        out.flush();
                        String line;
                        while ((line = in.readLine()) != null) {
                            if (line.toUpperCase(Locale.ROOT).startsWith("EHLO") ||
                                line.toUpperCase(Locale.ROOT).startsWith("HELO")) {
                                out.write("250-mailtest.local Hello\r\n");
                                out.write("250 STARTTLS\r\n");
                                out.flush();
                            } else if (line.equalsIgnoreCase("QUIT")) {
                                out.write("221 Bye\r\n");
                                out.flush();
                                break;
                            } else {
                                out.write("250 OK\r\n");
                                out.flush();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        Thread.sleep(1000);
    }

    @After
    public void after() throws InterruptedException {
        es.shutdown();
        assertTrue(es.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testTrustAllHostsDisableServerIdentity() throws Exception {
        Properties props = new Properties();
        props.put("mail.smtps.host", "mailtest.local");
        props.put("mail.smtps.port", PORT);
        props.put("mail.smtps.starttls.enable", "true");
        props.put("mail.smtps.ssl.trust", "*");
        props.put("mail.smtps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        Transport transport = session.getTransport("smtps");
        transport.connect();
        transport.close();
    }

    @Test
    public void testTrustAllHostsEnableServerIdentity() throws Exception {
        Properties props = new Properties();
        props.put("mail.smtps.host", "mailtest.local");
        props.put("mail.smtps.port", PORT);
        props.put("mail.smtps.starttls.enable", "true");
        props.put("mail.smtps.ssl.trust", "*");
        // It will check that trust=*, and it will not fail
        props.put("mail.smtps.ssl.checkserveridentity", "true");
        Session session = Session.getInstance(props);
        Transport transport = session.getTransport("smtps");
        transport.connect();
        transport.close();
    }

    @Test
    public void testHostnameVerificationFails() throws Exception {
        Properties props = new Properties();
        props.put("mail.smtps.host", "mailtest.local");
        props.put("mail.smtps.port", PORT);
        props.put("mail.smtps.starttls.enable", "true");
        props.put("mail.smtps.ssl.trust", "other.domain");
        props.put("mail.smtps.ssl.checkserveridentity", "true");

        Session session = Session.getInstance(props);

        try {
            Transport transport = session.getTransport("smtps");
            transport.connect();
            fail("Expects exception");
        } catch (MessagingException e) {
            e.printStackTrace();
            assertEquals("Server is not trusted: mailtest.local", e.getCause().getMessage());
        }
    }

    @Test
    public void testHostnameVerificationWorks() throws Exception {
        Properties props = new Properties();
        props.put("mail.smtps.host", "mailtest.local");
        props.put("mail.smtps.port", PORT);
        props.put("mail.smtps.starttls.enable", "true");
        props.put("mail.smtps.ssl.trust", "other.domain");
        // Doesn't check host
        props.put("mail.smtps.ssl.checkserveridentity", "false");

        Session session = Session.getInstance(props);
        Transport transport = session.getTransport("smtps");
        transport.connect();
        transport.close();
    }
}