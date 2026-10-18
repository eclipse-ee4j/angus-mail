/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringTokenizer;

/**
 * Handle connection with XOAUTH2 authentication, enforcing the SMTP
 * command line length limit of RFC 5321.
 */
public class SMTPOAuth2Handler extends SMTPHandler {

    /**
     * The maximum length of a command line, without the CRLF.
     */
    private static final int MAX_COMMAND_LINE_LENGTH = 510;

    private final String token;

    public SMTPOAuth2Handler(String token) {
        this.token = token;
    }

    @Override
    public void ehlo() throws IOException {
        println("250-hello");
        println("250 AUTH XOAUTH2");
    }

    @Override
    public void auth(String line) throws IOException {
        if (line.length() > MAX_COMMAND_LINE_LENGTH) {
            println("500 5.5.2 Line too long");
            return;
        }
        StringTokenizer ct = new StringTokenizer(line, " ");
        ct.nextToken(); // AUTH
        String mech = ct.nextToken();
        if (!mech.equalsIgnoreCase("XOAUTH2")) {
            println("504 unrecognized authentication type");
            return;
        }
        String ir;
        if (ct.hasMoreTokens()) {
            ir = ct.nextToken();
        } else {
            println("334 ");
            ir = readLine();
        }
        String expected = "user=test\001auth=Bearer " + token + "\001\001";
        String actual = new String(Base64.getDecoder().decode(
                ir.getBytes(StandardCharsets.US_ASCII)), StandardCharsets.UTF_8);
        if (expected.equals(actual))
            println("235 Authenticated");
        else
            println("535 authentication failed");
    }
}
