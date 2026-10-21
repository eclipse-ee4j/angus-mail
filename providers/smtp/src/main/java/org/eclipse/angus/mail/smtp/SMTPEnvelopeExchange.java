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

import org.eclipse.angus.mail.util.DuplexSocket;
import org.eclipse.angus.mail.util.LineInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;

/**
 * Collects complete envelope replies while channel writes make progress.
 * Only the sending thread calls this object, including its input callback.
 * Transport and subclass callbacks are deferred until a group is written.
 */
final class SMTPEnvelopeExchange {
    static final int MAX_COMMANDS = 32;
    static final int MAX_COMMAND_BYTES = 32 * 1024;

    private final DuplexSocket socket;
    private final InputStream input;
    private final ArrayDeque<Reply> replies = new ArrayDeque<>(MAX_COMMANDS);
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(128);
    private final StringBuilder text = new StringBuilder(100);
    private final byte[] buffer = new byte[8192];
    private IOException failure;
    private Reply terminalReply;
    private int pending;
    private int code;

    SMTPEnvelopeExchange(DuplexSocket socket, InputStream input) {
        this.socket = socket;
        this.input = input;
        socket.setInputHandler(this::readAvailable);
    }

    void reserve(int count) {
        if (count < 1 || count > MAX_COMMANDS || pending != 0 || !replies.isEmpty())
            throw new IllegalStateException("Invalid SMTP reply reservation");
        pending = count;
        socket.setInputHandler(this::readAvailable);
    }

    Reply take() throws IOException, InterruptedException {
        if (replies.isEmpty()) {
            if (failure != null)
                throw failure;
            try {
                socket.awaitInput(() -> !replies.isEmpty());
            } catch (IOException ex) {
                fail(ex);
                if (replies.isEmpty())
                    throw failure;
            }
        }
        return replies.remove();
    }

    IOException getFailure() {
        return failure;
    }

    Reply getTerminalReply() {
        return terminalReply;
    }

    boolean hasReply() {
        return !replies.isEmpty();
    }

    private void readAvailable() throws IOException {
        try {
            int ready;
            while ((ready = input.available()) > 0) {
                int count = input.read(buffer, 0, Math.min(ready, buffer.length));
                if (count < 0)
                    throw new IOException("[EOF]");
                for (int i = 0; i < count; i++) {
                    line.write(buffer[i]);
                    if (buffer[i] == '\n')
                        completeLine();
                }
            }
        } catch (IOException ex) {
            fail(ex);
            throw ex;
        }
    }

    private void completeLine() throws IOException {
        // Keep LineInputStream's existing byte/UTF-8 decoding policy.
        String value = new LineInputStream(new ByteArrayInputStream(
                line.toByteArray())).readLine();
        line.reset();
        text.append(value).append('\n');
        if (pending == 0 || value.length() < 3 || value.charAt(0) < '2' ||
                value.charAt(0) > '5' || value.charAt(1) < '0' ||
                value.charAt(1) > '9' || value.charAt(2) < '0' ||
                value.charAt(2) > '9' || (value.length() > 3 &&
                value.charAt(3) != ' ' && value.charAt(3) != '-'))
            throw new IOException("Malformed SMTP reply: " + text);
        int next = Integer.parseInt(value.substring(0, 3));
        if (code != 0 && next != code)
            throw new IOException("Inconsistent SMTP reply: " + text);
        code = next;
        if (value.length() == 3 || value.charAt(3) == ' ') {
            Reply reply = new Reply(code, text.toString());
            pending--;
            code = 0;
            text.setLength(0);
            if (reply.code == 421) {
                terminalReply = reply;
                throw new IOException(reply.text);
            }
            replies.add(reply);
        }
    }

    private void fail(IOException ex) {
        if (failure == null)
            failure = ex;
        socket.abort();
    }

    void finish(boolean complete) {
        socket.setInputHandler(null);
        if (!complete)
            socket.abort();
    }

    static final class Reply {
        final int code;
        final String text;

        Reply(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }
}
