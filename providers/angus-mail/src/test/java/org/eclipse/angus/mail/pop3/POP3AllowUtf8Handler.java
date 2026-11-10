/*
 * Copyright (c) 2026 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.pop3;

import org.eclipse.angus.mail.test.ProtocolHandler;

import java.io.IOException;
import java.util.StringTokenizer;

/**
 * POP3 test server with a single message containing a UTF-8 To header.
 */
public class POP3AllowUtf8Handler extends ProtocolHandler {

    private static final String UTF8_TO = "b\u00F6b@example.com";

    private String currentLine;

    private final String top =
            "Mime-Version: 1.0\r\n" +
                    "From: joe@example.com\r\n" +
                    "To: " + UTF8_TO + "\r\n" +
                    "Subject: UTF-8 To header\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n";
    private final String msg = top + "plain text\r\n";

    @Override
    public void sendGreetings() throws IOException {
        println("+OK POP3 CUSTOM");
    }

    private void println(final String str) throws IOException {
        writer.print(str);
        writer.print("\r\n");
        writer.flush();
    }

    @Override
    public void handleCommand() throws IOException {
        currentLine = readLine();
        if (currentLine == null) {
            exit();
            return;
        }

        final StringTokenizer st = new StringTokenizer(currentLine, " ");
        final String commandName = st.nextToken().toUpperCase();
        final String arg = st.hasMoreTokens() ? st.nextToken() : null;

        if (commandName.equals("STAT")) {
            println("+OK 1 " + msg.length());
        } else if (commandName.equals("LIST")) {
            if (arg == null) {
                writer.println("+OK");
                writer.println("1 " + msg.length());
                println(".");
            } else {
                println("+OK 1 " + msg.length());
            }
        } else if (commandName.equals("RETR")) {
            println("+OK " + msg.length() + " octets");
            writer.write(msg);
            println(".");
        } else if (commandName.equals("TOP")) {
            println("+OK " + top.length() + " octets");
            writer.write(top);
            println(".");
        } else if (commandName.equals("UIDL")) {
            if (arg == null) {
                writer.println("+OK");
                writer.println("1 1");
                println(".");
            } else {
                println("+OK 1");
            }
        } else if (commandName.equals("USER") || commandName.equals("PASS") ||
                commandName.equals("NOOP") || commandName.equals("RSET")) {
            println("+OK");
        } else if (commandName.equals("QUIT")) {
            println("+OK");
            exit();
        } else {
            println("-ERR unknown command");
        }
    }
}
