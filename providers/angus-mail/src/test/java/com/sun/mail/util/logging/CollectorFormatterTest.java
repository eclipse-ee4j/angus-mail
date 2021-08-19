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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * The collector formatter tests.
 *
 * @author Jason Mehrens
 * @since JavaMail 1.5.2
 */
public class CollectorFormatterTest extends AbstractLogging {

    /**
     * See LogManager.
     */
    private static final String LOG_CFG_KEY = "java.util.logging.config.file";
    /**
     * Date and time simple format pattern.
     */
    private static final String DATE_TIME_FMT = "EEE, MMM dd HH:mm:ss:S ZZZ yyyy";

    /**
     * The line separator.
     */
    private static final String LINE_SEP = System.lineSeparator();

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
        Class<?>[] declared = CollectorFormatter.class.getDeclaredClasses();
        assertEquals(Arrays.toString(declared), 0, declared.length);
    }

    @Test
    public void testNewInstance() throws Exception {
        final String loggerName = CollectorFormatterTest.class.getName();
        final Class<?> k = CollectorFormatter.class;
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
    	//CollectorFormatter must not be swapped with other instances.
    	//The state is mutated with most method calls and therefore
    	//This class should maintain identity equals to work correctly.
    	CollectorFormatter cf = new CollectorFormatter();
    	assertFalse(cf.equals((Object) null));
    	assertNotEquals(cf, new CollectorFormatter());
    	assertTrue(cf.equals(cf));
    }

    @Test
    public void testHashCode() {
    	CollectorFormatter cf = new CollectorFormatter();
    	assertEquals(System.identityHashCode(cf), cf.hashCode());
    }


    @Test
    public void testFormatHead() {
        String msg = "message";
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter("{0}", xml,
                (Comparator<LogRecord>) null);
        assertEquals("", f.getHead((Handler) null));
        f.format(new LogRecord(Level.SEVERE, msg));

        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.getHead((Handler) null));
        assertEquals(result, expect);
        assertEquals("", f.getHead((Handler) null));
    }

    @Test(expected = NullPointerException.class)
    public void testFormatApplyReturnsNull() {
        CollectorFormatter f = new ApplyReturnsNull();
        for (int i = 0; i < 10; i++) {
            String o = f.format(new LogRecord(Level.INFO, ""));
            assertNotNull(o);
        }
    }

    @Test(expected = NullPointerException.class)
    public void testFormatNull() {
        CollectorFormatter f = new CollectorFormatter("{1}", (Formatter) null,
                (Comparator<LogRecord>) null);
        f.format((LogRecord) null);
    }

    private static final class TestFormatterAccept extends LogRecord {

        private static final long serialVersionUID = 1L;

        int inferred;
        @SuppressWarnings("FieldMayBeFinal")
        private transient Formatter f;

        TestFormatterAccept(Level level, CollectorFormatter f) {
            super(level, "");
            this.f = f;
        }

        @Override
        public Throwable getThrown() {
            if (inferred == 0) {
                assertEquals(Level.INFO.getLocalizedName().concat("11111"),
                        f.getTail((Handler) null));
            }
            return super.getThrown();
        }

        @Override
        public String getSourceMethodName() {
            ++inferred;
            return super.getSourceMethodName();
        }
    }

    @Test(timeout = 60000)
    public void testFormatAccept() throws Exception {
        final CollectorFormatter f = new CollectorFormatter(
                "{1}{3}{5}{7}{8}{13}",
                new CompactFormatter("%4$s"),
                new SeverityComparator());
        LogRecord first = new LogRecord(Level.INFO, "");
        first.setThrown(new Throwable());
        setEpochMilli(first, 1L);
        f.format(first);

        TestFormatterAccept r = new TestFormatterAccept(Level.FINE, f);
        setEpochMilli(r, 2L);
        r.setThrown(new Throwable());
        f.format(r);
        assertEquals(1, r.inferred);
        assertEquals(Level.FINE.getLocalizedName().concat("11222"),
                f.getTail((Handler) null));
    }

    private static final class TestFormatAcceptAndUpdate extends LogRecord {

        private static final long serialVersionUID = 1L;
        int inferred;
        @SuppressWarnings("FieldMayBeFinal")
        private transient Formatter f;

        public TestFormatAcceptAndUpdate(Level level, CollectorFormatter f) {
            super(level, "");
            this.f = f;
        }

        @Override
        public String getSourceMethodName() {
            if (++inferred == 1) {
                LogRecord r = new LogRecord(Level.INFO, "");
                setEpochMilli(r, 1L);
                f.format(r);
            }
            return super.getSourceMethodName();
        }
    }

    @Test(timeout = 60000)
    public void testFormatAcceptAndUpdate() throws Exception {
        final CollectorFormatter f = new CollectorFormatter(
                "{1}{3}{5}{7}{8}{13}",
                new CompactFormatter("%4$s"),
                new SeverityComparator());

        TestFormatAcceptAndUpdate r
                = new TestFormatAcceptAndUpdate(Level.SEVERE, f);
        setEpochMilli(r, 2L);
        r.setThrown(new Throwable());
        f.format(r);
        assertEquals(2, r.inferred);
        assertEquals(Level.SEVERE.getLocalizedName().concat("21121"),
                f.getTail((Handler) null));
    }

    @Test
    public void testFormatFormat() {
        String msg = "message";
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter("{1}", xml,
                (Comparator<LogRecord>) null);
        assertEquals("", f.getTail((Handler) null));
        LogRecord r;
        r = new LogRecord(Level.SEVERE, msg);
        f.format(r);

        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.format(r));
        assertEquals(result, expect);
        assertEquals("", f.getTail((Handler) null));
    }

    @Test
    public void testFormatFormatLocale() throws Exception {
        String msg = "message";
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter("{1}", xml,
                (Comparator<LogRecord>) null);

        assertEquals("", f.getTail((Handler) null));
        LogRecord r;
        r = new LogRecord(Level.SEVERE, LOG_CFG_KEY);
        Properties props = new Properties();
        props.put(LOG_CFG_KEY, msg);

        r.setResourceBundle(new LocaleResource(props, Locale.US));
        assertNotNull(r.getResourceBundle().getLocale());
        f.format(r);

        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.format(r));
        assertEquals(result, expect);
        assertEquals(msg, f.formatMessage(r));
        assertEquals(msg, xml.formatMessage(r));
        assertEquals("", f.getTail((Handler) null));
    }

    @Test
    public void testFormatNoRecords() {
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter((String) null, xml,
                (Comparator<LogRecord>) null);

        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.getHead((Handler) null))
                + f.finish(xml.getTail((Handler) null)) + '\n';
        assertEquals(result, expect);
    }

    @Test
    public void testFormatOneRecord() {
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter((String) null, xml,
                (Comparator<LogRecord>) null);
        LogRecord record = new LogRecord(Level.SEVERE, "message");
        assertEquals("", f.format(record));
        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.getHead((Handler) null))
                + f.finish(xml.format(record))
                + f.finish(xml.getTail((Handler) null)) + '\n';
        assertEquals(result, expect);
    }

    @Test
    public void testFormatTwoRecords() {
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter((String) null, xml,
                (Comparator<LogRecord>) null);
        LogRecord record = new LogRecord(Level.SEVERE, "first");
        assertEquals("", f.format(record));

        record = new LogRecord(Level.SEVERE, "second");
        assertEquals("", f.format(record));
        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.getHead((Handler) null))
                + f.finish(xml.format(record))
                + f.finish(xml.getTail((Handler) null)) + "... 1 more\n";
        assertEquals(result, expect);
    }

    @Test(expected = NullPointerException.class)
    public void testFinishNull() {
        CollectorFormatter f = new CollectorFormatter();
        assertNotNull(f);
        f.finish((String) null);
    }

    @Test
    public void testFinish() {
        CollectorFormatter f = new CollectorFormatter();
        String format = LINE_SEP + f.getClass().getName() + LINE_SEP;
        assertFalse(f.getClass().getName().equals(format));
        assertEquals(f.getClass().getName(), f.finish(format));
    }

    @Test
    public void testFormatTail() {
        String msg = "message";
        XMLFormatter xml = new XMLFormatter();
        CollectorFormatter f = new CollectorFormatter("{2}", xml,
                (Comparator<LogRecord>) null);
        f.format(new LogRecord(Level.SEVERE, msg));

        String result = f.getTail((Handler) null);
        String expect = f.finish(xml.getTail((Handler) null));
        assertEquals(result, expect);
        assertEquals(expect, f.getTail((Handler) null));
    }

    @Test
    public void testFormatCount() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                (Comparator<LogRecord>) null);
        assertEquals("0", f.getTail((Handler) null));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        String result = f.getTail((Handler) null);
        assertEquals(result, "4");
        assertEquals("0", f.getTail((Handler) null));
    }

    @Test
    public void testFormatRemaing() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{4}", (Formatter) null,
                (Comparator<LogRecord>) null);
        assertEquals("-1", f.getTail((Handler) null));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        String result = f.getTail((Handler) null);
        assertEquals(result, "3");
        assertEquals("-1", f.getTail((Handler) null));
    }

    @Test
    public void testFormatThrown() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{5}", (Formatter) null,
                (Comparator<LogRecord>) null);
        assertEquals("0", f.getTail((Handler) null));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        LogRecord r = new LogRecord(Level.SEVERE, msg);
        r.setThrown(new Exception());
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        r.setThrown(new Exception());
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        r.setThrown(new Exception());
        f.format(r);

        String result = f.getTail((Handler) null);
        assertEquals(result, "3");
        assertEquals("0", f.getTail((Handler) null));
    }

    @Test
    public void testFormatNormal() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{6}", (Formatter) null,
                (Comparator<LogRecord>) null);

        assertEquals("0", f.getTail((Handler) null));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        LogRecord r = new LogRecord(Level.SEVERE, msg);
        r.setThrown(new Exception());
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        r.setThrown(new Exception());
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        r.setThrown(new Exception());
        f.format(r);

        String result = f.getTail((Handler) null);
        assertEquals(result, "2");
        assertEquals("0", f.getTail((Handler) null));
    }

    @Test
    public void testFormatNextMin() throws Exception {
        CollectorFormatter minF = new CollectorFormatter("{7}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        tickMilli(); //Make sure the max not equal to the start time.

        final String min = minF.getTail((Handler) null);
        NumberFormat.getIntegerInstance().parse(min);
        tickMilli();

        //Next min is not old min.
        String next = minF.getTail((Handler) null);
        assertFalse(min + ' ' + next, min.equals(next));

        //All mins start at the init time.
        CollectorFormatter initF = new CollectorFormatter("{10}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        next = initF.getTail((Handler) null);
        assertEquals(min, next);
    }

    @Test
    public void testFormatMinDateTime() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{7,date,short} {7,time}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        long min = 100L;

        LogRecord r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 1000L);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 2000L);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 3000L);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 4000L);
        f.format(r);

        String result = f.getTail((Handler) null);
        
        //MessageFormat allows Number or Date instances as date and time.
        //Top level MessageFormat doc show that implementaiton is DateFormat
        //See DateFormat::format(Object,StringBuffer,FieldPosition)
        //This must format as Number to match CollectorFormatter.
        assertEquals(result,
                MessageFormat.format("{0,date,short} {0,time}", min));
    }

    @Test
    public void testFormatNextMax() throws Exception {
        CollectorFormatter f = new CollectorFormatter("{8}", (Formatter) null,
                (Comparator<LogRecord>) null);

        String now = f.getTail((Handler) null);
        Number num = NumberFormat.getIntegerInstance().parse(now);
        assertNotEquals(Long.MIN_VALUE, num.longValue());
        tickMilli();
        String next = f.getTail((Handler) null);
        assertNotEquals(Long.MIN_VALUE, NumberFormat.getIntegerInstance().parse(now).longValue());
        assertFalse(now.equals(next));
    }

    @Test
    public void testFormatMaxDateTime() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{8,date,short} {8,time}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        long min = 100L;
        long high = 4000L;

        LogRecord r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 1000L);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + high);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 2000L);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min);
        f.format(r);

        r = new LogRecord(Level.SEVERE, msg);
        setEpochMilli(r, min + 3000L);
        f.format(r);

        String result = f.getTail((Handler) null);
        
        //MessageFormat allows Number or Date instances as date and time.
        //Top level MessageFormat doc show that implementaiton is DateFormat
        //See DateFormat::format(Object,StringBuffer,FieldPosition)
        //This must format as Number to match CollectorFormatter.
        assertEquals(result, MessageFormat.format("{0,date,short} {0,time}", 
                min + high));
    }

    @Test
    public void testFormatWindowToInstant() throws Exception {
        CollectorFormatter f = new CollectorFormatter("{7}_{8}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        LogRecord r = new LogRecord(Level.SEVERE, "");
        setEpochMilli(r, 100);
        f.format(r);

        r = new LogRecord(Level.SEVERE, "");
        setEpochMilli(r, 200);
        f.format(r);

        //Check that the min and max are different.
        String output = f.getTail((Handler) null);
        int fence = output.indexOf('_');
        assertFalse(output.regionMatches(0, output, fence + 1,
                (output.length() - fence) - 1));

        r = new LogRecord(Level.SEVERE, "");
        setEpochMilli(r, 400);
        f.format(r);

        //Previous max is 200 so at this point the min and max better be 400.
        output = f.getTail((Handler) null);
        fence = output.indexOf('_');
        assertTrue(output.regionMatches(0, output, fence + 1,
                (output.length() - fence) - 1));
        assertEquals("400_400", output);
    }

    @Test
    public void testGetTail() {
        CollectorFormatter f = new CollectorFormatter(
                "{0}{1}{2}{3}{4}{5}{6}{7}{8}{9}{10}{11}{12}{13}",
                (Formatter) null,
                (Comparator<LogRecord>) null);
        assertTrue(f.getTail((Handler) null).length() != 0);
        assertTrue(f.getTail((Handler) null).length() != 0);
    }

    @Test
    public void testGetTailExample1() {
        String p = "{0}{1}{2}{4,choice,-1#|0#|0<... {4,number,integer} more}\n";
        CollectorFormatter cf = new CollectorFormatter(p);
        LogRecord r = new LogRecord(Level.WARNING, "warning message");
        cf.format(r);

        r = new LogRecord(Level.SEVERE, "Encoding failed.");
        RuntimeException npe = new NullPointerException();
        StackTraceElement frame = new StackTraceElement("java.lang.String",
                "getBytes", "String.java", 913);
        npe.setStackTrace(new StackTraceElement[]{frame});
        r.setThrown(npe);
        cf.format(r);

        cf.format(new LogRecord(Level.INFO, "info"));
        cf.format(new LogRecord(Level.INFO, "info"));
        String output = cf.getTail((Handler) null);
        assertNotNull(output);
    }

    @Test
    public void testGetTailExample2() {
        String p = "These {3} messages occurred between\n"
                + "{7,date,EEE, MMM dd HH:mm:ss:S ZZZ yyyy} and "
                + "{8,time,EEE, MMM dd HH:mm:ss:S ZZZ yyyy}\n";
        CollectorFormatter cf = new CollectorFormatter(p);
        LogRecord min = new LogRecord(Level.SEVERE, "");
        setEpochMilli(min, 1248203502449L);
        cf.format(min);

        int count = 290;
        for (int i = 0; i < count; ++i) {
            LogRecord mid = new LogRecord(Level.SEVERE, "");
            setEpochMilli(mid, min.getMillis());
            cf.format(mid);
        }

        LogRecord max = new LogRecord(Level.SEVERE, "");
        setEpochMilli(max, 1258723764000L);
        cf.format(max);
        Object[] args = new Object[9];
        args[3] = count + 2L;
        args[7] = min.getMillis();
        args[8] = max.getMillis();
        assertEquals(MessageFormat.format(p, args), cf.toString());
        String output = cf.getTail((Handler) null);
        assertNotNull(output);
    }

    @Test
    public void testGetTailExample3a() {
        String p = "These {3} messages occurred between\n"
                + "{7,date,EEE, MMM dd HH:mm:ss:S ZZZ yyyy}"
                + " and {8,time,EEE, MMM dd HH:mm:ss:S ZZZ yyyy}\n";
        CollectorFormatter cf = new CollectorFormatter(p);
        LogRecord min = new LogRecord(Level.SEVERE, "");
        setEpochMilli(min, 1248203502449L);
        cf.format(min);

        for (int i = 0; i < 71; ++i) {
            LogRecord mid = new LogRecord(Level.SEVERE, "");
            setEpochMilli(mid, min.getMillis());
            cf.format(mid);
        }

        LogRecord max = new LogRecord(Level.SEVERE, "");
        setEpochMilli(max, min.getMillis() + 110500);
        cf.format(max);

        String output = cf.getTail((Handler) null);
        assertNotNull(output);
    }

    @Test
    public void testGetTailExample3b() {
        String p = "These {3} messages occurred between "
                + "{9,choice,86400000#{7,date} {7,time} and {8,time}"
                + "|86400000<{7,date} and {8,date}}\n";
        CollectorFormatter cf = new CollectorFormatter(p);
        LogRecord min = new LogRecord(Level.SEVERE, "");
        setEpochMilli(min, 1248203502449L);

        cf.format(min);
        for (int i = 0; i < 114; ++i) {
            LogRecord mid = new LogRecord(Level.SEVERE, "");
            setEpochMilli(mid, min.getMillis());
            cf.format(mid);
        }

        LogRecord max = new LogRecord(Level.SEVERE, "");
        setEpochMilli(max, min.getMillis() + 2591000000L);
        cf.format(max);

        String output = cf.getTail((Handler) null);
        assertNotNull(output);
    }

    @Test
    public void testGetTailExample4() throws Exception {
        String p = "{13} alert reports since {10,date}.\n";
        CollectorFormatter cf = new CollectorFormatter(p);

        int count = 4320;
        for (int i = 1; i < count; ++i) {
            LogRecord mid = new LogRecord(Level.SEVERE, "");
            cf.format(mid);
            cf.getTail((Handler) null);
        }

        String output = cf.getTail((Handler) null);
        assertNotNull(output);
        String jd73 = NumberFormat.getIntegerInstance().format(count);
        assertTrue(output.startsWith(jd73));
    }

    @Test
    public void testNewDefaultFormatter() {
        String msg = "";
        CollectorFormatter f = new CollectorFormatter();
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        String result = f.getTail((Handler) null);
        assertTrue(result, result.length() != 0);
        assertTrue(result, result.contains("..."));
        assertTrue(result, result.contains("1"));
        assertTrue(result, result.contains("more"));
    }

    @Test
    public void testNewFormatterWithString() {
        CollectorFormatter f = new CollectorFormatter("{3}");
        f.format(new LogRecord(Level.SEVERE, ""));
        String result = f.getTail((Handler) null);
        assertEquals(result, "1");
    }

    @Test
    public void testNewFormatterNullString() {
        CollectorFormatter f = new CollectorFormatter((String) null);
        assertEquals(CollectorFormatter.class, f.getClass());
    }

    @Test
    public void testNewFormatterNullNonNullNonNull() {
        CollectorFormatter f = new CollectorFormatter((String) null,
                new XMLFormatter(), SeverityComparator.getInstance());
        assertEquals(CollectorFormatter.class, f.getClass());
    }

    @Test
    public void testNewFormatterNullNullNull() {
        CollectorFormatter f = new CollectorFormatter((String) null,
                (Formatter) null, (Comparator<LogRecord>) null);
        assertEquals(CollectorFormatter.class, f.getClass());
    }

    @Test
    public void testNewFormatterNonNullNullNull() {
        CollectorFormatter f = new CollectorFormatter("Test {0}",
                (Formatter) null, (Comparator<LogRecord>) null);
        assertEquals(CollectorFormatter.class, f.getClass());
    }

    @Test
    public void testToString() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                (Comparator<LogRecord>) null);

        assertEquals("0", f.toString());
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        String result = f.toString();
        assertEquals("4", result);
        assertEquals(result, f.toString());
        assertEquals(result, f.getTail((Handler) null));
        assertFalse(result.equals(f.toString()));
    }

    @Test
    public void testToStringInvalidPattern() {
        CollectorFormatter f = new CollectorFormatter("{5", (Formatter) null,
                (Comparator<LogRecord>) null);
        assertEquals(f.getClass().getName() + '@' +
                Integer.toHexString(System.identityHashCode(f)),
                f.toString());
    }

    @Test(expected = NullPointerException.class)
    public void testApplyNullAndNull() {
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                (Comparator<LogRecord>) null);
        f.apply((LogRecord) null, (LogRecord) null);
    }

    @Test(expected = NullPointerException.class)
    public void testApplyNullAndLogRecord() {
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                (Comparator<LogRecord>) null);
        f.apply((LogRecord) null, new LogRecord(Level.SEVERE, ""));
    }

    @Test(expected = NullPointerException.class)
    public void testApplyLogRecordAndNull() {
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                (Comparator<LogRecord>) null);
        f.apply(new LogRecord(Level.SEVERE, ""), (LogRecord) null);
    }

    @Test
    public void testApplyWithoutComparator() {
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                (Comparator<LogRecord>) null);
        LogRecord first = new LogRecord(Level.SEVERE, "");
        LogRecord second = new LogRecord(Level.WARNING, "");
        assertSame(second, f.apply(first, second));
    }

    @Test
    public void testApplyWithComparator() {
        CollectorFormatter f = new CollectorFormatter("{3}", (Formatter) null,
                SeverityComparator.getInstance());
        LogRecord first = new LogRecord(Level.SEVERE, "");
        LogRecord second = new LogRecord(Level.WARNING, "");
        assertSame(first, f.apply(first, second));
    }

    @Test
    public void testComparator() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator"), SeverityComparator.class.getName());
        props.put(p.concat(".comparator.reverse"), "false");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            LogRecord second = new LogRecord(Level.WARNING, Level.WARNING.getName());
            cf.format(second);
            cf.format(first);
            String result = cf.getTail((Handler) null);
            assertTrue(result, result.startsWith(Level.SEVERE.getName()));
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testComparatorReverse() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator"), SeverityComparator.class.getName());
        props.put(p.concat(".comparator.reverse"), "true");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            LogRecord second = new LogRecord(Level.WARNING, Level.WARNING.getName());
            cf.format(second);
            cf.format(first);
            String result = cf.getTail((Handler) null);
            assertTrue(result, result.startsWith(Level.WARNING.getName()));
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testComparatorNullLiteral() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator"), "null");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            LogRecord second = new LogRecord(Level.WARNING, Level.WARNING.getName());
            cf.format(second);
            cf.format(first);
            String result = cf.getTail((Handler) null);
            assertTrue(result, result.startsWith(Level.SEVERE.getName()));
        } finally {
            manager.reset();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testComparatorNullLiteralReverseException() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator"), "null");
        props.put(p.concat(".comparator.reverse"), "true");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            fail(cf.toString());
        } finally {
            manager.reset();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testComparatorDefaultReverseException() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator.reverse"), "true");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            fail(cf.toString());
        } finally {
            manager.reset();
        }
    }

    @Test(expected = UndeclaredThrowableException.class)
    public void testComparatorInvalidName() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator"), "invalid class name");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            fail(cf.toString());
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testComparatorNull() throws Exception {
        testComparatorNullOrEmpty((String) null);
    }

    @Test
    public void testComparatorEmpty() throws Exception {
        testComparatorNullOrEmpty("");
    }

    @Test(expected = ClassCastException.class)
    public void testComparatorRuntimeException() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".comparator"), Object.class.getName());
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            fail(cf.toString());
        } finally {
            manager.reset();
        }
    }


    private void testComparatorNullOrEmpty(String v) throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        if (v != null) {
            props.put(p.concat(".comparator"), v);
        }
        props.put(p.concat(".format"), "{1}");
        props.put(CompactFormatter.class.getName() + ".format", "%5$s");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, "1");
            first.setThrown(new Exception());
            LogRecord second = new LogRecord(Level.SEVERE, "2");
            second.setThrown(new Error());
            LogRecord third = new LogRecord(Level.SEVERE, "3");
            third.setThrown(new RuntimeException());
            cf.format(first);
            cf.format(second);
            cf.format(third);
            String result = cf.getTail((Handler) null);
            assertEquals("2", result);
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormat() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        final String expect = CollectorFormatterTest.class.getName();
        props.put(p.concat(".format"), expect);
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            assertEquals("", cf.format(first));
            String result = cf.getTail((Handler) null);
            assertEquals(expect, result);
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatInvalidName() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        final String expect = CollectorFormatterTest.class.getName();
        props.put(p.concat(".format"), expect);
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            assertEquals("", cf.format(first));
            String result = cf.getTail((Handler) null);
            assertEquals(expect, result);
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatInitEmpty() throws Exception {
        testFormatInitNullOrEmpty("");
    }

    @Test
    public void testFormatInitNull() throws Exception {
        testFormatInitNullOrEmpty((String) null);
    }

    private void testFormatInitNullOrEmpty(String v) throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        if(v != null) {
            props.put(p.concat(".format"), v);
        }
        props.put(p.concat(".formatter"), CompactFormatter.class.getName());
        props.put(CompactFormatter.class.getName()+ ".format", "%5$s");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            assertEquals("", cf.format(first));
            String result = cf.getTail((Handler) null);
            assertEquals(Level.SEVERE.getName(), result.trim());
        } finally {
            manager.reset();
        }
    }

    @Test(expected = ClassCastException.class)
    public void testFormatInitRuntimeException() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".formatter"), Object.class.getName());
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            fail(cf.toString());
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatter() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".formatter"), XMLFormatter.class.getName());
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            XMLFormatter xml = new XMLFormatter();
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            assertEquals("", cf.format(first));
            String result = cf.getTail((Handler) null);
            assertEquals(result, cf.finish(xml.getHead((Handler) null))
                    + cf.finish(xml.format(first))
                    + cf.finish(xml.getTail((Handler) null)) + '\n');
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatterInvalidName() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".formatter"), "invalid class name");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            fail(cf.toString());
        } catch (UndeclaredThrowableException expect) {
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatterNullLiteral() throws Exception {
        final String p = CollectorFormatter.class.getName();
        Properties props = new Properties();
        props.put(p.concat(".formatter"), "null");
        props.put(p.concat(".format"), "{1}");
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            assertEquals("", cf.format(first));
            String result = cf.getTail((Handler) null);
            assertEquals(result, cf.finish(cf.formatMessage(first)));
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatterNull() throws Exception {
        testFormatterNullOrEmpty((String) null);
    }

    @Test
    public void testFormatterEmpty() throws Exception {
        testFormatterNullOrEmpty("");
    }

    private void testFormatterNullOrEmpty(String v) throws Exception {
        final String p = CollectorFormatter.class.getName();
        final String expect = CompactFormatter.class.getName();
        Properties props = new Properties();
        if (v != null) {
            props.put(p.concat(".formatter"), v);
        }
        props.put(p.concat(".format"), "{1}");
        props.put(expect + ".format", expect);
        LogManager manager = LogManager.getLogManager();
        try {
            read(manager, props);
            CollectorFormatter cf = new CollectorFormatter();
            LogRecord first = new LogRecord(Level.SEVERE, Level.SEVERE.getName());
            assertEquals("", cf.format(first));
            String result = cf.getTail((Handler) null);
            assertEquals(expect, result);
        } finally {
            manager.reset();
        }
    }

    @Test
    public void testFormatElapsedTime() throws Exception {
        CollectorFormatter f = new CollectorFormatter("{9}", (Formatter) null,
                (Comparator<LogRecord>) null);
        LogRecord r = new LogRecord(Level.SEVERE, "");
        setEpochMilli(r, 25L);
        f.format(r);

        r = new LogRecord(Level.SEVERE, "");
        setEpochMilli(r, 100L);
        f.format(r);

        String init = f.getTail((Handler) null);
        Number n = NumberFormat.getIntegerInstance().parse(init);
        assertEquals(75, n.longValue());
    }

    @Test
    public void testFormatInitTimeMillis() throws Exception {
        CollectorFormatter f = new CollectorFormatter("{10}", (Formatter) null,
                (Comparator<LogRecord>) null);

        String init = f.getTail((Handler) null);
        NumberFormat.getIntegerInstance().parse(init);
        tickMilli();

        assertTrue(init.equals(f.getTail((Handler) null)));
    }

    @Test
    public void testFormatInitTimeDateTime() throws Exception {
        CollectorFormatter f = new CollectorFormatter(
                "{10,date," + DATE_TIME_FMT + "}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        String init = f.getTail((Handler) null);
        DateFormat df = new SimpleDateFormat(DATE_TIME_FMT);
        Date dt = df.parse(init);
        tickMilli();

        assertTrue(init.equals(f.getTail((Handler) null)));
        assertTrue(dt.equals(df.parse(f.getTail((Handler) null))));
    }

    @Test
    public void testFormatCurrentTimeMillis() throws Exception {
        CollectorFormatter f = new CollectorFormatter("{11}", (Formatter) null,
                (Comparator<LogRecord>) null);

        String now = f.getTail((Handler) null);
        NumberFormat.getIntegerInstance().parse(now);
        tickMilli();

        assertFalse(now.equals(f.getTail((Handler) null)));
    }

    @Test
    public void testFormatCurrentTimeDateTime() throws Exception {
        CollectorFormatter f = new CollectorFormatter(
                "{11,date," + DATE_TIME_FMT + "}",
                (Formatter) null,
                (Comparator<LogRecord>) null);

        String init = f.getTail((Handler) null);
        DateFormat df = new SimpleDateFormat(DATE_TIME_FMT);
        Date dt = df.parse(init);
        tickMilli();

        assertFalse(init.equals(f.getTail((Handler) null)));
        assertFalse(dt.equals(df.parse(f.getTail((Handler) null))));
    }

    @Test
    public void testFormatUpTime() throws Exception {
        CollectorFormatter f = new CollectorFormatter("{12}", (Formatter) null,
                (Comparator<LogRecord>) null);

        String up = f.getTail((Handler) null);
        NumberFormat.getIntegerInstance().parse(up);
        tickMilli();

        assertFalse(up.equals(f.getTail((Handler) null)));
    }

    @Test
    public void testFormatGeneration() {
        String msg = "message";
        CollectorFormatter f = new CollectorFormatter("{13}", (Formatter) null,
                (Comparator<LogRecord>) null);

        assertEquals("1", f.getTail((Handler) null));
        assertEquals("1", f.toString());
        assertEquals("1", f.getTail((Handler) null));
        assertEquals("1", f.toString());

        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        assertEquals("1", f.getTail((Handler) null)); //reset

        assertEquals("2", f.getTail((Handler) null));
        assertEquals("2", f.toString());
        assertEquals("2", f.getTail((Handler) null));
        assertEquals("2", f.toString());

        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));
        f.format(new LogRecord(Level.SEVERE, msg));

        assertEquals("2", f.getTail((Handler) null)); //reset
        assertEquals("3", f.getTail((Handler) null));
        assertEquals("3", f.toString());
        assertEquals("3", f.toString());
        assertEquals("3", f.getTail((Handler) null));
    }

    @Test
    public void testFormatNullFormatter() {
        CollectorFormatter f = new CollectorFormatter("{1}", (Formatter) null,
                (Comparator<LogRecord>) null);
        LogRecord r = new LogRecord(Level.SEVERE, "message {0}");
        r.setParameters(new Object[]{1});
        f.format(r);
        String output = f.getTail((Handler) null);
        assertEquals(f.formatMessage(r), output);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormatIllegalPattern() {
        CollectorFormatter f = new CollectorFormatter("{9");
        f.format(new LogRecord(Level.SEVERE, ""));
        f.getTail((Handler) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFormatIllegalTargetPattern() {
        CollectorFormatter f = new CollectorFormatter("{1}",
                new CompactFormatter("%1$#tc"), (Comparator<LogRecord>) null);
        f.format(new LogRecord(Level.SEVERE, ""));
        f.getTail((Handler) null);
    }

    @Test
    public void testLinkage() throws Exception {
        testLinkage(CollectorFormatter.class);
    }

    @Test
    public void testLogManagerModifiers() throws Exception {
        testLogManagerModifiers(CollectorFormatter.class);
    }

    @Test
    public void testWebappClassLoaderFieldNames() throws Exception {
        testWebappClassLoaderFieldNames(CollectorFormatter.class);
    }

    /**
     * An example of a broken implementation of apply.
     */
    private static class ApplyReturnsNull extends CollectorFormatter {

        /**
         * The number of records.
         */
        private int count;

        /**
         * Promote access level.
         */
        ApplyReturnsNull() {
        }

        @Override
        protected LogRecord apply(LogRecord t, LogRecord u) {
            assertNotNull(t);
            assertNotNull(u);
            return (++count & 1) == 1 ? u : null;
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
