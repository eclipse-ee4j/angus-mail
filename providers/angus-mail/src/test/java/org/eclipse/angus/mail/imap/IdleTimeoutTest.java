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

package org.eclipse.angus.mail.imap;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.eclipse.angus.mail.test.SingleThreadServer;
import org.junit.Test;

public class IdleTimeoutTest {

    private static final long TIMEOUT = 3000; 
    private final Executor executor = Executors.newCachedThreadPool();
    
    @Test
    public void testTimeout() throws Exception {
        try (SingleThreadServer server = new SingleThreadServer()) {

            // Predefine the server responses, so it is mocking an IMAP server

            server.prepareResponse("A0 CAPABILITY\r\n",
                    "* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN AUTH=LOGIN IDLE UIDPLUS\r\n" +
                    "A0 OK CAPABILITY completed\r\n");

            server.prepareResponse("A1 AUTHENTICATE PLAIN\r\n", "+\r\n");
            server.prepareResponse("AHVzZXJAdGVzdC5jb20AMTIzNA==\r\n",
                    "A1 OK AUTHENTICATE completed\r\n");

            server.prepareResponse("A2 CAPABILITY\r\n",
                    "* CAPABILITY IMAP4rev1 IDLE UIDPLUS\r\n" +
                    "A2 OK CAPABILITY completed\r\n");

            server.prepareResponse("A3 SELECT INBOX\r\n",
                    "* 0 EXISTS\r\n" +
                    "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n" +
                    "A3 OK [READ-WRITE] SELECT completed\r\n");

            server.prepareResponse("A4 IDLE\r\n", "+ idling\r\n");

            server.start();
            Properties properties = new Properties();
            properties.setProperty("mail.imap.host", "localhost");
            properties.setProperty("mail.imap.port", Integer.toString(server.getPort()));
            // We want to verify what happens when client gets SocketTimeoutException
            properties.setProperty("mail.imap.timeout", Long.toString(TIMEOUT));
            // Required to IDLE
            properties.setProperty("mail.imap.usesocketchannels", "true");
            Session session = Session.getDefaultInstance(properties);
            Store store = session.getStore("imap");
            store.connect("user@test.com", "1234");
            IdleManager im = new IdleManager(session, executor);
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);
            
            im.watch(folder);
            // Client is going to get the SocketTimeoutException after TIMEOUT
            Thread.sleep(9999999L);
        }
    }

}
