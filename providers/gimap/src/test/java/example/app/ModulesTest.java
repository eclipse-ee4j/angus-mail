/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved.
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

package example.app;

import jakarta.mail.Session;
import jakarta.mail.util.StreamProvider;
import org.eclipse.angus.mail.util.MailStreamProvider;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class ModulesTest {

    // With org.eclipse.angus:angus-mail and org.eclipse.angus:gimap
    @Test
    public void test() throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        StreamProvider provider = session.getStreamProvider();
        assertEquals(MailStreamProvider.class, provider.getClass());
        Class.forName("org.eclipse.angus.mail.imap.IMAPProvider");
        Class.forName("org.eclipse.angus.mail.pop3.POP3Provider");
        Class.forName("org.eclipse.angus.mail.smtp.SMTPProvider");
        Class.forName("org.eclipse.angus.mail.gimap.GmailProvider");
    }
}
