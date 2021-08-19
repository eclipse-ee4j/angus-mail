/*
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.mail.util;

import static jakarta.mail.Message.RecipientType.TO;
import static jakarta.mail.internet.MimeMessage.RecipientType.NEWSGROUPS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.Properties;

import org.junit.Test;

import com.sun.mail.test.AsciiStringInputStream;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.NewsAddress;
import jakarta.mail.util.StreamProvider.EncoderTypes;

/**
 * Test MimeMessage methods.
 *
 * XXX - just a beginning...
 *
 * @author Bill Shannon
 */
public class MimeMessageTest {

    private static final Session s = Session.getInstance(new Properties());

    /**
     * Test that setRecipients with a null string address removes the header.
     * (Bug 7021190)
     */
    @Test
    public void testSetRecipientsStringNull() throws Exception {
	String addr = "joe@example.com";
	MimeMessage m = new MimeMessage(s);
	m.setRecipients(TO, addr);
	assertEquals("To: is set", addr, m.getRecipients(TO)[0].toString());
	m.setRecipients(TO, (String)null);
	assertArrayEquals("To: is removed", null, m.getRecipients(TO));
    }

    /**
     * Test that setRecipient with a null string address removes the header.
     * (Bug 7536)
     */
    @Test
    public void testSetRecipientStringNull() throws Exception {
	String addr = "joe@example.com";
	MimeMessage m = new MimeMessage(s);
	m.setRecipient(TO, new InternetAddress(addr));
	assertEquals("To: is set", addr, m.getRecipients(TO)[0].toString());
	m.setRecipient(TO, (Address)null);
	assertArrayEquals("To: is removed", null, m.getRecipients(TO));
    }

    /**
     * Test that setFrom with a null address removes the header.
     * (Bug E 456)
     */
    @Test
    public void testSetFromStringNull() throws Exception {
        String addr = "joe@example.com";
        MimeMessage m = new MimeMessage(s);
        m.setFrom(new InternetAddress(addr));
        assertEquals("From: is set", addr, m.getFrom()[0].toString());
        m.setFrom((Address) null);
        assertArrayEquals("From: is removed", null, m.getFrom());
    }

    /**
     * Test that setSender with a null address removes the header.
     * (Bug E 456)
     */
    @Test
    public void testSetSenderStringNull() throws Exception {
        String addr = "joe@example.com";
        MimeMessage m = new MimeMessage(s);
        m.setSender(new InternetAddress(addr));
        assertEquals("Sender: is set", addr, m.getSender().toString());
        m.setSender((Address) null);
        assertNull("Sender: is removed", m.getSender());
    }

    /**
     * Test that setFrom with an address containing a newline is folded
     * properly.
     * (Bug 7529)
     */
    @Test
    public void testSetFromFold() throws Exception {
	InternetAddress addr = new InternetAddress("joe@bad.com", "Joe\r\nBad");
	MimeMessage m = new MimeMessage(s);
	m.setFrom(addr);
	assertEquals("Joe\r\n Bad <joe@bad.com>", m.getHeader("From", null));
    }

    /**
     * Test that setSender with an address containing a newline is folded
     * properly.
     * (Bug 7529)
     */
    @Test
    public void testSetSenderFold() throws Exception {
	InternetAddress addr = new InternetAddress("joe@bad.com", "Joe\r\nBad");
	MimeMessage m = new MimeMessage(s);
	m.setSender(addr);
	assertEquals("Joe\r\n Bad <joe@bad.com>", m.getHeader("Sender", null));
    }

    /**
     * Test that setRecipient with a newsgroup address containing a newline is
     * handled properly.
     * (Bug 7529)
     */
    @Test
    public void testSetNewsgroupWhitespace() throws Exception {
	NewsAddress addr = new NewsAddress("alt.\r\nbad");
	MimeMessage m = new MimeMessage(s);
	m.setRecipient(NEWSGROUPS, addr);
	assertEquals("alt.bad", m.getHeader("Newsgroups", null));
    }

    /**
     * Test that setRecipients with many newsgroup addresses is folded properly.
     * (Bug 7529)
     */
    @Test
    public void testSetNewsgroupFold() throws Exception {
	NewsAddress[] longng = NewsAddress.parse(
	    "alt.loooooooooooooooooooooooooooooooooooooooooooooooooong," +
	    "alt.verylongggggggggggggggggggggggggggggggggggggggggggggg");
	MimeMessage m = new MimeMessage(s);
	m.setRecipients(NEWSGROUPS, longng);
	assertTrue(m.getHeader("Newsgroups", null).indexOf("\r\n\t") > 0);
    }

    /**
     * Test that newsgroups can be set and read back (even if folded).
     */
    @Test
    public void testSetGetNewsgroups() throws Exception {
	NewsAddress[] longng = NewsAddress.parse(
	    "alt.loooooooooooooooooooooooooooooooooooooooooooooooooong," +
	    "alt.verylongggggggggggggggggggggggggggggggggggggggggggggg");
	MimeMessage m = new MimeMessage(s);
	m.setRecipients(NEWSGROUPS, longng);
	assertArrayEquals(longng, m.getRecipients(NEWSGROUPS));
    }

    /**
     * Test that copying a DataHandler from one message to another
     * has the desired effect.
     */
    @Test
    public void testCopyDataHandler() throws Exception {
	Session s = Session.getInstance(new Properties());
	// create a message and extract the DataHandler
	MimeMessage orig = createMessage(s);
	DataHandler dh = orig.getDataHandler();
	// create a new message and use the DataHandler
	MimeMessage msg = new MimeMessage(s);
	msg.setDataHandler(dh);
	// depend on copy constructor streaming the data
	msg = new MimeMessage(msg);
	assertEquals("text/x-test", msg.getContentType());
	assertEquals(EncoderTypes.QUOTED_PRINTABLE_ENCODER.getEncoder(), msg.getEncoding());
	assertEquals("test message", getString(msg.getInputStream()));
    }

    /**
     * Test that copying a DataHandler from one message to another
     * by setting the "dh" field in a subclass has the desired effect.
     */
    @Test
    public void testSetDataHandler() throws Exception {
	Session s = Session.getInstance(new Properties());
	// create a message and extract the DataHandler for a part
	MimeMessage orig = createMessage(s);
	final DataHandler odh = orig.getDataHandler();
	// create a new message and use the DataHandler
	MimeMessage msg = new MimeMessage(s) {
		{ dh = odh; }
	    };
	// depend on copy constructor streaming the data
	msg = new MimeMessage(msg);
	assertEquals("text/x-test", msg.getContentType());
	assertEquals(EncoderTypes.QUOTED_PRINTABLE_ENCODER.getEncoder(), msg.getEncoding());
	assertEquals("test message", getString(msg.getInputStream()));
    }

    /**
     * Test that address headers account for the header length when folding.
     */
    @Test
    public void testAddressHeaderFolding() throws Exception {
	Session s = Session.getInstance(new Properties());
	MimeMessage msg = new MimeMessage(s);
	InternetAddress[] addrs = InternetAddress.parse(
	"long-address1@example.com, long-address2@example.com, joe@foobar.com");
	msg.setReplyTo(addrs);	// use Reply-To because it's a long header name
	Enumeration<String> e 
		= msg.getMatchingHeaderLines(new String[] { "Reply-To" });
	String line = e.nextElement();
	int npos = line.indexOf("\r");
	// was the line folded where we expected?
	assertTrue("Header folded",
	    npos > 9 && npos <= 77 && npos < line.length());
    }

    private static MimeMessage createMessage(Session s)
				throws MessagingException {
        String content =
	    "Mime-Version: 1.0\n" +
	    "Subject: Example\n" +
	    "Content-Type: text/x-test\n" +
	    "Content-Transfer-Encoding: quoted-printable\n" +
	    "\n" +
	    "test message\n";

	return new MimeMessage(s, new AsciiStringInputStream(content));
    }

    private static String getString(InputStream is) throws IOException {
	BufferedReader r = new BufferedReader(new InputStreamReader(is));
	return r.readLine();
    }
}
