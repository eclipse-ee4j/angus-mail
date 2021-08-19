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

module jakarta.mail {

    requires transitive java.logging;
    requires java.xml;                  // for text/xml handler
    requires java.desktop;              // for image/jpeg handler
    requires static java.security.sasl; // for OAuth2 support
    requires transitive jakarta.activation;

    exports jakarta.mail;
    exports jakarta.mail.event;
    exports jakarta.mail.internet;
    exports jakarta.mail.search;
    exports jakarta.mail.util;

    exports com.sun.mail.util;
    exports com.sun.mail.auth;
    exports com.sun.mail.handlers;

    exports com.sun.mail.iap;
    exports com.sun.mail.imap;
    exports com.sun.mail.imap.protocol;

    exports com.sun.mail.pop3;

    exports com.sun.mail.smtp;

    exports com.sun.mail.util.logging;

    uses jakarta.mail.Provider;

    provides jakarta.mail.Provider with
            com.sun.mail.imap.IMAPProvider,
            com.sun.mail.imap.IMAPSSLProvider,
            com.sun.mail.smtp.SMTPProvider,
            com.sun.mail.smtp.SMTPSSLProvider,
            com.sun.mail.pop3.POP3Provider,
            com.sun.mail.pop3.POP3SSLProvider;
    provides jakarta.mail.util.StreamProvider with
            com.sun.mail.util.MailStreamProvider;
}
