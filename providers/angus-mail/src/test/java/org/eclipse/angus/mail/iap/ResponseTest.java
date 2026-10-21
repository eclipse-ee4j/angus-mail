/*
 * Copyright (c) 2013, 2023 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.iap;

import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Test response parsing.
 */
public class ResponseTest {
    // timeout the test in case of infinite loop
    @Rule
    public Timeout timeout = Timeout.seconds(5);

    private static String[] atomTests = {
            "atom", "atom ", "atom(", "atom)", "atom{", "atom*", "atom%",
            "atom\"", "atom\\ ", "atom]", "atom\001", "atom\177"
    };

    private static String[] astringTests = {
            "atom", "atom ", "atom(", "atom)", "atom{", "atom*", "atom%",
            "atom\"", "atom\\ ", "atom\001", "atom\177", "\"atom\"",
            "{4}\r\natom"
    };

    /**
     * Test parsing atoms.
     */
    @Test
    public void testAtom() throws Exception {
        for (String s : atomTests) {
            Response r = new Response("* " + s);
            assertEquals("atom", r.readAtom());
        }
        for (String s : atomTests) {
            Response r = new Response("* " + s + " ");
            assertEquals("atom", r.readAtom());
        }
    }

    /**
     * Test parsing astrings.
     */
    @Test
    public void testAString() throws Exception {
        for (String s : astringTests) {
            Response r = new Response("* " + s);
            assertEquals("atom", r.readAtomString());
        }
        for (String s : astringTests) {
            Response r = new Response("* " + s + " ");
            assertEquals("atom", r.readAtomString());
        }
    }

    /**
     * Test the special case where an astring can include ']'.
     */
    @Test
    public void testAStringSpecial() throws Exception {
        Response r = new Response("* " + "atom] ");
        assertEquals("atom]", r.readAtomString());
    }

    /**
     * Test astring lists.
     */
    @Test
    public void testAStringList() throws Exception {
        Response r = new Response("* " + "(A B C)");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
    }

    @Test
    public void testAStringListInitialSpace() throws Exception {
        Response r = new Response("* " + "( A B C)");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
    }

    @Test
    public void testAStringListTrailingSpace() throws Exception {
        Response r = new Response("* " + "(A B C )");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
    }

    @Test
    public void testAStringListInitialAndTrailingSpace() throws Exception {
        Response r = new Response("* " + "( A B C )");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
    }

    @Test
    public void testAStringListMultipleSpaces() throws Exception {
        Response r = new Response("* " + "(A  B    C)");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
    }

    @Test
    public void testAStringListQuoted() throws Exception {
        Response r = new Response("* " + "(A B \"C\")");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
    }

    /**
     * Test astring lists with more data following.
     */
    @Test
    public void testAStringListMore() throws Exception {
        Response r = new Response("* " + "(A B \"C\") atom");
        assertArrayEquals(new String[]{"A", "B", "C"},
                r.readAtomStringList());
        assertEquals("atom", r.readAtomString());
    }

    /**
     * Test empty astring lists.
     */
    @Test
    public void testAStringListEmpty() throws Exception {
        Response r = new Response("* " + "()");
        assertArrayEquals(new String[0], r.readAtomStringList());
    }

    /**
     * Test empty astring lists with more data following.
     */
    @Test
    public void testAStringListEmptyMore() throws Exception {
        Response r = new Response("* " + "() atom");
        assertArrayEquals(new String[0], r.readAtomStringList());
        assertEquals("atom", r.readAtomString());
    }

    /**
     * Test that reading a quoted string with escaped characters can be
     * repeated: the response itself must not be modified.
     */
    @Test
    public void testQuotedStringWithEscapesReadTwice() throws Exception {
        Response r = new Response("* \"\\\\Sent\" atom");
        assertEquals("\\Sent", r.readString());
        r.reset();
        assertEquals("\\Sent", r.readString());
        assertEquals("atom", r.readAtomString());
    }

    /**
     * Test that a copy of a response, which shares its data, reads a quoted
     * string with escaped characters the same after the original read it.
     */
    @Test
    public void testQuotedStringWithEscapesReadFromCopy() throws Exception {
        Response r = new Response("* \"\\\\Sent\"");
        Response copy = new Response(r);
        assertEquals("\\Sent", r.readString());
        assertEquals("\\Sent", copy.readString());
    }

    /**
     * Test that reading a quoted string with escaped characters as bytes
     * returns the unescaped bytes and keeps the response unchanged.
     */
    @Test
    public void testQuotedStringWithEscapesAsByteArray() throws Exception {
        String s = "* \"a\\\"b\\\\c\"";
        Response r = new Response(s);
        assertArrayEquals("a\"b\\c".getBytes(StandardCharsets.US_ASCII),
                r.readByteArray().getNewBytes());
        assertEquals(s, r.toString());
    }

    /**
     * Test readStringList
     */
    @Test
    public void testBadStringList() throws Exception {
        Response response = new Response(
                "* (\"name\", \"test\", \"version\", \"1.0\")");
        String[] list = response.readStringList();
        // anything other than an infinite loop timeout is considered success
    }
}
