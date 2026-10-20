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

package org.eclipse.angus.mail.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;

/**
 * Test quoted-printable decoder.
 */

public class QPDecoderStreamTest {

    private static byte[] decode(byte[] in) throws Exception {
        InputStream qp = new QPDecoderStream(new ByteArrayInputStream(in));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = qp.read()) != -1)
            bos.write(c);
        qp.close();
        return bos.toByteArray();
    }

    /**
     * A valid encoded atom decodes to the corresponding byte.
     */
    @Test
    public void testDecodeAtom() throws Exception {
        assertArrayEquals(new byte[]{'A'}, decode(new byte[]{'=', '4', '1'}));
    }

    /**
     * An '=' followed by a single hex digit at EOF must not add a byte that
     * wasn't in the input.  The truncated atom is passed through literally.
     */
    @Test
    public void testTruncatedAtomAtEOF() throws Exception {
        assertArrayEquals(new byte[]{'=', '4'}, decode(new byte[]{'=', '4'}));
        assertArrayEquals(new byte[]{'h', 'i', '=', '4'},
                decode(new byte[]{'h', 'i', '=', '4'}));
    }

    /**
     * An '=' followed by CR at EOF (a truncated soft line break) must not add
     * a byte that wasn't in the input.
     */
    @Test
    public void testTruncatedSoftBreakAtEOF() throws Exception {
        assertArrayEquals(new byte[0], decode(new byte[]{'=', '\r'}));
        assertArrayEquals(new byte[]{'A'}, decode(new byte[]{'A', '=', '\r'}));
    }
}
