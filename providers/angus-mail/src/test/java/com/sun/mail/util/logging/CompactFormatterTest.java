/*
 * Copyright (c) 2013, 2021 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2013, 2021 Jason Mehrens. All rights reserved.
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

package com.sun.mail.util.logging;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeUtility;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * Compact formatter tests.
 *
 * @author Jason Mehrens
 * @since JavaMail 1.5.2
 */
public class CompactFormatterTest extends AbstractLogging {

    private static final String UNKNOWN_CLASS_NAME
            = CompactFormatterTest.class.getName().concat("Foo");

    /**
     * The line separator.
     */
    private static final String LINE_SEP = System.lineSeparator();

    /**
     * The max width.
     */
    private static final int MAX_PRE = 160;
    /**
     * The default left to right pattern.
     */
    private static final String LEFT_TO_RIGHT = "%7$#." + MAX_PRE + "s%n";

    /**
     * See LogManager.
     */
    private static final String LOG_CFG_KEY = "java.util.logging.config.file";

    private static void checkJVMOptions() throws Exception {
        assertTrue(CollectorFormatterTest.class.desiredAssertionStatus());
        assertNull(System.getProperty("java.util.logging.manager"));
        assertNull(System.getProperty("java.util.logging.config.class"));
        assertNull(System.getProperty(LOG_CFG_KEY));
        assertEquals(LogManager.class, LogManager.getLogManager().getClass());
    }

    private static void fullFence() {
        LogManager.getLogManager().getProperty("");
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        checkJVMOptions();
        assertTrue("Recompile tests to include source line numbers.",
                new Throwable().getStackTrace()[0].getLineNumber() >= 0);
        try {
            throw new AssertionError(Class.forName(UNKNOWN_CLASS_NAME));
        } catch (ClassNotFoundException expect) {
        }
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        checkJVMOptions();
    }

    @Before
    public void setUp() {
        fullFence();
    }

    @After
    public void tearDown() {
        fullFence();
    }

    @Test
    public void testDeclaredClasses() throws Exception {
        testLoadDeclaredClasses(CompactFormatter.class);
    }

    @Test
    public void testNewInstance() throws Exception {
        final String loggerName = CompactFormatterTest.class.getName();
        final Class<?> k = CompactFormatter.class;
        assertNotNull(LogManagerProperties.newFormatter(k.getName()));

        Logger l;
        LogManager m = LogManager.getLogManager();
        try {
            Properties props = new Properties();
            String p = ConsoleHandler.class.getName();
            props.put(loggerName.concat(".handlers"), p);
            props.put(p.concat(".formatter"), k.getName());
            read(m, props);

            l = Logger.getLogger(loggerName);
            final Handler[] handlers = l.getHandlers();
            assertEquals(1, handlers.length);
            for (Handler h : handlers) {
                assertEquals(p, h.getClass().getName());
                assertEquals(k, h.getFormatter().getClass());
            }
        } finally {
            m.reset();
        }
        assertNotNull(l); //Enusre handler is closed by reset
    }

    @Test
    public void testEquals() {
    	CompactFormatter cf = new CompactFormatter();
    	assertFalse(cf.equals((Object) null));
    	assertNotEquals(cf, new CollectorFormatter());
    	assertTrue(cf.equals(cf));
    }

    @Test
    public void testHashCode() {
    	CompactFormatter cf = new CompactFormatter();
    	assertEquals(System.identityHashCode(cf), cf.hashCode());
    }

    @Test
    public void testToString() {
    	CompactFormatter cf = new CompactFormatter();
    	String s = cf.toString();
    	assertTrue(s, s.startsWith(CompactFormatter.class.getName()));
    	assertTrue(s, s.endsWith(Integer.toHexString(cf.hashCode())));
    }

    @Test
    public void testFormat() throws Exception {
        final String p = CompactFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".format"), "%9$s");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CompactFormatter cf = new CompactFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.INFO.getName());
            first.setSequenceNumber(Short.MAX_VALUE);
            String result = cf.format(first);
            assertEquals(String.valueOf((int) Short.MAX_VALUE), result);
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testNewFormatterWithPattern() {
        CompactFormatter cf = new CompactFormatter("%4$s");
        String result = cf.format(new LogRecord(Level.SEVERE, ""));
        assertEquals(Level.SEVERE.getLocalizedName(), result);
    }

    @Test
    public void testNewFormatterNullPattern() {
        CompactFormatter cf = new CompactFormatter((String) null);
        LogRecord r = new LogRecord(Level.SEVERE, "message");
        String result = cf.format(r);
        assertTrue(result, result.contains(r.getMessage()));
    }

    @Test
    public void testGetHeadAndGetTail() {
        CompactFormatter cf = new CompactFormatter();
        assertEquals("", cf.getHead((Handler) null));
        assertEquals("", cf.getTail((Handler) null));
    }

    @Test
    public void testFormatWithMessage() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        CompactFormatter cf = new CompactFormatter();
        String result = cf.format(record);
        assertTrue(result, result.startsWith(record.getMessage()));
        assertTrue(result, result.endsWith(LINE_SEP));
    }

    @Test
    public void testFormatWithMessageUpperCase() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        CompactFormatter cf = new CompactFormatter("%7$#.160S%n");
        String result = cf.format(record);
        String upper = record.getMessage().toUpperCase(Locale.US);
        assertFalse(record.getMessage().equals(upper));
        assertTrue(result, result.startsWith(upper));
        assertTrue(result, result.endsWith(LINE_SEP));
    }

    @Test
    public void testFormatWithMessageLeftJustifiedPad() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        CompactFormatter cf = new CompactFormatter("%7$#-12.6s%n");
        String result = cf.format(record);
        assertTrue(result, result.startsWith("messag|"));
        assertTrue(result, result.contains("\u0020\u0020\u0020"
                + "\u0020\u0020\u0020"));
        assertTrue(result, result.endsWith(LINE_SEP));
    }

    @Test
    public void testFormatWithMessagePrecisionOverWidth() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter("%7$#6.12s");
        String result = cf.format(record);
        assertEquals("mes|Throwable", result);
    }

    @Test
    public void testFormatWithMessageWidthOverPrecision() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter("%7$#12.6s");
        String result = cf.format(record);
        assertEquals("mes\u0020\u0020\u0020|Thr\u0020\u0020\u0020", result);
    }

    @Test
    public void testFormatWithMessageNoSurrogateWidthLess() {
        testFormatWithMessageWidthLess("message");
    }

    @Test
    public void testFormatWithMessageSurrogateWidthLess() {
        testFormatWithMessageWidthLess(
                "m\ud801\udc00ss\ud801\udc00g\ud801\udc00");
    }

    private void testFormatWithMessageWidthLess(String message) {
        assertEquals(7, message.codePointCount(0, message.length()));
        LogRecord record = new LogRecord(Level.SEVERE, message);
        Throwable t = new Throwable(record.getMessage());
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        t.setStackTrace(new StackTraceElement[]{frame});
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%7$#90s");
        String result = cf.format(record);
        final int fence = result.indexOf('|');
        assertEquals(90 / 2, result.codePointCount(0, fence));
        assertEquals(90 / 2, result.codePointCount(fence + 1, result.length()));
        assertTrue(result, result.startsWith(record.getMessage()));
        assertTrue(result, result.charAt(fence - 1) == '\u0020');
        assertTrue(result, result.endsWith("\u0020"));
        String l = result.substring(0, fence);
        String r = result.substring(fence + 1);
        String lt = l.trim();
        String rt = r.trim();
        assertTrue(r, r.startsWith(t.getClass().getSimpleName()));
        assertEquals(l.codePointCount(0, l.length()),
                lt.codePointCount(0, lt.length()) + 38);
        assertEquals(r.codePointCount(0, r.length()),
                rt.codePointCount(0, rt.length()) + 5);
    }

    @Test
    public void testFormatWithMessageNoSurrogateWidthEqual() {
        testFormatWithMessageWidthEqual("message");
    }

    @Test
    public void testFormatWithMessageSurrogateWidthEqual() {
        testFormatWithMessageWidthEqual(
                "m\ud801\udc00ss\ud801\udc00g\ud801\udc00");
    }

    private void testFormatWithMessageWidthEqual(String message) {
        assertEquals(7, message.codePointCount(0, message.length()));
        LogRecord record = new LogRecord(Level.SEVERE, message);
        Throwable t = new Throwable(message);
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        t.setStackTrace(new StackTraceElement[]{frame});
        record.setThrown(t);

        CompactFormatter cf = new CompactFormatter("%7$#80s");
        record.setMessage(cf.formatThrown(record));

        String result = cf.format(record);
        final int fence = result.indexOf('|');
        assertEquals(80 / 2, result.codePointCount(0, fence));
        assertEquals(80 / 2, result.codePointCount(fence + 1, result.length()));
        assertTrue(result, result.startsWith(record.getMessage()));
        String l = result.substring(0, fence);
        String r = result.substring(fence + 1);
        String lt = l.trim();
        String rt = r.trim();
        assertTrue(r, r.startsWith(t.getClass().getSimpleName()));
        assertEquals(l.codePointCount(0, l.length()),
                lt.codePointCount(0, lt.length()));
        assertEquals(r.codePointCount(0, r.length()),
                rt.codePointCount(0, rt.length()));
    }

    @Test
    public void testFormatWithMessageNoSurrogateWidthMore() {
        testFormatWithMessageWidthMore("message" + "message");
    }

    @Test
    public void testFormatWithMessageSurrogateWidthMore() {
        testFormatWithMessageWidthMore(
                "m\ud801\udc00ss\ud801\udc00g\ud801\udc00"
              + "m\ud801\udc00ss\ud801\udc00g\ud801\udc00");
    }

    private void testFormatWithMessageWidthMore(String message) {
        assertEquals(14, message.codePointCount(0, message.length()));
        LogRecord record = new LogRecord(Level.SEVERE, message);
        Throwable t = new Throwable(message);
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        t.setStackTrace(new StackTraceElement[]{frame});
        record.setThrown(t);

        CompactFormatter cf = new CompactFormatter("%7$#90s");
        record.setMessage(cf.formatThrown(record));

        String result = cf.format(record);
        final int fence = result.indexOf('|');
        assertEquals(94 / 2, result.codePointCount(0, fence));
        assertEquals(94 / 2, result.codePointCount(fence + 1, result.length()));
        assertTrue(result, result.startsWith(record.getMessage()));
        String l = result.substring(0, fence);
        String r = result.substring(fence + 1);
        String lt = l.trim();
        String rt = r.trim();
        assertTrue(r, r.startsWith(t.getClass().getSimpleName()));
        assertEquals(l.codePointCount(0, l.length()),
                lt.codePointCount(0, lt.length()));
        assertEquals(r.codePointCount(0, r.length()),
                rt.codePointCount(0, rt.length()));
    }

    @Test
    public void testFormatWithMessageNoSurrogateWidthHuge() {
        testFormatWithMessageWidthHuge(rpad("a", 160 * 2, "b"));
        testFormatWithMessageWidthHuge(rpad("a", 160 * 3, "b"));
    }

    @Test
    public void testFormatWithMessageSurrogateWidthHuge() {
        testFormatWithMessageWidthHuge(rpad("a", 160 * 2, "\ud801\udc00"));
        testFormatWithMessageWidthHuge(rpad("a", 160 * 3, "\ud801\udc00"));
    }

    private void testFormatWithMessageWidthHuge(String message) {
        LogRecord record = new LogRecord(Level.SEVERE, message);
        Throwable t = new Throwable(message);
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        t.setStackTrace(new StackTraceElement[]{frame});
        record.setThrown(t);

        CompactFormatter cf = new CompactFormatter("%7$#160s");
        record.setMessage(cf.formatThrown(record));

        String result = cf.format(record);
        final int fence = result.indexOf('|');
        assertTrue(result.codePointCount(0, fence) > 160 / 2);
        assertTrue(result.codePointCount(fence + 1, result.length()) > 160 / 2);
        assertTrue(result, result.startsWith(record.getMessage()));
        String l = result.substring(0, fence);
        String r = result.substring(fence + 1);
        String lt = l.trim();
        String rt = r.trim();
        assertTrue(r, r.startsWith(t.getClass().getSimpleName()));
        assertEquals(l.codePointCount(0, l.length()),
                lt.codePointCount(0, lt.length()));
        assertEquals(r.codePointCount(0, r.length()),
                rt.codePointCount(0, rt.length()));
    }

    @Test
    public void testFormatWithMessageEmpty() {
        LogRecord record = new LogRecord(Level.SEVERE, "");
        CompactFormatter cf = new CompactFormatter();
        String result = cf.format(record);
        assertEquals(LINE_SEP, result);
    }

    @Test
    public void testFormatMessageSurrogateEvenLess() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "a\ud801\udc00\ud801\udc00\ud801\udc00\ud801\udc00");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(5, m.codePointCount(0, m.length()));
        assertTrue(result, result.startsWith("a\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thro" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateEvenLess() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "abbbb");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(5, m.codePointCount(0, m.length()));
        assertEquals(2, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("ab"));
        assertTrue(result, result.endsWith("|Thro" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateOddEqual() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "abbbb");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.5s%n");
        String result = cf.format(record);

        assertEquals(5, m.codePointCount(0, m.length()));
        assertEquals(2, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("ab"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateOddLess() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "a\ud801\udc00\ud801\udc00\ud801\udc00\ud801\udc00");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.7s%n");
        String result = cf.format(record);

        assertEquals(5, m.codePointCount(0, m.length()));
        assertTrue(result, result.startsWith("a\ud801\udc00"));
        assertTrue(result, result.endsWith("|Throw" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateOddEqual() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "a\ud801\udc00\ud801\udc00\ud801\udc00\ud801\udc00");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.5s%n");
        String result = cf.format(record);

        assertEquals(5, m.codePointCount(0, m.length()));
        assertEquals(2, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("a\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateOddMore() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "a\ud801\udc00\ud801\udc00\ud801\udc00"
                 + "\ud801\udc00\ud801\udc00"
                 + "\ud801\udc00\ud801\udc00");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.7s%n");
        String result = cf.format(record);

        assertEquals(8, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("a\ud801\udc00\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thro" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateEvenEqual() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "abbbbb");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(6, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("abb"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateEvenEqual() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "a\ud801\udc00\ud801\udc00\ud801\udc00"
                    + "\ud801\udc00\ud801\udc00");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(6, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("a\ud801\udc00\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateEvenMore() {
        LogRecord record = new LogRecord(Level.SEVERE, "abbbbbbb");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(8, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("abb"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateEvenMore() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "a\ud801\udc00\ud801\udc00\ud801\udc00"
                 + "\ud801\udc00\ud801\udc00"
                 + "\ud801\udc00\ud801\udc00");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(8, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("a\ud801\udc00\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateEvenHuge() {
        int cap = 202;
        LogRecord record = new LogRecord(Level.SEVERE,
                rpad("a", cap, "b"));
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(cap, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("abb"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateEvenHuge() {
        int cap = 202;
        LogRecord record = new LogRecord(Level.SEVERE,
                rpad("a", cap, "\ud801\udc00"));
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.6s%n");
        String result = cf.format(record);

        assertEquals(cap, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("a\ud801\udc00\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateOddHuge() {
        int cap = 201;
        LogRecord record = new LogRecord(Level.SEVERE,
                rpad("a", cap, "b"));
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.5s%n");
        String result = cf.format(record);

        assertEquals(cap, m.codePointCount(0, m.length()));
        assertEquals(2, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("ab"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateOddLess() {
        LogRecord record = new LogRecord(Level.SEVERE,
                "abbbb");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.7s%n");
        String result = cf.format(record);

        assertEquals(5, m.codePointCount(0, m.length()));
        assertEquals(2, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("ab"));
        assertTrue(result, result.endsWith("|Throw" + LINE_SEP));
    }

    @Test
    public void testFormatMessageNoSurrogateOddMore() {
        LogRecord record = new LogRecord(Level.SEVERE, "abbbbbbb");
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.7s%n");
        String result = cf.format(record);

        assertEquals(8, m.codePointCount(0, m.length()));
        assertEquals(3, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("abb"));
        assertTrue(result, result.endsWith("|Thro" + LINE_SEP));
    }

    @Test
    public void testFormatMessageSurrogateOddHuge() {
        int cap = 201;
        LogRecord record = new LogRecord(Level.SEVERE,
                rpad("a", cap, "\ud801\udc00"));
        String m = record.getMessage();
        record.setThrown(new Throwable(m));
        CompactFormatter cf = new CompactFormatter("%7$#.5s%n");
        String result = cf.format(record);

        assertEquals(cap, m.codePointCount(0, m.length()));
        assertEquals(2, result.codePointCount(0, result.indexOf('|')));
        assertTrue(result, result.startsWith("a\ud801\udc00"));
        assertTrue(result, result.endsWith("|Thr" + LINE_SEP));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormatMessageZeroWidth() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter("%7$#0s%n");
        assertNotNull(cf.format(record));
        //Zero width is not allowed
        //java.util.FormatFlagsConversionMismatchException:
        //Conversion = s, Flags = 0
    }

    @Test
    public void testFormatMessageZeroPrecision() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter("%7$#.0s%n");
        String result = cf.format(record);
        assertEquals(LINE_SEP, result);
    }

    @Test
    public void testFormatMessageNullRecordMessagePrecision() {
        LogRecord record = new LogRecord(Level.SEVERE, (String) null);
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter("%7$#.0s%n");
        String result = cf.format(record);
        assertEquals(LINE_SEP, result);
    }

    @Test
    public void testFormatMessageNullRecordMessage() {
        LogRecord record = new LogRecord(Level.SEVERE, (String) null);
        Throwable t = new Throwable(record.getMessage());
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        t.setStackTrace(new StackTraceElement[]{frame});
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%7$#s");
        String result = cf.format(record);
        assertEquals("null|Throwable String.getBytes(:913)", result);
    }

    @Test
    public void testFormatWithMessageAndThrownLeftToRight() {
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter();
        String result = cf.format(record);
        assertTrue(result, result.startsWith(record.getMessage()));
        assertTrue(result, result.contains("|"));
        assertTrue(result, result.contains(Throwable.class.getSimpleName()));
        assertTrue(result, result.contains(CompactFormatterTest.class.getSimpleName()));
        assertTrue(result, result.contains("testFormatWithMessageAndThrown"));
        assertTrue(result, result.contains(String.valueOf(
                record.getThrown().getStackTrace()[0].getLineNumber())));
        assertTrue(result, result.endsWith(LINE_SEP));
    }

    @Test
    public void testFormatWithThrownLeftToRight() {
        testFormatWithThrown(LEFT_TO_RIGHT);
    }

    private void testFormatWithThrown(String fmt) {
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(new Throwable("thrown"));
        CompactFormatter cf = new CompactFormatter(fmt);
        String result = cf.format(record);
        assertFalse(result, result.startsWith("|"));
        assertTrue(result, result.contains(record.getThrown().getMessage()));
        assertTrue(result, result.endsWith(LINE_SEP));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatMessageNull() {
        CompactFormatter cf = new CompactFormatter();
        assertNotNull(cf.formatMessage((LogRecord) null));
    }

    @Test
    public void testFormatMessageLocale() throws Exception {
        String msg = "message";
        CompactFormatter cf = new CompactFormatter("%5$s");
        Properties props = new Properties();
        props.put(LOG_CFG_KEY, msg);

        LogRecord r = new LogRecord(Level.SEVERE, LOG_CFG_KEY);
        r.setResourceBundle(new LocaleResource(props, Locale.US));
        assertNotNull(r.getResourceBundle().getLocale());
        String result = cf.format(r);
        assertEquals(msg, result);
        assertEquals(msg, cf.formatMessage(r));
    }

    @Test
    public void testFormatMessage_LogRecordLeftToRight() {
        testFormatMessage_LogRecord(LEFT_TO_RIGHT);
    }

    private void testFormatMessage_LogRecord(String fmt) {
        Exception e = new IOException();
        assertNull(e.getMessage(), e.getMessage());

        e = new Exception(e.toString(), e);
        assertNotNull(e.getMessage(), e.getMessage());

        LogRecord record = new LogRecord(Level.SEVERE, e.toString());
        record.setThrown(e);
        CompactFormatter cf = new CompactFormatter(fmt);
        String result = cf.format(record);

        assertTrue(result, result.contains("|"));
        assertTrue(e.toString(), e.toString().contains(Exception.class.getPackage().getName()));
        assertTrue(e.toString(), e.toString().contains(IOException.class.getPackage().getName()));
        int idx;
        idx = result.indexOf(Exception.class.getSimpleName());
        assertTrue(result, idx >= 0);

        idx = result.indexOf(IOException.class.getSimpleName(), idx);
        assertTrue(result, idx >= 0);

        assertTrue(result, result.contains(record.getThrown().getClass().getSimpleName()));
        assertTrue(result, result.contains(record.getThrown().getCause().getClass().getSimpleName()));

        assertFalse(result, result.contains(Exception.class.getPackage().getName()));
        assertFalse(result, result.contains(IOException.class.getPackage().getName()));

        assertFalse(result, result.contains(Exception.class.getName()));
        assertFalse(result, result.contains(IOException.class.getName()));

        assertTrue(result, result.endsWith(LINE_SEP));
    }

    @Test(timeout = 30000)
    public void testFormatMessage_LogRecordEvil() {
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(createEvilThrowable());
        CompactFormatter cf = new CompactFormatter("%5$s");
        String result = cf.formatMessage(record);
        assertNotNull(result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatMaxMessageWidthLeftToRight() {
        testFormatMaxMessageWidth(LEFT_TO_RIGHT, MAX_PRE);
    }

    private void testFormatMaxMessageWidth(String fmt, int width) {
        assertTrue(fmt, fmt.contains(Integer.toString(width)));
        assertTrue(String.valueOf(width), width < Integer.MAX_VALUE / 4);

        CompactFormatter cf = new CompactFormatter(fmt);
        LogRecord record = new LogRecord(Level.SEVERE, "");
        int padding = LINE_SEP.length();
        for (int i = 1; i < width; i++) {
            record.setMessage(rpad("", i, "A"));
            String result = cf.format(record);
            assertTrue(result, result.length() == i + padding);
            assertTrue(result, result.endsWith(LINE_SEP));
        }

        for (int i = width; i <= (width * 4); i++) {
            record.setMessage(rpad("", i, "A"));
            String result = cf.format(record);
            assertTrue(result, result.length() == width + padding);
            assertTrue(result, result.endsWith(LINE_SEP));
        }
    }

    @Test
    public void testFormatMaxThrowableWidthLeftToRight() {
        String fmt = LEFT_TO_RIGHT;
        int width = MAX_PRE;
        assertTrue(fmt, fmt.contains(Integer.toString(width)));
        assertTrue(String.valueOf(width), width < Integer.MAX_VALUE / 4);

        CompactFormatter cf = new CompactFormatter(fmt);
        LogRecord record = new LogRecord(Level.SEVERE, "");
        int padding = LINE_SEP.length();
        for (int i = 0; i < width; i++) {
            record.setThrown(new Throwable(rpad("", i, "A")));
            String result = cf.format(record);
            assertTrue(result, result.length() <= width + padding);

            assertTrue(result, result.endsWith(LINE_SEP));
        }

        for (int i = width; i <= (width * 4); i++) {
            record.setThrown(new Throwable(rpad("", i, "A")));
            String result = cf.format(record);
            assertTrue(result.length() + ", " + (width + padding),
                    result.length() == width + padding);
            assertTrue(result, result.endsWith(LINE_SEP));
        }
    }

    @Test
    public void testFormatThrownTrailingDot() {
        testFormatThrownIllegalClassName("Hello.");
    }

    @Test
    public void testFormatThrownClassDotSpace() {
        String msg = "test";
        String prefix = IllegalStateException.class.getName() + ". ";
        Throwable t = new PrefixException(prefix, null, null);
        assertEquals(prefix, t.toString());
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        String cns = t.getClass().getSimpleName()
                + ": " + IllegalStateException.class.getSimpleName();
        assertTrue(result, result.startsWith(cns));
        assertTrue(result, result.indexOf(cns) == result.lastIndexOf(cns));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.indexOf(msg) == result.lastIndexOf(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownLeadingDot() {
        testFormatThrownIllegalClassName(".Hello");
    }

    @Test
    public void testFormatThrownDotDot() {
        testFormatThrownIllegalClassName("Hello..World");
        testFormatThrownIllegalClassName("..HelloWorld");
        testFormatThrownIllegalClassName("HelloWorld..");
    }

    @Test
    public void testFormatThrownColonSpace() {
        testFormatThrownIllegalClassName("Hello: World");
    }

    @Test
    public void testFormatThrownEndSign() {
        //Some of these are legal but not worth considering legal.
        testFormatThrownIllegalClassName("HelloWorld$");
        testFormatThrownIllegalClassName("HelloWorld.$");
        testFormatThrownIllegalClassName("Hello.World$");
    }

    @Test
    public void testFormatThrownStartSign() {
        testFormatThrownIllegalClassName("$HelloWorld");
        testFormatThrownIllegalClassName("$.HelloWorld");
    }

    @Test
    public void testFormatThrownDotDotSign() {
        testFormatThrownIllegalClassName("Hello..$World");
        testFormatThrownIllegalClassName("..$HelloWorld");
        testFormatThrownIllegalClassName("HelloWorld..$");
    }

    @Test
    public void testFormatThrownSignDot() {
        testFormatThrownIllegalClassName("$.HelloWorld");
        testFormatThrownIllegalClassName("HelloWorld$.");
    }

    @Test
    public void testFormatThrownSignDotDot() {
        testFormatThrownIllegalClassName("Hello$..World");
        testFormatThrownIllegalClassName("$..HelloWorld");
        testFormatThrownIllegalClassName("HelloWorld$..");
    }

    @Test
    public void testFormatThrownDotSignDot() {
        testFormatThrownIllegalClassName("Hello.$.World");
        testFormatThrownIllegalClassName(".$.HelloWorld");
        testFormatThrownIllegalClassName("HelloWorld.$.");
    }

    private void testFormatThrownIllegalClassName(String prefix) {
        Throwable t = new PrefixException(prefix, null, null);
        assertEquals(prefix, t.toString());
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        String cn = t.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cn));
        assertTrue(result, result.indexOf(cn) == result.lastIndexOf(cn));
        assertTrue(result, result.contains(prefix));
        assertTrue(result, result.indexOf(prefix) == result.lastIndexOf(prefix));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownSimpleClassNameNullMessage() {
        //javax.management.BadStringOperationException
        String op = "some op";
        Throwable t = new PrefixException(PrefixException.class.getSimpleName()
                + ": " + op, (String) null, null);
        assertNull(t.getMessage());
        assertNotNull(t.toString());
        assertTrue(t.toString().startsWith(t.getClass().getSimpleName()));

        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        String sn = t.getClass().getSimpleName();
        assertTrue(result, result.startsWith(sn));
        assertTrue(result, result.indexOf(sn) == result.lastIndexOf(sn));
        assertTrue(result, result.contains(op));
        assertTrue(result, result.indexOf(op) == result.lastIndexOf(op));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatServerSidetMetroException() {
        //com.sun.xml.ws.developer.ServerSideException
        String msg = "server error";
        NullPointerException npe = new NullPointerException(msg);
        Throwable t = new PrefixException(npe.getClass().getName(), msg, null);
        assertEquals(msg, npe.getMessage());
        assertEquals(msg, t.getMessage());
        assertEquals(npe.toString(), t.toString());

        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        String cns = t.getClass().getSimpleName()
                + ": " + npe.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cns));
        assertTrue(result, result.indexOf(cns) == result.lastIndexOf(cns));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownPrefixMessageRetainsFqn() {
        String msg = "java.io.tmpdir";
        NullPointerException npe = new NullPointerException(msg);
        Throwable t = new PrefixException(npe.getClass().getName(), msg, null);
        assertEquals(msg, npe.getMessage());
        assertEquals(msg, t.getMessage());
        assertEquals(npe.toString(), t.toString());

        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        String cns = t.getClass().getSimpleName()
                + ": " + npe.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cns));
        assertTrue(result, result.indexOf(cns) == result.lastIndexOf(cns));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownHiddenMessageRetainsFqn() {
        String msg = "java.io.tmpdir";
        NullPointerException npe = new NullPointerException(msg);
        Throwable t = new ToStringException(npe.getClass().getName(), msg);
        assertEquals(msg, npe.getMessage());
        assertEquals(msg, t.getMessage());

        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        String cns = t.getClass().getSimpleName()
                + ": " + npe.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cns));
        assertTrue(result, result.indexOf(cns) == result.lastIndexOf(cns));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatXMLParseXercesException() {
        //com.sun.org.apache.xerces.internal.xni.parser.XMLParseException
        String msg = "XML";
        String prefix = "1:two:3:four";
        Throwable t = new PrefixException(prefix, msg, null);

        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        assertTrue(prefix, t.toString().startsWith(prefix));
        String cn = t.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cn));
        assertTrue(result, result.indexOf(cn) == result.lastIndexOf(cn));
        assertTrue(result, result.contains(prefix));
        assertTrue(result, result.indexOf(prefix) == result.lastIndexOf(prefix));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatGSSException() {
        //org.ietf.jgss.GSSException
        String msg = "Invalid name provided";
        String prefix = PrefixException.class.getSimpleName();
        Throwable t = new PrefixException(prefix, msg, null);
        assertTrue(t.toString().startsWith(t.getClass().getSimpleName()));
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        assertTrue(prefix, t.toString().startsWith(prefix));
        String cn = t.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cn));
        assertTrue(result, result.indexOf(cn) == result.lastIndexOf(cn));
        assertTrue(result, result.contains(prefix));
        assertTrue(result, result.indexOf(prefix) == result.lastIndexOf(prefix));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatMismatchedTreeNodeException() {
        //org.antlr.runtime.MismatchedTreeNodeException
        String prefix = ToStringException.class.getSimpleName()
                + '(' + String.class.getName() + "!="
                + Throwable.class.getName() + ')';

        Throwable t = new ToStringException(prefix, (String) null);
        assertNull(t.getLocalizedMessage());
        assertNull(t.getMessage());
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        assertTrue(prefix, t.toString().startsWith(prefix));
        String cn = t.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cn));
        assertTrue(result, result.indexOf(cn) == result.lastIndexOf(cn));
        assertTrue(result, result.contains(prefix));
        assertTrue(result, result.indexOf(prefix) == result.lastIndexOf(prefix));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatInnerException() {
        String msg = "inner class";
        String prefix = '(' + String.class.getName() + "!="
                + Throwable.class.getName() + ')';

        Throwable t = new ToStringException(ToStringException.class.getName()
                + prefix, msg);
        assertFalse(t.toString().contains(t.getLocalizedMessage()));
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(t);
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.format(record);
        StackTraceElement[] ste = t.getStackTrace();
        String frame = CompactFormatterTest.class.getSimpleName()
                + '.' + ste[0].getMethodName() + "(:"
                + ste[0].getLineNumber() + ")";

        assertTrue(prefix, t.toString().contains(prefix));
        String cn = t.getClass().getSimpleName();
        assertTrue(result, result.startsWith(cn));
        assertTrue(result, result.indexOf(cn) == result.lastIndexOf(cn));
        assertTrue(result, result.contains(prefix));
        assertTrue(result, result.indexOf(prefix) == result.lastIndexOf(prefix));
        assertTrue(result, result.contains(msg));
        assertTrue(result, result.endsWith(frame));

        cf = new CompactFormatter("%11$s %14$s");
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatMessage_Throwable() {
        Exception e = new IOException(Exception.class.getName());
        e = new Exception(e.toString(), e);
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter();
        String result = cf.formatMessage(e);
        assertEquals(IOException.class.getSimpleName()
                + ": " + Exception.class.getSimpleName(), result);
    }

    @Test
    public void testFormatMessage_ThrowableNull() {
        CompactFormatter cf = new CompactFormatter();
        String result = cf.formatMessage((Throwable) null);
        assertEquals("", result);
    }

    @Test
    public void testFormatMessage_ThrowableNullMessage() {
        CompactFormatter cf = new CompactFormatter();
        String result = cf.formatMessage(new Throwable());
        String expect = Throwable.class.getSimpleName();
        assertEquals(expect, result);
    }

    @Test(timeout = 30000)
    public void testFormatMessage_ThrowableEvil() {
        CompactFormatter cf = new CompactFormatter("%6$s");
        LogRecord r = new LogRecord(Level.SEVERE, "");
        r.setThrown(createEvilThrowable());
        String result = cf.formatMessage(r.getThrown());
        assertNotNull(result);
        assertTrue(cf.format(r).contains(result));
    }

    @Test
    public void testFormatLevel() {
        CompactFormatter cf = new CompactFormatter("%4$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        String result = cf.formatLevel(record);
        assertEquals(record.getLevel().getLocalizedName(), result);
        assertEquals(result, cf.format(record));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatLevelNull() {
        CompactFormatter cf = new CompactFormatter("%4$s");
        assertNotNull(cf.formatLevel((LogRecord) null));
    }

    @Test
    public void testFormatLogger() {
        CompactFormatter cf = new CompactFormatter("%3$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setSourceMethodName(null);
        record.setSourceClassName(null);
        record.setLoggerName(Object.class.getName());
        String result = cf.formatLoggerName(record);
        assertNotNull(result);
        assertEquals(Object.class.getSimpleName(), result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatLoggerSurrogate() {
        CompactFormatter cf = new CompactFormatter("%3$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setSourceMethodName(null);
        record.setSourceClassName(null);
        record.setLoggerName("mail.Foo\ud801\udc00$\ud801\udc00Holder");
        String result = cf.formatLoggerName(record);
        assertNotNull(result);
        assertEquals("\ud801\udc00Holder", result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatRootLogger() {
        testFormatLoggerNonClassName("");
    }

    @Test
    public void testFormatGlobalLogger() {
        testFormatLoggerNonClassName("global");
    }

    @Test
    public void testFormatLoggerLeadingDot() {
        testFormatLoggerNonClassName(".Hello");
    }

    @Test
    public void testFormatLoggerDotDot() {
        testFormatLoggerNonClassName("Hello..World");
    }

    @Test
    public void testFormatLoggerColonSpace() {
        testFormatLoggerNonClassName("Hello: World");
    }

    private void testFormatLoggerNonClassName(String name) {
        CompactFormatter cf = new CompactFormatter("%3$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setSourceMethodName(null);
        record.setSourceClassName(null);
        record.setLoggerName(name);
        String result = cf.formatLoggerName(record);
        assertEquals(name, result);
        assertEquals(result, cf.format(record));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatLoggerNull() {
        CompactFormatter cf = new CompactFormatter("%3$s");
        assertNotNull(cf.formatLoggerName((LogRecord) null));
    }

    @Test
    public void testFormatMillis() {
        String p = "%1$tc";
        CompactFormatter cf = new CompactFormatter(p);
        LogRecord r = new LogRecord(Level.SEVERE, "");
        assertEquals(String.format(p, r.getMillis()),
                cf.format(r));
    }

    @Test
    public void testFormatMillisLocale() throws Exception {
        String p = "%1$tc";
        CompactFormatter cf = new CompactFormatter(p);
        Properties props = new Properties();
        LogRecord r = new LogRecord(Level.SEVERE, "");
        r.setResourceBundle(new LocaleResource(props, Locale.ENGLISH));
        assertEquals(String.format(Locale.ENGLISH, p, r.getMillis()),
                cf.format(r));
    }

    @Test
    public void testFormatMillisByParts() {
        String p = "%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp";
        CompactFormatter cf = new CompactFormatter(p);
        LogRecord r = new LogRecord(Level.SEVERE, "");
        assertEquals(String.format(p, r.getMillis()), cf.format(r));
    }

    @Test
    public void testFormatMillisByPartsLocale() throws Exception {
        String p = "%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp";
        CompactFormatter cf = new CompactFormatter(p);
        Properties props = new Properties();
        LogRecord r = new LogRecord(Level.SEVERE, "");
        r.setResourceBundle(new LocaleResource(props, Locale.ENGLISH));
        assertEquals(String.format(Locale.ENGLISH, p, r.getMillis()),
                cf.format(r));
    }

    @Test
    public void testFormatMillisAsLong() {
        String p = "%1$tQ";
        CompactFormatter cf = new CompactFormatter(p);
        LogRecord r = new LogRecord(Level.SEVERE, "");
        assertEquals(String.format(p, r.getMillis()), cf.format(r));
    }

    @Test
    public void testFormatZoneDateTime() throws Exception {
        LogRecord r = new LogRecord(Level.SEVERE, "");
        Object zdt = LogManagerProperties.getZonedDateTime(r);
        if (zdt != null) {
            String p = "%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS.%1$tN %1$Tp";
            CompactFormatter cf = new CompactFormatter(p);
            assertEquals(String.format(p, zdt), cf.format(r));
        } else {
            try {
                Method m = LogRecord.class.getMethod("getInstant");
                fail(m.toString());
            } catch (final NoSuchMethodException expect) {
            }
        }
    }

    @Test(expected = NullPointerException.class)
    public void testFormatNull() {
        CompactFormatter cf = new CompactFormatter();
        assertNotNull(cf.format((LogRecord) null));
    }

    @Test
    public void testFormatResourceBundleName() {
        CompactFormatter cf = new CompactFormatter("%15$s");
        LogRecord r = new LogRecord(Level.SEVERE, "");
        r.setResourceBundleName("name");
        String output = cf.format(r);
        assertEquals(r.getResourceBundleName(), output);
    }

    @Test
    public void testFormatKey() {
        CompactFormatter cf = new CompactFormatter("%16$s");
        LogRecord r = new LogRecord(Level.SEVERE, "message {0}{1}{2}");
        r.setParameters(new Object[]{null, "", cf});
        String output = cf.format(r);
        assertEquals(r.getMessage(), output);
        assertFalse(output.equals(cf.formatMessage(r)));
    }

    @Test
    public void testFormatSequence() {
        CompactFormatter cf = new CompactFormatter("%9$d");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        String output = cf.format(record);
        String expect = Long.toString(record.getSequenceNumber());
        assertEquals(expect, output);
    }

    @Test
    public void testFormatParameterSimpleName() {
        CompactFormatter cf = new CompactFormatter("%5$s");
        LogRecord r = new LogRecord(Level.SEVERE, cf.getClass().getName()
                + " {0},{1},{2}");
        r.setParameters(new Object[]{null, "", cf});
        String output = cf.format(r);
        assertTrue(output, output.startsWith(
                "CompactFormatter null,,CompactFormatter@"));
    }

    @Test
    public void testFormatSourceByLogger() {
        CompactFormatter cf = new CompactFormatter("%2$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setSourceMethodName(null);
        record.setSourceClassName(null);
        record.setLoggerName(Object.class.getName());
        String result = cf.formatSource(record);
        assertEquals(Object.class.getSimpleName(), result);
        assertEquals(result, cf.format(record));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatSourceNull() {
        CompactFormatter cf = new CompactFormatter("%2$s");
        assertNotNull(cf.formatSource((LogRecord) null));
    }

    @Test
    public void testFormatSourceByClass() {
        CompactFormatter cf = new CompactFormatter("%2$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setSourceMethodName(null);
        record.setSourceClassName(Object.class.getName());
        record.setLoggerName("");
        String result = cf.formatSource(record);
        assertEquals(Object.class.getSimpleName(), result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatSourceByClassAndMethod() {
        CompactFormatter cf = new CompactFormatter("%2$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setSourceMethodName("method");
        record.setSourceClassName(Object.class.getName());
        record.setLoggerName("");
        String result = cf.formatSource(record);
        assertFalse(result, record.getSourceClassName()
                .equals(record.getSourceMethodName()));
        assertTrue(result, result.startsWith(Object.class.getSimpleName()));
        assertTrue(result, result.endsWith(record.getSourceMethodName()));
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownNullThrown() {
        CompactFormatter cf = new CompactFormatter("%6$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        String result = cf.formatThrown(record);
        assertTrue(result, result.startsWith(cf.formatMessage(record.getThrown())));
        assertTrue(result, result.endsWith(cf.formatBackTrace(record)));
        assertEquals(result, cf.format(record));
    }

    @Test(timeout = 30000)
    public void testFormatThrownEvilThrown() {
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(createEvilThrowable());
        CompactFormatter cf = new CompactFormatter("%6$s");
        String result = cf.formatThrown(record);
        assertNotNull(result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrown() {
        Exception e = new IOException("Fake I/O");
        e = new Exception(e.toString(), e);
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%6$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);
        String result = cf.formatThrown(record);
        assertTrue(result, result.startsWith(e.getCause().getClass().getSimpleName()));
        assertTrue(result, result.contains(cf.formatMessage(record.getThrown())));
        assertTrue(result, result.endsWith(cf.formatBackTrace(record)));
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownEmptyEmpty() {
        //E[0] -> IOE[0]
        Exception e = new IOException("Fake I/O");
        e.setStackTrace(new StackTraceElement[0]);
        e = new Exception(e.toString(), e);
        e.setStackTrace(new StackTraceElement[0]);
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%6$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);

        String result = cf.formatThrown(record);
        assertEquals("IOException: Fake I/O", result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatThrownLocalized() {
        //sun.security.provider.PolicyParser$ParsingException
        CountLocalizedException cle = new CountLocalizedException();
        CompactFormatter cf = new CompactFormatter();
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(cle);
        String result = cf.formatThrown(record);
        assertNotNull(result, result);
        assertTrue(cle.localizedMessage > 0);
    }

    @Test
    public void testInheritsFormatMessage() {
        InheritsFormatMessage cf = new InheritsFormatMessage();
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(new Throwable());
        String result = cf.formatThrown(record);
        assertNotNull(cf.getClass().getName(), result);

        result = cf.formatError(record);
        assertNotNull(cf.getClass().getName(), result);
    }

    @Test(expected = NullPointerException.class)
    public void testFormatThrownNullRecord() {
        CompactFormatter cf = new CompactFormatter();
        assertNotNull(cf.formatThrown((LogRecord) null));
    }

    @Test
    public void testFormatThreadID() {
        CompactFormatter cf = new CompactFormatter("%10$d");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThreadID(10);
        String output = cf.format(record);
        String expect = Long.toString(record.getThreadID());
        assertEquals(expect, output);

        record.setThreadID(-1); //Largest value for the CompactFormatter.
        output = cf.format(record);
        expect = Long.toString((1L << 32L) - 1L);
        assertEquals(expect, output);

        //Test that downcast works right.
        Number id = cf.formatThreadID(record);
        assertEquals(record.getThreadID(), id.intValue());
        assertEquals(expect, Long.toString(id.longValue()));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatThreadIDNull() {
        CompactFormatter cf = new CompactFormatter();
        assertNotNull(cf.formatThreadID((LogRecord) null));
    }

    @Test
    public void testFormatThreadIDReturnsNull() {
        CompactFormatter cf = new ThreadIDReturnsNull();
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThreadID(10);
        assertNull(cf.formatThreadID(record));
        String output = cf.format(record);
        assertEquals("null", output);
    }

    @Test
    public void testFormatError() {
        CompactFormatter cf = new CompactFormatter("%11$s");
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("error"));
        String output = cf.format(record);
        assertTrue(output.startsWith(record.getThrown()
                .getClass().getSimpleName()));
        assertTrue(output.endsWith(record.getThrown().getMessage()));
        assertEquals(output, cf.formatError(record));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatErrorNull() {
        CompactFormatter cf = new CompactFormatter("%11$s");
        assertNotNull(cf.formatError((LogRecord) null));
    }

    @Test
    public void testFormatErrorNullMessage() {
        CompactFormatter cf = new CompactFormatter("%11$s");
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable());
        String output = cf.format(record);
        assertNotNull(output);
        assertEquals(output, cf.formatError(record));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatThrownMessageApplyReturnsNull() {
        CompactFormatter cf = new ApplyReturnsNull();
        for (int i = 0; i < 10; i++) {
            String output = cf.formatMessage(new Throwable());
            assertEquals(Throwable.class.getSimpleName(), output);
        }
    }

    @Test
    public void testFormatMessageError() {
        CompactFormatter cf = new CompactFormatter("%12$s");
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("error"));
        String output = cf.format(record);
        int t = output.indexOf(record.getThrown().getClass().getSimpleName());
        int f = output.indexOf('|');
        int m = output.indexOf(record.getThrown().getMessage());

        assertTrue(output, t > -1);
        assertTrue(output, m > -1);
        assertTrue(output, f > -1);
        assertTrue(output, t < m);
        assertTrue(output, t > f);
        assertTrue(output, f < m);
        assertTrue(output, output.startsWith(record.getMessage()));
        assertTrue(output, output.endsWith(cf.formatError(record)));
    }

    @Test
    public void testFormatErrorMessage() {
        CompactFormatter cf = new CompactFormatter("%13$s");
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        record.setThrown(new Throwable("error"));
        String output = cf.format(record);
        int t = output.indexOf(record.getThrown().getClass().getSimpleName());
        int f = output.indexOf('|');
        int m = output.indexOf(record.getThrown().getMessage());

        assertTrue(output, t > -1);
        assertTrue(output, m > -1);
        assertTrue(output, f > -1);
        assertTrue(output, t < m);
        assertTrue(output, t < f);
        assertTrue(output, f > m);
        assertTrue(output, output.endsWith(record.getMessage()));
        assertTrue(output, output.startsWith(cf.formatError(record)));
    }

    @Test(expected = NullPointerException.class)
    public void testErrorApplyReturnsNull() {
        CompactFormatter cf = new ApplyReturnsNull();
        LogRecord r = new LogRecord(Level.SEVERE, "");
        for (int i = 0; i < 10; i++) {
            String output = cf.formatError(r);
            assertNotNull(output);
            r.setThrown(new Throwable(Integer.toString(i), r.getThrown()));
            assertNotNull(cf.format(r));
        }
    }

    @Test
    public void testFormatExample1() {
        String p = "%7$#.160s%n";
        LogRecord r = new LogRecord(Level.SEVERE, "Encoding failed.");
        RuntimeException npe = new NullPointerException();
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        npe.setStackTrace(new StackTraceElement[]{frame});
        r.setThrown(npe);
        CompactFormatter cf = new CompactFormatter(p);
        String output = cf.format(r);
        assertNotNull(output);
    }

    @Test
    public void testFormatExample2() {
        String p = "%7$#.20s%n";
        LogRecord r = new LogRecord(Level.SEVERE, "Encoding failed.");
        RuntimeException npe = new NullPointerException();
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        npe.setStackTrace(new StackTraceElement[]{frame});
        r.setThrown(npe);
        CompactFormatter cf = new CompactFormatter(p);
        String output = cf.format(r);
        assertNotNull(output);
    }

    @Test
    public void testFormatExample3() {
        String p = "%1$tc %2$s%n%4$s: %5$s%6$s%n";
        LogRecord r = new LogRecord(Level.SEVERE, "Encoding failed.");
        r.setSourceClassName("MyClass");
        r.setSourceMethodName("fatal");
        setEpochMilli(r, 1258723764000L);
        RuntimeException npe = new NullPointerException();
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        npe.setStackTrace(new StackTraceElement[]{frame});
        r.setThrown(npe);
        CompactFormatter cf = new CompactFormatter(p);
        String output = cf.format(r);
        assertNotNull(output);
    }

    @Test
    public void testFormatExample4() {
        String p = "%4$s: %12$#.160s%n";
        LogRecord r = new LogRecord(Level.SEVERE, "Unable to send notification.");
        r.setSourceClassName("MyClass");
        r.setSourceMethodName("fatal");
        setEpochMilli(r, 1258723764000L);

        Exception t = new SocketException("Permission denied: connect");
        t = new MessagingException("Couldn't connect to host", t);
        r.setThrown(t);
        CompactFormatter cf = new CompactFormatter(p);
        String output = cf.format(r);
        assertNotNull(output);
    }

    @Test
    public void testFormatExample5() {
        String p = "[%9$d][%1$tT][%10$d][%2$s] %5$s%n%6$s%n";
        LogRecord r = new LogRecord(Level.SEVERE, "Unable to send notification.");
        r.setSequenceNumber(125);
        r.setThreadID(38);
        r.setSourceClassName("MyClass");
        r.setSourceMethodName("fatal");
        setEpochMilli(r, 1248203502449L);

        Exception t = new SocketException("Permission denied: connect");

        StackTraceElement frame = new StackTraceElement(
                "com.sun.mail.smtp.SMTPTransport",
                "openServer", "SMTPTransport.java", 1949);
        t.setStackTrace(new StackTraceElement[]{frame});

        t = new MessagingException("Couldn't connect to host", t);
        r.setThrown(t);
        CompactFormatter cf = new CompactFormatter(p);
        String output = cf.format(r);
        assertNotNull(output);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormatIllegalPattern() {
        CompactFormatter f = new CompactFormatter("%1$#tc");
        assertNotNull(f.format(new LogRecord(Level.SEVERE, "")));
        //java.util.FormatFlagsConversionMismatchException:
        //Conversion = c, Flags = #
    }

    @Test(expected = NullPointerException.class)
    public void testFormatApplyReturnsNull() {
        CompactFormatter cf = new ApplyReturnsNull();
        LogRecord r = new LogRecord(Level.SEVERE, "");
        for (int i = 0; i < 10; i++) {
            String output = cf.format(r);
            assertNotNull(output);
            r.setThrown(new Throwable(Integer.toString(i), r.getThrown()));
            assertNotNull(cf.format(r));
        }
    }

    @Test
    public void testFormatBackTrace() {
        Exception e = new IOException("Fake I/O");
        e = new Exception(e.toString(), e);
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);

        String result = cf.formatBackTrace(record);
        assertTrue(result, result.startsWith("CompactFormatterTest"));
        assertTrue(result, result.contains("testFormatBackTrace"));
        assertTrue(result, Character.isDigit(result.charAt(result.length() - 2)));
        assertFalse(result, result.contains(".java"));
        assertEquals(result, cf.format(record));
    }

    @Test(expected = NullPointerException.class)
    public void testBackTraceApplyReturnsNull() {
        CompactFormatter cf = new ApplyReturnsNull();
        LogRecord r = new LogRecord(Level.SEVERE, "");
        for (int i = 0; i < 10; i++) {
            String output = cf.formatBackTrace(r);
            assertNotNull(output);
            r.setThrown(new Throwable(Integer.toString(i), r.getThrown()));
            assertNotNull(cf.format(r));
        }
    }

    @Test
    public void testFormatBackTraceUnknown() {
        Exception e = new IOException("Fake I/O");
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(CompactFormatterTest.class.getName(),
            "testFormatBackTrace", null, -2)});
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);
        String result = cf.formatBackTrace(record);
        assertTrue(result, result.startsWith("CompactFormatterTest"));
        assertTrue(result, result.contains("testFormatBackTrace"));
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatBackTracePunt() {
        final Class<?> k = Collections.class;
        Exception e = new NullPointerException("Fake NPE");
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(k.getName(), "newSetFromMap", null, 3878)});
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);
        String result = cf.formatBackTrace(record);
        assertTrue(result, result.startsWith(k.getSimpleName()));
        assertTrue(result, result.contains("newSetFromMap"));
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatBackTraceChainPunt() {
        final Class<?> k = Collections.class;
        Throwable e = new NullPointerException("Fake NPE");
        e.setStackTrace(new StackTraceElement[0]);
        e = new RuntimeException(e);
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(k.getName(), "newSetFromMap",
                    k.getSimpleName() +".java", 3878)});
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);
        String result = cf.formatBackTrace(record);
        assertTrue(result, result.startsWith(k.getSimpleName()));
        assertTrue(result, result.contains("newSetFromMap"));
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatBackTraceEmptyEmpty() {
        //E[0] -> IOE[0]
        Exception e = new IOException("Fake I/O");
        e.setStackTrace(new StackTraceElement[0]);
        e = new Exception(e.toString(), e);
        e.setStackTrace(new StackTraceElement[0]);
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);

        String result = cf.formatBackTrace(record);
        assertTrue(result, result.isEmpty());
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatBackTraceNonEmptyEmpty() {
        final Class<?> k = Collection.class;
        //RE[1] -> NPE[0]
        Throwable e = new NullPointerException("Fake NPE");
        e.setStackTrace(new StackTraceElement[0]);
        e = new RuntimeException(e);
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(k.getName(), "contains",
                    k.getSimpleName() +".java", 288)});
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);

        //When root trace is empty the parent trace is used.
        String result = cf.formatBackTrace(record);
        assertTrue(result, result.startsWith(k.getSimpleName()));
        assertTrue(result, result.contains("contains"));
        assertTrue(result, result.contains(":288"));
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testFormatBackTraceNoFileExt() {
        final Class<?> k = Collection.class;
        //RE[1] -> NPE[0]
        Throwable e = new NullPointerException("Fake NPE");
        e.setStackTrace(new StackTraceElement[0]);
        e = new RuntimeException(e);
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(k.getName(), "contains",
                    "Foo", 288)});
        assertNotNull(e.getMessage(), e.getMessage());

        CompactFormatter cf = new CompactFormatter("%14$s");
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(e);
        String result = cf.formatBackTrace(record);
        assertTrue(result, result.startsWith(k.getSimpleName()));
        assertTrue(result, result.contains("contains"));
        assertTrue(result, result.contains(":288"));
        assertEquals(result, cf.format(record));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatBackTraceNull() {
        CompactFormatter cf = new CompactFormatter("%14$s");
        assertNotNull(cf.formatBackTrace((LogRecord) null));
    }

    @Test(timeout = 30000)
    public void testFormatBackTraceEvil() {
        LogRecord record = new LogRecord(Level.SEVERE, "");
        record.setThrown(createEvilThrowable());
        CompactFormatter cf = new CompactFormatter("%14$s");
        String result = cf.formatBackTrace(record);
        assertNotNull(result);
        assertEquals(result, cf.format(record));
    }

    @Test(timeout = 30000)
    public void testFormatBackTraceEvilIgnore() {
        LogRecord record = new LogRecord(Level.SEVERE, "");
        Throwable second = new Throwable();
        Throwable first = new Throwable(second);
        second.initCause(first); //Pure Evil.
        first.setStackTrace(new StackTraceElement[0]);
        second.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(CompactFormatterTest.class.getName(),
            "dummy$bridge", null, -1)});
        record.setThrown(first);
        CompactFormatter cf = new CompactFormatter("%14$s");
        String result = cf.formatBackTrace(record);
        assertNotNull(result);
        assertEquals(result, cf.format(record));
    }

    @Test
    public void testApply() {
        CompactFormatter cf = new CompactFormatter();
        assertNull(cf.apply((Throwable) null));

        final Throwable t = new Throwable();
        Throwable e = cf.apply(t);
        assertSame(t, e);
    }

    @Test
    public void testApplyNull() {
        assertNull(new CompactFormatter().apply(null));
    }

    @Test(timeout = 30000)
    public void testApplyEvil() {
        CompactFormatter cf = new CompactFormatter();
        assertNotNull(cf.apply(createEvilThrowable()));
    }

    private Throwable createEvilThrowable() {
        Throwable third = new Throwable();
        Throwable second = new Throwable(third);
        Throwable first = new Throwable(second);
        return third.initCause(first);  // Pure evil.
    }

    @Test(expected = NullPointerException.class)
    public void testIgnoreNull() {
        CompactFormatter cf = new CompactFormatter();
        assertNotNull(cf.ignore((StackTraceElement) null));
    }

    @Test
    public void testIgnoreKnownClass() {
        CompactFormatter cf = new CompactFormatter();
        String n = cf.getClass().getName();
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "format", f, 20);
        assertFalse(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreUnknownClass() {
        CompactFormatter cf = new CompactFormatter();
        String n = UNKNOWN_CLASS_NAME;
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "format", f, 20);
        assertFalse(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnorePrivateInnerClass() {
        CompactFormatter cf = new CompactFormatter();
        String n = Arrays.asList("foo", "bar", "baz").getClass().getName();
        assertTrue(n, n.contains("$"));

        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "size", f, 20);
        assertFalse(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnorePrivateStaticInnerClass() {
        CompactFormatter cf = new CompactFormatter();
        String n = Collections.emptySet().getClass().getName();
        assertTrue(n, n.contains("$"));

        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "size", f, 20);
        assertFalse(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreStaticUtilityClass() {
        CompactFormatter cf = new CompactFormatter();
        String n = MimeUtility.class.getName();
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "encodeText", f, 400);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreStaticUtilityClass_Util() {
        CompactFormatter cf = new CompactFormatter();
        String n = getClass().getName().concat("MimeUtility");
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "encodeText", f, 400);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreStaticUtilityClass_s() {
        CompactFormatter cf = new CompactFormatter();
        String n = getClass().getName().concat("Collections");
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "nCopies", f, 400);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreStaticUtilityClass_es() {
        CompactFormatter cf = new CompactFormatter();
        String n = getClass().getName().concat("Properties");
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "get", f, 400);
        assertFalse(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreStaticUtilityClass_Throwables() {
        CompactFormatter cf = new CompactFormatter();
        String n = getClass().getName().concat("Throwables");
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "propagate", f, 400);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreSyntheticMethod() {
        CompactFormatter cf = new CompactFormatter();
        String n = UNKNOWN_CLASS_NAME;
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "access$100", f, 10);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreNativeMethod() {
        CompactFormatter cf = new CompactFormatter();
        String n = UNKNOWN_CLASS_NAME;
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "foo", f, -2);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreReflectMethod() {
        CompactFormatter cf = new CompactFormatter();
        String n = java.lang.reflect.Method.class.getName();
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "invoke", f, 10);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreReflectMethodApiError() {
        CompactFormatter cf = new CompactFormatter();
        String n = "java.lang.reflect.".concat(getClass().getName());
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "invoke", f, 10);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreReflectMethodSunError() {
        CompactFormatter cf = new CompactFormatter();
        String n = "sun.reflect.".concat(getClass().getName());
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "invoke", f, 10);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreReflectConstructor() {
        CompactFormatter cf = new CompactFormatter();
        String n = java.lang.reflect.Constructor.class.getName();
        String f = n.concat(".java");
        StackTraceElement s = new StackTraceElement(n, "newInstance", f, 10);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIgnoreUnknownLine() {
        CompactFormatter cf = new CompactFormatter();
        String n = UNKNOWN_CLASS_NAME;
        StackTraceElement s = new StackTraceElement(n, "foo", null, -1);
        assertTrue(s.toString(), cf.ignore(s));
    }

    @Test
    public void testIsStaticUtilityNull() throws Exception {
        testNullPointerFor("isStaticUtility");
    }

    @Test
    public void testIsReflectionNull() throws Exception {
        testNullPointerFor("isReflection");
    }

    private void testNullPointerFor(String method) throws Exception {
        Method m = CompactFormatter.class
                .getDeclaredMethod(method, StackTraceElement.class);
        m.setAccessible(true);
        try {
            m.invoke(new CompactFormatter(), (StackTraceElement) null);
            fail("Null was allowed.");
        } catch (InvocationTargetException expect) {
            assertEquals(NullPointerException.class,
                    expect.getCause().getClass());
        }
    }

    @Test
    public void testToAlternate() {
        CompactFormatter cf = new CompactFormatter();
        assertEquals("", cf.toAlternate(LINE_SEP));
    }

    @Test
    public void testToAlternateNull() {
        CompactFormatter cf = new CompactFormatter();
        assertNull(cf.toAlternate((String) null));
    }

    @Test
    public void testLinkage() throws Exception {
        testLinkage(CompactFormatter.class);
    }

    @Test
    public void testLogManagerModifiers() throws Exception {
        testLogManagerModifiers(CompactFormatter.class);
    }

    @Test
    public void testWebappClassLoaderFieldNames() throws Exception {
        testWebappClassLoaderFieldNames(CompactFormatter.class);
    }

    private static String rpad(String s, final int len, String p) {
        final int existing = s.codePointCount(0, s.length());
        if (existing < len) {
            final int step = p.codePointCount(0, p.length());
            StringBuilder sb = new StringBuilder();
            sb.append(s);
            for (int i = existing; i < len; i += step) {
                sb.append(p);
            }
            return sb.toString();
        } else {
            return s;
        }
    }

    private final static class CountLocalizedException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        public int localizedMessage;

        CountLocalizedException() {
            super();
        }

        @Override
        public String getLocalizedMessage() {
            localizedMessage++;
            return super.getLocalizedMessage();
        }
    }

    private final static class ToStringException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String toString;

        ToStringException(String toString, String msg) {
            super(msg);
            this.toString = toString;
        }

        @Override
        public String toString() {
            return toString;
        }
    }

    private final static class PrefixException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String prefix;

        PrefixException(String prefix, String msg, Throwable cause) {
            super(msg, cause);
            this.prefix = prefix;
        }

        @Override
        public String toString() {
            String message = getLocalizedMessage();
            return (message != null) ? (prefix + ": " + message) : prefix;
        }
    }

    /**
     * An example of a broken implementation of thread ID.
     */
    private static class ThreadIDReturnsNull extends CompactFormatter {

        /**
         * Promote access level.
         */
        ThreadIDReturnsNull() {
            super("%10$d");
        }

        @Override
        public Number formatThreadID(LogRecord record) {
            return null;
        }
    }

    private static class InheritsFormatMessage extends CompactFormatter {

        InheritsFormatMessage() {
        }

        @Override
        public String formatMessage(Throwable t) {
            return InheritsFormatMessage.class.getName();
        }
    }

    /**
     * An example of a broken implementation of apply.
     */
    private static class ApplyReturnsNull extends CompactFormatter {

        /**
         * The number of throwables.
         */
        private int count;

        /**
         * Promote access level.
         */
        ApplyReturnsNull() {
            super("%6$s%11$s%14$s%7$#.160s%8$#.160s%12$#.160s%13$#.160s");
        }

        @Override
        protected Throwable apply(Throwable t) {
            return (++count & 1) == 1 ? t : null;
        }
    }

    /**
     * A properties resource bundle with locale.
     */
    private static class LocaleResource extends PropertyResourceBundle {

        /**
         * The locale
         */
        private final Locale locale;

        /**
         * Creates the locale resource.
         *
         * @param p the properties.
         * @param l the locale.
         * @throws IOException if there is a problem.
         */
        LocaleResource(Properties p, Locale l) throws IOException {
            super(toStream(p));
            locale = l;
        }

        private static InputStream toStream(Properties p) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.store(out, LocaleResource.class.getName());
            return new ByteArrayInputStream(out.toByteArray());
        }

        @Override
        public Locale getLocale() {
            return locale;
        }
    }
}
