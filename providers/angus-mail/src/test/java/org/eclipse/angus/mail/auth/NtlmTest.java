/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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

package org.eclipse.angus.mail.auth;

import org.eclipse.angus.mail.util.MailLogger;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test NTLM type 3 message generation.
 */
public class NtlmTest {

    private static final int NTLMSSP_NEGOTIATE_UNICODE = 0x00000001;
    private static final int NTLMSSP_NEGOTIATE_NTLM = 0x00000200;

    /**
     * A type 2 message that does not echo the extended session
     * security flag must not downgrade a client that asked for
     * NTLMv2 to the LM and NTLMv1 responses.
     */
    @Test
    public void testV2NotDowngradedByServerFlags() {
        Ntlm ntlm = newNtlm();
        ntlm.generateType1Msg(0, true);
        byte[] type3 = type3(ntlm, NTLMSSP_NEGOTIATE_UNICODE
                | NTLMSSP_NEGOTIATE_NTLM);
        // NTLMv1 responses are 24 bytes, NTLMv2 responses are longer
        assertTrue("NT response length " + readShort(type3, 20),
                readShort(type3, 20) > 24);
    }

    @Test
    public void testV1WhenV2Disabled() {
        Ntlm ntlm = newNtlm();
        ntlm.generateType1Msg(0, false);
        byte[] type3 = type3(ntlm, NTLMSSP_NEGOTIATE_UNICODE
                | NTLMSSP_NEGOTIATE_NTLM);
        assertEquals(24, readShort(type3, 20));
    }

    private static Ntlm newNtlm() {
        return new Ntlm("DOMAIN", "client.example.com", "user", "password",
                new MailLogger(NtlmTest.class, "DEBUG NTLM", false,
                        System.out));
    }

    private static byte[] type3(Ntlm ntlm, int flags) {
        byte[] type2 = new byte[48];
        System.arraycopy("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII), 0,
                type2, 0, 8);
        type2[8] = 2;
        type2[20] = (byte) flags;
        type2[21] = (byte) (flags >> 8);
        type2[22] = (byte) (flags >> 16);
        type2[23] = (byte) (flags >> 24);
        for (int i = 0; i < 8; i++)
            type2[24 + i] = (byte) (0x11 * (i + 1));
        String msg = ntlm.generateType3Msg(
                Base64.getEncoder().encodeToString(type2));
        return Base64.getDecoder().decode(msg);
    }

    private static int readShort(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
}
