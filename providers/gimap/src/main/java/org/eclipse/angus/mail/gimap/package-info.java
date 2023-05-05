/*
 * Copyright (c) 2021 Oracle and/or its affiliates. All rights reserved.
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

/**
 * An EXPERIMENTAL IMAP protocol provider that supports the
 * <A HREF="https://developers.google.com/google-apps/gmail/imap_extensions"
 * TARGET="_top">
 * Gmail-specific IMAP protocol extensions
 * </A>.
 * This provider supports all the features of the IMAP provider, plus
 * additional Gmail-specific features.
 * This provider can be used by including gimap.jar in your CLASSPATH
 * along with mail.jar, and by using the "gimap" protocol instead of
 * the "imap" protocol.
 * Remember that this means that all properties should be named "mail.gimap.*",
 * but that otherwise this provider supports all the same properties as the
 * IMAP protocol provider.
 * The Gmail IMAP provider defaults to using SSL to connect to "imap.gmail.com".
 * <br>
 * <P>
 * In general, applications should not need to use the classes in this
 * package directly.  Instead, they should use the APIs defined by the
 * <code>jakarta.mail</code> package (and subpackages).  Applications should
 * never construct instances of <code>GmailStore</code> or
 * <code>GmailFolder</code> directly.  Instead, they should use the
 * <code>Session</code> method <code>getStore</code> to acquire an
 * appropriate <code>Store</code> object, and from that acquire
 * <code>Folder</code> objects.
 * </P>
 * <P>
 * Message objects returned by this provider may be cast to GmailMessage
 * to access Gmail-specific data, e.g., using the methods GmailMessage.getMsgId(),
 * GmailMessage.getThrId(), and GmailMessage.getLabels().
 * For example:
 * </P>
 * <PRE>
 * GmailMessage gmsg = (GmailMessage)msg;
 * System.out.println("Gmail message ID is " + gmsg.getMsgId());
 * String[] labels = gmsg.getLabels();
 * for (String s : labels)
 * System.out.println("Gmail message label: " + s);
 * </PRE>
 * <P>
 * Gmail-specific data may be prefetched using the GmailFolder.FetchProfileItems
 * MSGID, THRID, and LABELS.
 * For example:
 * </P>
 * <PRE>
 * FetchProfile fp = new FetchProfile();
 * fp.add(GmailFolder.FetchProfileItem.MSGID);
 * folder.fetch(fp);
 * </PRE>
 * <P>
 * You can search using Gmail-specific data using the GmailMsgIdTerm,
 * GmailThrIdTerm, and GmailRawSearchTerm search terms.
 * For example:
 * </P>
 * <PRE>
 * // find the message with this Gmail unique message ID
 * long msgid = ...;
 * Message[] msgs = folder.search(new GmailMsgIdTerm(msgid));
 * </PRE>
 * <P>
 * You can access the Gmail extended attributes (returned by XLIST) for a
 * folder using the IMAPFolder.getAttributes() method.
 * For example:
 * </P>
 * <PRE>
 * IMAPFolder ifolder = (IMAPFolder)folder;
 * String[] attrs = ifolder.getAttributes();
 * for (String s : attrs)
 * System.out.println("Folder attribute: " + s);
 * </PRE>
 * <P>
 * <strong>WARNING:</strong> The APIs unique to this package should be
 * considered <strong>EXPERIMENTAL</strong>.  They may be changed in the
 * future in ways that are incompatible with applications using the
 * current APIs.
 */
package org.eclipse.angus.mail.gimap;
