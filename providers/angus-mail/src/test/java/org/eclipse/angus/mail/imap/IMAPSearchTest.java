/*
 * Copyright (c) 2009, 2023 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.imap;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.SearchException;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.test.TestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
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
    @Test
    public void testUtf8Search() {
        TestServer server = null;
        try {
            server = new TestServer(new IMAPUtf8Handler() {
                @Override
                public void search(String line) throws IOException {
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
                Message[] msgs = folder.search(new SubjectTerm("\u2019"));
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
     * non-ASCII search text is sent in UTF-8, as a quoted string
     * (RFC 6855, section 3).
     */
    @Test
    public void testUtf8SearchText() throws Exception {
        assertSearchArguments("SUBJECT \"\u00e4\u00fc\" ALL",
                new SubjectTerm("\u00e4\u00fc"));
        assertSearchArguments("SUBJECT \"\u2019\u7cfb\u7edf\" ALL",
                new SubjectTerm("\u2019\u7cfb\u7edf"));
        assertSearchArguments("FROM \"\u2019\u7cfb\u7edf\" ALL",
                new FromStringTerm("\u2019\u7cfb\u7edf"));
    }

    /**
     * Test that double quotes and backslashes in UTF-8 search text are
     * escaped in the quoted string.
     */
    @Test
    public void testUtf8SearchTextWithQuotesAndBackslashes() throws Exception {
        assertSearchArguments("SUBJECT \"a\\\"\u00e4\\\\b\" ALL",
                new SubjectTerm("a\"\u00e4\\b"));
    }

    /**
     * Search with the given term on a server with UTF8=ACCEPT enabled and
     * check the arguments of the SEARCH command it receives.
     */
    private static void assertSearchArguments(String expected, SearchTerm term)
            throws Exception {
        final String[] searchLine = new String[1];
        TestServer server = new TestServer(new IMAPUtf8Handler() {
            @Override
            public void search(String line) throws IOException {
                searchLine[0] = line;
                untagged("SEARCH");
                ok();
            }
        });
        try {
            server.start();

            final Properties properties = new Properties();
            properties.setProperty("mail.imap.host", "localhost");
            properties.setProperty("mail.imap.port", String.valueOf(server.getPort()));
            properties.setProperty("mail.imap.throwsearchexception", "true");
            final Session session = Session.getInstance(properties);

            final Store store = session.getStore("imap");
            store.connect("test", "test");
            try {
                Folder folder = store.getFolder("INBOX");
                folder.open(Folder.READ_ONLY);
                folder.search(term);
                folder.close(false);
            } finally {
                store.close();
            }
        } finally {
            server.quit();
        }
        String line = searchLine[0];
        assertEquals(expected, line.substring(line.indexOf(" SEARCH ") + " SEARCH ".length()));
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
