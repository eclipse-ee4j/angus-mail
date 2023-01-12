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
 * Utility classes for use with the Jakarta Mail API.
 * These utility classes are not part of the Jakarta Mail specification.
 * While this package contains many classes used by the Jakarta Mail implementation
 * and not intended for direct use by applications, the classes documented
 * here may be of use to applications.
 *
 * <P>
 * Classes in this package log debugging information using
 * {@link java.util.logging} as described in the following table:
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>org.eclipse.angus.mail.util Loggers</CAPTION>
 * <TR>
 * <TH>Logger Name</TH>
 * <TH>Logging Level</TH>
 * <TH>Purpose</TH>
 * </TR>
 * 
 * <TR>
 * <TD>org.eclipse.angus.mail.util.socket</TD>
 * <TD>FINER</TD>
 * <TD>Debugging output related to creating sockets</TD>
 * </TR>
 * </TABLE>
 * 
 * <P>
 * <strong>WARNING:</strong> The APIs in this package should be
 * considered <strong>EXPERIMENTAL</strong>.  They may be changed in the
 * future in ways that are incompatible with applications using the
 * current APIs.
 */
package org.eclipse.angus.mail.util;
