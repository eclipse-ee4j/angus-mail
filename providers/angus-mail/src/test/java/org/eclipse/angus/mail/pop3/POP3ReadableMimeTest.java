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

package org.eclipse.angus.mail.pop3;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.test.TestServer;
import org.eclipse.angus.mail.util.ReadableMime;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test ReadableMime support for POP3.
 *
 * @author Bill Shannon
 */
public final class POP3ReadableMimeTest {

    private static TestServer server = null;
    private static Store store;
    private static Folder folder;

    private static void startServer(boolean cached) {
        startServer(new POP3Handler(), cached);
    }

    private static void startServer(POP3Handler handler, boolean cached) {
        try {
            server = new TestServer(handler);
            server.start();
            Thread.sleep(1000);

            final Properties properties = new Properties();
            properties.setProperty("mail.pop3.host", "localhost");
            properties.setProperty("mail.pop3.port", String.valueOf(server.getPort()));
            if (cached)
                properties.setProperty("mail.pop3.filecache.enable", "true");
            final Session session = Session.getInstance(properties);
            //session.setDebug(true);

            store = session.getStore("pop3");
            store.connect("test", "test");
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
        } catch (final Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    private static void stopServer() {
        try {
            if (folder != null)
                folder.close(false);
            if (store != null)
                store.close();
        } catch (MessagingException ex) {
            // ignore it
        } finally {
            if (server != null)
                server.quit();
        }
    }

    /**
     * Test that the data returned by the getMimeStream method
     * is exactly the same data as produced by the writeTo method.
     */
    @Test
    public void testReadableMime() throws Exception {
        test(false);
    }

    /**
     * Now test it using the file cache.
     */
    @Test
    public void testReadableMimeCached() throws Exception {
        test(true);
    }

    /**
     * Test that writeTo does not write a message that it streams from the
     * server to the output stream one byte at a time (issue #99).
     */
    @Test
    public void testWriteToWritesInBlocks() throws Exception {
        startServer(false);
        try {
            for (Message msg : folder.getMessages()) {
                CountingOutputStream os = new CountingOutputStream();
                msg.writeTo(os);
                assertTrue("block writes", os.blockWrites > 0);
                assertEquals("single byte writes", 0, os.singleByteWrites);
                verifyData(msg, os.toByteArray());
            }
        } finally {
            stopServer();
        }
    }

    /**
     * Test that writeTo writes messages of sizes around and above the size
     * of the blocks it writes, with lines that start with a dot, exactly as
     * the server sent them, without the dot stuffing.
     */
    @Test
    public void testWriteToMessagesAroundBlockSize() throws Exception {
        // the messages are written in blocks of 8192 bytes
        for (int size : new int[] {8191, 8192, 8193, 3 * 8192})
            assertWriteTo(message(size));
    }

    /**
     * Test that an exception thrown by the output stream is thrown by
     * writeTo after the whole message has been read, so that the
     * connection can still be used.
     */
    @Test
    public void testWriteToThrowsStreamException() throws Exception {
        for (Exception failure : new Exception[] {
                new IOException("write failed"), new IllegalStateException("write failed")}) {
            startServer(false);
            try {
                Message[] msgs = folder.getMessages();
                OutputStream failing = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throwIt();
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        throwIt();
                    }

                    private void throwIt() throws IOException {
                        if (failure instanceof IOException)
                            throw (IOException) failure;
                        throw (RuntimeException) failure;
                    }
                };
                Exception thrown = assertThrows(Exception.class, () -> msgs[0].writeTo(failing));
                assertSame(failure, thrown);
                verifyData(msgs[1]);
            } finally {
                stopServer();
            }
        }
    }

    /**
     * Test that when the server closes the connection after a write to the
     * output stream failed, the EOFException keeps the write failure as a
     * suppressed exception.
     */
    @Test
    public void testWriteToKeepsStreamExceptionOnEOF() throws Exception {
        final String message = message(3 * 8192).replace("\r\n.", "\r\n..");
        startServer(new POP3Handler() {
            @Override
            public void retr(String arg) throws IOException {
                println("+OK " + message.length() + " octets");
                // two blocks of the message, then close the connection
                writer.write(message.substring(0, 2 * 8192));
                writer.flush();
                exit();
            }
        }, false);
        try {
            final IOException failure = new IOException("write failed");
            OutputStream failing = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw failure;
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw failure;
                }
            };
            EOFException eof = assertThrows(EOFException.class,
                    () -> folder.getMessage(1).writeTo(failing));
            assertArrayEquals(new Throwable[] {failure}, eof.getSuppressed());
        } finally {
            stopServer();
        }
    }

    /**
     * A message of the given size, in which some lines start with a dot.
     */
    private static String message(int size) {
        StringBuilder sb = new StringBuilder("Mime-Version: 1.0\r\nSubject: test\r\n\r\n");
        for (int i = 0; sb.length() + 20 < size; i++)
            sb.append(i % 7 == 0 ? ".line " : "line ").append(i).append("\r\n");
        while (sb.length() < size - 2)
            sb.append('x');
        return sb.append("\r\n").toString();
    }

    /**
     * Serve the message from a server and check that writeTo writes it exactly.
     */
    private static void assertWriteTo(final String message) throws Exception {
        startServer(new POP3Handler() {
            @Override
            public void retr(String arg) throws IOException {
                println("+OK " + message.length() + " octets");
                writer.write(message.replace("\r\n.", "\r\n.."));
                println(".");
            }
        }, false);
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            folder.getMessage(1).writeTo(os);
            assertEquals("message of " + message.length() + " bytes", message, os.toString("US-ASCII"));
        } finally {
            stopServer();
        }
    }

    /**
     * Counts the calls of write(int).
     */
    private static final class CountingOutputStream extends ByteArrayOutputStream {
        int singleByteWrites;
        int blockWrites;

        @Override
        public synchronized void write(int b) {
            singleByteWrites++;
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            blockWrites++;
            super.write(b, off, len);
        }
    }

    private void test(boolean cached) throws Exception {
        startServer(cached);
        try {
            Message[] msgs = folder.getMessages();
            for (int i = 0; i < msgs.length; i++)
                verifyData(msgs[i]);
        } finally {
            stopServer();
        }
        // no exception is success!
    }

    private void verifyData(Part p) throws MessagingException, IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        p.writeTo(bos);
        bos.close();
        verifyData(p, bos.toByteArray());
    }

    private void verifyData(Part p, byte[] buf) throws MessagingException, IOException {
        assertTrue("ReadableMime", p instanceof ReadableMime);
        InputStream is = null;
        try {
            ReadableMime rp = (ReadableMime) p;
            is = rp.getMimeStream();
            int i, b;
            for (i = 0; (b = is.read()) != -1; i++)
                assertTrue("message data", b == (buf[i] & 0xff));
            assertTrue("data size", i == buf.length);
        } finally {
            try {
                is.close();
            } catch (IOException ex) {
            }
        }
    }
}
