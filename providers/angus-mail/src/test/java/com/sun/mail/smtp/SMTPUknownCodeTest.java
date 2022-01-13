/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.mail.smtp;

import com.sun.mail.test.TestServer;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.fail;

public class SMTPUknownCodeTest {

    /**
     * Test handling of unknown 2xx status code from the server
     */
    @Test
    public void testUnknown2xy() {
        TestServer server = null;
        try {
            server = new TestServer(new SMTPLoginHandler() {
                @Override
                public void rcpt(String line) throws IOException {
                    if (line.contains("alex")) {
                        println("254 XY");
                    } else {
                        super.rcpt(line);
                    }
                }
            });
            server.start();

            Properties properties = new Properties();
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", "" + server.getPort());
            properties.setProperty("mail.smtp.auth.mechanisms", "LOGIN");
//            properties.setProperty("mail.debug.auth", "true");
            Session session = Session.getInstance(properties);
//            session.setDebug(true);

            Transport t = session.getTransport("smtp");
            try {
                MimeMessage msg = new MimeMessage(session);
                msg.setRecipients(Message.RecipientType.TO, "joe@example.com");
                msg.addRecipients(Message.RecipientType.TO, "alex@example.com");
                msg.setSubject("test");
                msg.setText("test");
                t.connect();
                t.sendMessage(msg, msg.getAllRecipients());
            } catch (Exception ex) {
                fail(ex.toString());
            } finally {
                t.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            if (server != null) {
                server.quit();
                server.interrupt();
            }
        }
    }
}
