/*
 * Copyright (c) 2009, 2026 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.mbox;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Test of mbox folders.
 */
public final class MboxFolderTest {

    @BeforeClass
    public static void before() {
        System.setProperty("mail.mbox.locktype", "none");
    }

    @AfterClass
    public static void after() {
        System.getProperties().remove("mail.mbox.locktype");
    }

    /**
     * Test that a mailbox that has garbage at the beginning
     * (such as a gratuitous blank line) is handled without
     * crashing and without corrupting the mailbox.
     */
    @Test
    public void testGarbageAtStartOfFolder() throws Exception {
        Folder f = null;
        try {
            File temp = File.createTempFile("mbox", ".mbx");
            temp.deleteOnExit();
            PrintWriter pw = new PrintWriter(temp);
            pw.println();
            pw.println("From - Tue Aug 23 11:56:51 2011");
            pw.println();
            pw.println("test");
            pw.println();
            pw.close();
            long size = temp.length();

            Properties properties = new Properties();
            Session session = Session.getInstance(properties);
            Store store = session.getStore("mbox");
            store.connect();
            f = store.getFolder(temp.getAbsolutePath());
            f.open(Folder.READ_WRITE);
            assertEquals(0, f.getMessageCount());
            f.close(true);
            assertEquals(size, temp.length());
        } catch (Exception ex) {
            System.out.println(ex);
            //ex.printStackTrace();
            fail(ex.toString());
        } finally {
            if (f != null) {
                f.delete(false);
                f.getStore().close();
            }
        }
    }

    /**
     * Test that a Content-Length header in a delivered message can't
     * end the message early and make the rest of its body appear as a
     * separate message with headers chosen by the sender.
     */
    @Test
    public void testForgedContentLengthDoesNotSplitMessage() throws Exception {
        assertSubjects(
                "From alice@example.com Tue Aug 23 11:56:51 2011\n" +
                "From: alice@example.com\n" +
                "Subject: hello\n" +
                "Content-Length: 6\n" +
                "\n" +
                "hello\n" +
                "From: ceo@example.com\n" +
                "Subject: forged\n" +
                "\n" +
                "pay now\n" +
                "\n",
                "hello");
    }

    /**
     * Test that a negative Content-Length header doesn't hide the
     * messages that follow it.
     */
    @Test
    public void testNegativeContentLength() throws Exception {
        assertSubjects(
                "From alice@example.com Tue Aug 23 11:56:51 2011\n" +
                "Subject: one\n" +
                "Content-Length: -1\n" +
                "\n" +
                "body\n" +
                "\n" +
                "From bob@example.com Tue Aug 23 11:57:51 2011\n" +
                "Subject: two\n" +
                "\n" +
                "body\n" +
                "\n",
                "one", "two");
    }

    /**
     * Test that a Content-Length header that ends at the next message
     * separator is still used, so a body line starting with "From "
     * doesn't split the message.
     */
    @Test
    public void testValidContentLength() throws Exception {
        assertSubjects(
                "From alice@example.com Tue Aug 23 11:56:51 2011\n" +
                "Subject: one\n" +
                "Content-Length: 15\n" +
                "\n" +
                "From here\n" +
                "body\n" +
                "\n" +
                "From bob@example.com Tue Aug 23 11:57:51 2011\n" +
                "Subject: two\n" +
                "Content-Length: 5\n" +
                "\n" +
                "body\n" +
                "\n",
                "one", "two");
    }

    private static void assertSubjects(String mbox, String... subjects)
            throws Exception {
        Folder f = null;
        try {
            File temp = File.createTempFile("mbox", ".mbx");
            temp.deleteOnExit();
            try (OutputStream os = new FileOutputStream(temp)) {
                os.write(mbox.getBytes(StandardCharsets.ISO_8859_1));
            }

            Properties properties = new Properties();
            Session session = Session.getInstance(properties);
            Store store = session.getStore("mbox");
            store.connect();
            f = store.getFolder(temp.getAbsolutePath());
            f.open(Folder.READ_ONLY);
            Message[] msgs = f.getMessages();
            String[] actual = new String[msgs.length];
            for (int i = 0; i < msgs.length; i++)
                actual[i] = msgs[i].getSubject();
            assertArrayEquals(subjects, actual);
        } finally {
            if (f != null) {
                if (f.isOpen())
                    f.close(false);
                f.delete(false);
                f.getStore().close();
            }
        }
    }
}
