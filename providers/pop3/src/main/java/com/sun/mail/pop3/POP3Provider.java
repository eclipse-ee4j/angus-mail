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

package com.sun.mail.pop3;

import com.sun.mail.util.DefaultProvider;

import jakarta.mail.Provider;

/**
 * The POP3 protocol provider.
 */
@DefaultProvider	// Remove this annotation if you copy this provider
public class POP3Provider extends Provider {
    public POP3Provider() {
	super(Provider.Type.STORE, "pop3", POP3Store.class.getName(),
	    "Oracle", null);
    }
}
