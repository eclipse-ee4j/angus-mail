/*
 * Copyright (c) 2026 Benny Bottema (ai assisted). All rights reserved.
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

package org.eclipse.angus.mail.util;

import org.junit.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Checks the result contract shared by built-in and custom engine verifiers. */
public class NioSocketFetcherTest {
    @Test
    public void testRejectedVerifierIsPeerUnverified() throws Exception {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> verify((host, session) -> false));
        assertTrue(failure.getCause() instanceof SSLPeerUnverifiedException);
        assertTrue(failure.getCause().getMessage().contains("DENY"));
    }

    @Test
    public void testAcceptedVerifierIsCalled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        verify((host, session) -> {
            assertEquals("mail.example.test", host);
            calls.incrementAndGet();
            return true;
        });
        assertEquals(1, calls.get());
    }

    @Test
    public void testVerifierExceptionIsRetained() throws Exception {
        IllegalStateException original = new IllegalStateException("Verifier failed");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> verify((host, session) -> { throw original; }));
        assertSame(original, failure.getCause());
    }

    private static void verify(HostnameVerifier verifier) throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        java.lang.reflect.Method check = NioSocketFetcher.class.getDeclaredMethod(
                "checkServerIdentity", HostnameVerifier.class, String.class, SSLEngine.class);
        check.setAccessible(true);
        check.invoke(null, verifier, "mail.example.test", engine);
    }
}
