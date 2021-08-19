/*
 * Copyright (c) 2009, 2021 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.mail.imap;

import java.io.IOException;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.MessagingException;

import com.sun.mail.test.TestServer;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.Timeout;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Test IMAPStore methods.
 */
public final class IMAPStoreTest {

    // timeout the test in case of deadlock
    @Rule
    public Timeout deadlockTimeout = Timeout.seconds(20);

    private static final String utf8Folder = "test\u03b1";
    private static final String utf7Folder = "test&A7E-";

    public static abstract class IMAPTest {
	public void init(Properties props) { };
	public void test(Store store, TestServer server) throws Exception { };
    }

    /**
     * Test that UTF-8 user name works with LOGIN authentication.
     */
    @Test
    public void testUtf8UsernameLogin() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect(utf8Folder, utf8Folder);
		}
	    },
	    new IMAPLoginHandler() {
		@Override
		public void authlogin(String ir)
					throws IOException {
		    username = utf8Folder;
		    password = utf8Folder;
		    super.authlogin(ir);
		}
	    });
    }

    /**
     * Test that UTF-8 user name works with PLAIN authentication.
     */
    @Test
    public void testUtf8UsernamePlain() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect(utf8Folder, utf8Folder);
		}
	    },
	    new IMAPPlainHandler() {
		@Override
		public void authplain(String ir)
					throws IOException {
		    username = utf8Folder;
		    password = utf8Folder;
		    super.authplain(ir);
		}
	    });
    }

    /**
     * Test that UTF-7 folder names in the NAMESPACE command are
     * decoded properly.
     */
    @Test
    public void testUtf7Namespaces() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder[] pub = ((IMAPStore)store).getSharedNamespaces();
		    assertEquals(utf8Folder, pub[0].getName());
		}
	    },
	    new IMAPHandler() {
		{{ capabilities += " NAMESPACE"; }}
		@Override
		public void namespace() throws IOException {
		    untagged("NAMESPACE ((\"\" \"/\")) ((\"~\" \"/\")) " +
			"((\"" + utf7Folder + "/\" \"/\"))");
		    ok();
		}
	    });
    }

    /**
     * Test that using a UTF-8 folder name results in the proper UTF-8
     * unencoded name for the CREATE command.
     */
    @Test
    public void testUtf8FolderNameCreate() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder test = store.getFolder(utf8Folder);
		    assertTrue(test.create(Folder.HOLDS_MESSAGES));
		}
	    },
	    new IMAPUtf8Handler() {
		@Override
		public void create(String line) throws IOException {
		    StringTokenizer st = new StringTokenizer(line);
		    st.nextToken();	// skip tag
		    st.nextToken();	// skip "CREATE"
		    String name = unquote(st.nextToken());
		    if (name.equals(utf8Folder))
			ok();
		    else
			no("wrong name");
		}

		@Override
		public void list(String line) throws IOException {
		    untagged("LIST (\\HasNoChildren) \"/\" \"" +
							utf8Folder + "\"");
		    ok();
		}
	    });
    }

    /**
     * Test that Store.close also closes open Folders.
     */
    @Test
    public void testCloseClosesFolder() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder test = store.getFolder("INBOX");
		    test.open(Folder.READ_ONLY);
		    store.close();
		    assertFalse(test.isOpen());
		    assertEquals(1, server.clientCount());
		    server.waitForClients(1);
		    // test will timeout if clients don't terminate
		}
	    },
	    new IMAPHandler() {
	    });
    }

    /**
     * Test that Store.close closes connections in the pool.
     */
    @Test
    public void testCloseEmptiesPool() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void init(Properties props) {
		    props.setProperty("mail.imap.connectionpoolsize", "2");
		}

		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder test = store.getFolder("INBOX");
		    test.open(Folder.READ_ONLY);
		    Folder test2 = store.getFolder("INBOX");
		    test2.open(Folder.READ_ONLY);
		    test.close(false);
		    test2.close(false);
		    store.close();
		    assertEquals(2, server.clientCount());
		    server.waitForClients(2);
		    // test will timeout if clients don't terminate
		}
	    },
	    new IMAPHandler() {
	    });
    }

    /**
     * Test that Store failures don't close Folders.
     */
    @Test
    public void testStoreFailureDoesNotCloseFolder() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void init(Properties props) {
		    props.setProperty(
			"mail.imap.closefoldersonstorefailure", "false");
		}

		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder test = store.getFolder("INBOX");
		    test.open(Folder.READ_ONLY);
		    try {
			((IMAPStore)store).getSharedNamespaces();
			fail("MessagingException expected");
		    } catch (MessagingException mex) {
			// expected
		    }
		    assertTrue(test.isOpen());
		    store.close();
		    assertFalse(test.isOpen());
		    assertEquals(2, server.clientCount());
		    server.waitForClients(2);
		    // test will timeout if clients don't terminate
		}
	    },
	    new IMAPHandler() {
		{{ capabilities += " NAMESPACE"; }}

		@Override
		public void namespace() throws IOException {
		    exit();
		}
	    });
    }

    /**
     * Test that Store.close after Store failure will close all Folders
     * and empty the connectin pool.
     */
    @Test
    public void testCloseAfterFailure() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void init(Properties props) {
		    props.setProperty(
			"mail.imap.closefoldersonstorefailure", "false");
		}

		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder test = store.getFolder("INBOX");
		    test.open(Folder.READ_ONLY);
		    try {
			((IMAPStore)store).getSharedNamespaces();
			fail("MessagingException expected");
		    } catch (MessagingException mex) {
			// expected
		    }
		    assertTrue(test.isOpen());
		    test.close();	// put it back in the pool
		    store.close();
		    assertEquals(2, server.clientCount());
		    server.waitForClients(2);
		    // test will timeout if clients don't terminate
		}
	    },
	    new IMAPHandler() {
		{{ capabilities += " NAMESPACE"; }}

		@Override
		public void namespace() throws IOException {
		    exit();
		}
	    });
    }

    /**
     * Test that Store failures do close Folders.
     */
    @Test
    public void testStoreFailureDoesCloseFolder() {
	testWithHandler(
	    new IMAPTest() {
		@Override
		public void init(Properties props) {
		    props.setProperty(
			// the default, but just to be sure...
			"mail.imap.closefoldersonstorefailure", "true");
		}

		@Override
		public void test(Store store, TestServer server)
				    throws MessagingException, IOException {
		    store.connect("test", "test");
		    Folder test = store.getFolder("INBOX");
		    test.open(Folder.READ_ONLY);
		    try {
			((IMAPStore)store).getSharedNamespaces();
			fail("MessagingException expected");
		    } catch (MessagingException mex) {
			// expected
		    }
		    assertFalse(test.isOpen());
		    store.close();
		    assertEquals(2, server.clientCount());
		    server.waitForClients(2);
		    // test will timeout if clients don't terminate
		}
	    },
	    new IMAPHandler() {
		{{ capabilities += " NAMESPACE"; }}

		@Override
		public void namespace() throws IOException {
		    exit();
		}
	    });
    }

    private void testWithHandler(IMAPTest test, IMAPHandler handler) {
        TestServer server = null;
        try {
            server = new TestServer(handler);
            server.start();

            final Properties properties = new Properties();
            properties.setProperty("mail.imap.host", "localhost");
            properties.setProperty("mail.imap.port", "" + server.getPort());
	    test.init(properties);
            final Session session = Session.getInstance(properties);
            //session.setDebug(true);

            final Store store = session.getStore("imap");
            try {
		test.test(store, server);
	    } catch (Exception ex) {
		System.out.println(ex);
		//ex.printStackTrace();
		fail(ex.toString());
            } finally {
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

    private static String unquote(String s) {
	if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
	    s = s.substring(1, s.length() - 1);
	    // check for any escaped characters
	    if (s.indexOf('\\') >= 0) {
		StringBuilder sb = new StringBuilder(s.length());	// approx
		for (int i = 0; i < s.length(); i++) {
		    char c = s.charAt(i);
		    if (c == '\\' && i < s.length() - 1)
			c = s.charAt(++i);
		    sb.append(c);
		}
		s = sb.toString();
	    }
	}
	return s;
    }

    /**
     * An IMAPHandler that enables UTF-8 support.
     */
    private static class IMAPUtf8Handler extends IMAPHandler {
	{{ capabilities += " ENABLE UTF8=ACCEPT"; }}

	@Override
	public void enable(String line) throws IOException {
	    ok();
	}
    }
}
