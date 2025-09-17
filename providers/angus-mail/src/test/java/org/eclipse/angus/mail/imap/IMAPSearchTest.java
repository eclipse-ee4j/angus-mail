/*
 * Copyright (c) 2009, 2024 Oracle and/or its affiliates. All rights reserved.
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

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.SearchException;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.SearchSequence;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Properties;

import static org.junit.Assert.fail;

/**
 * Test the search method.
 */
public final class IMAPSearchTest {

    // timeout the test in case of deadlock
    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    @Test
    public void testWithinNotSupported() {
        TestServer server = null;
        try {
            server = new TestServer(new IMAPHandler() {
                @Override
                public void search(String line) throws IOException {
                    bad("WITHIN not supported");
                }
            });
            server.start();

            final Properties properties = new Properties();
            properties.setProperty("mail.imap.host", "localhost");
            properties.setProperty("mail.imap.port", String.valueOf(server.getPort()));
            properties.setProperty("mail.imap.throwsearchexception", "true");
            final Session session = Session.getInstance(properties);
            //session.setDebug(true);

            final Store store = session.getStore("imap");
            Folder folder = null;
            try {
                store.connect("test", "test");
                folder = store.getFolder("INBOX");
                folder.open(Folder.READ_ONLY);
                Message[] msgs = folder.search(new YoungerTerm(1));
                fail("search didn't fail");
            } catch (SearchException ex) {
                // success!
            } catch (Exception ex) {
                System.out.println(ex);
                //ex.printStackTrace();
                fail(ex.toString());
            } finally {
                if (folder != null)
                    folder.close(false);
                store.close();
            }
        } catch (final Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            if (server != null) {
                server.quit();
            }
        }
    }

    /**
     * Test that when the server supports UTF8 and the client enables it,
     * the client doesn't issue a SEARCH CHARSET command even if the search
     * term includes a non-ASCII character.
     * (see RFC 6855, section 3, last paragraph)
     */
    //TODO: Fix this TestServer/ProtocolHandler so this test passes.
    //TODO: Fix the test.
    @Test
    public void testUtf8Search() {
        final String find = "\u2019\u7cfb\u7edf";
        TestServer server = null;
        try {
            server = new TestServer(new IMAPUtf8Handler() {
                @Override
                public void search(String line) throws IOException {
                    System.out.println(line);
                    if (line.contains("CHARSET"))
                        bad("CHARSET not supported");
                    else
                        ok();
                }
            });
            server.start();

            final Properties properties = new Properties();
            properties.setProperty("mail.imap.host", "localhost");
            properties.setProperty("mail.imap.port", String.valueOf(server.getPort()));
            final Session session = Session.getInstance(properties);
            //session.setDebug(true);
            
            final Store store = session.getStore("imap");
            Folder folder = null;
            try {
                store.connect("test", "test");
                folder = store.getFolder("INBOX");
                folder.open(Folder.READ_ONLY);
                SubjectTerm term = new SubjectTerm(find);
                Message[] msgs = folder.search(term);
            } catch (Exception ex) {
                System.out.println(ex);
                //ex.printStackTrace();
                fail(ex.toString());
            } finally {
                if (folder != null)
                    folder.close(false);
                store.close();
            }
        } catch (final Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            if (server != null) {
                server.quit();
            }
        }
    }
    
    //@Test
    @org.junit.Ignore
    public void testUtf8SubjectLiteral() throws Exception {
        final String find = "\u2019\u7cfb\u7edf";
        SubjectTerm term = new SubjectTerm(find);
        InputStream in = new ByteArrayInputStream(find.getBytes("UTF-8"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, "UTF-8");
        Properties props = new Properties();

        IMAPProtocol p = new IMAPProtocol(in, out, props, false) {
            public boolean supportsUtf8() {
                return true;
            }
        };
        
        SearchSequence ss = new SearchSequence(p);
        ss.generateSequence(term, "UTF-8").write(p);
        
        System.out.println(baos.toString("UTF-8"));
    }

    /**
     * An IMAPHandler that enables UTF-8 support.
     */
    private static class IMAPUtf8Handler extends IMAPHandler {
        {
            {
                capabilities += " ENABLE UTF8=ACCEPT";
            }
        }

        @Override
        public void enable(String line) throws IOException {
            ok();
        }
    }
}
