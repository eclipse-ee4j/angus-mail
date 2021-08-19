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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringTokenizer;
import java.util.logging.Level;

import com.sun.mail.test.ProtocolHandler;

/**
 * Handle IMAP connection.
 *
 * @author Bill Shannon
 */
public class IMAPHandler extends ProtocolHandler {

    /** Current line. */
    private String currentLine;

    /** Tag for current command */
    protected String tag;

    /** IMAP capabilities supported */
    protected String capabilities = "IMAP4REV1 IDLE ID";

    /** Number of messages */
    protected int numberOfMessages = 0;

    /** Number of recent messages */
    protected int numberOfRecentMessages = 0;

    /**
     * Send greetings.
     *
     * @throws IOException unable to write to socket
     */
    @Override
    public void sendGreetings() throws IOException {
        untagged("OK [CAPABILITY " + capabilities + "] IMAPHandler");
    }

    /**
     * Send String to socket.
     *
     * @param str String to send
     * @throws IOException unable to write to socket
     */
    public void println(final String str) throws IOException {
        writer.print(str);
	writer.print("\r\n");
        writer.flush();
    }

    /**
     * Send a tagged response.
     *
     * @param resp the response to send
     * @throws IOException unable to read/write to socket
     */
    public void tagged(final String resp) throws IOException {
	println(tag + " " + resp);
    }

    /**
     * Send an untagged response.
     *
     * @param resp the response to send
     * @throws IOException unable to read/write to socket
     */
    public void untagged(final String resp) throws IOException {
	println("* " + resp);
    }

    /**
     * Send a tagged OK response.
     *
     * @throws IOException unable to read/write to socket
     */
    public void ok() throws IOException {
	tagged("OK");
    }

    /**
     * Send a tagged OK response with a message.
     *
     * @param msg the message to send
     * @throws IOException unable to read/write to socket
     */
    public void ok(final String msg) throws IOException {
	tagged("OK " + (msg != null ? msg : ""));
    }

    /**
     * Send a tagged NO response with a message.
     *
     * @param msg the message to send
     * @throws IOException unable to read/write to socket
     */
    public void no(final String msg) throws IOException {
	tagged("NO " + (msg != null ? msg : ""));
    }

    /**
     * Send a tagged BAD response with a message.
     *
     * @param msg the message to send
     * @throws IOException unable to read/write to socket
     */
    public void bad(final String msg) throws IOException {
	tagged("BAD " + (msg != null ? msg : ""));
    }

    /**
     * Send an untagged BYE response with a message, then exit.
     *
     * @param msg the message to send
     * @throws IOException unable to read/write to socket
     */
    public void bye(final String msg) throws IOException {
	untagged("BYE " + (msg != null ? msg : ""));
	exit();
    }

    /**
     * Send a "continue" command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void cont() throws IOException {
	println("+ please continue");
    }

    /**
     * Send a "continue" command with a message.
     *
     * @throws IOException unable to read/write to socket
     */
    public void cont(String msg) throws IOException {
	println("+ " + (msg != null ? msg : ""));
    }

    /**
     * Handle command.
     *
     * @throws IOException unable to read/write to socket
     */
    @Override
    public void handleCommand() throws IOException {
        currentLine = super.readLine();

        if (currentLine == null) {
	    // probably just EOF because the socket was closed
            //LOGGER.severe("Current line is null!");
            exit();
            return;
        }

        StringTokenizer ct = new StringTokenizer(currentLine, " ");
	if (!ct.hasMoreTokens()) {
            LOGGER.log(Level.SEVERE, "ERROR no command tag: {0}",
							escape(currentLine));
            bad("no command tag");
	    return;
	}
	tag = ct.nextToken();
	if (!ct.hasMoreTokens()) {
            LOGGER.log(Level.SEVERE, "ERROR no command: {0}",
							escape(currentLine));
            bad("no command");
	    return;
	}
        final String commandName = ct.nextToken().toUpperCase();
        if (commandName == null) {
            LOGGER.severe("Command name is empty!");
            exit();
            return;
        }

        if (commandName.equals("LOGIN")) {
            login();
        } else if (commandName.equals("AUTHENTICATE")) {
	    String mech = ct.nextToken().toUpperCase();
	    String ir = null;
	    if (ct.hasMoreTokens())
	    	ir = ct.nextToken();
            authenticate(mech, ir);
        } else if (commandName.equals("CAPABILITY")) {
            capability();
        } else if (commandName.equals("NOOP")) {
            noop();
        } else if (commandName.equals("SELECT")) {
            select(currentLine);
        } else if (commandName.equals("EXAMINE")) {
            examine(currentLine);
        } else if (commandName.equals("LIST")) {
            list(currentLine);
        } else if (commandName.equals("IDLE")) {
            idle();
        } else if (commandName.equals("FETCH")) {
            fetch(currentLine);
        } else if (commandName.equals("STORE")) {
            store(currentLine);
        } else if (commandName.equals("SEARCH")) {
            search(currentLine);
        } else if (commandName.equals("APPEND")) {
            append(currentLine);
        } else if (commandName.equals("CLOSE")) {
            close();
        } else if (commandName.equals("LOGOUT")) {
            logout();
        } else if (commandName.equals("UID")) {
	    String subcommandName = ct.nextToken().toUpperCase();
	    if (subcommandName.equals("FETCH")) {
		uidfetch(currentLine);
	    } else if (subcommandName.equals("STORE")) {
		uidstore(currentLine);
	    } else {
		LOGGER.log(Level.SEVERE, "ERROR UID command unknown: {0}",
								subcommandName);
		bad("unknown UID command");
	    }
        } else if (commandName.equals("ID")) {
	    id(currentLine);
        } else if (commandName.equals("ENABLE")) {
            enable(currentLine);
        } else if (commandName.equals("CREATE")) {
            create(currentLine);
        } else if (commandName.equals("DELETE")) {
            delete(currentLine);
        } else if (commandName.equals("STATUS")) {
            status(currentLine);
        } else if (commandName.equals("NAMESPACE")) {
            namespace();
        } else {
            LOGGER.log(Level.SEVERE, "ERROR command unknown: {0}",
							escape(currentLine));
            bad("unknown command");
        }
    }

    /**
     * LOGIN command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void login() throws IOException {
        ok("[CAPABILITY " + capabilities + "]");
    }

    /**
     * AUTHENTICATE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void authenticate(String mech, String ir) throws IOException {
	if (mech.equals("LOGIN"))
	    authlogin(ir);
	else if (mech.equals("PLAIN"))
	    authplain(ir);
	else
	    bad("AUTHENTICATE not supported");
    }

    /**
     * AUTHENTICATE LOGIN command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void authlogin(String ir) throws IOException {
	if (ir != null)
	    bad("AUTHENTICATE LOGIN does not support initial response");
	cont(base64encode("Username"));
	String username = readLine();
	cont(base64encode("Password"));
	String password = readLine();
        ok("[CAPABILITY " + capabilities + "]");
    }

    /**
     * AUTHENTICATE PLAIN command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void authplain(String ir) throws IOException {
	if (ir == null) {
	    cont("");
	    String resp = readLine();
	}
        ok("[CAPABILITY " + capabilities + "]");
    }

    /**
     * CAPABILITY command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void capability() throws IOException {
	untagged("CAPABILITY " + capabilities);
        ok();
    }

    /**
     * SELECT command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void select(String line) throws IOException {
	untagged(numberOfMessages + " EXISTS");
	untagged(numberOfRecentMessages + " RECENT");
        ok();
    }

    /**
     * EXAMINE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void examine(String line) throws IOException {
	untagged(numberOfMessages + " EXISTS");
	untagged(numberOfRecentMessages + " RECENT");
        ok();
    }

    /**
     * LIST command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void list(String line) throws IOException {
        ok();
    }

    /**
     * IDLE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void idle() throws IOException {
        cont();
	idleWait();
	ok();
    }

    @Override
    protected String readLine() throws IOException {
        currentLine = super.readLine();
        if (currentLine == null) {
            LOGGER.severe("Current line is null!");
            exit();
        }
	return currentLine;
    }

    protected void idleWait() throws IOException {
        String line = readLine();

        if (line != null && !line.equalsIgnoreCase("DONE")) {
            LOGGER.severe("Didn't get DONE response to IDLE");
            exit();
            return;
        }
    }

    /**
     * FETCH command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void fetch(String line) throws IOException {
        ok();	// XXX
    }

    /**
     * STORE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void store(String line) throws IOException {
        ok();	// XXX
    }

    /**
     * SEARCH command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void search(String line) throws IOException {
	untagged("SEARCH");
        ok();	// XXX
    }

    /**
     * UID FETCH command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void uidfetch(String line) throws IOException {
        ok();	// XXX
    }

    /**
     * UID STORE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void uidstore(String line) throws IOException {
        ok();	// XXX
    }

    /**
     * APPEND command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void append(String line) throws IOException {
	int left = line.lastIndexOf('{');
	int right = line.indexOf('}', left);
	int bytes = Integer.parseInt(line.substring(left + 1, right));
	cont("waiting for message");
	collectMessage(bytes);
        ok();	// XXX
    }

    /**
     * ID command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void id(String line) throws IOException {
	untagged("ID NIL");
        ok();
    }

    /**
     * ENABLE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void enable(String line) throws IOException {
        no("can't enable");
    }

    /**
     * CREATE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void create(String line) throws IOException {
        no("can't create");
    }

    /**
     * DELETE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void delete(String line) throws IOException {
        no("can't delete");
    }

    /**
     * STATUS command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void status(String line) throws IOException {
        no("can't get status");
    }

    /**
     * NAMESPACE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void namespace() throws IOException {
        no("no namespaces");
    }

    /**
     * Collect "bytes" worth of data for the message being appended.
     */
    protected void collectMessage(int bytes) throws IOException {
	readLiteral(bytes);	// read the data and throw it away
	super.readLine();	// data followed by a newline
    }

    /**
     * Read a literal of "bytes" bytes and return it as a UTF-8 string.
     */
    protected String readLiteral(int bytes) throws IOException {
	println("+");
	byte[] data = new byte[bytes];
	int len = data.length;
	int off = 0;
	int n;
	while (len > 0 && (n = in.read(data, off, len)) > 0) {
	    off += n;
	    len -= n;
	}
	return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * CLOSE command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void close() throws IOException {
        ok();
    }

    /**
     * NOOP command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void noop() throws IOException {
        ok();
    }

    /**
     * LOGOUT command.
     *
     * @throws IOException unable to read/write to socket
     */
    public void logout() throws IOException {
        ok();
        exit();
    }

    /**
     * Base64 encode the string.
     */
    protected String base64encode(String s) throws IOException {
	return new String(Base64.getEncoder().encode(s.getBytes("US-ASCII")),
			"US-ASCII");
    }

    /**
     * Escape any non-printable characters in "s",
     * limiting total length to about 100 characters.
     */
    private String escape(String s) {
	StringBuilder sb = new StringBuilder();
	for (int i = 0; i < s.length(); i++) {
	    if (sb.length() >= 100) {
		sb.append("...");
		break;
	    }
	    char c = s.charAt(i);
	    if (c < ' ' || c == '\177') {
		if (c == '\r')
		    sb.append("\\r");
		else if (c == '\n')
		    sb.append("\\n");
		else if (c == '\t')
		    sb.append("\\t");
		else
		    sb.append('\\').append(String.format("%03o", (int)c));
	    } else if (c >= '\200') {
		    sb.append("\\u").append(String.format("%04x", (int)c));
	    } else
		sb.append(c);
	}
	return sb.toString();
    }
}
