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

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;

/**
 * Test that POP3Message honors mail.mime.allowutf8 when loading headers.
 */
public final class POP3AllowUtf8Test {

    private static final String UTF8_TO = "b\u00F6b@example.com";

    @Test
    public void testAllowUtf8Headers() throws Exception {
        final POP3AllowUtf8Handler handler = new POP3AllowUtf8Handler();
        final TestServer server = new TestServer(handler);
        server.start();
        Thread.sleep(1000);
        try {
            final Properties properties = new Properties();
            properties.setProperty("mail.pop3.host", "localhost");
            properties.setProperty("mail.pop3.port",
                    String.valueOf(server.getPort()));
            properties.setProperty("mail.mime.allowutf8", "true");
            final Session session = Session.getInstance(properties);

            final Store store = session.getStore("pop3");
            store.connect("test", "test");
            try {
                final Folder folder = store.getFolder("INBOX");
                folder.open(Folder.READ_ONLY);
                try {
                    final Message msg = folder.getMessage(1);
                    assertEquals(UTF8_TO, msg.getHeader("To")[0]);
                } finally {
                    folder.close(false);
                }
            } finally {
                store.close();
            }
        } finally {
            server.quit();
        }
    }
}
