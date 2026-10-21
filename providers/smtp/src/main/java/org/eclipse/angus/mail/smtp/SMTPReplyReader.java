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

import org.eclipse.angus.mail.util.LineInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Reads only the replies reserved by the sending thread. This helper never
 * acquires the transport monitor or calls transport or subclass methods.
 * The sender drains one group completely before reserving another group.
 */
final class SMTPReplyReader implements Runnable {
    static final int MAX_COMMANDS = 32;
    static final int MAX_COMMAND_BYTES = 32 * 1024;

    private final Socket socket;
    private final LineInputStream input;
    private final Semaphore requests = new Semaphore(0);
    private final ArrayBlockingQueue<Reply> replies =
            new ArrayBlockingQueue<>(MAX_COMMANDS);
    private final Thread thread;
    private volatile IOException failure;
    private volatile Reply terminalReply;
    private volatile boolean stopping;

    /**
     * Starts an idle helper for this envelope, using the existing traced input.
     */
    SMTPReplyReader(Socket socket, LineInputStream input) {
        this.socket = socket;
        this.input = input;
        thread = new Thread(this, "Angus SMTP envelope reader");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reserves capacity for the entire group before any of it is written.
     */
    void reserve(int count) {
        if (count < 1 || count > MAX_COMMANDS || !replies.isEmpty())
            throw new IllegalStateException("Invalid SMTP reply reservation");
        requests.release(count);
    }

    /**
     * Returns the next complete reply to the sending thread.
     */
    Reply take() throws IOException, InterruptedException {
        Reply reply = replies.poll();
        if (reply == null) {
            if (failure != null)
                throw failure;
            reply = replies.take();
        }
        if (reply.failure != null)
            throw reply.failure;
        return reply;
    }

    /**
     * Returns a terminal reader failure, including one that aborted a write.
     */
    IOException getFailure() {
        return failure;
    }

    Reply getTerminalReply() {
        return terminalReply;
    }

    boolean hasReply() {
        return !replies.isEmpty();
    }

    /**
     * Reads on the helper thread, without touching mutable transport state.
     */
    @Override
    public void run() {
        try {
            while (true) {
                requests.acquire();
                if (stopping)
                    return;
                Reply reply = readReply();
                if (reply.code == 421) {
                    terminalReply = reply;
                    throw new IOException(reply.text);
                }
                // The sender reserved all slots before starting its writes.
                replies.add(reply);
            }
        } catch (InterruptedException ex) {
            if (!stopping)
                fail(new IOException("SMTP reply reader interrupted", ex));
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            fail(ex);
        } catch (RuntimeException ex) {
            fail(new IOException("SMTP reply reader failed", ex));
        }
    }

    /**
     * Reads and validates a whole reply, including matching multiline codes.
     */
    private Reply readReply() throws IOException {
        StringBuilder text = new StringBuilder(100);
        int code = 0;
        while (true) {
            String line = input.readLine();
            if (line == null)
                throw new EOFException(text.length() == 0 ? "[EOF]" : text.toString());
            text.append(line).append('\n');
            if (line.length() < 3 || line.charAt(0) < '2' ||
                    line.charAt(0) > '5' || line.charAt(1) < '0' ||
                    line.charAt(1) > '9' || line.charAt(2) < '0' ||
                    line.charAt(2) > '9' || (line.length() > 3 &&
                    line.charAt(3) != ' ' && line.charAt(3) != '-'))
                throw new IOException("Malformed SMTP reply: " + text);
            int next = Integer.parseInt(line.substring(0, 3));
            if (code != 0 && next != code)
                throw new IOException("Inconsistent SMTP reply: " + text);
            code = next;
            if (line.length() == 3 || line.charAt(3) == ' ')
                return new Reply(code, text.toString(), null);
        }
    }

    /**
     * Publishes the original failure before closing the socket to wake writers.
     */
    private void fail(IOException ex) {
        failure = ex;
        replies.offer(new Reply(0, "", ex));
        abort();
    }

    /**
     * Aborts physical I/O without acquiring the transport monitor.
     */
    private void abort() {
        try {
            // This is an abort, not a graceful TLS close. JSSE otherwise can
            // wait for the very writer that this close must unblock.
            socket.setSoLinger(true, 0);
            // Bound JSSE's attempt to consume pending input during close.
            socket.setSoTimeout(1);
        } catch (IOException ex) {
            if (failure != null)
                failure.addSuppressed(ex);
        }
        try {
            socket.close();
        } catch (IOException ex) {
            if (failure != null)
                failure.addSuppressed(ex);
        }
    }

    /**
     * Stops and joins the helper before any other use of the connection.
     * Abnormal completion closes the socket first. Interruption also aborts
     * the connection and is restored after the helper has terminated.
     */
    void finish(boolean complete) {
        stopping = true;
        if (!complete)
            abort();
        thread.interrupt();
        boolean interrupted = false;
        try {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException ex) {
                    interrupted = true;
                    abort();
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * An immutable result. Transport fields are updated only by its sender.
     */
    static final class Reply {
        final int code;
        final String text;
        final IOException failure;

        private Reply(int code, String text, IOException failure) {
            this.code = code;
            this.text = text;
            this.failure = failure;
        }
    }
}
