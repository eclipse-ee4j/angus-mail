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

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.util.LineInputStream;
import org.eclipse.angus.mail.util.WriteTimeoutSocket;
import org.junit.Assume;
import org.junit.Test;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Uses SMTPTransport and SocketFetcher, including their actual timeout/TLS
 * layering. Key-update frequency may be increased in an isolated test JVM.
 * The optional valid-command pressure cases require an external process
 * watchdog: a stalled TLS write need not respond to a JUnit interruption.
 */
public class SMTPPipeliningTlsTest {
    @Test(timeout = 30000)
    public void testPlainEnvelopeAndReuse() throws Exception {
        exchange(0, false);
    }

    @Test(timeout = 30000)
    public void testStartTlsEnvelopeAndReuse() throws Exception {
        exchange(1, false);
    }

    @Test(timeout = 30000)
    public void testImplicitTlsEnvelopeAndReuse() throws Exception {
        exchange(2, false);
    }

    @Test(timeout = 30000)
    public void testPlainEarlyReplyStress() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.tls.stress"));
        exchange(0, true);
    }

    @Test(timeout = 30000)
    public void testStartTlsEarlyReplyStress() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.tls.stress"));
        exchange(1, true);
    }

    @Test(timeout = 30000)
    public void testImplicitTlsEarlyReplyStress() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.tls.stress"));
        exchange(2, true);
    }

    @Test(timeout = 120000)
    public void testPlainValidCommandPressure() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.tls.pressure"));
        exchange(0, false, true);
    }

    @Test(timeout = 120000)
    public void testStartTlsValidCommandPressure() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.tls.pressure"));
        exchange(1, false, true);
    }

    @Test(timeout = 120000)
    public void testImplicitTlsValidCommandPressure() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("angus.pipelining.tls.pressure"));
        exchange(2, false, true);
    }

    private void exchange(int mode, boolean stress) throws Exception {
        exchange(mode, stress, false);
    }

    private void exchange(int mode, boolean stress, boolean pressure) throws Exception {
        int writeTimeout = Integer.getInteger("angus.pipelining.tls.writeTimeout",
                pressure ? -1 : 1500);
        boolean cancel = Boolean.getBoolean("angus.pipelining.tls.cancelRaw");
        boolean pipelining = Boolean.parseBoolean(System.getProperty(
                "angus.pipelining.tls.enabled", "true"));
        int sends = pressure ? Integer.getInteger("angus.pipelining.tls.sends", 3) : 3;
        try (Peer peer = new Peer(mode, stress, pressure)) {
            String protocol = mode == 2 ? "smtps" : "smtp";
            String prefix = "mail." + protocol;
            Properties props = new Properties();
            props.setProperty(prefix + ".pipelining", String.valueOf(pipelining));
            props.setProperty(prefix + ".localhost", "localhost");
            props.setProperty(prefix + ".connectiontimeout", "5000");
            props.setProperty(prefix + ".timeout", "10000");
            props.setProperty(prefix + ".writetimeout", String.valueOf(writeTimeout));
            props.setProperty(prefix + ".socketFactory.fallback", "false");
            props.setProperty(prefix + ".ssl.checkserveridentity", "true");
            props.setProperty(prefix + ".ssl.protocols", "TLSv1.3");
            props.setProperty(prefix + ".quitwait", "false");
            props.put(prefix + ".socketFactory", peer.clientFactory);
            props.put(prefix + ".ssl.socketFactory", peer.tlsFactory);
            if (mode == 1)
                props.setProperty(prefix + ".starttls.required", "true");
            if (stress)
                props.setProperty(prefix + ".mailextension", "x".repeat(4 * 1024 * 1024));
            if (pressure) {
                props.setProperty("mail.from", "sender@example.test");
                assertEquals(254, longAddress(0).length());
                props.setProperty(prefix + ".from", envelopeAddress(0, peer.longCommands));
                props.setProperty(prefix + ".dsn.notify", "SUCCESS,FAILURE,DELAY");
                props.setProperty(prefix + ".dsn.ret", "HDRS");
                props.setProperty(prefix + ".mailextension", "ENVID=" + "e".repeat(90));
            }
            Session session = Session.getInstance(props);
            SMTPTransport transport = (SMTPTransport) session.getTransport(protocol);
            transport.connect("localhost", peer.listener.getLocalPort(), null, null);
            assertTrue(transport.supportsExtension("PIPELINING"));
            if (mode != 0 && writeTimeout > 0)
                assertTrue(peer.clientFactory.timeoutBelowTls.get());
            if (pressure) {
                Socket client = peer.clientFactory.raw.get();
                Socket server = peer.rawServer.get();
                assertEquals(mode == 0 ? "plain" : "TLSv1.3", peer.negotiated.get());
                System.out.println("SMTP-TLS pressure enabled=" + pipelining +
                        " clientSend=" + client.getSendBufferSize() +
                        " clientReceive=" + client.getReceiveBufferSize() +
                        " serverSend=" + server.getSendBufferSize() +
                        " serverReceive=" + server.getReceiveBufferSize() +
                        " mailReply=" + peer.mailReplyBytes +
                        " recipientReply=" + peer.recipientReplyBytes +
                        " recipients=" + peer.recipientCount +
                        " longCommands=" + peer.longCommands);
            }
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress("sender@example.test"));
            message.setText("body\r\n" + ("content ".repeat(10) + "\r\n").repeat(300));
            message.saveChanges();
            Address[] recipients = new Address[peer.recipientCount];
            for (int i = 0; i < recipients.length; i++)
                recipients[i] = new InternetAddress(pressure ?
                        envelopeAddress(i + 1, peer.longCommands) :
                        "recipient" + i + "@" + "a".repeat(60) + "." +
                        "b".repeat(60) + ".example.test");
            Future<?> canceller = null;
            if (cancel) {
                canceller = peer.executor.submit(() -> {
                    assertTrue(peer.earlyReply.await(5, TimeUnit.SECONDS));
                    Thread.sleep(1500);
                    peer.clientFactory.raw.get().close();
                    return null;
                });
            }
            long start = System.nanoTime();
            try {
                for (int i = 0; i < (stress ? 1 : sends); i++) {
                    if (pressure)
                        System.out.println("SMTP-TLS send started=" + (i + 1));
                    transport.sendMessage(message, recipients);
                }
                if (stress)
                    fail("Early-reply stress should fail without submitting content");
                assertEquals(sends, peer.messages.get());
                assertEquals(peer.recipientCount * sends, peer.recipients.get());
                assertTrue(transport.isConnected());
            } catch (MessagingException ex) {
                if (!stress) {
                    if (pressure) {
                        assertFalse(transport.isConnected());
                        assertEquals("No content from the failed attempt",
                                peer.messages.get(), peer.dataCommands.get());
                        assertNoReader();
                        System.out.println("SMTP-TLS failed connection closed; reader stopped; " +
                                "DATA commands=" + peer.dataCommands.get());
                    }
                    throw ex;
                }
                assertFalse(transport.isConnected());
                assertEquals(0, peer.messages.get());
                assertNotNull(ex.getCause());
                System.out.println("SMTP-TLS failure=" + ex.getClass().getSimpleName() +
                        " cause=" + ex.getCause().getClass().getSimpleName());
            } finally {
                System.out.println("SMTP-TLS mode=" + mode + " stress=" + stress +
                        " pressure=" + pressure + " enabled=" + pipelining +
                        " writeTimeout=" + writeTimeout + " cancel=" + cancel +
                        " messages=" + peer.messages.get() +
                        " recipients=" + peer.recipients.get() +
                        " maxCommandBytes=" + peer.maxCommandBytes.get() +
                        " protocol=" + peer.negotiated.get() + " elapsedMs=" +
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
            if (canceller != null)
                canceller.get(5, TimeUnit.SECONDS);
            assertNoReader();
            if (transport.isConnected())
                transport.close();
            peer.worker.get(5, TimeUnit.SECONDS);
        }
    }

    private static void assertNoReader() {
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread ->
                thread.isAlive() && thread.getName().equals("Angus SMTP envelope reader")));
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket listener;
        final ExecutorService executor;
        final SSLContext context;
        final CapturingFactory clientFactory = new CapturingFactory();
        final SSLSocketFactory tlsFactory;
        final AtomicReference<Socket> rawServer = new AtomicReference<>();
        final AtomicReference<String> negotiated = new AtomicReference<>("plain");
        final AtomicInteger messages = new AtomicInteger();
        final AtomicInteger dataCommands = new AtomicInteger();
        final AtomicInteger recipients = new AtomicInteger();
        final AtomicInteger maxCommandBytes = new AtomicInteger();
        final CountDownLatch earlyReply = new CountDownLatch(1);
        final Future<?> worker;
        final boolean pressure;
        final int mailReplyBytes;
        final int recipientReplyBytes;
        final int recipientCount;
        final boolean longCommands;

        Peer(int mode, boolean stress, boolean pressure) throws Exception {
            this.pressure = pressure;
            mailReplyBytes = pressure ? Integer.getInteger(
                    "angus.pipelining.tls.mailReplyBytes", 65536) : 65536;
            recipientReplyBytes = pressure ? Integer.getInteger(
                    "angus.pipelining.tls.recipientReplyBytes", 512) : 0;
            recipientCount = pressure ? Integer.getInteger(
                    "angus.pipelining.tls.recipients", 100) : 100;
            longCommands = pressure && Boolean.parseBoolean(System.getProperty(
                    "angus.pipelining.tls.longCommands", "true"));
            assertTrue(recipientCount > 0 && recipientCount <= 1000);
            KeyStore store = KeyStore.getInstance("JKS");
            try (InputStream in = SMTPPipeliningTlsTest.class.getResourceAsStream("pipelining.jks")) {
                store.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509");
            keys.init(store, "changeit".toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance("SunX509");
            trust.init(store);
            context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            tlsFactory = new TrackingTlsFactory(context.getSocketFactory(), clientFactory);
            listener = new ServerSocket();
            int bufferBytes = Integer.getInteger("angus.pipelining.tls.bufferBytes", 8192);
            if (bufferBytes > 0)
                listener.setReceiveBufferSize(bufferBytes);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            executor = Executors.newFixedThreadPool(2);
            worker = executor.submit(() -> {
                try {
                    serve(mode, stress);
                } catch (IOException ex) {
                    if (!stress)
                        throw ex;
                }
                return null;
            });
        }

        private void serve(int mode, boolean stress) throws Exception {
            Socket socket = listener.accept();
            rawServer.set(socket);
            configure(socket);
            if (mode == 2)
                socket = tls(socket);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            LineInputStream lines = new LineInputStream(input);
            reply(output, "220 localhost");
            assertTrue(lines.readLine().startsWith("EHLO "));
            if (mode == 1) {
                reply(output, "250-localhost\r\n250-PIPELINING\r\n250 STARTTLS");
                assertEquals("STARTTLS", lines.readLine());
                reply(output, "220 ready");
                socket = tls(socket);
                input = socket.getInputStream();
                output = socket.getOutputStream();
                lines = new LineInputStream(input);
                assertTrue(lines.readLine().startsWith("EHLO "));
            }
            reply(output, "250-localhost\r\n" + (pressure ? "250-DSN\r\n" : "") +
                    "250 PIPELINING");
            if (stress) {
                // Deliberately adversarial: reply before consuming an oversized
                // command. This is not an ordinary valid SMTP envelope.
                assertEquals('M', input.read());
                earlyReply.countDown();
                multiline(output, 4 * 1024 * 1024);
                while (input.read() != -1) {
                    // Keep the connection alive until timeout/cancellation.
                }
                return;
            }
            String line;
            while ((line = lines.readLine()) != null) {
                if (pressure) {
                    assertTrue("SMTP command exceeds 512 octets", line.length() + 2 <= 512);
                    assertTrue("Non-ASCII command", line.chars().allMatch(c -> c < 128));
                    maxCommandBytes.accumulateAndGet(line.length() + 2, Math::max);
                }
                if (line.startsWith("MAIL ")) {
                    if (pressure) {
                        assertEquals("MAIL FROM:<" + envelopeAddress(0, longCommands) +
                                "> RET=HDRS ENVID=" + "e".repeat(90), line);
                        Thread.sleep(Integer.getInteger(
                                "angus.pipelining.tls.replyDelayMillis", 0));
                        System.out.println("SMTP-TLS valid MAIL bytes=" + (line.length() + 2));
                    }
                    // Legal multiline reply large enough to cross the
                    // diagnostic key-update threshold several times.
                    multiline(output, mailReplyBytes);
                } else if (line.startsWith("RCPT ")) {
                    if (pressure)
                        assertEquals("RCPT TO:<" + envelopeAddress(
                                recipients.get() % recipientCount + 1, longCommands) +
                                "> NOTIFY=SUCCESS,FAILURE,DELAY", line);
                    recipients.incrementAndGet();
                    if (pressure && recipients.get() % 16 == 0)
                        System.out.println("SMTP-TLS valid RCPT count=" + recipients.get());
                    if (recipientReplyBytes > 0)
                        multiline(output, recipientReplyBytes);
                    else
                        reply(output, "250 recipient");
                } else if (line.equals("DATA")) {
                    dataCommands.incrementAndGet();
                    if (pressure)
                        System.out.println("SMTP-TLS DATA count=" + dataCommands.get());
                    reply(output, "354 content");
                    do {
                        line = lines.readLine();
                        assertNotNull(line);
                    } while (!line.equals("."));
                    messages.incrementAndGet();
                    reply(output, "250 queued");
                } else if (line.equals("QUIT")) {
                    reply(output, "221 bye");
                    return;
                } else if (line.equals("NOOP")) {
                    reply(output, "250 ok");
                } else {
                    fail("Unexpected command " + line);
                }
            }
        }

        private Socket tls(Socket raw) throws IOException {
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(
                    raw, "localhost", raw.getPort(), true);
            socket.setUseClientMode(false);
            socket.setEnabledProtocols(new String[]{"TLSv1.3"});
            socket.startHandshake();
            negotiated.set(socket.getSession().getProtocol());
            return socket;
        }

        private void multiline(OutputStream output, int bytes) throws IOException {
            byte[] line = ("250-" + "r".repeat(pressure ? 506 : 480) + "\r\n").
                    getBytes(StandardCharsets.US_ASCII);
            assertTrue(line.length <= 512);
            for (int written = 0; written < bytes; written += line.length)
                output.write(line);
            reply(output, "250 accepted");
        }

        private void reply(OutputStream output, String line) throws IOException {
            output.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            // Test cleanup owns raw handles and must not wait on TLS close.
            try {
                if (clientFactory.raw.get() != null)
                    clientFactory.raw.get().close();
            } finally {
                try {
                    if (rawServer.get() != null)
                        rawServer.get().close();
                } finally {
                    try {
                        listener.close();
                    } finally {
                        executor.shutdownNow();
                        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                    }
                }
            }
        }
    }

    private static void configure(Socket socket) throws IOException {
        int bufferBytes = Integer.getInteger("angus.pipelining.tls.bufferBytes", 8192);
        if (bufferBytes > 0) {
            socket.setSendBufferSize(bufferBytes);
            socket.setReceiveBufferSize(bufferBytes);
        }
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
    }

    /** A 254-octet mailbox: 64 local-part octets and legal DNS label lengths. */
    private static String longAddress(int index) {
        String suffix = String.valueOf(index);
        return "r".repeat(64 - suffix.length()) + suffix + "@" +
                "a".repeat(63) + "." + "b".repeat(63) + "." +
                "c".repeat(48) + ".example.test";
    }

    private static String envelopeAddress(int index, boolean longCommands) {
        return longCommands ? longAddress(index) :
                (index == 0 ? "sender" : "recipient" + index) + "@example.test";
    }

    /** Only chooses small raw buffers; Angus still builds all wrappers. */
    private static final class CapturingFactory extends SocketFactory {
        final AtomicReference<Socket> raw = new AtomicReference<>();
        final AtomicBoolean timeoutBelowTls = new AtomicBoolean();

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = new Socket();
            configure(socket);
            raw.set(socket);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Socket createSocket(String host, int port,
                                   InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Socket createSocket(InetAddress host, int port,
                                   InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }
    }

    /** Records the handle below TLS without changing Angus's wrapper order. */
    private static final class TrackingTlsFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final CapturingFactory capture;

        TrackingTlsFactory(SSLSocketFactory delegate, CapturingFactory capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port,
                                   boolean autoClose) throws IOException {
            configure(socket);
            capture.timeoutBelowTls.set(socket instanceof WriteTimeoutSocket);
            capture.raw.compareAndSet(null, socket);
            return delegate.createSocket(socket, host, port, autoClose);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(String host, int port,
                                   InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port,
                                   InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }
    }
}
