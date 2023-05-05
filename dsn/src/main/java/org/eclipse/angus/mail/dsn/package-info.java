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
 * Support for creating and parsing Delivery Status Notifications.
 * Refer to <A HREF="http://www.ietf.org/rfc/rfc3462.txt" TARGET="_top">
 * RFC 3462</A>
 * and <A HREF="http://www.ietf.org/rfc/rfc3464.txt" TARGET="_top">RFC 3464</A>
 * for more information.
 * <br>
 * <P>
 * A Delivery Status Notification is a MIME message with a Content-Type
 * of <code>multipart/report</code>.
 * A {@link org.eclipse.angus.mail.dsn.MultipartReport MultipartReport} object
 * represents the content of such a message.
 * The MultipartReport object contains several parts that represent the
 * information in a delivery status notification.
 * The first part is usually a <code>text/plain</code> part that
 * describes the reason for the notification.
 * The second part is a <code>message/delivery-status</code> part,
 * which is represented by a
 * {@link org.eclipse.angus.mail.dsn.DeliveryStatus DeliveryStatus} object, and contains
 * details about the notification.
 * The third part is either an entire copy of the original message
 * that is returned, represented by a
 * {@link jakarta.mail.internet.MimeMessage MimeMessage} object, or
 * just the headers of the original message, represented by a
 * {@link org.eclipse.angus.mail.dsn.MessageHeaders MessageHeaders} object.
 * </P>
 * <P>
 * To use the classes in this package, include <code>dsn.jar</code>
 * in your class path.
 * </P>
 * <P>
 * Classes in this package log debugging information using
 * {@link java.util.logging} as described in the following table:
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>org.eclipse.angus.mail.dsn Loggers</CAPTION>
 * <TR>
 * <TH>Logger Name</TH>
 * <TH>Logging Level</TH>
 * <TH>Purpose</TH>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.dsn</TD>
 * <TD>FINER</TD>
 * <TD>General debugging output</TD>
 * </TR>
 * </TABLE>
 *
 * <P>
 * <strong>WARNING:</strong> The APIs unique to this package should be
 * considered <strong>EXPERIMENTAL</strong>.  They may be changed in the
 * future in ways that are incompatible with applications using the
 * current APIs.
 */
package org.eclipse.angus.mail.dsn;
