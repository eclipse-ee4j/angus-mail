/*
 * Copyright (c) 2024, 2024 Jason Mehrens. All rights reserved.
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
package org.eclipse.angus.mail.util.logging;

import jakarta.mail.Authenticator;
import org.junit.Assume;
import org.junit.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ErrorManager;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MHThisEscapeTest extends AbstractLogging {

    public MHThisEscapeTest() {
    }

    private static final class FinalizerHandler extends MailHandler {

        private static FinalizerHandler INSTANCE;

        public static FinalizerHandler getInstance() throws Exception {
            final Class<?> lock = FinalizerHandler.class;
            synchronized (lock) {
                try {
                    FinalizerHandler fh = new FinalizerHandler();
                    throw new AssertionError(fh);
                } catch (UnknownError ignore) {
                }

                for (int i = 0; i < 100; ++i) {
                    if (INSTANCE == null) {
                        System.gc();
                        System.runFinalization();
                        lock.wait(10);
                    } else {
                        break;
                    }
                }

                Assume.assumeNotNull(INSTANCE);
                return INSTANCE;
            }
        }

        @Override
        public String getEncoding() {
            final Class<?> lock = FinalizerHandler.class;
            synchronized (lock) {
                if (INSTANCE == null) {
                    throw new UnknownError();
                } else {
                    return null;
                }
            }
        }

        @Override
        public Level getLevel() {
            assertEquals(Level.OFF, super.getLevel());
            return Level.ALL;
        }

        @Override
        public boolean isLoggable(LogRecord record) {
            assertEquals(Level.OFF, super.getLevel());
            assertNull(super.getFilter());
            assertEquals(0, super.getAttachmentFilters().length);
            return true;
        }

        @Override
        protected void reportError(String msg, Exception ex, int code) {
            throw new AssertionError(msg + " code: " + code, ex);
        }

        @Deprecated
        @SuppressWarnings({"override", "FinalizeDoesntCallSuperFinalize", "FinalizeDeclaration"})
        protected void finalize() throws Throwable {
            final Class<?> lock = FinalizerHandler.class;
            synchronized (lock) {
                INSTANCE = this;
                lock.notifyAll();
            }
        }
    }

    public static final class FinalizerErrorManager extends ErrorManager {

        private static final AtomicInteger ACCESSES = new AtomicInteger();

        public static int seen() {
            return ACCESSES.get();
        }

        public FinalizerErrorManager() {
            ACCESSES.getAndIncrement();
        }

        @Override
        public void error(String msg, Exception ex, int code) {
            ACCESSES.getAndIncrement();
            new ErrorManager().error(msg, ex, code);
        }
    }

    @Test
    public void testThisEscapeViaFinalizer() throws Exception {
        final String p = FinalizerHandler.class.getName();
        final LogManager manager = LogManager.getLogManager();
        final Properties props = MailHandlerTest.createInitProperties(p);
        props.put(p.concat(".errorManager"),
                FinalizerErrorManager.class.getName());
        props.put(p.concat(".authenticator"), "password");
        props.setProperty(p.concat(".verify"), "local");

        read(manager, props);
        try {
            MailHandler h = FinalizerHandler.getInstance();
            try {
                Authenticator a = h.getAuthenticator();
                fail(String.valueOf(a));
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                h.setAuthenticator((Authenticator) null);
                fail();
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                h.setAuthenticator(new char[0]);
                fail();
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                h.setAuthentication((String) null);
                fail();
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                Properties mail = h.getMailProperties();
                fail(mail.toString());
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                h.setMailProperties(new Properties());
                fail();
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                ErrorManager em = h.getErrorManager();
                fail(String.valueOf(em));
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            try {
                h.setCapacity(1);
                fail();
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }

            //Make sure this is not a multiple of 1000 (default capacity) so
            //the close method has records to push.
            for (int i = 0; i < 2500; ++i) {
                LogRecord r = new LogRecord(Level.SEVERE, "");
                assertTrue(h.getClass().getName(),
                        h.isLoggable(r));
                h.publish(r);
            }

            try {
                h.close();
                fail();
            } catch (SecurityException expect) {
                assertEquals("this-escape", expect.getMessage());
            }
            assertEquals(1, FinalizerErrorManager.seen());
        } finally {
            manager.reset();
        }
    }
}
