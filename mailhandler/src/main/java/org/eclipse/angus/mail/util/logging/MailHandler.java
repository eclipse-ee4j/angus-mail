/*
 * Copyright (c) 2009, 2024 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2009, 2024 Jason Mehrens. All rights reserved.
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

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileTypeMap;
import jakarta.activation.MimetypesFileTypeMap;
import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessageContext;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Service;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.mail.util.StreamProvider.EncoderTypes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.ErrorManager;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.eclipse.angus.mail.util.logging.LogManagerProperties.fromLogManager;

/**
 * <code>Handler</code> that formats log records as an email message.
 *
 * <p>
 * This <code>Handler</code> will store a fixed number of log records used to
 * generate a single email message.  When the internal buffer reaches capacity,
 * all log records are formatted and placed in an email which is sent to an
 * email server.  The code to manually setup this handler can be as simple as
 * the following:
 *
 * <pre>
 *      Properties props = new Properties();
 *      props.put("mail.smtp.host", "my-mail-server");
 *      props.put("mail.to", "me@example.com");
 *      props.put("verify", "local");
 *      MailHandler h = new MailHandler(props);
 *      h.setLevel(Level.WARNING);
 * </pre>
 *
 * <p>
 * <b><a id="configuration">Configuration:</a></b>
 * The LogManager should define at least one or more recipient addresses and a
 * mail host for outgoing email.  The code to setup this handler via the
 * logging properties can be as simple as the following:
 *
 * <pre>
 *      #Default MailHandler settings.
 *      org.eclipse.angus.mail.util.logging.MailHandler.mail.smtp.host = my-mail-server
 *      org.eclipse.angus.mail.util.logging.MailHandler.mail.to = me@example.com
 *      org.eclipse.angus.mail.util.logging.MailHandler.level = WARNING
 *      org.eclipse.angus.mail.util.logging.MailHandler.verify = local
 * </pre>
 *
 * For a custom handler, e.g. <code>com.foo.MyHandler</code>, the properties
 * would be:
 *
 * <pre>
 *      #Subclass com.foo.MyHandler settings.
 *      com.foo.MyHandler.mail.smtp.host = my-mail-server
 *      com.foo.MyHandler.mail.to = me@example.com
 *      com.foo.MyHandler.level = WARNING
 *      com.foo.MyHandler.verify = local
 * </pre>
 *
 * All mail <a id="top-level-properties">properties</a> documented in the
 * <code>Jakarta Mail API</code> cascade to the LogManager by prefixing a key
 * using the fully qualified class name of this <code>MailHandler</code> or the
 * fully qualified derived class name dot mail property.  If the prefixed
 * property is not found, then the mail property itself is searched in the
 * LogManager. By default each <code>MailHandler</code> is initialized using the
 * following LogManager configuration properties where
 * <code>&lt;handler-name&gt;</code> refers to the fully qualified class name of
 * the handler.  If properties are not defined, or contain invalid values, then
 * the specified default values are used.
 *
 * <ul>
 * <li>&lt;handler-name&gt;.attachment.filters a comma
 * separated list of <code>Filter</code> class names used to create each
 * attachment.  The literal <code>null</code> is reserved for attachments that
 * do not require filtering. (defaults to the
 * {@linkplain java.util.logging.Handler#getFilter() body} filter)
 *
 * <li>&lt;handler-name&gt;.attachment.formatters a comma
 * separated list of <code>Formatter</code> class names used to create each
 * attachment. (default is no attachments)
 *
 * <li>&lt;handler-name&gt;.attachment.names a comma separated
 * list of names or <code>Formatter</code> class names of each attachment.  All
 * control characters are removed from the attachment names.
 * (default is {@linkplain java.util.logging.Formatter#toString() toString}
 * of the attachment formatter)
 *
 * <li>&lt;handler-name&gt;.authenticator name of an
 * {@linkplain jakarta.mail.Authenticator} class used to provide login credentials
 * to the email server or string literal that is the password used with the
 * {@linkplain Authenticator#getDefaultUserName() default} user name.
 * (default is <code>null</code>)
 *
 * <li>&lt;handler-name&gt;.capacity the max number of
 * <code>LogRecord</code> objects include in each email message.
 * (defaults to <code>1000</code>)
 *
 * <li>&lt;handler-name&gt;.comparator name of a
 * {@linkplain java.util.Comparator} class used to sort the published
 * <code>LogRecord</code> objects prior to all formatting.
 * (defaults to <code>null</code> meaning records are unsorted).
 *
 * <li>&lt;handler-name&gt;.comparator.reverse a boolean
 * <code>true</code> to reverse the order of the specified comparator or
 * <code>false</code> to retain the original order.
 * (defaults to <code>false</code>)
 *
 * <li>&lt;handler-name&gt;.enabled a boolean
 * <code>true</code> to allow this handler to accept records or
 * <code>false</code> to turn off this handler.
 * (defaults to <code>true</code>)
 *
 * <li>&lt;handler-name&gt;.encoding the name of the Java
 * {@linkplain java.nio.charset.Charset#name() character set} to use for the
 * email message. (defaults to <code>null</code>, the
 * {@linkplain jakarta.mail.internet.MimeUtility#getDefaultJavaCharset() default}
 * platform encoding).
 *
 * <li>&lt;handler-name&gt;.errorManager name of an
 * <code>ErrorManager</code> class used to handle any configuration or mail
 * transport problems. (defaults to <code>java.util.logging.ErrorManager</code>)
 *
 * <li>&lt;handler-name&gt;.filter name of a <code>Filter</code>
 * class used for the body of the message. (defaults to <code>null</code>,
 * allow all records)
 *
 * <li>&lt;handler-name&gt;.formatter name of a
 * <code>Formatter</code> class used to format the body of this message.
 * (defaults to <code>java.util.logging.SimpleFormatter</code>)
 *
 * <li>&lt;handler-name&gt;.level specifies the default level
 * for this <code>Handler</code> (defaults to <code>Level.WARNING</code>).
 *
 * <li>&lt;handler-name&gt;.mail.bcc a comma separated list of
 * addresses which will be blind carbon copied.  Typically, this is set to the
 * recipients that may need to be privately notified of a log message or
 * notified that a log message was sent to a third party such as a support team.
 * The empty string can be used to specify no blind carbon copied address.
 * (defaults to <code>null</code>, none)
 *
 * <li>&lt;handler-name&gt;.mail.cc a comma separated list of
 * addresses which will be carbon copied.  Typically, this is set to the
 * recipients that may need to be notified of a log message but, are not
 * required to provide direct support.  The empty string can be used to specify
 * no carbon copied address.  (defaults to <code>null</code>, none)
 *
 * <li>&lt;handler-name&gt;.mail.from a comma separated list of
 * addresses which will be from addresses. Typically, this is set to the email
 * address identifying the user running the application.  The empty string can
 * be used to override the default behavior and specify no from address.
 * (defaults to the {@linkplain jakarta.mail.Message#setFrom() local address})
 *
 * <li>&lt;handler-name&gt;.mail.host the host name or IP
 * address of the email server. (defaults to <code>null</code>, use
 * {@linkplain Transport#protocolConnect default}
 * <code>Java Mail</code> behavior)
 *
 * <li>&lt;handler-name&gt;.mail.reply.to a comma separated
 * list of addresses which will be reply-to addresses.  Typically, this is set
 * to the recipients that provide support for the application itself.  The empty
 * string can be used to specify no reply-to address.
 * (defaults to <code>null</code>, none)
 *
 * <li>&lt;handler-name&gt;.mail.to a comma separated list of
 * addresses which will be send-to addresses. Typically, this is set to the
 * recipients that provide support for the application, system, and/or
 * supporting infrastructure.  The empty string can be used to specify no
 * send-to address which overrides the default behavior.  (defaults to
 * {@linkplain jakarta.mail.internet.InternetAddress#getLocalAddress
 * local address}.)
 *
 * <li>&lt;handler-name&gt;.mail.sender a single address
 * identifying sender of the email; never equal to the from address.  Typically,
 * this is set to the email address identifying the application itself.  The
 * empty string can be used to specify no sender address.
 * (defaults to <code>null</code>, none)
 *
 * <li>&lt;handler-name&gt;.mailEntries specifies the mail session properties
 * for this <code>Handler</code>.  The format for the value is described in
 * {@linkplain #setMailEntries(java.lang.String) setMailEntries} method.
 * This property eagerly loads the assigned mail properties where as the
 * <a href="#top-level-properties">top level mail properties</a> are lazily
 * loaded.  Prefer using this property when <a href="#verify">verification</a>
 * is off or when verification does not force the provider to load required
 * mail properties.  (defaults to <code>null</code>).
 *
 * <li>&lt;handler-name&gt;.subject the name of a
 * <code>Formatter</code> class or string literal used to create the subject
 * line.  The empty string can be used to specify no subject.  All control
 * characters are removed from the subject line. (defaults to {@linkplain
 * CollectorFormatter CollectorFormatter}.)
 *
 * <li>&lt;handler-name&gt;.pushFilter the name of a
 * <code>Filter</code> class used to trigger an early push.
 * (defaults to <code>null</code>, no early push)
 *
 * <li>&lt;handler-name&gt;.pushLevel the level which will
 * trigger an early push. (defaults to <code>Level.OFF</code>, only push when
 * full)
 *
 * <li>&lt;handler-name&gt;.verify <a id="verify">used</a> to
 * verify the <code>Handler</code> configuration prior to a push.
 * <ul>
 *      <li>If the value is not set, equal to an empty string, or equal to the
 *      literal <code>null</code> then no settings are verified prior to a push.
 *      <li>If set to a value of <code>limited</code> then the
 *      <code>Handler</code> will verify minimal local machine settings.
 *      <li>If set to a value of <code>local</code> the <code>Handler</code>
 *      will verify all of settings of the local machine.
 *      <li>If set to a value of <code>resolve</code>, the <code>Handler</code>
 *      will verify all local settings and try to resolve the remote host name
 *      with the domain name server.
 *      <li>If set to a value of <code>login</code>, the <code>Handler</code>
 *      will verify all local settings and try to establish a connection with
 *      the email server.
 *      <li>If set to a value of <code>remote</code>, the <code>Handler</code>
 *      will verify all local settings, try to establish a connection with the
 *      email server, and try to verify the envelope of the email message.
 * </ul>
 * If this <code>Handler</code> is only implicitly closed by the
 * <code>LogManager</code>, then verification should be turned on.
 * (defaults to <code>null</code>, no verify).
 * </ul>
 *
 * <p>
 * <b>Normalization:</b>
 * The error manager, filters, and formatters when loaded from the LogManager
 * are converted into canonical form inside the MailHandler.  The pool of
 * interned values is limited to each MailHandler object such that no two
 * MailHandler objects created by the LogManager will be created sharing
 * identical error managers, filters, or formatters.  If a filter or formatter
 * should <b>not</b> be interned then it is recommended to retain the identity
 * equals and identity hashCode methods as the implementation.  For a filter or
 * formatter to be interned the class must implement the
 * {@linkplain java.lang.Object#equals(java.lang.Object) equals}
 * and {@linkplain java.lang.Object#hashCode() hashCode} methods.
 * The recommended code to use for stateless filters and formatters is:
 * <pre>
 * public boolean equals(Object obj) {
 *     return obj == null ? false : obj.getClass() == getClass();
 * }
 *
 * public int hashCode() {
 *     return 31 * getClass().hashCode();
 * }
 * </pre>
 *
 * <p>
 * <b>Sorting:</b>
 * All <code>LogRecord</code> objects are ordered prior to formatting if this
 * <code>Handler</code> has a non null comparator.  Developers might be
 * interested in sorting the formatted email by thread id, time, and sequence
 * properties of a <code>LogRecord</code>.  Where as system administrators might
 * be interested in sorting the formatted email by thrown, level, time, and
 * sequence properties of a <code>LogRecord</code>.  If comparator for this
 * handler is <code>null</code> then the order is unspecified.
 *
 * <p>
 * <b>Formatting:</b>
 * The main message body is formatted using the <code>Formatter</code> returned
 * by <code>getFormatter()</code>.  Only records that pass the filter returned
 * by <code>getFilter()</code> will be included in the message body.  The
 * subject <code>Formatter</code> will see all <code>LogRecord</code> objects
 * that were published regardless of the current <code>Filter</code>.  The MIME
 * type of the message body can be
 * {@linkplain FileTypeMap#setDefaultFileTypeMap overridden}
 * by adding a MIME {@linkplain MimetypesFileTypeMap entry} using the simple
 * class name of the body formatter as the file extension.  The MIME type of the
 * attachments can be overridden by changing the attachment file name extension
 * or by editing the default MIME entry for a specific file name extension.
 *
 * <p>
 * <b>Attachments:</b>
 * This <code>Handler</code> allows multiple attachments per each email message.
 * The presence of an attachment formatter will change the content type of the
 * email message to a multi-part message.  The attachment order maps directly to
 * the array index order in this <code>Handler</code> with zero index being the
 * first attachment.  The number of attachment formatters controls the number of
 * attachments per email and the content type of each attachment.  The
 * attachment filters determine if a <code>LogRecord</code> will be included in
 * an attachment.  If an attachment filter is <code>null</code> then all records
 * are included for that attachment.  Attachments without content will be
 * omitted from email message.  The attachment name formatters create the file
 * name for an attachment.  Custom attachment name formatters can be used to
 * generate an attachment name based on the contents of the attachment.
 *
 * <p>
 * <b>Push Level and Push Filter:</b>
 * The push method, push level, and optional push filter can be used to
 * conditionally trigger a push at or prior to full capacity.  When a push
 * occurs, the current buffer is formatted into an email and is sent to the
 * email server.  If the push method, push level, or push filter trigger a push
 * then the outgoing email is flagged as high importance with urgent priority.
 *
 * <p>
 * <b>Buffering:</b>
 * Log records that are published are stored in an internal buffer.  When this
 * buffer reaches capacity the existing records are formatted and sent in an
 * email.  Any published records can be sent before reaching capacity by
 * explictly calling the <code>flush</code>, <code>push</code>, or
 * <code>close</code> methods.  If a circular buffer is required then this
 * handler can be wrapped with a {@linkplain java.util.logging.MemoryHandler}
 * typically with an equivalent capacity, level, and push level.
 *
 * <p>
 * <b>Error Handling:</b>
 * If the transport of an email message fails, the email is converted to
 * a {@linkplain jakarta.mail.internet.MimeMessage#writeTo raw}
 * {@linkplain java.io.ByteArrayOutputStream#toString(java.lang.String) string}
 * and is then passed as the <code>msg</code> parameter to
 * {@linkplain Handler#reportError reportError} along with the exception
 * describing the cause of the failure.  This allows custom error managers to
 * store, {@linkplain jakarta.mail.internet.MimeMessage#MimeMessage(
 *jakarta.mail.Session, java.io.InputStream) reconstruct}, and resend the
 * original MimeMessage.  The message parameter string is <b>not</b> a raw email
 * if it starts with value returned from <code>Level.SEVERE.getName()</code>.
 * Custom error managers can use the following test to determine if the
 * <code>msg</code> parameter from this handler is a raw email:
 *
 * <pre>
 * public void error(String msg, Exception ex, int code) {
 *      if (msg == null || msg.length() == 0 || msg.startsWith(Level.SEVERE.getName())) {
 *          super.error(msg, ex, code);
 *      } else {
 *          //The 'msg' parameter is a raw email.
 *      }
 * }
 * </pre>
 *
 * @author Jason Mehrens
 * @since JavaMail 1.4.3
 */
public class MailHandler extends Handler {
    /**
     * Use the emptyFilterArray method.
     */
    private static final Filter[] EMPTY_FILTERS = new Filter[0];
    /**
     * Use the emptyFormatterArray method.
     */
    private static final Formatter[] EMPTY_FORMATTERS = new Formatter[0];
    /**
     * Min byte size for header data.  Used for initial arrays sizing.
     */
    private static final int MIN_HEADER_SIZE = 1024;
    /**
     * Cache the off value.
     */
    private static final int offValue = Level.OFF.intValue();
    /**
     * The action to set the context class loader for use with the Jakarta Mail API.
     * Load and pin this before it is loaded in the close method. The field is
     * declared as java.security.PrivilegedAction so
     * WebappClassLoader.clearReferencesStaticFinal() method will ignore this
     * field.
     */
    private static final PrivilegedAction<Object> MAILHANDLER_LOADER
            = new GetAndSetContext(MailHandler.class);
    /**
     * A thread local mutex used to prevent logging loops.  This code has to be
     * prepared to deal with unexpected null values since the
     * WebappClassLoader.clearReferencesThreadLocals() and
     * InnocuousThread.eraseThreadLocals() can remove thread local values.
     * The MUTEX has 5 states:
     * 1. A null value meaning default state of not publishing.
     * 2. MUTEX_PUBLISH on first entry of a push or publish.
     * 3. The index of the first filter to accept a log record.
     * 4. MUTEX_REPORT when cycle of records is detected.
     * 5. MUTEXT_LINKAGE when a linkage error is reported.
     */
    private static final ThreadLocal<Integer> MUTEX = new ThreadLocal<>();
    /**
     * The marker object used to report a publishing state.
     * This must be less than the body filter index (-1).
     */
    private static final Integer MUTEX_PUBLISH = -2;
    /**
     * The used for the error reporting state.
     * This must be less than the PUBLISH state.
     */
    private static final Integer MUTEX_REPORT = -4;
    /**
     * The used for linkage error reporting.
     * This must be less than the REPORT state.
     */
    private static final Integer MUTEX_LINKAGE = -8;
    /**
     * Used to turn off security checks.
     */
    private volatile boolean sealed;
    /**
     * Determines if we are inside of a push.
     * Makes the handler properties read-only during a push.
     */
    private boolean isWriting;
    /**
     * Holds all of the email server properties.
     */
    private Properties mailProps;
    /**
     * Holds the authenticator required to login to the email server.
     */
    private Authenticator auth;
    /**
     * Holds the session object used to generate emails.
     * Sessions can be shared by multiple threads.
     * See JDK-6228391 and K 6278.
     */
    private Session session;
    /**
     * A mapping of log record to matching filter index.  Negative one is used
     * to track the body filter.  Zero and greater is used to track the
     * attachment parts.  All indexes less than or equal to the matched value
     * have already seen the given log record.
     */
    private int[] matched;
    /**
     * Holds all of the log records that will be used to create the email.
     */
    private LogRecord[] data;
    /**
     * The number of log records in the buffer.
     */
    private int size;
    /**
     * The maximum number of log records to format per email.
     * Used to roughly bound the size of an email.
     * Every time the capacity is reached, the handler will push.
     * The capacity will be negative if this handler is closed.
     * Negative values are used to ensure all records are pushed.
     */
    private int capacity;
    /**
     * The level recorded at the time the handler was disabled.
     * Null means enabled and non-null is disabled.
     */
    private Level disabledLevel;
    /**
     * Used to order all log records prior to formatting.  The main email body
     * and all attachments use the order determined by this comparator.  If no
     * comparator is present the log records will be in no specified order.
     */
    private Comparator<? super LogRecord> comparator;
    /**
     * Holds the formatter used to create the subject line of the email.
     * A subject formatter is not required for the email message.
     * All published records pass through the subject formatter.
     */
    private Formatter subjectFormatter;
    /**
     * Holds the push level for this handler.
     * This is only required if an email must be sent prior to shutdown
     * or before the buffer is full.
     */
    private Level pushLevel;
    /**
     * Holds the push filter for trigger conditions requiring an early push.
     * Only gets called if the given log record is greater than or equal
     * to the push level and the push level is not Level.OFF.
     */
    private Filter pushFilter;
    /**
     * Holds the entry and body filter for this handler.
     * There is no way to un-seal the super handler.
     */
    private volatile Filter filter;
    /**
     * Holds the level for this handler.
     * There is no way to un-seal the super handler.
     */
    private volatile Level logLevel = Level.ALL;
    /**
     * Holds the filters for each attachment.  Filters are optional for
     * each attachment.  This is declared volatile because this is treated as
     * copy-on-write. The VO_VOLATILE_REFERENCE_TO_ARRAY warning is a false
     * positive.
     */
    @SuppressWarnings("VolatileArrayField")
    private volatile Filter[] attachmentFilters;
    /**
     * Holds the encoding name for this handler.
     * There is no way to un-seal the super handler.
     */
    private String encoding;
    /**
     * Holds the entry and body filter for this handler.
     * There is no way to un-seal the super handler.
     */
    private Formatter formatter;
    /**
     * Holds the formatters that create the content for each attachment.
     * Each formatter maps directly to an attachment.  The formatters
     * getHead, format, and getTail methods are only called if one or more
     * log records pass through the attachment filters.
     */
    private Formatter[] attachmentFormatters;
    /**
     * Holds the formatters that create the file name for each attachment.
     * Each formatter must produce a non null and non empty name.
     * The final file name will be the concatenation of one getHead call, plus
     * all of the format calls, plus one getTail call.
     */
    private Formatter[] attachmentNames;
    /**
     * Used to override the content type for the body and set the content type
     * for each attachment.
     */
    private FileTypeMap contentTypes;
    /**
     * Holds the error manager for this handler.
     * There is no way to un-seal the super handler.
     */
    private volatile ErrorManager errorManager = defaultErrorManager();

    /**
     * Creates a <code>MailHandler</code> that is configured by the
     * <code>LogManager</code> configuration properties.
     *
     * @throws SecurityException if a security manager exists and the
     *                           caller does not have <code>LoggingPermission("control")</code>.
     */
    public MailHandler() {
        init((Properties) null);
    }

    /**
     * Creates a <code>MailHandler</code> that is configured by the
     * <code>LogManager</code> configuration properties but overrides the
     * <code>LogManager</code> capacity with the given capacity.
     *
     * @param capacity of the internal buffer.
     * @throws SecurityException        if a security manager exists and the
     *                                  caller does not have <code>LoggingPermission("control")</code>.
     */
    public MailHandler(final int capacity) {
        init((Properties) null);
        setCapacity0(capacity);
    }

    /**
     * Creates a mail handler with the given mail properties.
     * The key/value pairs are defined in the <code>Java Mail API</code>
     * documentation.  This <code>Handler</code> will also search the
     * <code>LogManager</code> for defaults if needed.
     *
     * @param props a properties object or null. A null value will supply the
     * <code>mailEntries</code> from the <code>LogManager</code>.
     * @throws SecurityException    if a security manager exists and the
     *                              caller does not have <code>LoggingPermission("control")</code>.
     */
    public MailHandler(Properties props) {
        init(props); //Must pass null or original object
    }

    /**
     * Check if this <code>Handler</code> would actually log a given
     * <code>LogRecord</code> into its internal buffer.
     * <p>
     * This method checks if the <code>LogRecord</code> has an appropriate level
     * and whether it satisfies any <code>Filter</code> including any
     * attachment filters.
     * However it does <b>not</b> check whether the <code>LogRecord</code> would
     * result in a "push" of the buffer contents.
     *
     * @param record a <code>LogRecord</code> or null.
     * @return true if the <code>LogRecord</code> would be logged.
     */
    @Override
    public boolean isLoggable(final LogRecord record) {
        if (record == null) { //JDK-8233979
            return false;
        }

        int levelValue = getLevel().intValue();
        if (record.getLevel().intValue() < levelValue || levelValue == offValue) {
            return false;
        }

        Filter body = getFilter();
        if (body == null || body.isLoggable(record)) {
            setMatchedPart(-1);
            return true;
        }

        return isAttachmentLoggable(record);
    }

    /**
     * Stores a <code>LogRecord</code> in the internal buffer.
     * <p>
     * The <code>isLoggable</code> method is called to check if the given log
     * record is loggable. If the given record is loggable, it is copied into
     * an internal buffer.  Then the record's level property is compared with
     * the push level. If the given level of the <code>LogRecord</code>
     * is greater than or equal to the push level then the push filter is
     * called.  If no push filter exists, the push filter returns true,
     * or the capacity of the internal buffer has been reached then all buffered
     * records are formatted into one email and sent to the server.
     *
     * @param record description of the log event or null.
     */
    @Override
    public void publish(final LogRecord record) {
        /**
         * It is possible for the handler to be closed after the
         * call to isLoggable.  In that case, the current thread
         * will push to ensure that all published records are sent.
         * See close().
         */

        if (tryMutex()) {
            try {
                if (isLoggable(record)) {
                    if (record != null) {
                        record.getSourceMethodName(); //Infer caller.
                        publish0(record);
                    } else { //Override of isLoggable is broken.
                        reportNullError(ErrorManager.WRITE_FAILURE);
                    }
                }
            } catch (final LinkageError JDK8152515) {
                reportLinkageError(JDK8152515, ErrorManager.WRITE_FAILURE);
            } finally {
                releaseMutex();
            }
        } else {
            reportUnPublishedError(record);
        }
    }

    /**
     * Performs the publish after the record has been filtered.
     *
     * @param record the record which must not be null.
     * @since JavaMail 1.4.5
     */
    private void publish0(final LogRecord record) {
        Message msg;
        boolean priority;
        synchronized (this) {
            if (size == data.length && size < capacity) {
                grow();
            }

            if (size < data.length) {
                //assert data.length == matched.length;
                matched[size] = getMatchedPart();
                data[size] = record;
                ++size; //Be nice to client compiler.
                priority = isPushable(record);
                if (priority || size >= capacity) {
                    msg = writeLogRecords(ErrorManager.WRITE_FAILURE);
                } else {
                    msg = null;
                }
            } else {
                priority = false;
                msg = null;
            }
        }

        if (msg != null) {
            send(msg, priority, ErrorManager.WRITE_FAILURE);
        }
    }

    /**
     * Report to the error manager that a logging loop was detected and
     * we are going to break the cycle of messages.  It is possible that
     * a custom error manager could continue the cycle in which case
     * we will stop trying to report errors.
     *
     * @param record the record or null.
     * @since JavaMail 1.4.6
     */
    private void reportUnPublishedError(LogRecord record) {
        final Integer idx = MUTEX.get();
        if (idx == null || idx > MUTEX_REPORT) {
            MUTEX.set(MUTEX_REPORT);
            try {
                final String msg;
                if (record != null) {
                    final Formatter f = createSimpleFormatter();
                    msg = "Log record " + record.getSequenceNumber()
                            + " was not published. "
                            + head(f) + format(f, record) + tail(f, "");
                } else {
                    msg = null;
                }
                Exception e = new IllegalStateException(
                        "Recursive publish detected by thread "
                                + Thread.currentThread());
                reportError(msg, e, ErrorManager.WRITE_FAILURE);
            } finally {
                if (idx != null) {
                    MUTEX.set(idx);
                } else {
                    MUTEX.remove();
                }
            }
        }
    }

    /**
     * Used to detect reentrance by the current thread to the publish method.
     * This mutex is thread local scope and will not block other threads.
     * The state is advanced on if the current thread is in a reset state.
     *
     * @return true if the mutex was acquired.
     * @since JavaMail 1.4.6
     */
    private boolean tryMutex() {
        if (MUTEX.get() == null) {
            MUTEX.set(MUTEX_PUBLISH);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Releases the mutex held by the current thread.
     * This mutex is thread local scope and will not block other threads.
     *
     * @since JavaMail 1.4.6
     */
    private void releaseMutex() {
        MUTEX.remove();
    }

    /**
     * This is used to get the filter index from when {@code isLoggable} and
     * {@code isAttachmentLoggable} was invoked by {@code publish} method.
     *
     * @return the filter index or MUTEX_PUBLISH if unknown.
     * @throws NullPointerException if tryMutex was not called.
     * @since JavaMail 1.5.5
     */
    private int getMatchedPart() {
        //assert Thread.holdsLock(this);
        Integer idx = MUTEX.get();
        if (idx == null || idx >= readOnlyAttachmentFilters().length) {
            idx = MUTEX_PUBLISH;
        }
        return idx;
    }

    /**
     * This is used to record the filter index when {@code isLoggable} and
     * {@code isAttachmentLoggable} was invoked by {@code publish} method.
     *
     * @param index the filter index.
     * @since JavaMail 1.5.5
     */
    private void setMatchedPart(int index) {
        if (MUTEX_PUBLISH.equals(MUTEX.get())) {
            MUTEX.set(index);
        }
    }

    /**
     * Clear previous matches when the filters are modified and there are
     * existing log records that were matched.
     *
     * @param index the lowest filter index to clear.
     * @since JavaMail 1.5.5
     */
    private void clearMatches(int index) {
        assert Thread.holdsLock(this);
        for (int r = 0; r < size; ++r) {
            if (matched[r] >= index) {
                matched[r] = MUTEX_PUBLISH;
            }
        }
    }

    /**
     * A callback method for when this object is about to be placed into
     * commission. This contract is defined by the
     * {@code org.glassfish.hk2.api.PostConstruct} interface. If this class is
     * loaded via a lifecycle managed environment other than HK2 then it is
     * recommended that this method is called either directly or through
     * extending this class to signal that this object is ready for use.
     *
     * @since JavaMail 1.5.3
     */
    //@javax.annotation.PostConstruct
    public void postConstruct() {
    }

    /**
     * A callback method for when this object is about to be decommissioned.
     * This contract is defined by the {@code org.glassfish.hk2.api.PreDestory}
     * interface. If this class is loaded via a lifecycle managed environment
     * other than HK2 then it is recommended that this method is called either
     * directly or through extending this class to signal that this object will
     * be destroyed.
     *
     * @since JavaMail 1.5.3
     */
    //@javax.annotation.PreDestroy
    public void preDestroy() {
        /**
         * Close can require permissions so just trigger a push.
         */
        push(false, ErrorManager.CLOSE_FAILURE);
    }

    /**
     * Pushes any buffered records to the email server as high importance with
     * urgent priority.  The internal buffer is then cleared.  Does nothing if
     * called from inside a push.
     *
     * @see #flush()
     */
    public void push() {
        push(true, ErrorManager.FLUSH_FAILURE);
    }

    /**
     * Pushes any buffered records to the email server as normal priority.
     * The internal buffer is then cleared.  Does nothing if called from inside
     * a push.
     *
     * @see #push()
     */
    @Override
    public void flush() {
        push(false, ErrorManager.FLUSH_FAILURE);
    }

    /**
     * Prevents any other records from being published.
     * Pushes any buffered records to the email server as normal priority.
     * The internal buffer is then cleared.  Once this handler is closed it
     * will remain closed.
     * <p>
     * If this <code>Handler</code> is only implicitly closed by the
     * <code>LogManager</code>, then <a href="#verify">verification</a> should
     * be turned on and or <code>mailEntries</code> should be declared to define
     * the mail properties.
     *
     * @throws SecurityException if a security manager exists and the
     *                           caller does not have <code>LoggingPermission("control")</code>.
     * @see #flush()
     */
    @Override
    public void close() {
        try {
            checkAccess(); //Ensure setLevel works before clearing the buffer.
            Message msg = null;
            synchronized (this) {
                try {
                    msg = writeLogRecords(ErrorManager.CLOSE_FAILURE);
                } finally {  //Change level after formatting.
                    this.logLevel = Level.OFF;
                    this.disabledLevel = null; //free reference
                    /**
                     * The sign bit of the capacity is set to ensure that
                     * records that have passed isLoggable, but have yet to be
                     * added to the internal buffer, are immediately pushed as
                     * an email.
                     */
                    if (this.capacity > 0) {
                        this.capacity = -this.capacity;
                    }

                    //Ensure not inside a push.
                    if (size == 0 && data.length != 1) {
                        this.data = new LogRecord[1];
                        this.matched = new int[this.data.length];
                    }
                }
            }

            if (msg != null) {
                send(msg, false, ErrorManager.CLOSE_FAILURE);
            }
        } catch (final LinkageError JDK8152515) {
            reportLinkageError(JDK8152515, ErrorManager.CLOSE_FAILURE);
        }
    }

    /**
     * Gets the enabled status of this handler.
     *
     * @return true if this handler is accepting log records.
     * @see #setEnabled(boolean)
     * @see #setLevel(java.util.logging.Level)
     * @since Angus Mail 2.0.3
     */
    public boolean isEnabled() {
        return this.logLevel.intValue() != offValue; //Volatile read
    }

    /**
     * Used to enable or disable this handler.
     *
     * Pushes any buffered records to the email server as normal priority.
     * The internal buffer is then cleared.
     *
     * @param enabled true to enable and false to disable.
     * @throws SecurityException if a security manager exists and if the caller
     * does not have <code>LoggingPermission("control")</code>.
     * @see #flush()
     * @see #isEnabled()
     * @since Angus Mail 2.0.3
     */
    public void setEnabled(final boolean enabled) {
        checkAccess();
        setEnabled0(enabled);
    }

    /**
     * Used to enable or disable this handler.
     *
     * Pushes any buffered records to the email server as normal priority.
     * The internal buffer is then cleared.
     *
     * @param enabled true to enable and false to disable.
     * @since Angus Mail 2.0.3
     */
    private synchronized void setEnabled0(final boolean enabled) {
        if (this.capacity > 0) { //handler is open
            this.push(false, ErrorManager.FLUSH_FAILURE);
            if (enabled) {
                if (disabledLevel != null) { //was disabled
                    this.logLevel = this.disabledLevel;
                    this.disabledLevel = null;
                }
            } else {
                if (disabledLevel == null) {
                    this.disabledLevel = this.logLevel;
                    this.logLevel = Level.OFF;
                }
            }
        }
    }

    /**
     * Set the log level specifying which message levels will be
     * logged by this <code>Handler</code>.  Message levels lower than this
     * value will be discarded.
     *
     * @param newLevel the new value for the log level
     * @throws NullPointerException if <code>newLevel</code> is
     *                              <code>null</code>.
     * @throws SecurityException    if a security manager exists and
     *                              the caller does not have
     *                              <code>LoggingPermission("control")</code>.
     */
    @Override
    public void setLevel(final Level newLevel) {
        Objects.requireNonNull(newLevel);
        checkAccess();

        //Don't allow a closed handler to be opened (half way).
        synchronized (this) { //Wait for writeLogRecords.
            if (this.capacity > 0) {
                //if disabled then track the new level to be used when enabled.
                if (this.disabledLevel != null) {
                    this.disabledLevel = newLevel;
                } else {
                    this.logLevel = newLevel;
                }
           }
        }
    }

    /**
     * Get the log level specifying which messages will be logged by this
     * <code>Handler</code>.  Message levels lower than this level will be
     * discarded.
     *
     * @return the level of messages being logged.
     */
    @Override
    public Level getLevel() {
        return logLevel; //Volatile access.
    }

    /**
     * Retrieves the ErrorManager for this Handler.
     *
     * @return the ErrorManager for this Handler
     * @throws SecurityException if a security manager exists and if the caller
     *                           does not have <code>LoggingPermission("control")</code>.
     */
    @Override
    public ErrorManager getErrorManager() {
        checkAccess();
        return this.errorManager; //Volatile access.
    }

    /**
     * Define an ErrorManager for this Handler.
     * <p>
     * The ErrorManager's "error" method will be invoked if any errors occur
     * while using this Handler.
     *
     * @param em the new ErrorManager
     * @throws SecurityException    if a security manager exists and if the
     *                              caller does not have <code>LoggingPermission("control")</code>.
     * @throws NullPointerException if the given error manager is null.
     */
    @Override
    public void setErrorManager(final ErrorManager em) {
        checkAccess();
        setErrorManager0(em);
    }

    /**
     * Sets the error manager on this handler and the super handler.  In secure
     * environments the super call may not be allowed which is not a failure
     * condition as it is an attempt to free the unused handler error manager.
     *
     * @param em a non null error manager.
     * @throws NullPointerException if the given error manager is null.
     * @since JavaMail 1.5.6
     */
    private void setErrorManager0(final ErrorManager em) {
        Objects.requireNonNull(em);
        try {
            synchronized (this) { //Wait for writeLogRecords.
                this.errorManager = em;
                super.setErrorManager(em); //Try to free super error manager.
            }
        } catch (RuntimeException | LinkageError ignore) {
        }
    }

    /**
     * Get the current <code>Filter</code> for this <code>Handler</code>.
     *
     * @return a <code>Filter</code> object (may be null)
     */
    @Override
    public Filter getFilter() {
        return this.filter; //Volatile access.
    }

    /**
     * Set a <code>Filter</code> to control output on this <code>Handler</code>.
     * <P>
     * For each call of <code>publish</code> the <code>Handler</code> will call
     * this <code>Filter</code> (if it is non-null) to check if the
     * <code>LogRecord</code> should be published or discarded.
     *
     * @param newFilter a <code>Filter</code> object (may be null)
     * @throws SecurityException if a security manager exists and if the caller
     *                           does not have <code>LoggingPermission("control")</code>.
     */
    @Override
    public void setFilter(final Filter newFilter) {
        checkAccess();
        synchronized (this) {  //Wait for writeLogRecords.
            if (newFilter != filter) {
                clearMatches(-1);
            }
            this.filter = newFilter; //Volatile access.
        }
    }

    /**
     * Return the character encoding for this <code>Handler</code>.
     *
     * @return The encoding name.  May be null, which indicates the default
     * encoding should be used.
     */
    @Override
    public synchronized String getEncoding() {
        return this.encoding;
    }

    /**
     * Set the character encoding used by this <code>Handler</code>.
     * <p>
     * The encoding should be set before any <code>LogRecords</code> are written
     * to the <code>Handler</code>.
     *
     * @param encoding The name of a supported character encoding.  May be
     *                 null, to indicate the default platform encoding.
     * @throws SecurityException            if a security manager exists and if the caller
     *                                      does not have <code>LoggingPermission("control")</code>.
     * @throws UnsupportedEncodingException if the named encoding is not
     *                                      supported.
     */
    @Override
    public void setEncoding(String encoding) throws UnsupportedEncodingException {
        checkAccess();
        setEncoding0(encoding);
    }

    /**
     * Set the character encoding used by this handler.  This method does not
     * check permissions of the caller.
     *
     * @param e any encoding name or null for the default.
     * @throws UnsupportedEncodingException if the given encoding is not supported.
     */
    private void setEncoding0(String e) throws UnsupportedEncodingException {
        if (e != null) {
            try {
                if (!java.nio.charset.Charset.isSupported(e)) {
                    throw new UnsupportedEncodingException(e);
                }
            } catch (java.nio.charset.IllegalCharsetNameException icne) {
                throw new UnsupportedEncodingException(e);
            }
        }

        synchronized (this) {  //Wait for writeLogRecords.
            this.encoding = e;
        }
    }

    /**
     * Return the <code>Formatter</code> for this <code>Handler</code>.
     *
     * @return the <code>Formatter</code> (may be null).
     */
    @Override
    public synchronized Formatter getFormatter() {
        return this.formatter;
    }

    /**
     * Set a <code>Formatter</code>.  This <code>Formatter</code> will be used
     * to format <code>LogRecords</code> for this <code>Handler</code>.
     * <p>
     * Some <code>Handlers</code> may not use <code>Formatters</code>, in which
     * case the <code>Formatter</code> will be remembered, but not used.
     *
     * @param newFormatter the <code>Formatter</code> to use (may not be null)
     * @throws SecurityException    if a security manager exists and if the caller
     *                              does not have <code>LoggingPermission("control")</code>.
     * @throws NullPointerException if the given formatter is null.
     */
    @Override
    public synchronized void setFormatter(Formatter newFormatter) throws SecurityException {
        checkAccess();
        this.formatter = Objects.requireNonNull(newFormatter);
    }

    /**
     * Gets the push level.  The default is <code>Level.OFF</code> meaning that
     * this <code>Handler</code> will only push when the internal buffer is full.
     *
     * @return a non-null push level.
     */
    public final synchronized Level getPushLevel() {
        return this.pushLevel;
    }

    /**
     * Sets the push level.  This level is used to trigger a push so that
     * all pending records are formatted and sent to the email server.  When
     * the push level triggers a send, the resulting email is flagged as
     * high importance with urgent priority.
     *
     * @param level any level object or null meaning off.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    public final synchronized void setPushLevel(Level level) {
        checkAccess();
        if (level == null) {
            level = Level.OFF;
        }

        if (isWriting) {
            throw new IllegalStateException();
        }
        this.pushLevel = level;
    }

    /**
     * Gets the push filter.  The default is <code>null</code>.
     *
     * @return the push filter or <code>null</code>.
     */
    public final synchronized Filter getPushFilter() {
        return this.pushFilter;
    }

    /**
     * Sets the push filter.  This filter is only called if the given
     * <code>LogRecord</code> level was greater than the push level.  If this
     * filter returns <code>true</code>, all pending records are formatted and
     * sent to the email server.  When the push filter triggers a send, the
     * resulting email is flagged as high importance with urgent priority.
     *
     * @param filter push filter or <code>null</code>
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    public final synchronized void setPushFilter(final Filter filter) {
        checkAccess();
        if (isWriting) {
            throw new IllegalStateException();
        }
        this.pushFilter = filter;
    }

    /**
     * Gets the comparator used to order all <code>LogRecord</code> objects
     * prior to formatting.  If <code>null</code> then the order is unspecified.
     *
     * @return the <code>LogRecord</code> comparator.
     */
    public final synchronized Comparator<? super LogRecord> getComparator() {
        return this.comparator;
    }

    /**
     * Sets the comparator used to order all <code>LogRecord</code> objects
     * prior to formatting.  If <code>null</code> then the order is unspecified.
     *
     * @param c the <code>LogRecord</code> comparator.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    public final synchronized void setComparator(Comparator<? super LogRecord> c) {
        checkAccess();
        if (isWriting) {
            throw new IllegalStateException();
        }
        this.comparator = c;
    }

    /**
     * Gets the number of log records the internal buffer can hold.  When
     * capacity is reached, <code>Handler</code> will format all
     * <code>LogRecord</code> objects into one email message.
     *
     * @return the capacity.
     */
    public final synchronized int getCapacity() {
        assert capacity != Integer.MIN_VALUE && capacity != 0 : capacity;
        return Math.abs(capacity);
    }

    /**
     * Sets the capacity for this handler.
     *
     * Pushes any buffered records to the email server as normal priority.
     * The internal buffer is then cleared.
     *
     * @param newCapacity the max number of records.
     * @throws SecurityException if a security manager exists and the caller
     * does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalArgumentException is the new capacity is less than one.
     * @throws IllegalStateException if called from inside a push.
     * @see #flush()
     * @since Angus Mail 2.0.3
     */
    public final synchronized void setCapacity(int newCapacity) {
        setCapacity0(newCapacity);
    }

    /**
     * Gets the <code>Authenticator</code> used to login to the email server.
     *
     * @return an <code>Authenticator</code> or <code>null</code> if none is
     * required.
     * @throws SecurityException if a security manager exists and the
     *                           caller does not have <code>LoggingPermission("control")</code>.
     */
    public final synchronized Authenticator getAuthenticator() {
        checkAccess();
        return this.auth;
    }

    /**
     * Sets the <code>Authenticator</code> used to login to the email server.
     *
     * @param auth an <code>Authenticator</code> object or null if none is
     *             required.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    public final void setAuthenticator(final Authenticator auth) {
        this.setAuthenticator0(auth);
    }

    /**
     * Sets the <code>Authenticator</code> used to login to the email server.
     *
     * @param password a password, empty array can be used to only supply a
     *                 user name set by <code>mail.user</code> property, or null if no
     *                 credentials are required.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see String#toCharArray()
     * @since JavaMail 1.4.6
     */
    public final void setAuthenticator(final char... password) {
        if (password == null) {
            setAuthenticator0((Authenticator) null);
        } else {
            setAuthenticator0(DefaultAuthenticator.of(new String(password)));
        }
    }

    /**
     * Sets the <code>Authenticator</code> class name or password used to login
     * to the email server.
     *
     * @param auth the class name of the authenticator, literal password, or
     * empty string can be used to only supply a user name set by
     * <code>mail.user</code> property. A null value can be used if no
     * credentials are required.
     * @throws SecurityException if a security manager exists and the caller
     * does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see #getAuthenticator()
     * @see #setAuthenticator(char...)
     * @since Angus Mail 2.0.3
     */
    public final synchronized void setAuthentication(final String auth) {
        setAuthenticator0(newAuthenticator(auth));
    }

    /**
     * A private hook to handle possible future overrides. See public method.
     *
     * @param auth see public method.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    private void setAuthenticator0(final Authenticator auth) {
        checkAccess();

        Session settings;
        synchronized (this) {
            if (isWriting) {
                throw new IllegalStateException();
            }
            this.auth = auth;
            settings = updateSession();
        }
        verifySettings(settings);
    }

    /**
     * Sets the mail properties used for the session.  The key/value pairs
     * are defined in the <code>Java Mail API</code> documentation.  This
     * <code>Handler</code> will also search the <code>LogManager</code> for
     * defaults if needed.  A key named <code>verify</code> can be declared to
     * trigger <a href="#verify">verification</a>.
     *
     * @param props properties object or null. A null value will supply the
     * <code>mailEntries</code> from the <code>LogManager</code>.  An empty
     * properties will clear all existing mail properties assigned to this
     * handler.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    public final void setMailProperties(Properties props) {
        if (props == null) {
            final String p = getClass().getName();
            props = parseProperties(
                    fromLogManager(p.concat(".mailEntries")));
            setMailProperties0(props != null ? props : new Properties());
        } else {
            setMailProperties0(copyOf(props));
        }
    }

    /**
     * Copies a properties object.  Checks that given properties clone
     * returns the a Properties object and that it is not null.
     *
     * @param props a properties object
     * @return a copy of the properties object.
     * @throws ClassCastException if clone doesn't return a Properties object.
     * @throws NullPointerExeption if props is null or if the copy was null.
     * @since Angus Mail 2.0.3
     */
    private Properties copyOf(Properties props) {
        Properties copy = (Properties) props.clone(); //Allow subclass
        return Objects.requireNonNull(copy); //Broken subclass
    }

    /**
     * A private hook to handle overrides when the public method is declared
     * non final. See public method for details.
     *
     * @param props a safe properties object.
     * @return true if verification key was present.
     * @throws NullPointerException if props is null.
     */
    private boolean setMailProperties0(Properties props) {
        Objects.requireNonNull(props);
        checkAccess();
        Session settings;
        synchronized (this) {
            if (isWriting) {
                throw new IllegalStateException();
            }
            this.mailProps = props;
            settings = updateSession();
        }
        return verifySettings(settings);
    }

    /**
     * Gets a copy of the mail properties used for the session.
     *
     * @return a non null properties object.
     * @throws SecurityException if a security manager exists and the
     *                           caller does not have <code>LoggingPermission("control")</code>.
     */
    public final Properties getMailProperties() {
        checkAccess();
        final Properties props;
        synchronized (this) {
            props = this.mailProps;
        }

        //Null check to force an error sooner rather than later.
        return Objects.requireNonNull((Properties) props.clone());
    }

    /**
     * Parses the given properties lines then clears and sets all of the mail
     * properties used for the session.  Any parsing errors are reported to the
     * error manager.  This method provides bean style properties support.
     * <p>
     * The given string should be treated as lines of a properties file.  The
     * character {@code '='} or {@code ':'} are used to separate an entry also
     * known as a key/value pair.  The line terminator characters {@code \r} or
     * {@code \n} or {@code \r\n} are used to separate each entry.  The
     * characters {@code '#!'} together can be used to signal the end of an
     * entry when escape characters are not supported.
     * <p>
     * The example from the <a href="#configuration">configuration</a>
     * section would be formatted as the following string:
     * <pre>
     * mail.smtp.host:my-mail-server#!mail.to:me@example.com#!verify:local
     * </pre>
     * <p>
     * The key/value pairs are defined in the <code>Java Mail API</code>
     * documentation. This <code>Handler</code> will also search the
     * <code>LogManager</code> for defaults if needed.  A key named
     * <code>verify</code> can be declared to trigger
     * <a href="#verify">verification</a>.
     *
     * @param entries one or more key/value pairs. A null value will supply the
     * <code>mailEntries</code> from the <code>LogManager</code>.  An empty
     * string or the literal null are all treated as empty properties and will
     * clear all existing mail properties assigned to this handler.
     * @throws SecurityException if a security manager exists and the caller
     * does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see #getMailProperties()
     * @see java.io.StringReader
     * @see java.util.Properties#load(Reader)
     * @since Angus Mail 2.0.3
     */
    public final void setMailEntries(String entries) {
        if (entries == null) {
            final String p = getClass().getName();
            entries = fromLogManager(p.concat(".mailEntries"));
        }
        final Properties props = parseProperties(entries);
        setMailProperties0(props != null ? props : new Properties());
    }

    /**
     * Formats the current mail properties as properties lines.  Any formatting
     * errors are reported to the error manager.  The returned string should be
     * treated as lines of a properties file.  The value of this string is
     * reconstructed from the properties object and therefore may be different
     * from what was originally set. This method provides bean style properties
     * support.
     *
     * @return string representation of the mail properties.
     * @throws SecurityException if a security manager exists and the caller
     * does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see #getMailProperties()
     * @see java.io.StringWriter
     * @see java.util.Properties#store(java.io.Writer, java.lang.String)
     * @since Angus Mail 2.0.3
     */
    public final String getMailEntries() {
        checkAccess();
        final Properties props;
        synchronized (this) {
            props = this.mailProps;
        }

        final StringWriter sw = new StringWriter();
        try {
            //Dynamic cast used so byte code verifier doesn't load StringWriter
            props.store(Writer.class.cast(sw), (String) null);
        } catch (IOException | RuntimeException ex) {
            reportError(props.toString(), ex, ErrorManager.GENERIC_FAILURE);
            //partially constructed values are allowed to be returned
        }

        //Properties.store will always write a date comment
        //which is removed by this code.
        String entries = sw.toString();
        if (entries.startsWith("#")) {
            String sep = System.lineSeparator();
            int end = entries.indexOf(sep);
            if (end > 0) {
                entries = entries.substring(end + sep.length(), entries.length());
            }
        }
        return entries;
    }

    /**
     * Gets the attachment filters.  If the attachment filter does not
     * allow any <code>LogRecord</code> to be formatted, the attachment may
     * be omitted from the email.
     *
     * @return a non null array of attachment filters.
     */
    public final Filter[] getAttachmentFilters() {
        return readOnlyAttachmentFilters().clone();
    }

    /**
     * Sets the attachment filters.
     *
     * @param filters array of filters.  A <code>null</code> array is treated
     * the same as an empty array and will remove all attachments.  A
     * <code>null</code> index value means that all records are allowed for the
     * attachment at that index.
     * @throws SecurityException         if a security manager exists and the
     *                                   caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException     if called from inside a push.
     */
    public final void setAttachmentFilters(Filter... filters) {
        checkAccess();
        if (filters == null || filters.length == 0) {
            filters = emptyFilterArray();
        } else {
            filters = Arrays.copyOf(filters, filters.length, Filter[].class);
        }

        synchronized (this) {
            if (isWriting) {
                throw new IllegalStateException();
            }

            if (size != 0) {
                final int len = Math.min(filters.length, attachmentFilters.length);
                int i = 0;
                for (; i < len; ++i) {
                    if (filters[i] != attachmentFilters[i]) {
                        break;
                    }
                }
                clearMatches(i);
            }
            this.attachmentFilters = filters;
            this.alignAttachmentFormatters(filters.length);
            this.alignAttachmentNames(filters.length);
        }
    }

    /**
     * Gets the attachment formatters.  This <code>Handler</code> is using
     * attachments only if the returned array length is non zero.
     *
     * @return a non <code>null</code> array of formatters.
     */
    public final Formatter[] getAttachmentFormatters() {
        Formatter[] formatters;
        synchronized (this) {
            formatters = this.attachmentFormatters;
        }
        return formatters.clone();
    }

    /**
     * Sets the attachment <code>Formatter</code> object for this handler.
     * The number of formatters determines the number of attachments per
     * email.  This method should be the first attachment method called.
     * To remove all attachments, call this method with empty array.
     *
     * @param formatters an array of formatters.  A null array is treated as an
     * empty array.  Any null indexes is replaced with a
     * {@linkplain java.util.logging.SimpleFormatter SimpleFormatter}.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    public final void setAttachmentFormatters(Formatter... formatters) {
        checkAccess();
        if (formatters == null || formatters.length == 0) { //Null check and length check.
            formatters = emptyFormatterArray();
        } else {
            formatters = Arrays.copyOf(formatters,
                    formatters.length, Formatter[].class);
            for (int i = 0; i < formatters.length; ++i) {
                if (formatters[i] == null) {
                    formatters[i] = createSimpleFormatter();
                }
            }
        }

        synchronized (this) {
            if (isWriting) {
                throw new IllegalStateException();
            }

            this.attachmentFormatters = formatters;
            this.alignAttachmentFilters(formatters.length);
            this.alignAttachmentNames(formatters.length);
        }
    }

    /**
     * Gets the attachment name formatters.
     * If the attachment names were set using explicit names then
     * the names can be returned by calling <code>toString</code> on each
     * attachment name formatter.
     *
     * @return non <code>null</code> array of attachment name formatters.
     */
    public final Formatter[] getAttachmentNames() {
        final Formatter[] formatters;
        synchronized (this) {
            formatters = this.attachmentNames;
        }
        return formatters.clone();
    }

    /**
     * Sets the attachment file name for each attachment.  All control
     * characters are removed from the attachment names.
     * This method will create a set of custom formatters.
     *
     * @param names an array of names.  A null array is treated as an empty
     * array.  Any null or empty indexes are replaced with the string
     * representation of the attachment formatter.
     * @throws SecurityException         if a security manager exists and the
     *                                   caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException     if called from inside a push.
     * @see Character#isISOControl(char)
     * @see Character#isISOControl(int)
     */

    public final void setAttachmentNames(final String... names) {
        checkAccess();

        final Formatter[] formatters;
        if (names == null || names.length == 0) {
            formatters = emptyFormatterArray();
        } else {
            formatters = new Formatter[names.length];
        }

        synchronized (this) {
            if (isWriting) {
                throw new IllegalStateException();
            }

            this.alignAttachmentFormatters(formatters.length);
            this.alignAttachmentFilters(formatters.length);
            for (int i = 0; i < formatters.length; ++i) {
                //names is non-null if formatters length is not zero
                String name = names[i];
                if (isEmpty(name)) {
                    name = toString(this.attachmentFormatters[i]);
                }
                formatters[i] = TailNameFormatter.of(name);
            }
            this.attachmentNames = formatters;
        }
    }

    /**
     * Sets the attachment file name formatters.  The format method of each
     * attachment formatter will see only the <code>LogRecord</code> objects
     * that passed its attachment filter during formatting. The format method
     * will typically return an empty string. Instead of being used to format
     * records, it is used to gather information about the contents of an
     * attachment.  The <code>getTail</code> method should be used to construct
     * the attachment file name and reset any formatter collected state.  All
     * control characters will be removed from the output of the formatter.  The
     * <code>toString</code> method of the given formatter should be overridden
     * to provide a useful attachment file name, if possible.
     *
     * @param formatters and array of attachment name formatters.
     * @throws SecurityException         if a security manager exists and the
     *                                   caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException     if called from inside a push.
     * @see Character#isISOControl(char)
     * @see Character#isISOControl(int)
     */
    public final void setAttachmentNames(Formatter... formatters) {
        setAttachmentNameFormatters(formatters);
    }

    /**
     * Sets the attachment file name formatters.  The format method of each
     * attachment formatter will see only the <code>LogRecord</code> objects
     * that passed its attachment filter during formatting. The format method
     * will typically return an empty string. Instead of being used to format
     * records, it is used to gather information about the contents of an
     * attachment.  The <code>getTail</code> method should be used to construct
     * the attachment file name and reset any formatter collected state.  All
     * control characters will be removed from the output of the formatter.  The
     * <code>toString</code> method of the given formatter should be overridden
     * to provide a useful attachment file name, if possible.
     *
     * @param formatters and array of attachment name formatters.
     * @throws SecurityException         if a security manager exists and the
     *                                   caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException     if called from inside a push.
     * @see Character#isISOControl(char)
     * @see Character#isISOControl(int)
     * @since Angus Mail 2.0.3
     */
    public final void setAttachmentNameFormatters(Formatter... formatters) {
        checkAccess();

        if (formatters == null || formatters.length == 0) {
            formatters = emptyFormatterArray();
        } else {
            formatters = Arrays.copyOf(formatters, formatters.length,
                    Formatter[].class);
        }

        synchronized (this) {
            if (isWriting) {
                throw new IllegalStateException();
            }

            this.alignAttachmentFormatters(formatters.length);
            this.alignAttachmentFilters(formatters.length);
            for (int i = 0; i < formatters.length; ++i) {
                if (formatters[i] == null) {
                    formatters[i] = TailNameFormatter.of(toString(this.attachmentFormatters[i]));
                }
            }
            this.attachmentNames = formatters;
        }
    }

    /**
     * Gets the formatter used to create the subject line.
     * If the subject was created using a literal string then
     * the <code>toString</code> method can be used to get the subject line.
     *
     * @return the formatter.
     */
    public final synchronized Formatter getSubject() {
        return getSubjectFormatter();
    }

    /**
     * Gets the formatter used to create the subject line.
     * If the subject was created using a literal string then
     * the <code>toString</code> method can be used to get the subject line.
     *
     * @return the formatter.
     * @since Angus Mail 2.0.3
     */
    public final synchronized Formatter getSubjectFormatter() {
        return this.subjectFormatter;
    }

    /**
     * Sets a literal string for the email subject.  All control characters are
     * removed from the subject line of the email
     *
     * @param subject a non <code>null</code> string.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws NullPointerException  if <code>subject</code> is
     *                               <code>null</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see Character#isISOControl(char)
     * @see Character#isISOControl(int)
     */
    public synchronized final void setSubject(final String subject) {
        if (subject != null) {
            this.setSubjectFormatter(TailNameFormatter.of(subject));
        } else {
            checkAccess();
            initSubject((String) null);
        }
    }

    /**
     * Sets the subject formatter for email.  The format method of the subject
     * formatter will see all <code>LogRecord</code> objects that were published
     * to this <code>Handler</code> during formatting and will typically return
     * an empty string.  This formatter is used to gather information to create
     * a summary about what information is contained in the email.  The
     * <code>getTail</code> method should be used to construct the subject and
     * reset any formatter collected state.  All control characters
     * will be removed from the formatter output.  The <code>toString</code>
     * method of the given formatter should be overridden to provide a useful
     * subject, if possible.
     *
     * @param format the subject formatter or null for default formatter.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see Character#isISOControl(char)
     * @see Character#isISOControl(int)
     */
    public final void setSubject(final Formatter format) {
        setSubjectFormatter(format);
    }

    /**
     * Sets the subject formatter for email.  The format method of the subject
     * formatter will see all <code>LogRecord</code> objects that were published
     * to this <code>Handler</code> during formatting and will typically return
     * an empty string.  This formatter is used to gather information to create
     * a summary about what information is contained in the email.  The
     * <code>getTail</code> method should be used to construct the subject and
     * reset any formatter collected state.  All control characters
     * will be removed from the formatter output.  The <code>toString</code>
     * method of the given formatter should be overridden to provide a useful
     * subject, if possible.
     *
     * @param format the subject formatter or null for default formatter.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     * @see Character#isISOControl(char)
     * @see Character#isISOControl(int)
     * @since Angus Mail 2.0.3
     */
    public synchronized final void setSubjectFormatter(final Formatter format) {
        checkAccess();
        if (format != null) {
            if (isWriting) {
                throw new IllegalStateException();
            }
            this.subjectFormatter = format;
        } else {
            initSubject((String) null);
        }
    }

    /**
     * Protected convenience method to report an error to this Handler's
     * ErrorManager.  This method will prefix all non null error messages with
     * <code>Level.SEVERE.getName()</code>.  This allows the receiving error
     * manager to determine if the <code>msg</code> parameter is a simple error
     * message or a raw email message.
     *
     * @param msg  a descriptive string (may be null)
     * @param ex   an exception (may be null)
     * @param code an error code defined in ErrorManager
     */
    @Override
    protected void reportError(String msg, Exception ex, int code) {
        try {
            if (msg != null) {
                errorManager.error(Level.SEVERE.getName()
                        .concat(": ").concat(msg), ex, code);
            } else {
                errorManager.error((String) null, ex, code);
            }
        } catch (RuntimeException | LinkageError GLASSFISH_21258) {
            reportLinkageError(GLASSFISH_21258, code);
        }
    }

    /**
     * Checks logging permissions if this handler has been sealed.
     * @throws SecurityException if a security manager exists and the caller
     *                           does not have {@code LoggingPermission("control")}.
     */
    private void checkAccess() {
        if (sealed) {
            LogManagerProperties.checkLogManagerAccess();
        }
    }

    /**
     * Determines the mimeType of a formatter from the getHead call.
     * This could be made protected, or a new class could be created to do
     * this type of conversion.  Currently, this is only used for the body
     * since the attachments are computed by filename.
     * Package-private for unit testing.
     *
     * @param chunk any char sequence or null.
     * @return return the mime type or null for text/plain.
     */
    final String contentTypeOf(CharSequence chunk) {
        if (!isEmpty(chunk)) {
            final int MAX_CHARS = 25;
            if (chunk.length() > MAX_CHARS) {
                chunk = chunk.subSequence(0, MAX_CHARS);
            }
            try {
                final String charset = getEncodingName();
                final byte[] b = chunk.toString().getBytes(charset);
                final ByteArrayInputStream in = new ByteArrayInputStream(b);
                assert in.markSupported() : in.getClass().getName();
                return URLConnection.guessContentTypeFromStream(in);
            } catch (final IOException IOE) {
                reportError(IOE.getMessage(), IOE, ErrorManager.FORMAT_FAILURE);
            }
        }
        return null; //text/plain
    }

    /**
     * Determines the mimeType of a formatter by the class name.  This method
     * avoids calling getHead and getTail of content formatters during verify
     * because they might trigger side effects or excessive work.  The name
     * formatters and subject are usually safe to call.
     * Package-private for unit testing.
     *
     * @param f the formatter or null.
     * @return return the mime type or null, meaning text/plain.
     * @since JavaMail 1.5.6
     */
    final String contentTypeOf(final Formatter f) {
        assert Thread.holdsLock(this);
        if (f != null) {
            String type = getContentType(f.getClass().getName());
            if (type != null) {
                return type;
            }

            for (Class<?> k = f.getClass(); k != Formatter.class;
                 k = k.getSuperclass()) {
                String name;
                try {
                    name = k.getSimpleName();
                } catch (final InternalError JDK8057919) {
                    name = k.getName();
                }
                name = name.toLowerCase(Locale.ENGLISH);
                for (int idx = name.indexOf('$') + 1;
                     (idx = name.indexOf("ml", idx)) > -1; idx += 2) {
                    if (idx > 0) {
                        if (name.charAt(idx - 1) == 'x') {
                            return "application/xml";
                        }
                        if (idx > 1 && name.charAt(idx - 2) == 'h'
                                && name.charAt(idx - 1) == 't') {
                            return "text/html";
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Determines if the given throwable is a no content exception.  It is
     * assumed Transport.sendMessage will call Message.writeTo so we need to
     * ignore any exceptions that could be layered on top of that call chain to
     * infer that sendMessage is failing because of writeTo.  Package-private
     * for unit testing.
     *
     * @param msg the message without content.
     * @param t   the throwable chain to test.
     * @return true if the throwable is a missing content exception.
     * @throws NullPointerException if any of the arguments are null.
     * @since JavaMail 1.4.5
     */
    @SuppressWarnings({"UseSpecificCatch", "ThrowableResultIgnored"})
    final boolean isMissingContent(Message msg, Throwable t) {
        final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
        try {
            msg.writeTo(new ByteArrayOutputStream(MIN_HEADER_SIZE));
        } catch (final RuntimeException RE) {
            throw RE; //Avoid catch all.
        } catch (final Exception noContent) {
            final String txt = noContent.getMessage();
            if (!isEmpty(txt)) {
                int limit = 0;
                while (t != null) {
                    if (noContent.getClass() == t.getClass()
                            && txt.equals(t.getMessage())) {
                        return true;
                    }

                    //Not all Jakarta Mail implementations support JDK 1.4
                    //exception chaining.
                    final Throwable cause = t.getCause();
                    if (cause == null && t instanceof MessagingException) {
                        t = ((MessagingException) t).getNextException();
                    } else {
                        t = cause;
                    }

                    //Deal with excessive cause chains and cyclic throwables.
                    if (++limit == (1 << 16)) {
                        break; //Give up.
                    }
                }
            }
        } finally {
            getAndSetContextClassLoader(ccl);
        }
        return false;
    }

    /**
     * Converts a mime message to a raw string or formats the reason
     * why message can't be changed to raw string and reports it.
     *
     * @param msg  the mime message.
     * @param ex   the original exception.
     * @param code the ErrorManager code.
     * @since JavaMail 1.4.5
     */
    @SuppressWarnings("UseSpecificCatch")
    private void reportError(Message msg, Exception ex, int code) {
        try {
            try { //Use direct call so we do not prefix raw email.
                errorManager.error(toRawString(msg), ex, code);
            } catch (final Exception e) {
                reportError(toMsgString(e), ex, code);
            }
        } catch (final LinkageError GLASSFISH_21258) {
            reportLinkageError(GLASSFISH_21258, code);
        }
    }

    /**
     * Reports the given linkage error or runtime exception.
     *
     * The current LogManager code will stop closing all remaining handlers if
     * an error is thrown during resetLogger.  This is a workaround for
     * GLASSFISH-21258 and JDK-8152515.
     *
     * @param le   the linkage error or a RuntimeException.
     * @param code the ErrorManager code.
     * @throws NullPointerException if error is null.
     * @since JavaMail 1.5.3
     */
    private void reportLinkageError(final Throwable le, final int code) {
        if (le == null) {
            throw new NullPointerException(String.valueOf(code));
        }

        final Integer idx = MUTEX.get();
        if (idx == null || idx > MUTEX_LINKAGE) {
            MUTEX.set(MUTEX_LINKAGE);
            try {
                Thread.currentThread().getUncaughtExceptionHandler()
                        .uncaughtException(Thread.currentThread(), le);
            } catch (RuntimeException | LinkageError ignore) {
            } finally {
                if (idx != null) {
                    MUTEX.set(idx);
                } else {
                    MUTEX.remove();
                }
            }
        }
    }

    /**
     * Determines the mimeType from the given file name.
     * Used to override the body content type and used for all attachments.
     *
     * @param name the file name or class name.
     * @return the mime type or null for text/plain.
     */
    private String getContentType(final String name) {
        assert Thread.holdsLock(this);
        if (contentTypes == null) {
            return null;
        }

        final String type = contentTypes.getContentType(name);
        if ("application/octet-stream".equalsIgnoreCase(type)) {
            return null; //Formatters return strings, default to text/plain.
        }
        return type;
    }

    /**
     * Gets the encoding set for this handler, mime encoding, or file encoding.
     *
     * @return the java charset name, never null.
     * @since JavaMail 1.4.5
     */
    private String getEncodingName() {
        String charset = getEncoding();
        if (charset == null) {
            charset = MimeUtility.getDefaultJavaCharset();
        }
        return charset;
    }

    /**
     * Set the content for a part using the encoding assigned to the handler.
     *
     * @param part the part to assign.
     * @param buf  the formatted data.
     * @param type the mime type or null, meaning text/plain.
     * @throws MessagingException if there is a problem.
     */
    private void setContent(MimePart part, CharSequence buf, String type) throws MessagingException {
        final String charset = getEncodingName();
        if (type != null && !"text/plain".equalsIgnoreCase(type)) {
            type = contentWithEncoding(type, charset);
            try {
                DataSource source = new ByteArrayDataSource(buf.toString(), type);
                part.setDataHandler(new DataHandler(source));
            } catch (final IOException IOE) {
                reportError(IOE.getMessage(), IOE, ErrorManager.FORMAT_FAILURE);
                part.setText(buf.toString(), charset);
            }
        } else {
            part.setText(buf.toString(), MimeUtility.mimeCharset(charset));
        }
    }

    /**
     * Replaces the charset parameter with the current encoding.
     *
     * @param type     the content type.
     * @param encoding the java charset name.
     * @return the type with a specified encoding.
     */
    private String contentWithEncoding(String type, String encoding) {
        assert encoding != null;
        try {
            final ContentType ct = new ContentType(type);
            ct.setParameter("charset", MimeUtility.mimeCharset(encoding));
            encoding = ct.toString(); //See jakarta.mail.internet.ContentType.
            if (!isEmpty(encoding)) { //Support pre K5687.
                type = encoding;
            }
        } catch (final MessagingException ME) {
            reportError(type, ME, ErrorManager.FORMAT_FAILURE);
        }
        return type;
    }

    /**
     * Sets the capacity for this handler.
     *
     * @param newCapacity the max number of records.
     * @throws SecurityException     if a security manager exists and the
     *                               caller does not have <code>LoggingPermission("control")</code>.
     * @throws IllegalStateException if called from inside a push.
     */
    private synchronized void setCapacity0(int newCapacity) {
        checkAccess();
        if (newCapacity <= 0) {
            newCapacity = 1000;
        }

        if (isWriting) {
            throw new IllegalStateException();
        }

        if (this.capacity < 0) { //If closed, remain closed.
            this.capacity = -newCapacity;
        } else {
            this.push(false, ErrorManager.FLUSH_FAILURE);
            this.capacity = newCapacity;
            if (this.data != null && this.data.length > newCapacity) {
                this.data = Arrays.copyOf(data, newCapacity, LogRecord[].class);
                this.matched = Arrays.copyOf(matched, newCapacity);
            }
        }
    }

    /**
     * Gets the attachment filters using a happens-before relationship between
     * this method and setAttachmentFilters.  The attachment filters are treated
     * as copy-on-write, so the returned array must never be modified or
     * published outside this class.
     *
     * @return a read only array of filters.
     */
    private Filter[] readOnlyAttachmentFilters() {
        return this.attachmentFilters;
    }

    /**
     * Factory for empty formatter arrays.
     *
     * @return an empty array.
     */
    private static Formatter[] emptyFormatterArray() {
        return EMPTY_FORMATTERS;
    }

    /**
     * Factory for empty filter arrays.
     *
     * @return an empty array.
     */
    private static Filter[] emptyFilterArray() {
        return EMPTY_FILTERS;
    }

    /**
     * Expand or shrink the attachment name formatters with the attachment
     * formatters.
     *
     * @return true if size was changed.
     */
    private boolean alignAttachmentNames(int expect) {
        assert Thread.holdsLock(this);
        boolean fixed = false;
        final int current = this.attachmentNames.length;
        if (current != expect) {
            this.attachmentNames = Arrays.copyOf(attachmentNames, expect,
                    Formatter[].class);
            fixed = current != 0;
        }

        //Copy of zero length array is cheap, warm up copyOf.
        if (expect == 0) {
            this.attachmentNames = emptyFormatterArray();
            assert this.attachmentNames.length == 0;
        } else {
            for (int i = 0; i < expect; ++i) {
                if (this.attachmentNames[i] == null) {
                    this.attachmentNames[i] = TailNameFormatter.of(
                            toString(this.attachmentFormatters[i]));
                }
            }
        }
        return fixed;
    }

    private boolean alignAttachmentFormatters(int expect) {
        assert Thread.holdsLock(this);
        boolean fixed = false;
        final int current = this.attachmentFormatters.length;
        if (current != expect) {
            this.attachmentFormatters = Arrays.copyOf(attachmentFormatters, expect,
                    Formatter[].class);
            fixed = current != 0;
        }

        //Copy of zero length array is cheap, warm up copyOf.
        if (expect == 0) {
            this.attachmentFormatters = emptyFormatterArray();
            assert this.attachmentFormatters.length == 0;
        } else {
            for (int i = current; i < expect; ++i) {
                if (this.attachmentFormatters[i] == null) {
                    this.attachmentFormatters[i] = createSimpleFormatter();
                }
            }
        }
        return fixed;
    }

    /**
     * Expand or shrink the attachment filters with the attachment formatters.
     *
     * @return true if the size was changed.
     */
    private boolean alignAttachmentFilters(int expect) {
        assert Thread.holdsLock(this);

        boolean fixed = false;
        final int current = this.attachmentFilters.length;
        if (current != expect) {
            this.attachmentFilters = Arrays.copyOf(attachmentFilters, expect,
                    Filter[].class);
            clearMatches(Math.min(current, expect));
            fixed = current != 0;

            //Array elements default to null so skip filling if body filter
            //is null.  If not null then only assign to expanded elements.
            final Filter body = this.filter;
            if (body != null) {
                for (int i = current; i < expect; ++i) {
                    this.attachmentFilters[i] = body;
                }
            }
        }

        //Copy of zero length array is cheap, warm up copyOf.
        if (expect == 0) {
            this.attachmentFilters = emptyFilterArray();
            assert this.attachmentFilters.length == 0;
        }
        return fixed;
    }

    /**
     * Sets the size to zero and clears the current buffer.
     */
    private void reset() {
        assert Thread.holdsLock(this);
        if (size < data.length) {
            Arrays.fill(data, 0, size, null);
        } else {
            Arrays.fill(data, null);
        }
        this.size = 0;
    }

    /**
     * Expands the internal buffer up to the capacity.
     */
    private void grow() {
        assert Thread.holdsLock(this);
        final int len = data.length;
        int newCapacity = len + (len >> 1) + 1;
        if (newCapacity > capacity || newCapacity < len) {
            newCapacity = capacity;
        }
        assert len != capacity : len;
        this.data = Arrays.copyOf(data, newCapacity, LogRecord[].class);
        this.matched = Arrays.copyOf(matched, newCapacity);
    }

    /**
     * Configures the handler properties from the log manager.  On normal return
     * this object will be sealed.
     *
     * @param props the given mail properties.  Maybe null and are never
     *              captured by this handler.
     * @throws SecurityException if a security manager exists and the
     *                           caller does not have <code>LoggingPermission("control")</code>.
     * @see #sealed
     */
    private synchronized void init(final Properties props) {
        assert this.errorManager != null;
        final String p = getClass().getName();
        this.mailProps = new Properties(); //ensure non-null on exception
        final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
        try {
            this.contentTypes = FileTypeMap.getDefaultFileTypeMap();
        } finally {
            getAndSetContextClassLoader(ccl);
        }

        //Assign any custom error manager first so it can detect all failures.
        initErrorManager(fromLogManager(p.concat(".errorManager")));
        initCapacity(fromLogManager(p.concat(".capacity")));
        initLevel(fromLogManager(p.concat(".level")));
        initEnabled(fromLogManager(p.concat(".enabled")));
        initFilter(fromLogManager(p.concat(".filter")));
        this.auth = newAuthenticator(fromLogManager(p.concat(".authenticator")));

        initEncoding(fromLogManager(p.concat(".encoding")));
        initFormatter(fromLogManager(p.concat(".formatter")));
        initComparator(fromLogManager(p.concat(".comparator")));
        initComparatorReverse(fromLogManager(p.concat(".comparator.reverse")));
        initPushLevel(fromLogManager(p.concat(".pushLevel")));
        initPushFilter(fromLogManager(p.concat(".pushFilter")));

        initSubject(fromLogManager(p.concat(".subject")));

        initAttachmentFormaters(fromLogManager(p.concat(".attachment.formatters")));
        initAttachmentFilters(fromLogManager(p.concat(".attachment.filters")));
        initAttachmentNames(fromLogManager(p.concat(".attachment.names")));

        //Verification of all of the MailHandler properties starts here
        //That means setting new object members goes above this comment.
        //Entries are always parsed to report any errors.
        Properties entries = parseProperties(fromLogManager(p.concat(".mailEntries")));
        sealed = true;
        boolean verified;
        if (props != null) {
            //Given properties do not fallback to log manager.
            setMailProperties0(copyOf(props));
            verified = true;
        } else if (entries != null) {
            //.mailEntries should fallback to log manager when verify key not present.
            verified = setMailProperties0(entries);
        } else {
            checkAccess();
            verified = false;
        }

        if (!verified && fromLogManager(p.concat(".verify")) != null) {
            verifySettings(initSession());
        }
        intern(); //Show verify warnings first.
    }

    /**
     * Interns the error manager, formatters, and filters contained in this
     * handler.  The comparator is not interned.  This method can only be
     * called from init after all of formatters and filters are in a constructed
     * and in a consistent state.
     *
     * @since JavaMail 1.5.0
     */
    private void intern() {
        assert Thread.holdsLock(this);
        try {
            Object canidate;
            Object result;
            final Map<Object, Object> seen = new HashMap<>();
            try {
                intern(seen, this.errorManager);
            } catch (final SecurityException se) {
                reportError(se.getMessage(), se, ErrorManager.OPEN_FAILURE);
            }

            try {
                canidate = this.filter;
                result = intern(seen, canidate);
                if (result != canidate && result instanceof Filter) {
                    this.filter = (Filter) result;
                }

                canidate = this.formatter;
                result = intern(seen, canidate);
                if (result != canidate && result instanceof Formatter) {
                    this.formatter = (Formatter) result;
                }
            } catch (final SecurityException se) {
                reportError(se.getMessage(), se, ErrorManager.OPEN_FAILURE);
            }

            canidate = this.subjectFormatter;
            result = intern(seen, canidate);
            if (result != canidate && result instanceof Formatter) {
                this.subjectFormatter = (Formatter) result;
            }

            canidate = this.pushFilter;
            result = intern(seen, canidate);
            if (result != canidate && result instanceof Filter) {
                this.pushFilter = (Filter) result;
            }

            for (int i = 0; i < attachmentFormatters.length; ++i) {
                canidate = attachmentFormatters[i];
                result = intern(seen, canidate);
                if (result != canidate && result instanceof Formatter) {
                    attachmentFormatters[i] = (Formatter) result;
                }

                canidate = attachmentFilters[i];
                result = intern(seen, canidate);
                if (result != canidate && result instanceof Filter) {
                    attachmentFilters[i] = (Filter) result;
                }

                canidate = attachmentNames[i];
                result = intern(seen, canidate);
                if (result != canidate && result instanceof Formatter) {
                    attachmentNames[i] = (Formatter) result;
                }
            }
        } catch (final Exception skip) {
            reportError(skip.getMessage(), skip, ErrorManager.OPEN_FAILURE);
        } catch (final LinkageError skip) {
            reportError(skip.getMessage(), new InvocationTargetException(skip),
                    ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * If possible performs an intern of the given object into the
     * map.  If the object can not be interned the given object is returned.
     *
     * @param m the map used to record the interned values.
     * @param o the object to try an intern.
     * @return the original object or an intern replacement.
     * @throws SecurityException if this operation is not allowed by the
     *                           security manager.
     * @throws Exception         if there is an unexpected problem.
     * @since JavaMail 1.5.0
     */
    private Object intern(Map<Object, Object> m, Object o) throws Exception {
        if (o == null) {
            return null;
        }

        /**
         * The common case is that most objects will not intern.  The given
         * object has a public no argument constructor or is an instance of a
         * TailNameFormatter.  TailNameFormatter is safe use as a map key.
         * For everything else we create a clone of the given object.
         * This is done because of the following:
         * 1. Clones can be used to test that a class provides an equals method
         * and that the equals method works correctly.
         * 2. Calling equals on the given object is assumed to be cheap.
         * 3. The intern map can be filtered so it only contains objects that
         * can be interned, which reduces the memory footprint.
         * 4. Clones are method local garbage.
         * 5. Hash code is only called on the clones so bias locking is not
         * disabled on the objects the handler will use.
         */
        final Object key;
        if (o.getClass().getName().equals(TailNameFormatter.class.getName())) {
            key = o;
        } else {
            //This call was already made in the LogManagerProperties so this
            //shouldn't trigger loading of any lazy reflection code.
            key = o.getClass().getConstructor().newInstance();
        }

        final Object use;
        //Check the classloaders of each object avoiding the security manager.
        if (key.getClass() == o.getClass()) {
            Object found = m.get(key); //Transitive equals test.
            if (found == null) {
                //Ensure that equals is symmetric to prove intern is safe.
                final boolean right = key.equals(o);
                final boolean left = o.equals(key);
                if (right && left) {
                    //Assume hashCode is defined at this point.
                    found = m.put(o, o);
                    if (found != null) {
                        reportNonDiscriminating(key, found);
                        found = m.remove(key);
                        if (found != o) {
                            reportNonDiscriminating(key, found);
                            m.clear(); //Try to restore order.
                        }
                    }
                } else {
                    if (right != left) {
                        reportNonSymmetric(o, key);
                    }
                }
                use = o;
            } else {
                //Check for a discriminating equals method.
                if (o.getClass() == found.getClass()) {
                    use = found;
                } else {
                    reportNonDiscriminating(o, found);
                    use = o;
                }
            }
        } else {
            use = o;
        }
        return use;
    }

    /**
     * Factory method used to create a java.util.logging.SimpleFormatter.
     *
     * @return a new SimpleFormatter.
     * @since JavaMail 1.5.6
     */
    private static Formatter createSimpleFormatter() {
        //Don't force the byte code verifier to load the formatter.
        return Formatter.class.cast(new SimpleFormatter());
    }

    /**
     * Checks a char sequence value for null or empty.
     *
     * @param s the char sequence.
     * @return true if the given string is null or zero length.
     */
    private static boolean isEmpty(final CharSequence s) {
        return s == null || s.length() == 0;
    }

    /**
     * Checks that a string is not empty and not equal to the literal "null".
     *
     * @param name the string to check for a value.
     * @return true if the string has a valid value.
     */
    private static boolean hasValue(final String name) {
        return !isEmpty(name) && !"null".equalsIgnoreCase(name);
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param list the list of attachment filter class names.
     * @throws SecurityException    if not allowed.
     */
    private void initAttachmentFilters(final String list) {
        assert Thread.holdsLock(this);
        assert this.attachmentFormatters != null;
        if (!isEmpty(list)) {
            final String[] names = list.split(",");
            Filter[] a = new Filter[names.length];
            for (int i = 0; i < a.length; ++i) {
                names[i] = names[i].trim();
                if (!"null".equalsIgnoreCase(names[i])) {
                    try {
                        a[i] = LogManagerProperties.newFilter(names[i]);
                    } catch (final SecurityException SE) {
                        throw SE; //Avoid catch all.
                    } catch (final Exception E) {
                        reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
                    }
                }
            }

            this.attachmentFilters = a;
            if (alignAttachmentFilters(attachmentFormatters.length)) {
                reportError("Attachment filters.",
                        attachmentMismatch("Length mismatch."), ErrorManager.OPEN_FAILURE);
            }
        } else {
            this.attachmentFilters = emptyFilterArray();
            alignAttachmentFilters(attachmentFormatters.length);
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param list the list of attachment formatter class names or literal names.
     * @throws SecurityException    if not allowed.
     */
    private void initAttachmentFormaters(final String list) {
        assert Thread.holdsLock(this);
        if (!isEmpty(list)) {
            final Formatter[] a;
            final String[] names = list.split(",");
            if (names.length == 0) {
                a = emptyFormatterArray();
            } else {
                a = new Formatter[names.length];
            }

            for (int i = 0; i < a.length; ++i) {
                names[i] = names[i].trim();
                if (!"null".equalsIgnoreCase(names[i])) {
                    try {
                        a[i] = LogManagerProperties.newFormatter(names[i]);
                        if (a[i] instanceof TailNameFormatter) {
                            final Exception CNFE = new ClassNotFoundException(a[i].toString());
                            reportError("Attachment formatter.", CNFE, ErrorManager.OPEN_FAILURE);
                            a[i] = createSimpleFormatter();
                        }
                    } catch (final SecurityException SE) {
                        throw SE; //Avoid catch all.
                    } catch (final Exception E) {
                        reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
                        a[i] = createSimpleFormatter();
                    }
                } else {
                    a[i] = createSimpleFormatter();
                }
            }

            this.attachmentFormatters = a;
        } else {
            this.attachmentFormatters = emptyFormatterArray();
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param list of formatter class names or literals.
     * @throws SecurityException    if not allowed.
     */
    private void initAttachmentNames(final String list) {
        assert Thread.holdsLock(this);
        assert this.attachmentFormatters != null;

        if (!isEmpty(list)) {
            final String[] names = list.split(",");
            final Formatter[] a = new Formatter[names.length];
            for (int i = 0; i < a.length; ++i) {
                names[i] = names[i].trim();
                if (!"null".equalsIgnoreCase(names[i])) {
                    try {
                        try {
                            a[i] = LogManagerProperties.newFormatter(names[i]);
                        } catch (ClassNotFoundException
                                 | ClassCastException literal) {
                            a[i] = TailNameFormatter.of(names[i]);
                        }
                    } catch (final SecurityException SE) {
                        throw SE; //Avoid catch all.
                    } catch (final Exception E) {
                        reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
                    }
                } else {
                    a[i] = TailNameFormatter.of(toString(attachmentFormatters[i]));
                }
            }

            this.attachmentNames = a;
            if (alignAttachmentNames(attachmentFormatters.length)) {
                reportError("Attachment names.",
                        attachmentMismatch("Length mismatch."), ErrorManager.OPEN_FAILURE);
            }
        } else {
            this.attachmentNames = emptyFormatterArray();
            alignAttachmentNames(attachmentFormatters.length);
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param name the authenticator class name, literal password, or empty string.
     * @throws SecurityException    if not allowed.
     */
    private Authenticator newAuthenticator(final String name) {
        Authenticator a = null;
        if (name != null && !"null".equalsIgnoreCase(name)) {
            if (!name.isEmpty()) {
                try {
                    a = LogManagerProperties
                            .newObjectFrom(name, Authenticator.class);
                } catch (final SecurityException SE) {
                    throw SE;
                } catch (final ClassNotFoundException
                               | ClassCastException literalAuth) {
                    a = DefaultAuthenticator.of(name);
                } catch (final Exception E) {
                    reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
                } catch (final LinkageError JDK8152515) {
                   reportLinkageError(JDK8152515, ErrorManager.OPEN_FAILURE);
                }
            } else { //Authenticator is installed to provide the user name.
                a = DefaultAuthenticator.of(name);
            }
        }
        return a;
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param nameOrNumber the level name or number.
     * @throws SecurityException    if not allowed.
     */
    private void initLevel(final String nameOrNumber) {
        assert Thread.holdsLock(this);
        assert disabledLevel == null : disabledLevel;
        try {
            if (!isEmpty(nameOrNumber)) {
                logLevel = Level.parse(nameOrNumber);
            } else {
                logLevel = Level.WARNING;
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final RuntimeException RE) {
            reportError(RE.getMessage(), RE, ErrorManager.OPEN_FAILURE);
            logLevel = Level.WARNING;
        }
    }

    /**
     * Parses the given properties lines. Any parsing errors are reported to the
     * error manager.
     *
     * @param entries one or more key/value pairs. An empty string, null value
     * or, the literal null are all treated as empty properties and will simply
     * clear all existing mail properties assigned to this handler.
     * @return the parsed properties or null if entries was null.
     * @since Angus Mail 2.0.3
     * @see #setMailEntries(java.lang.String)
     */
    private Properties parseProperties(String entries) {
        if (entries != null) {
           final Properties props = new Properties();
           if (!hasValue(entries)) {
              return props;
           }

           /**
            * The characters # and ! are used for comment lines in properties
            * format.  The characters \r or \n are not allowed in WildFly form
            * validation however, properties comment characters are allowed.
            * Comment lines are useless for this handler therefore, "#!"
            * characters are used to represent logical lines and are assumed to
            * not be present together in a key or value.
            */
            try {
                entries = entries.replace("#!", "\r\n");
                //Dynamic cast used so byte code verifier doesn't load StringReader
                props.load(Reader.class.cast(new StringReader(entries)));
            } catch (IOException | RuntimeException ex) {
                reportError(entries, ex, ErrorManager.OPEN_FAILURE);
                //Allow a partial load of properties to be set
            }
            return props;
        }
        return null;
    }

    /**
     * Disables this handler if the property was specified in LogManager.
     * Assumes that initLevel was called before this method.
     *
     * @param enabled the string false will only disable this handler.
     * @since Angus Mail 2.0.3
     */
    private void initEnabled(final String enabled) {
        assert Thread.holdsLock(this);
        assert logLevel != null;
        assert capacity != 0;
        //By default the Handler is enabled so only need to disable it on init.
        if (hasValue(enabled) && !Boolean.parseBoolean(enabled)) {
            setEnabled0(false);
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param name the filter class name or null.
     * @throws SecurityException    if not allowed.
     */
    private void initFilter(final String name) {
        assert Thread.holdsLock(this);
        try {
            if (hasValue(name)) {
                filter = LogManagerProperties.newFilter(name);
            } else {
                filter = null;
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final Exception E) {
            reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param value the capacity value.
     * @throws SecurityException    if not allowed.
     */
    private void initCapacity(final String value) {
        assert Thread.holdsLock(this);
        final int DEFAULT_CAPACITY = 1000;
        try {
            if (value != null) {
                this.setCapacity0(Integer.parseInt(value));
            } else {
                this.setCapacity0(DEFAULT_CAPACITY);
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final RuntimeException RE) {
            reportError(RE.getMessage(), RE, ErrorManager.OPEN_FAILURE);
        }

        if (capacity <= 0) {
            capacity = DEFAULT_CAPACITY;
        }

        this.data = new LogRecord[1];
        this.matched = new int[this.data.length];
    }

    /**
     * Sets the encoding of this handler.
     *
     * @param e the encoding name or null.
     * @throws SecurityException    if not allowed.
     */
    private void initEncoding(final String e) {
        assert Thread.holdsLock(this);
        try {
            setEncoding0(e);
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (UnsupportedEncodingException | RuntimeException UEE) {
            reportError(UEE.getMessage(), UEE, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Used to get or create the default ErrorManager used before init.
     *
     * @return the super error manager or a new ErrorManager.
     * @since JavaMail 1.5.3
     */
    private ErrorManager defaultErrorManager() {
        ErrorManager em;
        try { //Try to share the super error manager.
            em = super.getErrorManager();
        } catch (RuntimeException | LinkageError ignore) {
            em = null;
        }

        //Don't assume that the super call is not null.
        if (em == null) {
            em = new ErrorManager();
        }
        return em;
    }

    /**
     * Creates the error manager for this handler.
     *
     * @param name the error manager class name.
     * @throws SecurityException    if not allowed.
     */
    private void initErrorManager(final String name) {
        assert Thread.holdsLock(this);
        try {
            if (name != null) {
                setErrorManager0(LogManagerProperties.newErrorManager(name));
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final Exception E) {
            reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param name the formatter class name or null.
     * @throws SecurityException    if not allowed.
     */
    private void initFormatter(final String name) {
        assert Thread.holdsLock(this);
        try {
            if (hasValue(name)) {
                final Formatter f
                        = LogManagerProperties.newFormatter(name);
                assert f != null;
                if (f instanceof TailNameFormatter == false) {
                    formatter = f;
                } else {
                    formatter = createSimpleFormatter();
                }
            } else {
                formatter = createSimpleFormatter();
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final Exception E) {
            reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
            formatter = createSimpleFormatter();
        }
    }

    /**
     * Creates the comparator for this handler.
     *
     * @param p the handler class name used as the prefix.
     * @throws SecurityException    if not allowed.
     */
    private void initComparator(final String name) {
        assert Thread.holdsLock(this);
        try {
            if (hasValue(name)) {
                comparator = LogManagerProperties.newComparator(name);
            } else {
                comparator = null;
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final Exception E) {
            reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
        }
    }


    private void initComparatorReverse(final String reverse) {
        if (Boolean.parseBoolean(reverse)) {
            if (comparator != null) {
                comparator = LogManagerProperties.reverseOrder(comparator);
            } else {
                IllegalArgumentException E = new IllegalArgumentException(
                            "No comparator to reverse.");
                reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
            }
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param nameOrNumber the level name, number, or null for OFF.
     * @throws SecurityException    if not allowed.
     */
    private void initPushLevel(final String nameOrNumber) {
        assert Thread.holdsLock(this);
        try {
            if (!isEmpty(nameOrNumber)) {
                this.pushLevel = Level.parse(nameOrNumber);
            } else {
                this.pushLevel = Level.OFF;
            }
        } catch (final RuntimeException RE) {
            reportError(RE.getMessage(), RE, ErrorManager.OPEN_FAILURE);
        }

        if (this.pushLevel == null) {
            this.pushLevel = Level.OFF;
        }
    }

    /**
     * Parses LogManager string values into objects used by this handler.
     *
     * @param name the push filter class name.
     * @throws SecurityException    if not allowed.
     */
    private void initPushFilter(final String name) {
        assert Thread.holdsLock(this);
        try {
            if (hasValue(name)) {
                this.pushFilter = LogManagerProperties.newFilter(name);
            } else {
                this.pushFilter = null;
            }
        } catch (final SecurityException SE) {
            throw SE; //Avoid catch all.
        } catch (final Exception E) {
            reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Creates the subject formatter used by this handler.
     *
     * @param name the formatter class name, string literal, or null.
     * @throws SecurityException    if not allowed.
     */
    private void initSubject(String name) {
        assert Thread.holdsLock(this);
        if (isWriting) {
            throw new IllegalStateException();
        }

        if (name == null) { //Soft dependency on CollectorFormatter.
            name = "org.eclipse.angus.mail.util.logging.CollectorFormatter";
        }

        if (hasValue(name)) {
            try {
                this.subjectFormatter = LogManagerProperties.newFormatter(name);
            } catch (final SecurityException SE) {
                throw SE; //Avoid catch all.
            } catch (ClassNotFoundException
                     | ClassCastException literalSubject) {
                this.subjectFormatter = TailNameFormatter.of(name);
            } catch (final Exception E) {
                this.subjectFormatter = TailNameFormatter.of(name);
                reportError(E.getMessage(), E, ErrorManager.OPEN_FAILURE);
            }
        } else { //User has forced empty or literal null.
            this.subjectFormatter = TailNameFormatter.of(name);
        }
    }

    /**
     * Check if any attachment would actually format the given
     * <code>LogRecord</code>.  This method does not check if the handler
     * is level is set to OFF or if the handler is closed.
     *
     * @param record a <code>LogRecord</code>
     * @return true if the <code>LogRecord</code> would be formatted.
     */
    private boolean isAttachmentLoggable(final LogRecord record) {
        final Filter[] filters = readOnlyAttachmentFilters();
        for (int i = 0; i < filters.length; ++i) {
            final Filter f = filters[i];
            if (f == null || f.isLoggable(record)) {
                setMatchedPart(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Check if this <code>Handler</code> would push after storing the
     * <code>LogRecord</code> into its internal buffer.
     *
     * @param record a <code>LogRecord</code>
     * @return true if the <code>LogRecord</code> triggers an email push.
     * @throws NullPointerException if tryMutex was not called.
     */
    private boolean isPushable(final LogRecord record) {
        assert Thread.holdsLock(this);
        final int value = getPushLevel().intValue();
        if (value == offValue || record.getLevel().intValue() < value) {
            return false;
        }

        final Filter push = getPushFilter();
        if (push == null) {
            return true;
        }

        final int match = getMatchedPart();
        if ((match == -1 && getFilter() == push)
                || (match >= 0 && attachmentFilters[match] == push)) {
            return true;
        } else {
            return push.isLoggable(record);
        }
    }

    /**
     * Used to perform push or flush.
     *
     * @param priority true for high priority otherwise false for normal.
     * @param code     the error manager code.
     */
    private void push(final boolean priority, final int code) {
        if (tryMutex()) {
            try {
                final Message msg = writeLogRecords(code);
                if (msg != null) {
                    send(msg, priority, code);
                }
            } catch (final LinkageError JDK8152515) {
                reportLinkageError(JDK8152515, code);
            } finally {
                releaseMutex();
            }
        } else {
            reportUnPublishedError(null);
        }
    }

    /**
     * Used to send the generated email or write its contents to the
     * error manager for this handler.  This method does not hold any
     * locks so new records can be added to this handler during a send or
     * failure.
     *
     * @param msg      the message or null.
     * @param priority true for high priority or false for normal.
     * @param code     the ErrorManager code.
     * @throws NullPointerException if message is null.
     */
    private void send(Message msg, boolean priority, int code) {
        try {
            envelopeFor(msg, priority);
            final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
            try {  //JDK-8025251
                Transport.send(msg); //Calls save changes.
            } finally {
                getAndSetContextClassLoader(ccl);
            }
        } catch (final Exception e) {
            reportError(msg, e, code);
        }
    }

    /**
     * Performs a sort on the records if needed.
     * Any exception thrown during a sort is considered a formatting error.
     */
    private void sort() {
        assert Thread.holdsLock(this);
        if (comparator != null) {
            try {
                if (size != 1) {
                    Arrays.sort(data, 0, size, comparator);
                } else {
                    if (comparator.compare(data[0], data[0]) != 0) {
                        throw new IllegalArgumentException(
                                comparator.getClass().getName());
                    }
                }
            } catch (final RuntimeException RE) {
                reportError(RE.getMessage(), RE, ErrorManager.FORMAT_FAILURE);
            }
        }
    }

    /**
     * Formats all records in the buffer and places the output in a Message.
     * This method under most conditions will catch, report, and continue when
     * exceptions occur.  This method holds a lock on this handler.
     *
     * @param code the error manager code.
     * @return null if there are no records or is currently in a push.
     * Otherwise a new message is created with a formatted message and
     * attached session.
     */
    private Message writeLogRecords(final int code) {
        try {
            synchronized (this) {
                if (size > 0 && !isWriting) {
                    isWriting = true;
                    try {
                        return writeLogRecords0();
                    } finally {
                        isWriting = false;
                        if (size > 0) {
                            reset();
                        }
                    }
                }
            }
        } catch (final Exception e) {
            reportError(e.getMessage(), e, code);
        }
        return null;
    }

    /**
     * Formats all records in the buffer and places the output in a Message.
     * This method under most conditions will catch, report, and continue when
     * exceptions occur.
     *
     * @return null if there are no records or is currently in a push. Otherwise
     * a new message is created with a formatted message and attached session.
     * @throws MessagingException if there is a problem.
     * @throws IOException        if there is a problem.
     * @throws RuntimeException   if there is an unexpected problem.
     * @since JavaMail 1.5.3
     */
    private Message writeLogRecords0() throws Exception {
        assert Thread.holdsLock(this);
        sort();
        if (session == null) {
            initSession();
        }
        MimeMessage msg = new MimeMessage(session);

        /**
         * Parts are lazily created when an attachment performs a getHead
         * call.  Therefore, a null part at an index means that the head is
         * required.
         */
        MimeBodyPart[] parts = new MimeBodyPart[attachmentFormatters.length];

        /**
         * The buffers are lazily created when the part requires a getHead.
         */
        StringBuilder[] buffers = new StringBuilder[parts.length];
        StringBuilder buf = null;
        final MimePart body;
        if (parts.length == 0) {
            msg.setDescription(descriptionFrom(
                    getFormatter(), getFilter(), subjectFormatter));
            body = msg;
        } else {
            msg.setDescription(descriptionFrom(
                    comparator, pushLevel, pushFilter));
            body = createBodyPart();
        }

        appendSubject(msg, head(subjectFormatter));
        final Formatter bodyFormat = getFormatter();
        final Filter bodyFilter = getFilter();

        Locale lastLocale = null;
        for (int ix = 0; ix < size; ++ix) {
            boolean formatted = false;
            final int match = matched[ix];
            final LogRecord r = data[ix];
            data[ix] = null; //Clear while formatting.

            final Locale locale = localeFor(r);
            appendSubject(msg, format(subjectFormatter, r));
            Filter lmf = null; //Identity of last matched filter.
            if (bodyFilter == null || match == -1 || parts.length == 0
                    || (match < -1 && bodyFilter.isLoggable(r))) {
                lmf = bodyFilter;
                if (buf == null) {
                    buf = new StringBuilder();
                    buf.append(head(bodyFormat));
                }
                formatted = true;
                buf.append(format(bodyFormat, r));
                if (locale != null && !locale.equals(lastLocale)) {
                    appendContentLang(body, locale);
                }
            }

            for (int i = 0; i < parts.length; ++i) {
                //A match index less than the attachment index means that
                //the filter has not seen this record.
                final Filter af = attachmentFilters[i];
                if (af == null || lmf == af || match == i
                        || (match < i && af.isLoggable(r))) {
                    if (lmf == null && af != null) {
                        lmf = af;
                    }
                    if (parts[i] == null) {
                        parts[i] = createBodyPart(i);
                        buffers[i] = new StringBuilder();
                        buffers[i].append(head(attachmentFormatters[i]));
                        appendFileName(parts[i], head(attachmentNames[i]));
                    }
                    formatted = true;
                    appendFileName(parts[i], format(attachmentNames[i], r));
                    buffers[i].append(format(attachmentFormatters[i], r));
                    if (locale != null && !locale.equals(lastLocale)) {
                        appendContentLang(parts[i], locale);
                    }
                }
            }

            if (formatted) {
                if (body != msg && locale != null
                        && !locale.equals(lastLocale)) {
                    appendContentLang(msg, locale);
                }
            } else {  //Belongs to no mime part.
                reportFilterError(r);
            }
            lastLocale = locale;
        }
        this.size = 0;

        for (int i = parts.length - 1; i >= 0; --i) {
            if (parts[i] != null) {
                appendFileName(parts[i], tail(attachmentNames[i], "err"));
                buffers[i].append(tail(attachmentFormatters[i], ""));

                if (buffers[i].length() > 0) {
                    String name = parts[i].getFileName();
                    if (isEmpty(name)) { //Exceptional case.
                        name = toString(attachmentFormatters[i]);
                        parts[i].setFileName(name);
                    }
                    setContent(parts[i], buffers[i], getContentType(name));
                } else {
                    setIncompleteCopy(msg);
                    parts[i] = null; //Skip this part.
                }
                buffers[i] = null;
            }
        }

        if (buf != null) {
            buf.append(tail(bodyFormat, ""));
            //This body part is always added, even if the buffer is empty,
            //so the body is never considered an incomplete-copy.
        } else {
            buf = new StringBuilder(0);
        }

        appendSubject(msg, tail(subjectFormatter, ""));

        String contentType = contentTypeOf(buf);
        String altType = contentTypeOf(bodyFormat);
        setContent(body, buf, altType == null ? contentType : altType);
        if (body != msg) {
            final MimeMultipart multipart = createMultipart();
            //assert body instanceof BodyPart : body;
            multipart.addBodyPart((BodyPart) body);

            for (int i = 0; i < parts.length; ++i) {
                if (parts[i] != null) {
                    multipart.addBodyPart(parts[i]);
                }
            }
            msg.setContent(multipart);
        }

        return msg;
    }

    /**
     * Checks all of the settings if the caller requests a verify and a verify
     * was not performed yet and no verify is in progress.  A verify is
     * performed on create because this handler may be at the end of a handler
     * chain and therefore may not see any log records until LogManager.reset()
     * is called and at that time all of the settings have been cleared.
     *
     * @param session the current session or null.
     * @return true if verification key was present.
     * @since JavaMail 1.4.4
     */
    private boolean verifySettings(final Session session) {
        try {
            if (session == null) {
                return false;
            }

            final Properties props = session.getProperties();
            final Object check = props.put("verify", "");
            if (check == null) {
                return false;
            }

            if (check instanceof String) {
                String value = (String) check;
                //Perform the verify if needed.
                if (hasValue(value)) {
                    verifySettings0(session, value);
                }
                return true;
            } else { //Pass some invalid string.
                verifySettings0(session, check.getClass().toString());
            }
        } catch (final LinkageError JDK8152515) {
            reportLinkageError(JDK8152515, ErrorManager.OPEN_FAILURE);
        }
        return false;
    }

    /**
     * Checks all of the settings using the given setting.
     * This triggers the LogManagerProperties to copy all of the mail
     * settings without explictly knowing them.  Once all of the properties
     * are copied this handler can handle LogManager.reset clearing all of the
     * properties.  It is expected that this method is, at most, only called
     * once per session.
     *
     * @param session the current session.
     * @param verify  the type of verify to perform.
     * @since JavaMail 1.4.4
     */
    private void verifySettings0(Session session, String verify) {
        assert verify != null : (String) null;
        if (!"local".equals(verify) && !"remote".equals(verify)
                && !"limited".equals(verify) && !"resolve".equals(verify)
                && !"login".equals(verify)) {
            reportError("Verify must be 'limited', local', "
                            + "'resolve', 'login', or 'remote'.",
                    new IllegalArgumentException(verify),
                    ErrorManager.OPEN_FAILURE);
            return;
        }

        final MimeMessage abort = new MimeMessage(session);
        final String msg;
        if (!"limited".equals(verify)) {
            msg = "Local address is "
                    + InternetAddress.getLocalAddress(session) + '.';

            try { //Verify subclass or declared mime charset.
                Charset.forName(getEncodingName());
            } catch (final RuntimeException RE) {
                UnsupportedEncodingException UEE =
                        new UnsupportedEncodingException(RE.toString());
                UEE.initCause(RE);
                reportError(msg, UEE, ErrorManager.FORMAT_FAILURE);
            }
        } else {
            msg = "Skipping local address check.";
        }

        //Perform all of the copy actions first.
        String[] atn;
        synchronized (this) { //Create the subject.
            appendSubject(abort, head(subjectFormatter));
            appendSubject(abort, tail(subjectFormatter, ""));
            atn = new String[attachmentNames.length];
            for (int i = 0; i < atn.length; ++i) {
                atn[i] = head(attachmentNames[i]);
                if (atn[i].length() == 0) {
                    atn[i] = tail(attachmentNames[i], "");
                } else {
                    atn[i] = atn[i].concat(tail(attachmentNames[i], ""));
                }
            }
        }

        setIncompleteCopy(abort); //Original body part is never added.
        envelopeFor(abort, true);
        saveChangesNoContent(abort, msg);
        try {
            //Ensure transport provider is installed.
            Address[] all = abort.getAllRecipients();
            if (all == null) { //Don't pass null to sendMessage.
                all = new InternetAddress[0];
            }
            Transport t;
            try {
                final Address[] any = all.length != 0 ? all : abort.getFrom();
                if (any != null && any.length != 0) {
                    t = session.getTransport(any[0]);
                    session.getProperty("mail.transport.protocol"); //Force copy
                } else {
                    MessagingException me = new MessagingException(
                            "No recipient or from address.");
                    reportError(msg, me, ErrorManager.OPEN_FAILURE);
                    throw me;
                }
            } catch (final MessagingException protocol) {
                //Switching the CCL emulates the current send behavior.
                Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
                try {
                    t = session.getTransport();
                } catch (final MessagingException fail) {
                    throw attach(protocol, fail);
                } finally {
                    getAndSetContextClassLoader(ccl);
                }
            }

            String local = null;
            if ("remote".equals(verify) || "login".equals(verify)) {
                MessagingException closed = null;
                t.connect();
                try {
                    try {
                        //Capture localhost while connection is open.
                        local = getLocalHost(t);

                        //A message without content will fail at message writeTo
                        //when sendMessage is called.  This allows the handler
                        //to capture all mail properties set in the LogManager.
                        if ("remote".equals(verify)) {
                            t.sendMessage(abort, all);
                        }
                    } finally {
                        try {
                            t.close();
                        } catch (final MessagingException ME) {
                            closed = ME;
                        }
                    }
                    //Close the transport before reporting errors.
                    if ("remote".equals(verify)) {
                        reportUnexpectedSend(abort, verify, null);
                    } else {
                        final String protocol = t.getURLName().getProtocol();
                        verifyProperties(session, protocol);
                    }
                } catch (final SendFailedException sfe) {
                    Address[] recip = sfe.getInvalidAddresses();
                    if (recip != null && recip.length != 0) {
                        setErrorContent(abort, verify, sfe);
                        reportError(abort, sfe, ErrorManager.OPEN_FAILURE);
                    }

                    recip = sfe.getValidSentAddresses();
                    if (recip != null && recip.length != 0) {
                        reportUnexpectedSend(abort, verify, sfe);
                    }
                } catch (final MessagingException ME) {
                    if (!isMissingContent(abort, ME)) {
                        setErrorContent(abort, verify, ME);
                        reportError(abort, ME, ErrorManager.OPEN_FAILURE);
                    }
                }

                if (closed != null) {
                    setErrorContent(abort, verify, closed);
                    reportError(abort, closed, ErrorManager.CLOSE_FAILURE);
                }
            } else {
                //Force a property copy, JDK-7092981.
                final String protocol = t.getURLName().getProtocol();
                verifyProperties(session, protocol);
                String mailHost = session.getProperty("mail."
                        + protocol + ".host");
                if (isEmpty(mailHost)) {
                    mailHost = session.getProperty("mail.host");
                } else {
                    session.getProperty("mail.host");
                }

                local = session.getProperty("mail." + protocol + ".localhost");
                if (isEmpty(local)) {
                    local = session.getProperty("mail."
                            + protocol + ".localaddress");
                } else {
                    session.getProperty("mail." + protocol + ".localaddress");
                }

                if ("resolve".equals(verify)) {
                    try { //Resolve the remote host name.
                        String transportHost = t.getURLName().getHost();
                        if (!isEmpty(transportHost)) {
                            verifyHost(transportHost);
                            if (!transportHost.equalsIgnoreCase(mailHost)) {
                                verifyHost(mailHost);
                            }
                        } else {
                            verifyHost(mailHost);
                        }
                    } catch (final RuntimeException | IOException IOE) {
                        MessagingException ME =
                                new MessagingException(msg, IOE);
                        setErrorContent(abort, verify, ME);
                        reportError(abort, ME, ErrorManager.OPEN_FAILURE);
                    }
                }
            }

            if (!"limited".equals(verify)) {
                try { //Verify host name and hit the host name cache.
                    if (!"remote".equals(verify) && !"login".equals(verify)) {
                        local = getLocalHost(t);
                    }
                    verifyHost(local);
                } catch (final RuntimeException | IOException IOE) {
                    MessagingException ME = new MessagingException(msg, IOE);
                    setErrorContent(abort, verify, ME);
                    reportError(abort, ME, ErrorManager.OPEN_FAILURE);
                }

                try { //Verify that the DataHandler can be loaded.
                    Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
                    try {
                        //Always load the multipart classes.
                        MimeMultipart multipart = new MimeMultipart();
                        MimeBodyPart[] ambp = new MimeBodyPart[atn.length];
                        final MimeBodyPart body;
                        final String bodyContentType;
                        synchronized (this) {
                            bodyContentType = contentTypeOf(getFormatter());
                            body = createBodyPart();
                            for (int i = 0; i < atn.length; ++i) {
                                ambp[i] = createBodyPart(i);
                                ambp[i].setFileName(atn[i]);
                                //Convert names to mime type under lock.
                                atn[i] = getContentType(atn[i]);
                            }
                        }

                        body.setDescription(verify);
                        setContent(body, "", bodyContentType);
                        multipart.addBodyPart(body);
                        for (int i = 0; i < ambp.length; ++i) {
                            ambp[i].setDescription(verify);
                            setContent(ambp[i], "", atn[i]);
                        }

                        abort.setContent(multipart);
                        abort.saveChanges();
                        abort.writeTo(new ByteArrayOutputStream(MIN_HEADER_SIZE));
                    } finally {
                        getAndSetContextClassLoader(ccl);
                    }
                } catch (final IOException IOE) {
                    MessagingException ME = new MessagingException(msg, IOE);
                    setErrorContent(abort, verify, ME);
                    reportError(abort, ME, ErrorManager.FORMAT_FAILURE);
                }
            }

            //Verify all recipients.
            if (all.length != 0) {
                verifyAddresses(all);
            } else {
                throw new MessagingException("No recipient addresses.");
            }

            //Verify from and sender addresses.
            Address[] from = abort.getFrom();
            Address sender = abort.getSender();
            if (sender instanceof InternetAddress) {
                ((InternetAddress) sender).validate();
            }

            //If from address is declared then check sender.
            if (abort.getHeader("From", ",") != null && from.length != 0) {
                verifyAddresses(from);
                for (int i = 0; i < from.length; ++i) {
                    if (from[i].equals(sender)) {
                        MessagingException ME = new MessagingException(
                                "Sender address '" + sender
                                        + "' equals from address.");
                        throw new MessagingException(msg, ME);
                    }
                }
            } else {
                if (sender == null) {
                    MessagingException ME = new MessagingException(
                            "No from or sender address.");
                    throw new MessagingException(msg, ME);
                }
            }

            //Verify reply-to addresses.
            verifyAddresses(abort.getReplyTo());
        } catch (final Exception ME) {
            setErrorContent(abort, verify, ME);
            reportError(abort, ME, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Handles all exceptions thrown when save changes is called on a message
     * that doesn't have any content.
     *
     * @param abort the message requiring save changes.
     * @param msg   the error description.
     * @since JavaMail 1.6.0
     */
    private void saveChangesNoContent(final Message abort, final String msg) {
        if (abort != null) {
            try {
                try {
                    abort.saveChanges();
                } catch (final NullPointerException xferEncoding) {
                    //Workaround GNU JavaMail bug in MimeUtility.getEncoding
                    //when the mime message has no content.
                    try {
                        String cte = "Content-Transfer-Encoding";
                        if (abort.getHeader(cte) == null) {
                            abort.setHeader(cte, EncoderTypes.BASE_64.getEncoder());
                            abort.saveChanges();
                        } else {
                            throw xferEncoding;
                        }
                    } catch (RuntimeException | MessagingException e) {
                        if (e != xferEncoding) {
                            e.addSuppressed(xferEncoding);
                        }
                        throw e;
                    }
                }
            } catch (RuntimeException | MessagingException ME) {
                reportError(msg, ME, ErrorManager.FORMAT_FAILURE);
            }
        }
    }

    /**
     * Cache common session properties into the LogManagerProperties.  This is
     * a workaround for JDK-7092981.
     *
     * @param session  the session.
     * @param protocol the mail protocol.
     * @throws NullPointerException if session is null.
     * @since JavaMail 1.6.0
     */
    private static void verifyProperties(Session session, String protocol) {
        session.getProperty("mail.from");
        session.getProperty("mail." + protocol + ".from");
        session.getProperty("mail.dsn.ret");
        session.getProperty("mail." + protocol + ".dsn.ret");
        session.getProperty("mail.dsn.notify");
        session.getProperty("mail." + protocol + ".dsn.notify");
        session.getProperty("mail." + protocol + ".port");
        session.getProperty("mail.user");
        session.getProperty("mail." + protocol + ".user");
        session.getProperty("mail." + protocol + ".localport");
    }

    /**
     * Perform a lookup of the host address or FQDN.
     *
     * @param host the host or null.
     * @return the address.
     * @throws IOException       if the host name is not valid.
     * @throws SecurityException if security manager is present and doesn't
     *                           allow access to check connect permission.
     * @since JavaMail 1.5.0
     */
    private static InetAddress verifyHost(String host) throws IOException {
        InetAddress a;
        if (isEmpty(host)) {
            a = InetAddress.getLocalHost();
        } else {
            a = InetAddress.getByName(host);
        }
        if (a.getCanonicalHostName().length() == 0) {
            throw new UnknownHostException();
        }
        return a;
    }

    /**
     * Calls validate for every address given.
     * If the addresses given are null, empty or not an InternetAddress then
     * the check is skipped.
     *
     * @param all any address array, null or empty.
     * @throws AddressException if there is a problem.
     * @since JavaMail 1.4.5
     */
    private static void verifyAddresses(Address[] all) throws AddressException {
        if (all != null) {
            for (int i = 0; i < all.length; ++i) {
                final Address a = all[i];
                if (a instanceof InternetAddress) {
                    ((InternetAddress) a).validate();
                }
            }
        }
    }

    /**
     * Reports that an empty content message was sent and should not have been.
     *
     * @param msg    the MimeMessage.
     * @param verify the verify enum.
     * @param cause  the exception that caused the problem or null.
     * @since JavaMail 1.4.5
     */
    private void reportUnexpectedSend(MimeMessage msg, String verify, Exception cause) {
        final MessagingException write = new MessagingException(
                "An empty message was sent.", cause);
        setErrorContent(msg, verify, write);
        reportError(msg, write, ErrorManager.OPEN_FAILURE);
    }

    /**
     * Creates and sets the message content from the given Throwable.
     * When verify fails, this method fixes the 'abort' message so that any
     * created envelope data can be used in the error manager.
     *
     * @param msg    the message with or without content.
     * @param verify the verify enum.
     * @param t      the throwable or null.
     * @since JavaMail 1.4.5
     */
    private void setErrorContent(MimeMessage msg, String verify, Throwable t) {
        try { //Add content so toRawString doesn't fail.
            final MimeBodyPart body;
            final String subjectType;
            final String msgDesc;
            synchronized (this) {
                body = createBodyPart();
                msgDesc = descriptionFrom(comparator, pushLevel, pushFilter);
                subjectType = getClassId(subjectFormatter);
            }

            body.setDescription("Formatted using "
                    + (t == null ? Throwable.class.getName()
                    : t.getClass().getName()) + ", filtered with "
                    + verify + ", and named by "
                    + subjectType + '.');
            setContent(body, toMsgString(t), "text/plain");
            final MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(body);
            msg.setContent(multipart);
            msg.setDescription(msgDesc);
            setAcceptLang(msg);
            msg.saveChanges();
        } catch (MessagingException | RuntimeException ME) {
            reportError("Unable to create body.", ME, ErrorManager.OPEN_FAILURE);
        }
    }

    /**
     * Used to update the cached session object based on changes in
     * mail properties or authenticator.
     *
     * @return the current session or null if no verify is required.
     */
    private Session updateSession() {
        assert Thread.holdsLock(this);
        final Session settings;
        if (mailProps.getProperty("verify") != null) {
            settings = initSession();
            assert settings == session : session;
        } else {
            session = null; //Remove old session.
            settings = null;
        }
        return settings;
    }

    /**
     * Creates a session using a proxy properties object.
     *
     * @return the session that was created and assigned.
     */
    private Session initSession() {
        assert Thread.holdsLock(this);
        final String p = getClass().getName();
        final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
        try {
            LogManagerProperties proxy = new LogManagerProperties(mailProps, p);
            session = Session.getInstance(proxy, auth);
        } finally {
            getAndSetContextClassLoader(ccl);
        }
        return session;
    }

    /**
     * Creates all of the envelope information for a message.
     * This method is safe to call outside of a lock because the message
     * provides the safe snapshot of the mail properties.
     *
     * @param msg      the Message to write the envelope information.
     * @param priority true for high priority.
     */
    private void envelopeFor(Message msg, boolean priority) {
        setAcceptLang(msg);
        setFrom(msg);
        if (!setRecipient(msg, "mail.to", Message.RecipientType.TO)) {
            setDefaultRecipient(msg, Message.RecipientType.TO);
        }
        setRecipient(msg, "mail.cc", Message.RecipientType.CC);
        setRecipient(msg, "mail.bcc", Message.RecipientType.BCC);
        setReplyTo(msg);
        setSender(msg);
        setMailer(msg);
        setAutoSubmitted(msg);
        if (priority) {
            setPriority(msg);
        }

        try {
            msg.setSentDate(new java.util.Date());
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    private MimeMultipart createMultipart() throws MessagingException {
        assert Thread.holdsLock(this);
        final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
        try {
            return new MimeMultipart();
        } finally {
            getAndSetContextClassLoader(ccl);
        }
    }

    /**
     * Factory to create the in-line body part.
     *
     * @return a body part with default headers set.
     * @throws MessagingException if there is a problem.
     */
    private MimeBodyPart createBodyPart() throws MessagingException {
        assert Thread.holdsLock(this);
        final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
        try {
            final MimeBodyPart part = new MimeBodyPart();
            part.setDisposition(Part.INLINE);
            part.setDescription(descriptionFrom(getFormatter(),
                getFilter(), subjectFormatter));
            setAcceptLang(part);
            return part;
        } finally {
            getAndSetContextClassLoader(ccl);
        }
    }

    /**
     * Factory to create the attachment body part.
     *
     * @param index the attachment index.
     * @return a body part with default headers set.
     * @throws MessagingException        if there is a problem.
     * @throws IndexOutOfBoundsException if the given index is not an valid
     *                                   attachment index.
     */
    private MimeBodyPart createBodyPart(int index) throws MessagingException {
        assert Thread.holdsLock(this);
        final Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
        try {
            final MimeBodyPart part = new MimeBodyPart();
            part.setDisposition(Part.ATTACHMENT);
            part.setDescription(descriptionFrom(
            attachmentFormatters[index],
            attachmentFilters[index],
            attachmentNames[index]));
            setAcceptLang(part);
            return part;
        } finally {
            getAndSetContextClassLoader(ccl);
        }
    }

    /**
     * Gets the description for the MimeMessage itself.
     * The push level and filter are included because they play a role in
     * formatting of a message when triggered or not triggered.
     *
     * @param c the comparator.
     * @param l the pushLevel.
     * @param f the pushFilter
     * @return the description.
     * @throws NullPointerException if level is null.
     * @since JavaMail 1.4.5
     */
    private String descriptionFrom(Comparator<?> c, Level l, Filter f) {
        return "Sorted using " + (c == null ? "no comparator"
                : c.getClass().getName()) + ", pushed when " + l.getName()
                + ", and " + (f == null ? "no push filter"
                : f.getClass().getName()) + '.';
    }

    /**
     * Creates a description for a body part.
     *
     * @param f      the content formatter.
     * @param filter the content filter.
     * @param name   the naming formatter.
     * @return the description for the body part.
     */
    private String descriptionFrom(Formatter f, Filter filter, Formatter name) {
        return "Formatted using " + getClassId(f)
                + ", filtered with " + (filter == null ? "no filter"
                : filter.getClass().getName()) + ", and named by "
                + getClassId(name) + '.';
    }

    /**
     * Gets a class name represents the behavior of the formatter.
     * The class name may not be assignable to a Formatter.
     *
     * @param f the formatter.
     * @return a class name that represents the given formatter.
     * @throws NullPointerException if the parameter is null.
     * @since JavaMail 1.4.5
     */
    private String getClassId(final Formatter f) {
        if (f instanceof TailNameFormatter) {
            return String.class.getName(); //Literal string.
        } else {
            return f.getClass().getName();
        }
    }

    /**
     * Ensure that a formatter creates a valid string for a part name.
     *
     * @param f the formatter.
     * @return the to string value or the class name.
     */
    private String toString(final Formatter f) {
        //Should never be null but, guard against formatter bugs.
        final String name = f.toString();
        if (!isEmpty(name)) {
            return name;
        } else {
            return getClassId(f);
        }
    }

    /**
     * Constructs a file name from a formatter.  This method is called often
     * but, rarely does any work.
     *
     * @param part  to append to.
     * @param chunk non null string to append.
     */
    private void appendFileName(final Part part, final String chunk) {
        if (chunk != null) {
            if (chunk.length() > 0) {
                appendFileName0(part, chunk);
            }
        } else {
            reportNullError(ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * It is assumed that file names are short and that in most cases
     * getTail will be the only method that will produce a result.
     *
     * @param part  to append to.
     * @param chunk non null string to append.
     */
    private void appendFileName0(final Part part, String chunk) {
        try {
            //Remove all control character groups.
            chunk = chunk.replaceAll("[\\x00-\\x1F\\x7F]+", "");
            final String old = part.getFileName();
            part.setFileName(old != null ? old.concat(chunk) : chunk);
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Constructs a subject line from a formatter.
     *
     * @param msg   to append to.
     * @param chunk non null string to append.
     */
    private void appendSubject(final Message msg, final String chunk) {
        if (chunk != null) {
            if (chunk.length() > 0) {
                appendSubject0(msg, chunk);
            }
        } else {
            reportNullError(ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * It is assumed that subject lines are short and that in most cases
     * getTail will be the only method that will produce a result.
     *
     * @param msg   to append to.
     * @param chunk non null string to append.
     */
    private void appendSubject0(final Message msg, String chunk) {
        try {
            //Remove all control character groups.
            chunk = chunk.replaceAll("[\\x00-\\x1F\\x7F]+", "");
            final String charset = getEncodingName();
            final String old = msg.getSubject();
            assert msg instanceof MimeMessage : msg;
            ((MimeMessage) msg).setSubject(old != null ? old.concat(chunk)
                    : chunk, MimeUtility.mimeCharset(charset));
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Gets the locale for the given log record from the resource bundle.
     * If the resource bundle is using the root locale then the default locale
     * is returned.
     *
     * @param r the log record.
     * @return null if not localized otherwise, the locale of the record.
     * @since JavaMail 1.4.5
     */
    private Locale localeFor(final LogRecord r) {
        Locale l;
        final ResourceBundle rb = r.getResourceBundle();
        if (rb != null) {
            l = rb.getLocale();
            if (l == null || isEmpty(l.getLanguage())) {
                //The language of the fallback bundle (root) is unknown.
                //1. Use default locale.  Should only be wrong if the app is
                //   used with a langauge that was unintended. (unlikely)
                //2. Mark it as not localized (force null, info loss).
                //3. Use the bundle name (encoded) as an experimental language.
                l = Locale.getDefault();
            }
        } else {
            l = null;
        }
        return l;
    }

    /**
     * Appends the content language to the given mime part.
     * The language tag is only appended if the given language has not been
     * specified.  This method is only used when we have LogRecords that are
     * localized with an assigned resource bundle.
     *
     * @param p the mime part.
     * @param l the locale to append.
     * @throws NullPointerException if any argument is null.
     * @since JavaMail 1.4.5
     */
    private void appendContentLang(final MimePart p, final Locale l) {
        try {
            String lang = LogManagerProperties.toLanguageTag(l);
            if (lang.length() != 0) {
                String header = p.getHeader("Content-Language", null);
                if (isEmpty(header)) {
                    p.setHeader("Content-Language", lang);
                } else if (!header.equalsIgnoreCase(lang)) {
                    lang = ",".concat(lang);
                    int idx = 0;
                    while ((idx = header.indexOf(lang, idx)) > -1) {
                        idx += lang.length();
                        if (idx == header.length()
                                || header.charAt(idx) == ',') {
                            break;
                        }
                    }

                    if (idx < 0) {
                        int len = header.lastIndexOf("\r\n\t");
                        if (len < 0) { //If not folded.
                            len = (18 + 2) + header.length();
                        } else {
                            len = (header.length() - len) + 8;
                        }

                        //Perform folding of header if needed.
                        if ((len + lang.length()) > 76) {
                            header = header.concat("\r\n\t".concat(lang));
                        } else {
                            header = header.concat(lang);
                        }
                        p.setHeader("Content-Language", header);
                    }
                }
            }
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Sets the accept language to the default locale of the JVM.
     * If the locale is the root locale the header is not added.
     *
     * @param p the part to set.
     * @since JavaMail 1.4.5
     */
    private void setAcceptLang(final Part p) {
        try {
            final String lang = LogManagerProperties
                    .toLanguageTag(Locale.getDefault());
            if (lang.length() != 0) {
                p.setHeader("Accept-Language", lang);
            }
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Used when a log record was loggable prior to being inserted
     * into the buffer but at the time of formatting was no longer loggable.
     * Filters were changed after publish but prior to a push or a bug in the
     * body filter or one of the attachment filters.
     *
     * @param record that was not formatted.
     * @since JavaMail 1.4.5
     */
    private void reportFilterError(final LogRecord record) {
        assert Thread.holdsLock(this);
        final Formatter f = createSimpleFormatter();
        final String msg = "Log record " + record.getSequenceNumber()
                + " was filtered from all message parts.  "
                + head(f) + format(f, record) + tail(f, "");
        final String txt = getFilter() + ", "
                + Arrays.asList(readOnlyAttachmentFilters());
        reportError(msg, new IllegalArgumentException(txt),
                ErrorManager.FORMAT_FAILURE);
    }

    /**
     * Reports symmetric contract violations an equals implementation.
     *
     * @param o     the test object must be non null.
     * @param found the possible intern, must be non null.
     * @throws NullPointerException if any argument is null.
     * @since JavaMail 1.5.0
     */
    private void reportNonSymmetric(final Object o, final Object found) {
        reportError("Non symmetric equals implementation."
                , new IllegalArgumentException(o.getClass().getName()
                        + " is not equal to " + found.getClass().getName())
                , ErrorManager.OPEN_FAILURE);
    }

    /**
     * Reports equals implementations that do not discriminate between objects
     * of different types or subclass types.
     *
     * @param o     the test object must be non null.
     * @param found the possible intern, must be non null.
     * @throws NullPointerException if any argument is null.
     * @since JavaMail 1.5.0
     */
    private void reportNonDiscriminating(final Object o, final Object found) {
        reportError("Non discriminating equals implementation."
                , new IllegalArgumentException(o.getClass().getName()
                        + " should not be equal to " + found.getClass().getName())
                , ErrorManager.OPEN_FAILURE);
    }

    /**
     * Used to outline the bytes to report a null pointer exception.
     * See BUD ID 6533165.
     *
     * @param code the ErrorManager code.
     */
    private void reportNullError(final int code) {
        reportError("null", new NullPointerException(), code);
    }

    /**
     * Creates the head or reports a formatting error.
     *
     * @param f the formatter.
     * @return the head string or an empty string.
     */
    private String head(final Formatter f) {
        try {
            return f.getHead(this);
        } catch (final RuntimeException RE) {
            reportError(RE.getMessage(), RE, ErrorManager.FORMAT_FAILURE);
            return "";
        }
    }

    /**
     * Creates the formatted log record or reports a formatting error.
     *
     * @param f the formatter.
     * @param r the log record.
     * @return the formatted string or an empty string.
     */
    private String format(final Formatter f, final LogRecord r) {
        try {
            return f.format(r);
        } catch (final RuntimeException RE) {
            reportError(RE.getMessage(), RE, ErrorManager.FORMAT_FAILURE);
            return "";
        }
    }

    /**
     * Creates the tail or reports a formatting error.
     *
     * @param f   the formatter.
     * @param def the default string to use when there is an error.
     * @return the tail string or the given default string.
     */
    private String tail(final Formatter f, final String def) {
        try {
            return f.getTail(this);
        } catch (final RuntimeException RE) {
            reportError(RE.getMessage(), RE, ErrorManager.FORMAT_FAILURE);
            return def;
        }
    }

    /**
     * Sets the x-mailer header.
     *
     * @param msg the target message.
     */
    private void setMailer(final Message msg) {
        try {
            final Class<?> mail = MailHandler.class;
            final Class<?> k = getClass();
            String value;
            if (k == mail) {
                value = mail.getName();
            } else {
                try {
                    value = MimeUtility.encodeText(k.getName());
                } catch (final UnsupportedEncodingException E) {
                    reportError(E.getMessage(), E, ErrorManager.FORMAT_FAILURE);
                    value = k.getName().replaceAll("[^\\x00-\\x7F]", "\uu001A");
                }
                value = MimeUtility.fold(10, mail.getName() + " using the "
                        + value + " extension.");
            }
            msg.setHeader("X-Mailer", value);
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Sets the priority and importance headers.
     *
     * @param msg the target message.
     */
    private void setPriority(final Message msg) {
        try {
            msg.setHeader("Importance", "High");
            msg.setHeader("Priority", "urgent");
            msg.setHeader("X-Priority", "2"); //High
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Used to signal that body parts are missing from a message.  Also used
     * when LogRecords were passed to an attachment formatter but the formatter
     * produced no output, which is allowed.  Used during a verify because all
     * parts are omitted, none of the content formatters are used.  This is
     * not used when a filter prevents LogRecords from being formatted.
     * This header is defined in RFC 2156 and RFC 4021.
     *
     * @param msg the message.
     * @since JavaMail 1.4.5
     */
    private void setIncompleteCopy(final Message msg) {
        try {
            msg.setHeader("Incomplete-Copy", "");
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Signals that this message was generated by automatic process.
     * This header is defined in RFC 3834 section 5.
     *
     * @param msg the message.
     * @since JavaMail 1.4.6
     */
    private void setAutoSubmitted(final Message msg) {
        if (allowRestrictedHeaders()) {
            try { //RFC 3834 (5.2)
                msg.setHeader("auto-submitted", "auto-generated");
            } catch (final MessagingException ME) {
                reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
            }
        }
    }

    /**
     * Sets from address header.
     *
     * @param msg the target message.
     */
    private void setFrom(final Message msg) {
        final String from = getSession(msg).getProperty("mail.from");
        if (from != null) {
            try {
                final Address[] address = InternetAddress.parse(from, false);
                if (address.length > 0) {
                    if (address.length == 1) {
                        msg.setFrom(address[0]);
                    } else { //Greater than 1 address.
                        msg.addFrom(address);
                    }
                }
                //Can't place an else statement here because the 'from' is
                //not null which causes the local address computation
                //to fail.  Assume the user wants to omit the from address
                //header.
            } catch (final MessagingException ME) {
                reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
                setDefaultFrom(msg);
            }
        } else {
            setDefaultFrom(msg);
        }
    }

    /**
     * Sets the from header to the local address.
     *
     * @param msg the target message.
     */
    private void setDefaultFrom(final Message msg) {
        try {
            msg.setFrom();
        } catch (final MessagingException ME) {
            reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Computes the default to-address if none was specified.  This can
     * fail if the local address can't be computed.
     *
     * @param msg  the message
     * @param type the recipient type.
     * @since JavaMail 1.5.0
     */
    private void setDefaultRecipient(final Message msg,
                                     final Message.RecipientType type) {
        try {
            Address a = InternetAddress.getLocalAddress(getSession(msg));
            if (a != null) {
                msg.setRecipient(type, a);
            } else {
                final MimeMessage m = new MimeMessage(getSession(msg));
                m.setFrom(); //Should throw an exception with a cause.
                Address[] from = m.getFrom();
                if (from.length > 0) {
                    msg.setRecipients(type, from);
                } else {
                    throw new MessagingException("No local address.");
                }
            }
        } catch (MessagingException | RuntimeException ME) {
            reportError("Unable to compute a default recipient.",
                    ME, ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Sets reply-to address header.
     *
     * @param msg the target message.
     */
    private void setReplyTo(final Message msg) {
        final String reply = getSession(msg).getProperty("mail.reply.to");
        if (!isEmpty(reply)) {
            try {
                final Address[] address = InternetAddress.parse(reply, false);
                if (address.length > 0) {
                    msg.setReplyTo(address);
                }
            } catch (final MessagingException ME) {
                reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
            }
        }
    }

    /**
     * Sets sender address header.
     *
     * @param msg the target message.
     */
    private void setSender(final Message msg) {
        assert msg instanceof MimeMessage : msg;
        final String sender = getSession(msg).getProperty("mail.sender");
        if (!isEmpty(sender)) {
            try {
                final InternetAddress[] address =
                        InternetAddress.parse(sender, false);
                if (address.length > 0) {
                    ((MimeMessage) msg).setSender(address[0]);
                    if (address.length > 1) {
                        reportError("Ignoring other senders.",
                                tooManyAddresses(address, 1),
                                ErrorManager.FORMAT_FAILURE);
                    }
                }
            } catch (final MessagingException ME) {
                reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
            }
        }
    }

    /**
     * A common factory used to create the too many addresses exception.
     *
     * @param address the addresses, never null.
     * @param offset  the starting address to display.
     * @return the too many addresses exception.
     */
    private AddressException tooManyAddresses(Address[] address, int offset) {
        Object l = Arrays.asList(address).subList(offset, address.length);
        return new AddressException(l.toString());
    }

    /**
     * Sets the recipient for the given message.
     *
     * @param msg  the message.
     * @param key  the key to search in the session.
     * @param type the recipient type.
     * @return true if the key was contained in the session.
     */
    private boolean setRecipient(final Message msg,
                                 final String key, final Message.RecipientType type) {
        boolean containsKey;
        final String value = getSession(msg).getProperty(key);
        containsKey = value != null;
        if (!isEmpty(value)) {
            try {
                final Address[] address = InternetAddress.parse(value, false);
                if (address.length > 0) {
                    msg.setRecipients(type, address);
                }
            } catch (final MessagingException ME) {
                reportError(ME.getMessage(), ME, ErrorManager.FORMAT_FAILURE);
            }
        }
        return containsKey;
    }

    /**
     * Converts an email message to a raw string.  This raw string
     * is passed to the error manager to allow custom error managers
     * to recreate the original MimeMessage object.
     *
     * @param msg a Message object.
     * @return the raw string or null if msg was null.
     * @throws MessagingException if there was a problem with the message.
     * @throws IOException        if there was a problem.
     */
    private String toRawString(final Message msg) throws MessagingException, IOException {
        if (msg != null) {
            Object ccl = getAndSetContextClassLoader(MAILHANDLER_LOADER);
            try {  //JDK-8025251
                int nbytes = Math.max(msg.getSize() + MIN_HEADER_SIZE, MIN_HEADER_SIZE);
                ByteArrayOutputStream out = new ByteArrayOutputStream(nbytes);
                msg.writeTo(out);  //Headers can be UTF-8 or US-ASCII.
                return out.toString("UTF-8");
            } finally {
                getAndSetContextClassLoader(ccl);
            }
        } else { //Must match this.reportError behavior, see push method.
            return null; //Null is the safe choice.
        }
    }

    /**
     * Converts a throwable to a message string.
     *
     * @param t any throwable or null.
     * @return the throwable with a stack trace or the literal null.
     */
    private String toMsgString(final Throwable t) {
        if (t == null) {
            return "null";
        }

        final String charset = getEncodingName();
        try {
            final ByteArrayOutputStream out =
                    new ByteArrayOutputStream(MIN_HEADER_SIZE);

            //Create an output stream writer so streams are not double buffered.
            try (OutputStreamWriter ows = new OutputStreamWriter(out, charset);
                 PrintWriter pw = new PrintWriter(ows)) {
                pw.println(t.getMessage());
                t.printStackTrace(pw);
                pw.flush();
            } //Close OSW before generating string. JDK-6995537
            return out.toString(charset);
        } catch (final Exception badMimeCharset) {
            return t.toString() + ' ' + badMimeCharset.toString();
        }
    }

    /**
     * Replaces the current context class loader with our class loader.
     *
     * @param ccl null for boot class loader, a class loader, a class used to
     *            get the class loader, or a source object to get the class loader.
     * @return null for the boot class loader, a class loader, or a marker
     * object to signal that no modification was required.
     * @since JavaMail 1.5.3
     */
    private Object getAndSetContextClassLoader(final Object ccl) {
        if (ccl != GetAndSetContext.NOT_MODIFIED) {
            try {
                final PrivilegedAction<?> pa;
                if (ccl instanceof PrivilegedAction) {
                    pa = (PrivilegedAction<?>) ccl;
                } else {
                    pa = new GetAndSetContext(ccl);
                }
                return LogManagerProperties.runOrDoPrivileged(pa);
            } catch (final SecurityException ignore) {
            }
        }
        return GetAndSetContext.NOT_MODIFIED;
    }

    /**
     * A factory used to create a common attachment mismatch type.
     *
     * @param msg the exception message.
     * @return a RuntimeException to represent the type of error.
     */
    private static RuntimeException attachmentMismatch(final String msg) {
        return new IndexOutOfBoundsException(msg);
    }

    /**
     * Try to attach a suppressed exception to a MessagingException in any order
     * that is possible.
     *
     * @param required the exception expected to see as a reported failure.
     * @param optional the suppressed exception.
     * @return either the required or the optional exception.
     */
    private static MessagingException attach(
            MessagingException required, Exception optional) {
        if (optional != null && !required.setNextException(optional)) {
            if (optional instanceof MessagingException) {
                final MessagingException head = (MessagingException) optional;
                if (head.setNextException(required)) {
                    return head;
                }
            }

            if (optional != required) {
                required.addSuppressed(optional);
            }
        }
        return required;
    }

    /**
     * Gets the local host from the given service object.
     *
     * @param s the service to check.
     * @return the local host or null.
     * @since JavaMail 1.5.3
     */
    private String getLocalHost(final Service s) {
        try {
            return LogManagerProperties.getLocalHost(s);
        } catch (SecurityException | NoSuchMethodException
                 | LinkageError ignore) {
        } catch (final Exception ex) {
            reportError(s.toString(), ex, ErrorManager.OPEN_FAILURE);
        }
        return null;
    }

    /**
     * Google App Engine doesn't support Message.getSession.
     *
     * @param msg the message.
     * @return the session from the given message.
     * @throws NullPointerException if the given message is null.
     * @since JavaMail 1.5.3
     */
    private Session getSession(final Message msg) {
        Objects.requireNonNull(msg);
        return new MessageContext(msg).getSession();
    }

    /**
     * Determines if restricted headers are allowed in the current environment.
     *
     * @return true if restricted headers are allowed.
     * @since JavaMail 1.5.3
     */
    private boolean allowRestrictedHeaders() {
        //GAE will prevent delivery of email with forbidden headers.
        //Assume the environment is GAE if access to the LogManager is
        //forbidden.
        return LogManagerProperties.hasLogManager();
    }

    /**
     * Used for storing a password from the LogManager or literal string.
     *
     * @since JavaMail 1.4.6
     */
    private static final class DefaultAuthenticator extends Authenticator {

        /**
         * Creates an Authenticator for the given password.  This method is used
         * so class verification of assignments in MailHandler doesn't require
         * loading this class which otherwise can occur when using the
         * constructor.  Default access to avoid generating extra class files.
         *
         * @param pass the password.
         * @return an Authenticator for the password.
         * @since JavaMail 1.5.6
         */
        static Authenticator of(final String pass) {
            return new DefaultAuthenticator(pass);
        }

        /**
         * The password to use.
         */
        private final String pass;

        /**
         * Use the factory method instead of this constructor.
         *
         * @param pass the password.
         */
        private DefaultAuthenticator(final String pass) {
            assert pass != null;
            this.pass = pass;
        }

        @Override
        protected final PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(getDefaultUserName(), pass);
        }
    }

    /**
     * Performs a get and set of the context class loader with privileges
     * enabled.
     *
     * @since JavaMail 1.4.6
     */
    private static final class GetAndSetContext implements PrivilegedAction<Object> {
        /**
         * A marker object used to signal that the class loader was not
         * modified.
         */
        public static final Object NOT_MODIFIED = GetAndSetContext.class;
        /**
         * The source containing the class loader.
         */
        private final Object source;

        /**
         * Create the action.
         *
         * @param source null for boot class loader, a class loader, a class
         *               used to get the class loader, or a source object to get the class
         *               loader. Default access to avoid generating extra class files.
         */
        GetAndSetContext(final Object source) {
            this.source = source;
        }

        /**
         * Gets the class loader from the source and sets the CCL only if
         * the source and CCL are not the same.
         *
         * @return the replaced context class loader which can be null or
         * NOT_MODIFIED to indicate that nothing was modified.
         */
        @SuppressWarnings("override") //JDK-6954234
        public final Object run() {
            final Thread current = Thread.currentThread();
            final ClassLoader ccl = current.getContextClassLoader();
            final ClassLoader loader;
            if (source == null) {
                loader = null; //boot class loader
            } else if (source instanceof ClassLoader) {
                loader = (ClassLoader) source;
            } else if (source instanceof Class) {
                loader = ((Class<?>) source).getClassLoader();
            } else if (source instanceof Thread) {
                loader = ((Thread) source).getContextClassLoader();
            } else {
                assert !(source instanceof Class) : source;
                loader = source.getClass().getClassLoader();
            }

            if (ccl != loader) {
                current.setContextClassLoader(loader);
                return ccl;
            } else {
                return NOT_MODIFIED;
            }
        }
    }

    /**
     * Used for naming attachment file names and the main subject line.
     */
    private static final class TailNameFormatter extends Formatter {

        /**
         * Creates or gets a formatter from the given name.  This method is used
         * so class verification of assignments in MailHandler doesn't require
         * loading this class which otherwise can occur when using the
         * constructor.  Default access to avoid generating extra class files.
         *
         * @param name any not null string.
         * @return a formatter for that string.
         * @since JavaMail 1.5.6
         */
        static Formatter of(final String name) {
            return new TailNameFormatter(name);
        }

        /**
         * The value used as the output.
         */
        private final String name;

        /**
         * Use the factory method instead of this constructor.
         *
         * @param name any not null string.
         */
        private TailNameFormatter(final String name) {
            assert name != null;
            this.name = name;
        }

        @Override
        public final String format(LogRecord record) {
            return "";
        }

        @Override
        public final String getTail(Handler h) {
            return name;
        }

        /**
         * Equals method.
         *
         * @param o the other object.
         * @return true if equal
         * @since JavaMail 1.4.4
         */
        @Override
        public final boolean equals(Object o) {
            if (o instanceof TailNameFormatter) {
                return name.equals(((TailNameFormatter) o).name);
            }
            return false;
        }

        /**
         * Hash code method.
         *
         * @return the hash code.
         * @since JavaMail 1.4.4
         */
        @Override
        public final int hashCode() {
            return getClass().hashCode() + name.hashCode();
        }

        @Override
        public final String toString() {
            return name;
        }
    }
}
