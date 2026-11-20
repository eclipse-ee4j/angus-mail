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

import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test that envelope addresses can't add ESMTP parameters to the
 * MAIL and RCPT commands.
 */
public class SMTPEnvelopeAddressTest {

    private final List<String> commands = new CopyOnWriteArrayList<>();

    @Test
    public void testRecipientParameterInjection() throws Exception {
        Address[] rcpt = {
                new InternetAddress("<victim@example.com> NOTIFY=NEVER", "x"),
                new InternetAddress("victim@example.com> ORCPT=rfc822;x", "x")
        };
        try {
            send(null, rcpt);
            fail("sent to an address with ESMTP parameters");
        } catch (SendFailedException ex) {
            assertEquals(2, ex.getInvalidAddresses().length);
        }
        assertFalse(commands.toString(), hasCommand("RCPT"));
    }

    @Test
    public void testSenderParameterInjection() throws Exception {
        try {
            send("<a@example.com> AUTH=<boss@example.com>",
                    new Address[]{new InternetAddress("b@example.com")});
            fail("sent from an address with ESMTP parameters");
        } catch (AddressException ex) {
            // expected
        }
        assertFalse(commands.toString(), hasCommand("MAIL"));
    }

    @Test
    public void testQuotedLocalPart() throws Exception {
        send(null, new Address[]{
                new InternetAddress("\"john <doe>\"@example.com"),
                new InternetAddress("<plain@example.com>", "x")
        });
        assertTrue(commands.toString(), commands.contains(
                "RCPT TO:<\"john <doe>\"@example.com>"));
        assertTrue(commands.toString(), commands.contains(
                "RCPT TO:<plain@example.com>"));
    }

    private boolean hasCommand(String cmd) {
        for (String c : commands)
            if (c.startsWith(cmd))
                return true;
        return false;
    }

    private void send(String from, Address[] rcpt) throws Exception {
        TestServer server = null;
        try {
            server = new TestServer(new SMTPHandler() {
                @Override
                public void ehlo() throws IOException {
                    println("250-hello");
                    println("250-DSN");
                    println("250 AUTH PLAIN");
                }

                @Override
                public void mail(String line) throws IOException {
                    commands.add(line);
                    ok();
                }

                @Override
                public void rcpt(String line) throws IOException {
                    commands.add(line);
                    ok();
                }
            });
            server.start();

            Properties properties = new Properties();
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port",
                    String.valueOf(server.getPort()));
            Session session = Session.getInstance(properties);

            SMTPMessage msg = new SMTPMessage(session);
            msg.setFrom(new InternetAddress("a@example.com"));
            if (from != null)
                msg.setEnvelopeFrom(from);
            msg.setSubject("test");
            msg.setText("test\n");
            Transport t = session.getTransport("smtp");
            try {
                t.connect("test", "test");
                t.sendMessage(msg, rcpt);
            } finally {
                t.close();
            }
        } finally {
            if (server != null) {
                server.quit();
                server.interrupt();
            }
        }
    }
}
