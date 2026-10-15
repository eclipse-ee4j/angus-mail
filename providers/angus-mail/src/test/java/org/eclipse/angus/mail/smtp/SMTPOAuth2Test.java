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

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Test;

import java.util.Properties;

/**
 * Test XOAUTH2 authentication with access tokens too long to be sent as
 * the initial response of the AUTH command (RFC 4954, section 4).
 */
public class SMTPOAuth2Test {

    private static final String SHORT_TOKEN = "short-token";
    private static final String LONG_TOKEN = "x".repeat(1500);

    @Test
    public void testShortToken() throws Exception {
        connect(SHORT_TOKEN, false);
    }

    @Test
    public void testLongToken() throws Exception {
        connect(LONG_TOKEN, false);
    }

    @Test
    public void testShortTokenWithSasl() throws Exception {
        connect(SHORT_TOKEN, true);
    }

    @Test
    public void testLongTokenWithSasl() throws Exception {
        connect(LONG_TOKEN, true);
    }

    private static void connect(String token, boolean sasl) throws Exception {
        TestServer server = new TestServer(new SMTPOAuth2Handler(token));
        try {
            server.start();

            Properties properties = new Properties();
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", String.valueOf(server.getPort()));
            properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
            properties.setProperty("mail.smtp.auth.xoauth2.disable", "false");
            if (sasl) {
                properties.setProperty("mail.smtp.sasl.enable", "true");
                properties.setProperty("mail.smtp.sasl.mechanisms", "XOAUTH2");
            }
            Session session = Session.getInstance(properties);

            Transport t = session.getTransport("smtp");
            try {
                t.connect("test", token);
            } finally {
                t.close();
            }
        } finally {
            server.quit();
            server.interrupt();
        }
    }
}
