/*
 * Copyright (c) 1997, 2023 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.gimap;

import jakarta.mail.*;

import org.eclipse.angus.mail.iap.*;
import org.eclipse.angus.mail.imap.*;
import org.eclipse.angus.mail.imap.protocol.*;
import org.eclipse.angus.mail.gimap.protocol.*;
import org.eclipse.angus.mail.gimap.protocol.GmailProtocol;

/**
 * A Gmail folder.  Defines new FetchProfile items and
 * uses GmailMessage to store additional Gmail message attributes.
 *
 * @since JavaMail 1.4.6
 * @author Bill Shannon
 */

public class GmailFolder extends IMAPFolder {
    /**
     * A fetch profile item for fetching headers.
     * This inner class extends the <code>FetchProfile.Item</code>
     * class to add new FetchProfile item types, specific to Gmail.
     *
     * @see FetchProfile
     */
    public static class FetchProfileItem extends FetchProfile.Item {
	protected FetchProfileItem(String name) {
	    super(name);
	}

	/**
	 * MSGID is a fetch profile item that can be included in a
	 * <code>FetchProfile</code> during a fetch request to a Folder.
	 * This item indicates that the Gmail unique message ID for messages
	 * in the specified range are desired to be prefetched. <p>
	 * 
	 * An example of how a client uses this is below:
	 * <blockquote><pre>
	 *
	 * 	FetchProfile fp = new FetchProfile();
	 *	fp.add(GmailFolder.FetchProfileItem.MSGID);
	 *	folder.fetch(msgs, fp);
	 *
	 * </pre></blockquote>
	 */ 
	public static final FetchProfileItem MSGID = 
		new FetchProfileItem("X-GM-MSGID");

	/**
	 * THRID is a fetch profile item that can be included in a
	 * <code>FetchProfile</code> during a fetch request to a Folder.
	 * This item indicates that the Gmail unique thread ID for messages
	 * in the specified range are desired to be prefetched. <p>
	 * 
	 * An example of how a client uses this is below:
	 * <blockquote><pre>
	 *
	 * 	FetchProfile fp = new FetchProfile();
	 *	fp.add(GmailFolder.FetchProfileItem.THRID);
	 *	folder.fetch(msgs, fp);
	 *
	 * </pre></blockquote>
	 */ 
	public static final FetchProfileItem THRID = 
		new FetchProfileItem("X-GM-THRID");

	/**
	 * LABELS is a fetch profile item that can be included in a
	 * <code>FetchProfile</code> during a fetch request to a Folder.
	 * This item indicates that the Gmail labels for messages
	 * in the specified range are desired to be prefetched. <p>
	 * 
	 * An example of how a client uses this is below:
	 * <blockquote><pre>
	 *
	 * 	FetchProfile fp = new FetchProfile();
	 *	fp.add(GmailFolder.FetchProfileItem.LABELS);
	 *	folder.fetch(msgs, fp);
	 *
	 * </pre></blockquote>
	 */ 
	public static final FetchProfileItem LABELS = 
		new FetchProfileItem("X-GM-LABELS");
    }

    /**
     * Set the specified labels for the given array of messages.
     *
     * @param	msgs	the messages
     * @param	labels	the labels to add or remove
     * @param	set	true to add, false to remove
     * @exception	MessagingException	for failures
     * @since	JavaMail 1.5.5
     */
    public synchronized void setLabels(Message[] msgs,
				String[] labels, boolean set)
				throws MessagingException {
	checkOpened();

	if (msgs.length == 0) // boundary condition
	    return;

	synchronized(messageCacheLock) {
	    try {
		IMAPProtocol ip = getProtocol();
		assert ip instanceof GmailProtocol;
		GmailProtocol p = (GmailProtocol)ip;
		MessageSet[] ms = Utility.toMessageSetSorted(msgs, null);
		if (ms == null)
		    throw new MessageRemovedException(
					"Messages have been removed");
		p.storeLabels(ms, labels, set);
	    } catch (ConnectionException cex) {
		throw new FolderClosedException(this, cex.getMessage());
	    } catch (ProtocolException pex) {
		throw new MessagingException(pex.getMessage(), pex);
	    }
	}
    }

    /**
     * Set the specified labels for the given range of message numbers.
     *
     * @param	start	first message number
     * @param	end	last message number
     * @param	labels	the labels to add or remove
     * @param	set	true to add, false to remove
     * @exception	MessagingException	for failures
     * @since	JavaMail 1.5.5
     */
    public synchronized void setLabels(int start, int end,
				String[] labels, boolean set)
				throws MessagingException {
	checkOpened();
	Message[] msgs = new Message[end - start + 1];
	int i = 0;
	for (int n = start; n <= end; n++)
	    msgs[i++] = getMessage(n);
	setLabels(msgs, labels, set);
    }

    /**
     * Set the specified labels for the given array of message numbers.
     *
     * @param	msgnums	the message numbers
     * @param	labels	the labels to add or remove
     * @param	set	true to add, false to remove
     * @exception	MessagingException	for failures
     * @since	JavaMail 1.5.5
     */
    public synchronized void setLabels(int[] msgnums,
				String[] labels, boolean set)
				throws MessagingException {
	checkOpened();
	Message[] msgs = new Message[msgnums.length];
	for (int i = 0; i < msgnums.length; i++)
	    msgs[i] = getMessage(msgnums[i]);
	setLabels(msgs, labels, set);
    }

    /**
     * Constructor used to create a possibly non-existent folder.
     *
     * @param fullName	fullname of this folder
     * @param separator the default separator character for this 
     *			folder's namespace
     * @param store	the Store
     * @param isNamespace does this name represent a namespace?
     */
    protected GmailFolder(String fullName, char separator, IMAPStore store,
				Boolean isNamespace) {
	super(fullName, separator, store, isNamespace);
    }

    /**
     * Constructor used to create an existing folder.
     *
     * @param	li	the ListInfo for this folder
     * @param	store	the store containing this folder
     */
    protected GmailFolder(ListInfo li, IMAPStore store) {
	super(li, store);
    }

    /**
     * Create a new IMAPMessage object to represent the given message number.
     */
    protected IMAPMessage newIMAPMessage(int msgnum) {
	return new GmailMessage(this, msgnum);
    }
}
