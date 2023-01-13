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

import org.eclipse.angus.mail.util.*;
import org.eclipse.angus.mail.iap.*;
import org.eclipse.angus.mail.imap.*;
import org.eclipse.angus.mail.imap.protocol.*;
import org.eclipse.angus.mail.gimap.protocol.*;
import org.eclipse.angus.mail.gimap.protocol.GmailProtocol;

/**
 * A Gmail message.  Adds methods to access Gmail-specific per-message data.
 *
 * @since JavaMail 1.4.6
 * @author Bill Shannon
 */

public class GmailMessage extends IMAPMessage {
    /**
     * Constructor.
     *
     * @param	folder	the containing folder
     * @param	msgnum	the message sequence number
     */
    protected GmailMessage(IMAPFolder folder, int msgnum) {
	super(folder, msgnum);
    }

    /**
     * Constructor, for use by IMAPNestedMessage.
     *
     * @param	session	the Session
     */
    protected GmailMessage(Session session) {
	super(session);
    }

    /**
     * Return the Gmail unique message ID.
     *
     * @return	the message ID
     * @exception	MessagingException for failures
     */
    public long getMsgId() throws MessagingException {
	Long msgid = (Long)getItem(GmailProtocol.MSGID_ITEM);
	if (msgid != null)
	    return msgid.longValue();
	else
	    return -1;
    }

    /**
     * Return the Gmail unique thread ID.
     *
     * @return	the thread ID
     * @exception	MessagingException for failures
     */
    public long getThrId() throws MessagingException {
	Long thrid = (Long)getItem(GmailProtocol.THRID_ITEM);
	if (thrid != null)
	    return thrid.longValue();
	else
	    return -1;
    }

    /**
     * Return the Gmail labels associated with this message.
     *
     * @return	array of labels, or empty array if none
     * @exception	MessagingException for failures
     */
    public String[] getLabels() throws MessagingException {
	String[] labels = (String[])getItem(GmailProtocol.LABELS_ITEM);
	if (labels != null)
	    return (String[])(labels.clone());
	else
	    return new String[0];
    }

    /**
     * Set/Unset the given labels on this message.
     *
     * @param	labels	the labels to add or remove
     * @param	set	true to add labels, false to remove
     * @exception	MessagingException for failures
     * @since JavaMail 1.5.5
     */
    public synchronized void setLabels(String[] labels, boolean set)
			throws MessagingException {
        // Acquire MessageCacheLock, to freeze seqnum.
        synchronized(getMessageCacheLock()) {
	    try {
		IMAPProtocol ip = getProtocol();
		assert ip instanceof GmailProtocol;
		GmailProtocol p = (GmailProtocol)ip;
		checkExpunged(); // Insure that this message is not expunged
		p.storeLabels(getSequenceNumber(), labels, set);
	    } catch (ConnectionException cex) {
		throw new FolderClosedException(folder, cex.getMessage());
	    } catch (ProtocolException pex) {
		throw new MessagingException(pex.getMessage(), pex);
	    }
	}
    }

    /**
     * Clear any cached labels for this message.
     * The Gmail labels for a messge will be cached when first accessed
     * using either the fetch method or the getLabels method.  Gmail provides
     * no notification when the labels have been changed by another application
     * so applications may need to clear the cache if accessing the labels for
     * a message more than once while the Folder is open.
     *
     * @since JavaMail 1.5.6
     */
    public synchronized void clearCachedLabels() {
	if (items != null)
	    items.remove(GmailProtocol.LABELS_ITEM.getName());
    }
}
