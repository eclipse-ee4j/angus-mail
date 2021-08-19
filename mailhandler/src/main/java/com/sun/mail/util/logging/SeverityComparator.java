/*
 * Copyright (c) 2013, 2021 Oracle and/or its affiliates. All rights reserved.
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

import java.io.Serializable;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Orders log records by level, thrown, sequence, and time.
 *
 * This comparator orders LogRecords by how severely each is attributed to
 * failures in a program. The primary ordering is determined by the use of the
 * logging API throughout a program by specifying a level to each log message.
 * The secondary ordering is determined at runtime by the type of errors and
 * exceptions generated by the program. The remaining ordering assumes that
 * older log records are less severe than newer log records.
 *
 * <p>
 * The following LogRecord properties determine severity ordering:
 * <ol>
 * <li> The natural comparison of the LogRecord
 * {@linkplain Level#intValue level}.
 * <li> The expected recovery order of {@linkplain LogRecord#getThrown() thrown}
 * property of a LogRecord and its cause chain. This ordering is derived from
 * the JLS 11.1.1. The Kinds of Exceptions and JLS 11.5 The Exception Hierarchy.
 * This is performed by {@linkplain #apply(java.lang.Throwable) finding} the
 * throwable that best describes the entire cause chain. Once a specific
 * throwable of each chain is identified it is then ranked lowest to highest by
 * the following rules:
 *
 * <ul>
 * <li>All LogRecords with a {@code Throwable} defined as
 * "{@link #isNormal(java.lang.Throwable) normal occurrence}".
 * <li>All LogRecords that do not have a thrown object.
 * <li>All checked exceptions. This is any class that is assignable to the
 * {@code java.lang.Throwable} class and is not a
 * {@code java.lang.RuntimeException} or a {@code java.lang.Error}.
 * <li>All unchecked exceptions. This is all {@code java.lang.RuntimeException}
 * objects.
 * <li>All errors that indicate a serious problem. This is all
 * {@code java.lang.Error} objects.
 * </ul>
 * <li> The natural comparison of the LogRecord
 * {@linkplain LogRecord#getSequenceNumber() sequence}.
 * <li> The natural comparison of the LogRecord
 * {@linkplain LogRecord#getMillis() millis}.
 * </ol>
 *
 * @author Jason Mehrens
 * @since JavaMail 1.5.2
 */
public class SeverityComparator implements Comparator<LogRecord>, Serializable {

    /**
     * The generated serial version UID.
     */
    private static final long serialVersionUID = -2620442245251791965L;

    /**
     * A single instance that is shared among the logging package.
     * The field is declared as java.util.Comparator so
     * WebappClassLoader.clearReferencesStaticFinal() method will ignore this
     * field.
     */
    private static final Comparator<LogRecord> INSTANCE
            = new SeverityComparator();

    /**
     * A shared instance of a SeverityComparator. This is package private so the
     * public API is not polluted with more methods.
     *
     * @return a shared instance of a SeverityComparator.
     */
    static SeverityComparator getInstance() {
        return (SeverityComparator) INSTANCE;
    }

    /**
     * Creates a default {@code SeverityComparator}.
     */
    public SeverityComparator() {
        //readResolve() is not implemented in case the comparator
        //is the target of a synchronized block.
    }

    /**
     * Identifies a single throwable that best describes the given throwable and
     * the entire {@linkplain Throwable#getCause() cause} chain. This method can
     * be overridden to change the behavior of
     * {@link #compare(java.util.logging.LogRecord, java.util.logging.LogRecord)}.
     *
     * @param chain the throwable or null.
     * @return null if null was given, otherwise the throwable that best
     * describes the entire chain.
     * @see #isNormal(java.lang.Throwable)
     */
    public Throwable apply(final Throwable chain) {
        //Matches the j.u.f.UnaryOperator<Throwable> interface.
        int limit = 0;
        Throwable root = chain;
        Throwable high = null;
        Throwable normal = null;
        for (Throwable cause = chain; cause != null; cause = cause.getCause()) {
            root = cause;  //Find the deepest cause.

            //Find the deepest normal occurrance.
            if (isNormal(cause)) {
                normal = cause;
            }

            //Find the deepest error that happened before a normal occurrance.
            if (normal == null && cause instanceof Error) {
                high = cause;
            }

            //Deal with excessive cause chains and cyclic throwables.
            if (++limit == (1 << 16)) {
                break; //Give up.
            }
        }
        return high != null ? high : normal != null ? normal : root;
    }

    /**
     * {@link #apply(java.lang.Throwable) Reduces} each throwable chain argument
     * then compare each throwable result.
     *
     * @param tc1 the first throwable chain or null.
     * @param tc2 the second throwable chain or null.
     * @return a negative integer, zero, or a positive integer as the first
     * argument is less than, equal to, or greater than the second.
     * @see #apply(java.lang.Throwable)
     * @see #compareThrowable(java.lang.Throwable, java.lang.Throwable)
     */
    public final int applyThenCompare(Throwable tc1, Throwable tc2) {
        return tc1 == tc2 ? 0 : compareThrowable(apply(tc1), apply(tc2));
    }

    /**
     * Compares two throwable objects or null. This method does not
     * {@link #apply(java.lang.Throwable) reduce} each argument before
     * comparing. This is method can be overridden to change the behavior of
     * {@linkplain #compare(LogRecord, LogRecord)}.
     *
     * @param t1 the first throwable or null.
     * @param t2 the second throwable or null.
     * @return a negative integer, zero, or a positive integer as the first
     * argument is less than, equal to, or greater than the second.
     * @see #isNormal(java.lang.Throwable)
     */
    public int compareThrowable(final Throwable t1, final Throwable t2) {
        if (t1 == t2) { //Reflexive test including null.
            return 0;
        } else {
            //Only one or the other is null at this point.
            //Force normal occurrence to be lower than null.
            if (t1 == null) {
                return isNormal(t2) ? 1 : -1;
            } else {
                if (t2 == null) {
                    return isNormal(t1) ? -1 : 1;
                }
            }

            //From this point on neither are null.
            //Follow the shortcut if we can.
            if (t1.getClass() == t2.getClass()) {
                return 0;
            }

            //Ensure normal occurrence flow control is ordered low.
            if (isNormal(t1)) {
                return isNormal(t2) ? 0 : -1;
            } else {
                if (isNormal(t2)) {
                    return 1;
                }
            }

            //Rank the two unidenticial throwables using the rules from
            //JLS 11.1.1. The Kinds of Exceptions and
            //JLS 11.5 The Exception Hierarchy.
            if (t1 instanceof Error) {
                return t2 instanceof Error ? 0 : 1;
            } else if (t1 instanceof RuntimeException) {
                return t2 instanceof Error ? -1
                        : t2 instanceof RuntimeException ? 0 : 1;
            } else {
                return t2 instanceof Error
                        || t2 instanceof RuntimeException ? -1 : 0;
            }
        }
    }

    /**
     * Compares two log records based on severity.
     *
     * @param o1 the first log record.
     * @param o2 the second log record.
     * @return a negative integer, zero, or a positive integer as the first
     * argument is less than, equal to, or greater than the second.
     * @throws NullPointerException if either argument is null.
     */
    @SuppressWarnings("override") //JDK-6954234
    public int compare(final LogRecord o1, final LogRecord o2) {
        if (o1 == null || o2 == null) { //Don't allow null.
            throw new NullPointerException(toString(o1, o2));
        }

        /**
         * LogRecords are mutable so a reflexive relationship test is a safety
         * requirement.
         */
        if (o1 == o2) {
            return 0;
        }

        int cmp = compare(o1.getLevel(), o2.getLevel());
        if (cmp == 0) {
            cmp = applyThenCompare(o1.getThrown(), o2.getThrown());
            if (cmp == 0) {
                cmp = compare(o1.getSequenceNumber(), o2.getSequenceNumber());
                if (cmp == 0) {
                    cmp = compare(o1.getMillis(), o2.getMillis());
                }
            }
        }
        return cmp;
    }

    /**
     * Determines if the given object is also a comparator and it imposes the
     * same ordering as this comparator.
     *
     * @param o the reference object with which to compare.
     * @return true if this object equal to the argument; false otherwise.
     */
    @Override
    public boolean equals(final Object o) {
        return o == null ? false : o.getClass() == getClass();
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return Returns a hash code value for the object.
     */
    @Override
    public int hashCode() {
        return 31 * getClass().hashCode();
    }

    /**
     * Determines if the given throwable instance is "normal occurrence". This
     * is any checked or unchecked exception with 'Interrupt' in the class name
     * or ancestral class name. Any {@code java.lang.ThreadDeath} object or
     * subclasses.
     *
     * This method can be overridden to change the behavior of the
     * {@linkplain #apply(java.lang.Throwable)} method.
     *
     * @param t a throwable or null.
     * @return true the given throwable is a "normal occurrence".
     */
    public boolean isNormal(final Throwable t) {
        if (t == null) { //This is only needed when called directly.
            return false;
        }

        /**
         * Use the class names to avoid loading more classes.
         */
        final Class<?> root = Throwable.class;
        final Class<?> error = Error.class;
        for (Class<?> c = t.getClass(); c != root; c = c.getSuperclass()) {
            if (error.isAssignableFrom(c)) {
                if (c.getName().equals("java.lang.ThreadDeath")) {
                    return true;
                }
            } else {
                //Interrupt, Interrupted or Interruption.
                if (c.getName().contains("Interrupt")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Compare two level objects.
     *
     * @param a the first level.
     * @param b the second level.
     * @return a negative integer, zero, or a positive integer as the first
     * argument is less than, equal to, or greater than the second.
     */
    private int compare(final Level a, final Level b) {
        return a == b ? 0 : compare(a.intValue(), b.intValue());
    }

    /**
     * Outline the message create string.
     *
     * @param o1 argument one.
     * @param o2 argument two.
     * @return the message string.
     */
    private static String toString(final Object o1, final Object o2) {
        return o1 + ", " + o2;
    }

    /**
     * Compare two longs. Can be removed when JDK 1.7 is required.
     *
     * @param x the first long.
     * @param y the second long.
     * @return a negative integer, zero, or a positive integer as the first
     * argument is less than, equal to, or greater than the second.
     */
    private int compare(final long x, final long y) {
        return x < y ? -1 : x > y ? 1 : 0;
    }
}
