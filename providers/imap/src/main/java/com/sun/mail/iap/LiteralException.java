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

package com.sun.mail.iap;

/**
 * @author Bill Shannon
 */

public class LiteralException extends ProtocolException {

    private static final long serialVersionUID = -6919179828339609913L;

    /**
     * Constructs a LiteralException with the specified Response object.
     *
     * @param	r	the response object
     */
    public LiteralException(Response r) {
	super(r.toString());
	response = r;
    }
}
