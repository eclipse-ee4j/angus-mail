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
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.util.LineInputStream;
import org.eclipse.angus.mail.util.WriteTimeoutSocket;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-isolated TLS scheduling experiment, launched by SMTPSSLEngineTest.
 * Compares the integrated Transport with an independent test client and the
 * legacy socket path. In the engine paths the caller owns SMTP state;
 * a separate executor handles delegated TLS tasks only.
 */
public final class SMTPSSLEngineProbe {
    private static final String AUTH = Base64.getEncoder().encodeToString(
            "\0fixture\0fixture-password".getBytes(StandardCharsets.US_ASCII));
    private static final int SOCKET_BUFFER = Integer.getInteger("probe.socketBuffer", 512);
    private static final int RECIPIENTS = Integer.getInteger("probe.recipients", 100);
    private static final int MESSAGES = Integer.getInteger("probe.messages", 2);
    private static final int MAIL_REPLY = Integer.getInteger("probe.mailReply", 32768);
    private static final int RCPT_REPLY = Integer.getInteger("probe.rcptReply", 512);
    private static final int IO_CHUNK = Integer.getInteger("probe.ioChunk", 65536);
    private static final int BUFFER_BOUND = 65536;
    private static final int APPLICATION_HIGH_WATER = 8192;
    private static final byte[] EMPTY = new byte[0];

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Expected client mode, implicit TLS flag and scenario");
        String mode = args[0];
        boolean implicit = Boolean.parseBoolean(args[1]);
        String scenario = args[2];
        check(List.of("engine", "transport", "angus", "sequential").contains(mode), "Unknown mode");
        check(List.of("pressure", "disconnect", "silent", "post-content", "wrong-host", "untrusted")
                .contains(scenario), "Unknown scenario");
        if (mode.equals("angus")) {
            // Upstream ignores the unknown pipelining property. Refuse to label
            // that sequential execution as the reader-thread comparison.
            SMTPTransport.class.getDeclaredMethod("sendEnvelope");
            SMTPTransport.class.getDeclaredMethod("isPipeliningCompatible");
        }
        System.out.println("ENGINE-RUNTIME java=" + System.getProperty("java.runtime.version") +
                " provider=" + SMTPTransport.class.getProtectionDomain().getCodeSource().getLocation());
        long started = System.nanoTime();
        MimeMessage message = message();
        byte[] content = content(message);
        String expectedHash = hash(content);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicBoolean monitoring = new AtomicBoolean(true);
        String outcome = "ERROR";
        Throwable failure = null;
        EngineClient engine = null;
        int completed;
        int data;
        try (Peer peer = new Peer(implicit, scenario, expectedHash)) {
            Future<?> monitor = peer.monitor.submit(() -> {
                while (monitoring.get()) {
                    Thread.sleep(200);
                    if (monitoring.get() && stalledSnapshot()) {
                        snapshots.incrementAndGet();
                        System.out.println("ENGINE-STALL-SNAPSHOT");
                    }
                }
                return null;
            });
            try {
                if (mode.equals("engine")) {
                    SSLContext context = scenario.equals("untrusted") ? untrustedContext() : peer.context;
                    String host = scenario.equals("wrong-host") ? "wrong.example.test" : "localhost";
                    engine = new EngineClient(context, peer.listener.getLocalPort(), host);
                    engine.send(implicit, content, scenario);
                } else {
                    check(mode.equals("transport") || scenario.equals("pressure"),
                            "Legacy arms compare the pressure scenario only");
                    sendWithAngus(peer, message, !mode.equals("sequential"), mode.equals("transport"));
                }
                check(scenario.equals("pressure"), "Negative test unexpectedly completed");
                check(peer.completed.get() == MESSAGES, "Missing or duplicate message");
                check(peer.data.get() == MESSAGES, "Unexpected DATA count");
                check(peer.auth.get() == 1 && peer.ehlo.get() == (implicit ? 1 : 2), "Negotiation changed");
                outcome = "PASS";
            } catch (Throwable problem) {
                failure = problem;
                if (!scenario.equals("pressure")) {
                    boolean accepted;
                    if (scenario.equals("silent"))
                        accepted = hasCause(problem, SocketTimeoutException.class) && peer.data.get() == 0;
                    else if (scenario.equals("wrong-host") || scenario.equals("untrusted"))
                        accepted = hasCause(problem, SSLException.class) && peer.auth.get() == 0;
                    else if (scenario.equals("post-content"))
                        accepted = hasCause(problem, IOException.class) && peer.completed.get() == 1;
                    else
                        accepted = hasCause(problem, IOException.class) && peer.data.get() == 0;
                    if (accepted)
                        outcome = "EXPECTED_FAILURE";
                } else if (mode.equals("angus") && snapshots.get() >= 2 &&
                        peer.completed.get() < MESSAGES) {
                    outcome = "STALL";
                }
            } finally {
                if (engine != null)
                    engine.close();
                monitoring.set(false);
                monitor.get(3, TimeUnit.SECONDS);
                peer.stop();
            }
            if (peer.problem != null && !(peer.problem instanceof IOException && !outcome.equals("PASS")))
                throw new AssertionError("Peer failed", peer.problem);
            completed = peer.completed.get();
            data = peer.data.get();
            check(peer.connections.get() == 1, "Experiment reconnected unexpectedly");
            checkNoReader();
            if (engine != null)
                System.out.println(engine.metrics());
            System.out.println("ENGINE-RESULT outcome=" + outcome + " mode=" + mode +
                    " implicit=" + implicit + " scenario=" + scenario + " completed=" + completed +
                    " data=" + data + " connections=" + peer.connections.get() +
                    " stallSnapshots=" + snapshots.get() + " mailReply=" + wireReplySize(MAIL_REPLY) +
                    " firstRcptReply=" + wireReplySize(RCPT_REPLY) +
                    " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (failure != null)
                failure.printStackTrace(System.err);
            if (outcome.equals("ERROR"))
                throw new AssertionError("Unexpected probe failure", failure);
        }
    }

    private static void sendWithAngus(Peer peer, MimeMessage message, boolean pipeline,
            boolean integrated) throws Exception {
        String protocol = peer.implicit ? "smtps" : "smtp";
        String prefix = "mail." + protocol;
        Properties p = new Properties();
        p.setProperty(prefix + ".from", address(0));
        p.setProperty(prefix + ".localhost", "localhost");
        p.setProperty(prefix + ".pipelining", Boolean.toString(pipeline));
        p.setProperty(prefix + ".auth", "true");
        p.setProperty(prefix + ".auth.mechanisms", "PLAIN");
        p.setProperty(prefix + ".sasl.authorizationid", "");
        p.setProperty(prefix + ".connectiontimeout", "5000");
        p.setProperty(prefix + ".timeout", "8000");
        p.setProperty(prefix + ".writetimeout", "1500");
        p.put(prefix + ".executor.writetimeout", peer.timeouts);
        p.setProperty(prefix + ".socketFactory.fallback", "false");
        p.setProperty(prefix + ".ssl.checkserveridentity", "true");
        p.setProperty(prefix + ".ssl.protocols", "TLSv1.3");
        p.setProperty(prefix + ".dsn.notify", "SUCCESS,FAILURE,DELAY");
        p.setProperty(prefix + ".dsn.ret", "HDRS");
        p.setProperty(prefix + ".mailextension", "ENVID=" + "e".repeat(90));
        p.put(prefix + ".socketFactory", peer.factory);
        p.put(prefix + ".ssl.socketFactory", new TlsFactory(peer.context.getSocketFactory(), peer.factory));
        AtomicInteger engines = new AtomicInteger();
        if (integrated) {
            peer.factory.channel = true;
            p.remove(prefix + ".ssl.socketFactory");
            SSLContext context = peer.scenario.equals("untrusted") ? untrustedContext() : peer.context;
            p.put(prefix + ".ssl.engineFactory",
                    (java.util.function.BiFunction<String, Integer, SSLEngine>) (host, port) -> {
                        engines.incrementAndGet();
                        return context.createSSLEngine(peer.scenario.equals("wrong-host") ?
                                "wrong.example.test" : host, port);
                    });
        }
        if (!peer.implicit)
            p.setProperty(prefix + ".starttls.required", "true");
        SMTPTransport transport = peer.implicit ? new SMTPSSLTransport(Session.getInstance(p), null) :
                new SMTPTransport(Session.getInstance(p), null);
        try {
            transport.connect("localhost", peer.listener.getLocalPort(), "fixture", "fixture-password");
            check(transport.isSSL() && transport.supportsExtension("PIPELINING"), "Negotiation missing");
            if (integrated) {
                check(engines.get() == 1, "Integrated transport did not create one TLS engine");
                check(peer.factory.sockets.size() == 1, "Physical socket factory was bypassed");
            } else {
                check(peer.factory.timeoutBelowTls.get() == 1, "Write timeout wrapper moved above TLS");
            }
            Address[] recipients = new Address[RECIPIENTS];
            for (int i = 0; i < recipients.length; i++)
                recipients[i] = new InternetAddress(address(i + 1));
            for (int i = 0; i < MESSAGES; i++)
                transport.sendMessage(message, recipients);
        } finally {
            transport.close();
        }
    }

    /** One calling thread owns the channel, engine and SMTP parser. */
    private static final class EngineClient implements AutoCloseable {
        final SocketChannel channel = SocketChannel.open();
        final Selector selector = Selector.open();
        final SelectionKey key;
        final SSLEngine engine;
        final ExecutorService tasks = Executors.newSingleThreadExecutor(r -> new Thread(r, "probe-tls-task"));
        final ByteBuffer netIn = ByteBuffer.allocate(BUFFER_BOUND);
        final ByteBuffer netOut = ByteBuffer.allocate(BUFFER_BOUND);
        final ByteBuffer appIn = ByteBuffer.allocate(BUFFER_BOUND);
        final ArrayDeque<ByteBuffer> appOut = new ArrayDeque<>();
        final ReplyParser replies = new ReplyParser();
        Future<?> delegated;
        boolean tls;
        boolean initialFinished;
        boolean eof;
        boolean remoteClosed;
        boolean blockedWrite;
        long deadline;
        long zeroWrites;
        long readsWhileBlocked;
        long bytesWhileBlocked;
        long applicationBytesWhileBlocked;
        long envelopeBytesWhileBlocked;
        long envelopeControlWrapsWhileBlocked;
        long envelopeZeroWrites;
        long controlWrapsWhileBlocked;
        long controlWraps;
        long underflows;
        long taskCount;
        long selects;
        int maxNetIn;
        int maxNetOut;
        int maxAppQueued;
        String stage = "connect";

        EngineClient(SSLContext context, int port, String host) throws Exception {
            // A failure during construction must not leave open channels/tasks.
            SelectionKey registration = null;
            try {
                configure(channel.socket());
                channel.configureBlocking(false);
                registration = channel.register(selector, SelectionKey.OP_CONNECT);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                channel.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                while (!channel.finishConnect()) {
                    checkDeadline();
                    selector.select(20);
                    selector.selectedKeys().clear();
                }
                registration.interestOps(SelectionKey.OP_READ);
                engine = context.createSSLEngine(host, port);
                engine.setUseClientMode(true);
                SSLParameters parameters = engine.getSSLParameters();
                parameters.setProtocols(new String[]{"TLSv1.3"});
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                engine.setSSLParameters(parameters);
                check(engine.getSession().getPacketBufferSize() * 2 <= BUFFER_BOUND,
                        "TLS packet size exceeds experiment bound");
                key = registration;
            } catch (Throwable failure) {
                channel.close();
                selector.close();
                tasks.shutdownNow();
                throw failure;
            }
        }

        void send(boolean implicit, byte[] content, String scenario) throws Exception {
            if (implicit) {
                // The greeting can arrive in the same read as the final TLS
                // handshake records. Arm its parser before starting TLS.
                replies.begin(1, 220);
                handshake();
                stage = "greeting";
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!replies.complete()) {
                    if (remoteClosed || eof)
                        throw new EOFException("Peer closed before greeting");
                    pump();
                }
            } else {
                exchange("greeting", EMPTY, 1, 220);
            }
            exchange("EHLO", ascii("EHLO localhost\r\n"), 1, 250);
            if (!implicit) {
                check(replies.startTls, "STARTTLS not advertised");
                exchange("STARTTLS", ascii("STARTTLS\r\n"), 1, 220);
                check(netIn.position() == 0, "Unexpected cleartext after STARTTLS response");
                handshake();
                exchange("post-TLS EHLO", ascii("EHLO localhost\r\n"), 1, 250);
            }
            check(replies.pipeline && replies.auth && replies.dsn, "Fresh capabilities missing");
            exchange("AUTH", ascii("AUTH PLAIN " + AUTH + "\r\n"), 1, 235);
            for (int message = 0; message < MESSAGES; message++) {
                List<byte[]> commands = new ArrayList<>();
                commands.add(ascii(mail() + "\r\n"));
                for (int recipient = 1; recipient <= RECIPIENTS; recipient++)
                    commands.add(ascii(rcpt(recipient) + "\r\n"));
                int first = 0;
                while (first < commands.size()) {
                    ByteArrayOutputStream group = new ByteArrayOutputStream();
                    int count = 0;
                    while (first + count < commands.size() && count < 32) {
                        byte[] command = commands.get(first + count);
                        if (count > 0 && group.size() + command.length > 32768)
                            break;
                        group.write(command);
                        count++;
                    }
                    exchange("envelope " + message + "/" + first, group.toByteArray(), count, 250,
                            scenario.equals("silent") ? 600 : 8000);
                    first += count;
                }
                exchange("DATA", ascii("DATA\r\n"), 1, 354);
                exchange("body", dotStuff(content), 1, 250);
            }
            exchange("QUIT", ascii("QUIT\r\n"), 1, 221);
            stage = "close_notify";
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            engine.closeOutbound();
            while (!engine.isOutboundDone() || netOut.position() > 0 || !engine.isInboundDone())
                pump();
        }

        void handshake() throws Exception {
            stage = "handshake";
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            tls = true;
            engine.beginHandshake();
            while (!initialFinished || netOut.position() > 0)
                pump();
            check("TLSv1.3".equals(engine.getSession().getProtocol()), "TLS protocol changed");
        }

        void exchange(String name, byte[] bytes, int count, int code) throws Exception {
            exchange(name, bytes, count, code, 8000);
        }

        void exchange(String name, byte[] bytes, int count, int code, int millis) throws Exception {
            check(appOut.isEmpty() && netOut.position() == 0, "Previous write not drained");
            check(bytes.length <= 32768, "Experiment application queue exceeded 32 KiB");
            stage = name;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            replies.begin(count, code);
            // Match the existing buffered stream's 8 KiB application chunks.
            for (int offset = 0; offset < bytes.length; offset += 8192)
                appOut.add(ByteBuffer.wrap(bytes, offset, Math.min(8192, bytes.length - offset)));
            maxAppQueued = Math.max(maxAppQueued, bytes.length);
            while (!replies.complete() || !appOut.isEmpty() || netOut.position() > 0) {
                if (remoteClosed || eof)
                    throw new EOFException("Peer closed during " + stage);
                pump();
            }
        }

        void pump() throws Exception {
            checkDeadline();
            boolean progress = writeNetwork();
            progress |= readNetwork();
            if (tls) {
                progress |= processTls();
                // Process buffered ciphertext (including close_notify) before
                // interpreting EOF. A delegated task may still be outstanding.
                if (eof && delegated == null && !engine.isInboundDone() &&
                        engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    engine.closeInbound();
                    if (netIn.position() != 0)
                        throw new EOFException("Truncated TLS record during " + stage);
                }
            } else {
                netIn.flip();
                if (netIn.hasRemaining()) {
                    replies.accept(netIn);
                    progress = true;
                }
                netIn.compact();
                if (!appOut.isEmpty() && netOut.position() == 0) {
                    netOut.put(appOut.remove());
                    maxNetOut = Math.max(maxNetOut, netOut.position());
                    progress = true;
                }
            }
            progress |= writeNetwork();
            if (!progress) {
                int interests = !eof && netIn.hasRemaining() ? SelectionKey.OP_READ : 0;
                if (netOut.position() > 0)
                    interests |= SelectionKey.OP_WRITE;
                key.interestOps(interests);
                selects++;
                selector.select(20);
                selector.selectedKeys().clear();
            }
        }

        boolean processTls() throws Exception {
            boolean progress = false;
            for (int iteration = 0; iteration < 64; iteration++) {
                if (delegated != null) {
                    if (!delegated.isDone())
                        break;
                    delegated.get();
                    delegated = null;
                    progress = true;
                }
                SSLEngineResult.HandshakeStatus state = engine.getHandshakeStatus();
                if (state == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    List<Runnable> work = new ArrayList<>();
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null)
                        work.add(task);
                    check(!work.isEmpty(), "NEED_TASK without a task");
                    taskCount += work.size();
                    delegated = tasks.submit(() -> work.forEach(Runnable::run));
                    return true;
                }
                boolean step = false;
                if (state == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    // Reserve room for TLS control work even while application
                    // ciphertext is queued. All records stay in original order.
                    if (netOut.remaining() < engine.getSession().getPacketBufferSize())
                        break;
                    SSLEngineResult result = engine.wrap(ByteBuffer.wrap(EMPTY), netOut);
                    checkResult(result);
                    if (initialFinished && result.bytesProduced() > 0) {
                        controlWraps++;
                        if (blockedWrite)
                            controlWrapsWhileBlocked++;
                        if (blockedWrite && stage.startsWith("envelope"))
                            envelopeControlWrapsWhileBlocked++;
                    }
                    step = result.bytesProduced() > 0 || result.getHandshakeStatus() != state;
                } else {
                    if (netIn.position() > 0 && !engine.isInboundDone()) {
                        netIn.flip();
                        SSLEngineResult result = engine.unwrap(netIn, appIn);
                        netIn.compact();
                        checkResult(result);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW)
                            underflows++;
                        if (blockedWrite)
                            applicationBytesWhileBlocked += result.bytesProduced();
                        if (blockedWrite && stage.startsWith("envelope"))
                            envelopeBytesWhileBlocked += result.bytesProduced();
                        appIn.flip();
                        replies.accept(appIn);
                        appIn.clear();
                        step = result.bytesConsumed() > 0 || result.bytesProduced() > 0 ||
                                result.getHandshakeStatus() != state;
                        if (result.getStatus() == SSLEngineResult.Status.CLOSED)
                            remoteClosed = true;
                    }
                    if (!step && engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                            !appOut.isEmpty() && netOut.position() < APPLICATION_HIGH_WATER) {
                        ByteBuffer source = appOut.peek();
                        SSLEngineResult result = engine.wrap(source, netOut);
                        checkResult(result);
                        if (!source.hasRemaining())
                            appOut.remove();
                        step = result.bytesConsumed() > 0 || result.bytesProduced() > 0 ||
                                result.getHandshakeStatus() != state;
                    }
                }
                maxNetOut = Math.max(maxNetOut, netOut.position());
                progress |= step;
                if (!step)
                    break;
            }
            return progress;
        }

        void checkResult(SSLEngineResult result) throws IOException {
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
                throw new IOException("Fixed experiment TLS buffer overflow during " + stage);
            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED)
                initialFinished = true;
        }

        boolean readNetwork() throws IOException {
            if (eof || !netIn.hasRemaining())
                return false;
            int limit = netIn.limit();
            netIn.limit(Math.min(limit, netIn.position() + IO_CHUNK));
            int count;
            try {
                count = channel.read(netIn);
            } finally {
                netIn.limit(limit);
            }
            if (count < 0) {
                eof = true;
                return true;
            }
            if (count > 0 && blockedWrite) {
                readsWhileBlocked++;
                bytesWhileBlocked += count;
            }
            maxNetIn = Math.max(maxNetIn, netIn.position());
            return count > 0;
        }

        boolean writeNetwork() throws IOException {
            if (netOut.position() == 0) {
                blockedWrite = false;
                return false;
            }
            netOut.flip();
            int limit = netOut.limit();
            netOut.limit(Math.min(limit, IO_CHUNK));
            int count;
            try {
                count = channel.write(netOut);
            } finally {
                netOut.limit(limit);
                netOut.compact();
            }
            if (count == 0) {
                zeroWrites++;
                if (stage.startsWith("envelope"))
                    envelopeZeroWrites++;
                blockedWrite = true;
            }
            if (netOut.position() == 0)
                blockedWrite = false;
            return count > 0;
        }

        void checkDeadline() throws IOException {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Interrupted during " + stage);
            if (System.nanoTime() >= deadline)
                throw new SocketTimeoutException("Deadline during " + stage + ": " + metrics());
        }

        String metrics() {
            return "ENGINE-METRICS stage=" + stage.replace(' ', '_') + " zeroWrites=" + zeroWrites +
                    " readsWhileBlocked=" + readsWhileBlocked + " bytesWhileBlocked=" + bytesWhileBlocked +
                    " applicationBytesWhileBlocked=" + applicationBytesWhileBlocked +
                    " envelopeBytesWhileBlocked=" + envelopeBytesWhileBlocked +
                    " envelopeControlWrapsWhileBlocked=" + envelopeControlWrapsWhileBlocked +
                    " envelopeZeroWrites=" + envelopeZeroWrites +
                    " controlWrapsWhileBlocked=" + controlWrapsWhileBlocked + " controlWraps=" + controlWraps +
                    " maxNetIn=" + maxNetIn + " maxNetOut=" + maxNetOut + " maxAppQueued=" + maxAppQueued +
                    " underflows=" + underflows + " tasks=" + taskCount + " selects=" + selects +
                    " inboundClosed=" + engine.isInboundDone() + " outboundClosed=" + engine.isOutboundDone();
        }

        @Override
        public void close() throws Exception {
            try {
                channel.close();
                selector.close();
            } finally {
                tasks.shutdownNow();
                check(tasks.awaitTermination(3, TimeUnit.SECONDS), "TLS task thread survived cleanup");
            }
        }
    }

    /** Streams large multiline replies without retaining their text. */
    private static final class ReplyParser {
        final ByteArrayOutputStream line = new ByteArrayOutputStream(512);
        int expected;
        int code;
        int received;
        int currentCode;
        int replyBytes;
        boolean pipeline;
        boolean startTls;
        boolean auth;
        boolean dsn;

        void begin(int count, int requiredCode) {
            check(line.size() == 0 && currentCode == 0, "Incomplete reply at exchange boundary");
            expected = count;
            code = requiredCode;
            received = 0;
            pipeline = startTls = auth = dsn = false;
        }

        boolean complete() {
            return received == expected && line.size() == 0 && currentCode == 0;
        }

        void accept(ByteBuffer input) throws IOException {
            while (input.hasRemaining()) {
                int value = input.get() & 255;
                line.write(value);
                if (line.size() > 512 || ++replyBytes > 1048576)
                    throw new IOException("SMTP reply exceeds experiment bounds");
                if (value != '\n')
                    continue;
                byte[] bytes = line.toByteArray();
                if (bytes.length < 6 || bytes[bytes.length - 2] != '\r')
                    throw new IOException("Malformed SMTP reply line");
                String text = new String(bytes, StandardCharsets.US_ASCII);
                int actual;
                try {
                    actual = Integer.parseInt(text.substring(0, 3));
                } catch (NumberFormatException problem) {
                    throw new IOException("Malformed SMTP reply code", problem);
                }
                if (received >= expected || actual != code ||
                        (currentCode != 0 && actual != currentCode) ||
                        (text.charAt(3) != '-' && text.charAt(3) != ' '))
                    throw new IOException("Unexpected SMTP reply " + text.trim());
                currentCode = actual;
                String capability = text.substring(4).trim();
                pipeline |= capability.equals("PIPELINING");
                startTls |= capability.equals("STARTTLS");
                auth |= capability.equals("AUTH PLAIN");
                dsn |= capability.equals("DSN");
                if (text.charAt(3) == ' ') {
                    received++;
                    currentCode = 0;
                    replyBytes = 0;
                }
                line.reset();
            }
        }
    }

    /** Same legal commands, replies and socket pressure for every client arm. */
    private static final class Peer implements AutoCloseable {
        final boolean implicit;
        final String scenario;
        final String expectedHash;
        final SSLContext context = context();
        final ServerSocket listener = new ServerSocket();
        final CapturingFactory factory = new CapturingFactory();
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "probe-peer"));
        final ExecutorService monitor = Executors.newSingleThreadExecutor(r -> new Thread(r, "probe-monitor"));
        final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger ehlo = new AtomicInteger();
        final AtomicInteger auth = new AtomicInteger();
        final AtomicInteger data = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final Future<?> serving;
        volatile Socket raw;
        volatile Throwable problem;

        Peer(boolean implicit, String scenario, String expectedHash) throws Exception {
            this.implicit = implicit;
            this.scenario = scenario;
            this.expectedHash = expectedHash;
            listener.setReceiveBufferSize(SOCKET_BUFFER);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            serving = worker.submit(() -> {
                try {
                    raw = listener.accept();
                    connections.incrementAndGet();
                    configure(raw);
                    serve();
                } catch (Throwable failure) {
                    problem = failure;
                } finally {
                    if (raw != null)
                        raw.close();
                }
                return null;
            });
        }

        void serve() throws Exception {
            Socket socket = implicit ? tls(raw) : raw;
            OutputStream output = socket.getOutputStream();
            LineInputStream input = new LineInputStream(socket.getInputStream());
            reply(output, "220 localhost");
            check("EHLO localhost".equals(input.readLine()), "Missing first EHLO");
            ehlo.incrementAndGet();
            if (!implicit) {
                reply(output, "250-localhost\r\n250-PIPELINING\r\n250 STARTTLS");
                check("STARTTLS".equals(input.readLine()), "STARTTLS missing");
                reply(output, "220 ready");
                socket = tls(raw);
                input = new LineInputStream(socket.getInputStream());
                output = socket.getOutputStream();
                check("EHLO localhost".equals(input.readLine()), "Missing post-TLS EHLO");
                ehlo.incrementAndGet();
            }
            reply(output, "250-localhost\r\n250-DSN\r\n250-PIPELINING\r\n250 AUTH PLAIN");
            check(("AUTH PLAIN " + AUTH).equals(input.readLine()), "Authentication changed");
            auth.incrementAndGet();
            reply(output, "235 authenticated");
            int recipients = 0;
            boolean sender = false;
            String line;
            while ((line = input.readLine()) != null) {
                check(line.length() + 2 <= 512 && line.chars().allMatch(c -> c < 128), "Invalid command");
                if (line.startsWith("MAIL ")) {
                    check(!sender && line.equals(mail()), "MAIL parameters changed");
                    if (scenario.equals("disconnect")) {
                        raw.close();
                        return;
                    }
                    if (scenario.equals("silent")) {
                        while (input.readLine() != null) { /* No reply; deadline must abort the client. */ }
                        return;
                    }
                    sender = true;
                    Thread.sleep(100);
                    multiline(output, MAIL_REPLY);
                } else if (line.startsWith("RCPT ")) {
                    check(sender && line.equals(rcpt(++recipients)), "Recipient order or parameters changed");
                    multiline(output, recipients == 1 ? RCPT_REPLY : 512);
                } else if (line.equals("DATA")) {
                    check(sender && recipients == RECIPIENTS, "Incomplete envelope before DATA");
                    data.incrementAndGet();
                    reply(output, "354 content");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    while ((line = input.readLine()) != null && !line.equals(".")) {
                        if (line.startsWith(".."))
                            line = line.substring(1);
                        digest.update(ascii(line + "\r\n"));
                    }
                    check(line != null, "Incomplete body");
                    check(Base64.getEncoder().encodeToString(digest.digest()).equals(expectedHash), "Body changed");
                    completed.incrementAndGet();
                    if (scenario.equals("post-content")) {
                        raw.close();
                        return;
                    }
                    reply(output, "250 queued");
                    sender = false;
                    recipients = 0;
                } else if (line.equals("QUIT")) {
                    reply(output, "221 bye");
                    socket.close();
                    return;
                } else {
                    throw new AssertionError("Unexpected command " + line);
                }
            }
        }

        SSLSocket tls(Socket socket) throws IOException {
            SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true);
            tls.setUseClientMode(false);
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.startHandshake();
            check("TLSv1.3".equals(tls.getSession().getProtocol()), "Server TLS changed");
            return tls;
        }

        void multiline(OutputStream output, int bytes) throws IOException {
            byte[] line = ascii("250-" + "r".repeat(506) + "\r\n");
            for (int count = 0; count < bytes; count += line.length)
                output.write(line);
            reply(output, "250 accepted");
        }

        void stop() throws Exception {
            listener.close();
            if (raw != null)
                raw.close();
            for (Socket socket : factory.sockets)
                socket.close();
            serving.get(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                stop();
            } finally {
                worker.shutdownNow();
                monitor.shutdownNow();
                timeouts.shutdownNow();
                check(worker.awaitTermination(3, TimeUnit.SECONDS), "Peer thread survived");
                check(monitor.awaitTermination(3, TimeUnit.SECONDS), "Monitor thread survived");
                check(timeouts.awaitTermination(3, TimeUnit.SECONDS), "Timeout executor survived");
            }
        }
    }

    private static final class CapturingFactory extends SocketFactory {
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger timeoutBelowTls = new AtomicInteger();
        boolean channel;
        @Override public Socket createSocket() throws IOException {
            Socket socket = channel ? SocketChannel.open().socket() : new Socket();
            configure(socket);
            sockets.add(socket);
            return socket;
        }
        @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
    }

    private static final class TlsFactory extends SSLSocketFactory {
        final SSLSocketFactory delegate;
        final CapturingFactory factory;
        TlsFactory(SSLSocketFactory delegate, CapturingFactory factory) {
            this.delegate = delegate;
            this.factory = factory;
        }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            configure(socket);
            if (socket instanceof WriteTimeoutSocket)
                factory.timeoutBelowTls.incrementAndGet();
            return delegate.createSocket(socket, host, port, autoClose);
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
    }

    private static MimeMessage message() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setText("body\r\n.dot\r\n" + ("content ".repeat(10) + "\r\n").repeat(300), "us-ascii");
        message.saveChanges();
        message.setHeader("Date", "Mon, 1 Jan 2024 00:00:00 +0000");
        message.setHeader("Message-ID", "<engine-probe@example.test>");
        return message;
    }

    private static byte[] content(MimeMessage message) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes, new String[]{"Bcc", "Content-Length"});
        return bytes.toByteArray();
    }

    private static byte[] dotStuff(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean beginning = true;
        for (byte value : content) {
            if (beginning && value == '.')
                bytes.write('.');
            bytes.write(value);
            beginning = value == '\n';
        }
        check(beginning, "Fixture content must end in CRLF");
        bytes.write(ascii(".\r\n"));
        return bytes.toByteArray();
    }

    private static SSLContext context() throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream input = SMTPSSLEngineProbe.class.getResourceAsStream("pipelining.jks")) {
            check(input != null, "Missing pipelining.jks test fixture");
            store.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509");
        keys.init(store, "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance("SunX509");
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static SSLContext untrustedContext() throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance("SunX509");
        trust.init((KeyStore) null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static void configure(Socket socket) throws IOException {
        socket.setSendBufferSize(SOCKET_BUFFER);
        socket.setReceiveBufferSize(SOCKET_BUFFER);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
    }

    private static String address(int index) {
        String suffix = String.valueOf(index);
        return "r".repeat(64 - suffix.length()) + suffix + "@" + "a".repeat(63) + "." +
                "b".repeat(63) + "." + "c".repeat(48) + ".example.test";
    }

    private static String mail() { return "MAIL FROM:<" + address(0) + "> RET=HDRS ENVID=" + "e".repeat(90); }
    private static String rcpt(int index) { return "RCPT TO:<" + address(index) + "> NOTIFY=SUCCESS,FAILURE,DELAY"; }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static String hash(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static int wireReplySize(int bytes) { return ((bytes + 511) / 512) * 512 + 14; }
    private static void reply(OutputStream output, String text) throws IOException {
        output.write(ascii(text + "\r\n"));
        output.flush();
    }
    private static boolean hasCause(Throwable problem, Class<?> type) {
        for (Throwable current = problem; current != null; current = current.getCause())
            if (type.isInstance(current))
                return true;
        return false;
    }
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
    private static void checkNoReader() {
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread ->
                thread.isAlive() && (thread.getName().equals("Angus SMTP envelope reader") ||
                        thread.getName().equals("Angus TLS task"))), "Transport worker survived");
    }
    private static boolean stalledSnapshot() {
        boolean reader = false;
        boolean peer = false;
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement frame : entry.getValue()) {
                if (entry.getKey().getName().equals("Angus SMTP envelope reader") &&
                        frame.getClassName().equals("sun.security.ssl.OutputRecord") &&
                        frame.getMethodName().equals("changeWriteCiphers"))
                    reader = true;
                if (frame.getClassName().equals(Peer.class.getName()) && frame.getMethodName().equals("multiline"))
                    peer = true;
            }
        }
        return reader && peer;
    }
}
