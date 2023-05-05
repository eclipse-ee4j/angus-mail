/*
 * Copyright (c) 2021, 2023 Oracle and/or its affiliates. All rights reserved.
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

/**
 * An SMTP protocol provider for the Jakarta Mail API
 * that provides access to an SMTP server.
 * Refer to <A HREF="http://www.ietf.org/rfc/rfc821.txt" TARGET="_top">RFC 821</A>
 * for more information.
 * <P>
 * When sending a message, detailed information on each address that
 * fails is available in an
 * {@link org.eclipse.angus.mail.smtp.SMTPAddressFailedException SMTPAddressFailedException}
 * chained off the top level
 * {@link jakarta.mail.SendFailedException SendFailedException}
 * that is thrown.
 * In addition, if the <code>mail.smtp.reportsuccess</code> property
 * is set, an
 * {@link org.eclipse.angus.mail.smtp.SMTPAddressSucceededException
 * SMTPAddressSucceededException}
 * will be included in the list for each address that is successful.
 * Note that this will cause a top level
 * {@link jakarta.mail.SendFailedException SendFailedException}
 * to be thrown even though the send was successful.
 * </P>
 * <P>
 * The SMTP provider also supports ESMTP
 * (<A HREF="http://www.ietf.org/rfc/rfc1651.txt" TARGET="_top">RFC 1651</A>).
 * It can optionally use SMTP Authentication
 * (<A HREF="http://www.ietf.org/rfc/rfc2554.txt" TARGET="_top">RFC 2554</A>)
 * using the LOGIN, PLAIN, DIGEST-MD5, and NTLM mechanisms
 * (<A HREF="http://www.ietf.org/rfc/rfc4616.txt" TARGET="_top">RFC 4616</A>
 * and <A HREF="http://www.ietf.org/rfc/rfc2831.txt" TARGET="_top">RFC 2831</A>).
 * </P>
 * <P>
 * To use SMTP authentication you'll need to set the <code>mail.smtp.auth</code>
 * property (see below) or provide the SMTP Transport
 * with a username and password when connecting to the SMTP server.  You
 * can do this using one of the following approaches:
 * </P>
 * <UL>
 * <LI>
 * <P>
 * Provide an Authenticator object when creating your mail Session
 * and provide the username and password information during the
 * Authenticator callback.
 * </P>
 * <P>
 * Note that the <code>mail.smtp.user</code> property can be set to provide a
 * default username for the callback, but the password will still need to be
 * supplied explicitly.
 * </P>
 * <P>
 * This approach allows you to use the static Transport <code>send</code> method
 * to send messages.
 * </P>
 * </LI>
 * <LI>
 * <P>
 * Call the Transport <code>connect</code> method explicitly with username and
 * password arguments.
 * </P>
 * <P>
 * This approach requires you to explicitly manage a Transport object
 * and use the Transport <code>sendMessage</code> method to send the message.
 * The transport.java demo program demonstrates how to manage a Transport
 * object.  The following is roughly equivalent to the static
 * Transport <code>send</code> method, but supplies the needed username and
 * password:
 * </P>
 * <BLOCKQUOTE><PRE>
 * Transport tr = session.getTransport("smtp");
 * tr.connect(smtphost, username, password);
 * msg.saveChanges();	// don't forget this
 * tr.sendMessage(msg, msg.getAllRecipients());
 * tr.close();
 * </PRE></BLOCKQUOTE>
 * </LI>
 * </UL>
 * <P>
 * When using DIGEST-MD5 authentication,
 * you'll also need to supply an appropriate realm;
 * your mail server administrator can supply this information.
 * You can set this using the <code>mail.smtp.sasl.realm</code> property,
 * or the <code>setSASLRealm</code> method on <code>SMTPTransport</code>.
 * </P>
 * <P>
 * The SMTP protocol provider can use SASL
 * (<A HREF="http://www.ietf.org/rfc/rfc2222.txt" TARGET="_top">RFC 2222</A>)
 * authentication mechanisms on systems that support the
 * <CODE>javax.security.sasl</CODE> APIs, such as J2SE 5.0.
 * In addition to the SASL mechanisms that are built into
 * the SASL implementation, users can also provide additional
 * SASL mechanisms of their own design to support custom authentication
 * schemes.  See the
 * <A HREF="http://java.sun.com/j2se/1.5.0/docs/guide/security/sasl/sasl-refguide.html" TARGET="_top">
 * Java SASL API Programming and Deployment Guide</A> for details.
 * Note that the current implementation doesn't support SASL mechanisms
 * that provide their own integrity or confidentiality layer.
 * </P>
 * <P>
 * Support for OAuth 2.0 authentication via the
 * <A HREF="https://developers.google.com/gmail/xoauth2_protocol" TARGET="_top">
 * XOAUTH2 authentication mechanism</A> is provided either through the SASL
 * support described above or as a built-in authentication mechanism in the
 * SMTP provider.
 * The OAuth 2.0 Access Token should be passed as the password for this mechanism.
 * See <A HREF="https://eclipse-ee4j.github.io/mail/OAuth2" TARGET="_top">
 * OAuth2 Support</A> for details.
 * </P>
 * <P>
 * SMTP can also optionally request Delivery Status Notifications
 * (<A HREF="http://www.ietf.org/rfc/rfc1891.txt" TARGET="_top">RFC 1891</A>).
 * The delivery status will typically be reported using
 * a "multipart/report"
 * (<A HREF="http://www.ietf.org/rfc/rfc1892.txt" TARGET="_top">RFC 1892</A>)
 * message type with a "message/delivery-status"
 * (<A HREF="http://www.ietf.org/rfc/rfc1894.txt" TARGET="_top">RFC 1894</A>)
 * part.
 * You can use the classes in the <code>org.eclipse.angus.mail.dsn</code> package to
 * handle these MIME types.
 * Note that you'll need to include <code>dsn.jar</code> in your CLASSPATH
 * as this support is not included in <code>mail.jar</code>.
 * </P>
 * <P>
 * See below for the properties to enable these features.
 * </P>
 * <P>
 * Note also that <strong>THERE IS NOT SUFFICIENT DOCUMENTATION HERE TO USE THESE
 * FEATURES!!!</strong>  You will need to read the appropriate RFCs mentioned above
 * to understand what these features do and how to use them.  Don't just
 * start setting properties and then complain to us when it doesn't work
 * like you expect it to work.  <strong>READ THE RFCs FIRST!!!</strong>
 * </P>
 * <P>
 * The SMTP protocol provider supports the CHUNKING extension defined in
 * <A HREF="http://www.ietf.org/rfc/rfc3030.txt" TARGET="_top">RFC 3030</A>.
 * Set the <code>mail.smtp.chunksize</code> property to the desired chunk
 * size in bytes.
 * If the server supports the CHUNKING extension, the BDAT command will be
 * used to send the message in chunksize pieces.  Note that no pipelining is
 * done so this will be slower than sending the message in one piece.
 * Note also that the BINARYMIME extension described in RFC 3030 is NOT supported.
 * </P>
 * <A ID="properties"><STRONG>Properties</STRONG></A>
 * <P>
 * The SMTP protocol provider supports the following properties,
 * which may be set in the Jakarta Mail <code>Session</code> object.
 * The properties are always set as strings; the Type column describes
 * how the string is interpreted.  For example, use
 * </P>
 * <PRE>
 * props.put("mail.smtp.port", "888");
 * </PRE>
 * <P>
 * to set the <CODE>mail.smtp.port</CODE> property, which is of type int.
 * </P>
 * <P>
 * Note that if you're using the "smtps" protocol to access SMTP over SSL,
 * all the properties would be named "mail.smtps.*".
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>SMTP properties</CAPTION>
 * <TR>
 * <TH>Name</TH>
 * <TH>Type</TH>
 * <TH>Description</TH>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.user">mail.smtp.user</A></TD>
 * <TD>String</TD>
 * <TD>Default user name for SMTP.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.host">mail.smtp.host</A></TD>
 * <TD>String</TD>
 * <TD>The SMTP server to connect to.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.port">mail.smtp.port</A></TD>
 * <TD>int</TD>
 * <TD>The SMTP server port to connect to, if the connect() method doesn't
 * explicitly specify one. Defaults to 25.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.connectiontimeout">mail.smtp.connectiontimeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket connection timeout value in milliseconds.
 * This timeout is implemented by java.net.Socket.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.timeout">mail.smtp.timeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket read timeout value in milliseconds.
 * This timeout is implemented by java.net.Socket.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.writetimeout">mail.smtp.writetimeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket write timeout value in milliseconds.
 * This timeout is implemented by using a
 * {@link java.util.concurrent.ScheduledExecutorService} per connection
 * that schedules a thread to close the socket if the timeout expires.
 * Thus, the overhead of using this timeout is one thread per connection.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.executor.writetimeout">mail.smtp.executor.writetimeout</A></TD>
 * <TD>java.util.concurrent.ScheduledExecutorService</TD>
 * <TD> Provides specific ScheduledExecutorService for mail.smtp.writetimeout option.
 * The value of mail.smtp.writetimeout shouldn't be a null.
 * For provided executor pool it is highly recommended to have set up in true
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor#setRemoveOnCancelPolicy(boolean)}.
 * Without it, write methods will create garbage that would only be reclaimed after the timeout.
 * Be careful with calling {@link java.util.concurrent.ScheduledThreadPoolExecutor#shutdownNow()} in your executor,
 * it can kill the running tasks. It would be ok to use shutdownNow only when JavaMail sockets are closed.
 * This would be all service subclasses ({@link jakarta.mail.Store}/{@link jakarta.mail.Transport})
 * Invoking run {@link java.lang.Runnable#run()} on the returned {@link java.util.concurrent.Future} objects
 * would force close the open connections.
 * Instead of shutdownNow you can use {@link java.util.concurrent.ScheduledThreadPoolExecutor#shutdown()} ()}
 * and
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor#awaitTermination(long, java.util.concurrent.TimeUnit)} ()}.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.from">mail.smtp.from</A></TD>
 * <TD>String</TD>
 * <TD>
 * Email address to use for SMTP MAIL command.  This sets the envelope
 * return address.  Defaults to msg.getFrom() or
 * InternetAddress.getLocalAddress().  NOTE: mail.smtp.user was previously
 * used for this.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.localhost">mail.smtp.localhost</A></TD>
 * <TD>String</TD>
 * <TD>
 * Local host name used in the SMTP HELO or EHLO command.
 * Defaults to <code>InetAddress.getLocalHost().getHostName()</code>.
 * Should not normally need to
 * be set if your JDK and your name service are configured properly.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.localaddress">mail.smtp.localaddress</A></TD>
 * <TD>String</TD>
 * <TD>
 * Local address (host name) to bind to when creating the SMTP socket.
 * Defaults to the address picked by the Socket class.
 * Should not normally need to be set, but useful with multi-homed hosts
 * where it's important to pick a particular local address to bind to.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.localport">mail.smtp.localport</A></TD>
 * <TD>int</TD>
 * <TD>
 * Local port number to bind to when creating the SMTP socket.
 * Defaults to the port number picked by the Socket class.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ehlo">mail.smtp.ehlo</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If false, do not attempt to sign on with the EHLO command.  Defaults to
 * true.  Normally failure of the EHLO command will fallback to the HELO
 * command; this property exists only for servers that don't fail EHLO
 * properly or don't implement EHLO properly.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth">mail.smtp.auth</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, attempt to authenticate the user using the AUTH command.
 * Defaults to false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.mechanisms">mail.smtp.auth.mechanisms</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, lists the authentication mechanisms to consider, and the order
 * in which to consider them.  Only mechanisms supported by the server and
 * supported by the current implementation will be used.
 * The default is <code>"LOGIN PLAIN DIGEST-MD5 NTLM"</code>, which includes all
 * the authentication mechanisms supported by the current implementation
 * except XOAUTH2.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.login.disable">mail.smtp.auth.login.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH LOGIN</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.plain.disable">mail.smtp.auth.plain.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH PLAIN</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.digest-md5.disable">mail.smtp.auth.digest-md5.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH DIGEST-MD5</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.ntlm.disable">mail.smtp.auth.ntlm.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH NTLM</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.ntlm.domain">mail.smtp.auth.ntlm.domain</A></TD>
 * <TD>String</TD>
 * <TD>
 * The NTLM authentication domain.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.ntlm.flags">mail.smtp.auth.ntlm.flags</A></TD>
 * <TD>int</TD>
 * <TD>
 * NTLM protocol-specific flags.
 * See <A HREF="http://curl.haxx.se/rfc/ntlm.html#theNtlmFlags" TARGET="_top">
 * http://curl.haxx.se/rfc/ntlm.html#theNtlmFlags</A> for details.
 * </TD>
 * </TR>
 *
 * <!--
 * <TR>
 * <TD><A ID="mail.smtp.auth.ntlm.unicode">mail.smtp.auth.ntlm.unicode</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * Set this to "true" if the username or password may use
 * Unicode UTF-8 encoded characters.  Default is "true".
 * Currently has no effect.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.ntlm.lmcompat">mail.smtp.auth.ntlm.lmcompat</A></TD>
 * <TD>int</TD>
 * <TD>
 * Sets the LM compatibility level, as described here:
 * <A HREF="http://curl.haxx.se/rfc/ntlm.html#ntlmVersion2" TARGET="_top">
 * http://curl.haxx.se/rfc/ntlm.html#ntlmVersion2</A>
 * Defaults to "3".  Currently not used.
 * </TD>
 * </TR>
 * -->
 *
 * <TR>
 * <TD><A ID="mail.smtp.auth.xoauth2.disable">mail.smtp.auth.xoauth2.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTHENTICATE XOAUTH2</code> command.
 * Because the OAuth 2.0 protocol requires a special access token instead of
 * a password, this mechanism is disabled by default.  Enable it by explicitly
 * setting this property to "false" or by setting the "mail.smtp.auth.mechanisms"
 * property to "XOAUTH2".</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.submitter">mail.smtp.submitter</A></TD>
 * <TD>String</TD>
 * <TD>The submitter to use in the AUTH tag in the MAIL FROM command.
 * Typically used by a mail relay to pass along information about the
 * original submitter of the message.
 * See also the {@link org.eclipse.angus.mail.smtp.SMTPMessage#setSubmitter setSubmitter}
 * method of {@link org.eclipse.angus.mail.smtp.SMTPMessage SMTPMessage}.
 * Mail clients typically do not use this.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.dsn.notify">mail.smtp.dsn.notify</A></TD>
 * <TD>String</TD>
 * <TD>The NOTIFY option to the RCPT command.  Either NEVER, or some
 * combination of SUCCESS, FAILURE, and DELAY (separated by commas).</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.dsn.ret">mail.smtp.dsn.ret</A></TD>
 * <TD>String</TD>
 * <TD>The RET option to the MAIL command.  Either FULL or HDRS.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.allow8bitmime">mail.smtp.allow8bitmime</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, and the server supports the 8BITMIME extension, text
 * parts of messages that use the "quoted-printable" or "base64" encodings
 * are converted to use "8bit" encoding if they follow the RFC2045 rules
 * for 8bit text.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.sendpartial">mail.smtp.sendpartial</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, and a message has some valid and some invalid
 * addresses, send the message anyway, reporting the partial failure with
 * a SendFailedException.  If set to false (the default), the message is
 * not sent to any of the recipients if there is an invalid recipient
 * address.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.sasl.enable">mail.smtp.sasl.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, attempt to use the javax.security.sasl package to
 * choose an authentication mechanism for login.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.sasl.mechanisms">mail.smtp.sasl.mechanisms</A></TD>
 * <TD>String</TD>
 * <TD>
 * A space or comma separated list of SASL mechanism names to try
 * to use.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.sasl.authorizationid">mail.smtp.sasl.authorizationid</A></TD>
 * <TD>String</TD>
 * <TD>
 * The authorization ID to use in the SASL authentication.
 * If not set, the authentication ID (user name) is used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.sasl.realm">mail.smtp.sasl.realm</A></TD>
 * <TD>String</TD>
 * <TD>The realm to use with DIGEST-MD5 authentication.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.sasl.usecanonicalhostname">mail.smtp.sasl.usecanonicalhostname</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, the canonical host name returned by
 * {@link java.net.InetAddress#getCanonicalHostName InetAddress.getCanonicalHostName}
 * is passed to the SASL mechanism, instead of the host name used to connect.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.quitwait">mail.smtp.quitwait</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to false, the QUIT command is sent
 * and the connection is immediately closed.
 * If set to true (the default), causes the transport to wait
 * for the response to the QUIT command.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.quitonsessionreject">mail.smtp.quitonsessionreject</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to false (the default), on session initiation rejection the QUIT
 * command is not sent and the connection is immediately closed.
 * If set to true, causes the transport to send the QUIT command prior to
 * closing the connection.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.reportsuccess">mail.smtp.reportsuccess</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, causes the transport to include an
 * {@link org.eclipse.angus.mail.smtp.SMTPAddressSucceededException
 * SMTPAddressSucceededException}
 * for each address that is successful.
 * Note also that this will cause a
 * {@link jakarta.mail.SendFailedException SendFailedException}
 * to be thrown from the
 * {@link org.eclipse.angus.mail.smtp.SMTPTransport#sendMessage sendMessage}
 * method of
 * {@link org.eclipse.angus.mail.smtp.SMTPTransport SMTPTransport}
 * even if all addresses were correct and the message was sent
 * successfully.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.socketFactory">mail.smtp.socketFactory</A></TD>
 * <TD>SocketFactory</TD>
 * <TD>
 * If set to a class that implements the
 * <code>javax.net.SocketFactory</code> interface, this class
 * will be used to create SMTP sockets.  Note that this is an
 * instance of a class, not a name, and must be set using the
 * <code>put</code> method, not the <code>setProperty</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.socketFactory.class">mail.smtp.socketFactory.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, specifies the name of a class that implements the
 * <code>javax.net.SocketFactory</code> interface.  This class
 * will be used to create SMTP sockets.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.socketFactory.fallback">mail.smtp.socketFactory.fallback</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, failure to create a socket using the specified
 * socket factory class will cause the socket to be created using
 * the <code>java.net.Socket</code> class.
 * Defaults to true.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.socketFactory.port">mail.smtp.socketFactory.port</A></TD>
 * <TD>int</TD>
 * <TD>
 * Specifies the port to connect to when using the specified socket
 * factory.
 * If not set, the default port will be used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.enable">mail.smtp.ssl.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use SSL to connect and use the SSL port by default.
 * Defaults to false for the "smtp" protocol and true for the "smtps" protocol.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.checkserveridentity">mail.smtp.ssl.checkserveridentity</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to false, it does not check the server identity as specified by
 * <A HREF="http://www.ietf.org/rfc/rfc2595.txt" TARGET="_top">RFC 2595</A>.
 * These additional checks based on the content of the server's certificate
 * are intended to prevent man-in-the-middle attacks.
 * Defaults to true.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.trust">mail.smtp.ssl.trust</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, and a socket factory hasn't been specified, enables use of a
 * {@link org.eclipse.angus.mail.util.MailSSLSocketFactory MailSSLSocketFactory}.
 * If set to "*", all hosts are trusted.
 * If set to a whitespace separated list of hosts, those hosts are trusted.
 * Otherwise, trust depends on the certificate the server presents.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.socketFactory">mail.smtp.ssl.socketFactory</A></TD>
 * <TD>SSLSocketFactory</TD>
 * <TD>
 * If set to a class that extends the
 * <code>javax.net.ssl.SSLSocketFactory</code> class, this class
 * will be used to create SMTP SSL sockets.  Note that this is an
 * instance of a class, not a name, and must be set using the
 * <code>put</code> method, not the <code>setProperty</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.socketFactory.class">mail.smtp.ssl.socketFactory.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, specifies the name of a class that extends the
 * <code>javax.net.ssl.SSLSocketFactory</code> class.  This class
 * will be used to create SMTP SSL sockets.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.socketFactory.port">mail.smtp.ssl.socketFactory.port</A></TD>
 * <TD>int</TD>
 * <TD>
 * Specifies the port to connect to when using the specified socket
 * factory.
 * If not set, the default port will be used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.protocols">mail.smtp.ssl.protocols</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the SSL protocols that will be enabled for SSL connections.
 * The property value is a whitespace separated list of tokens acceptable
 * to the <code>javax.net.ssl.SSLSocket.setEnabledProtocols</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.ssl.ciphersuites">mail.smtp.ssl.ciphersuites</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the SSL cipher suites that will be enabled for SSL connections.
 * The property value is a whitespace separated list of tokens acceptable
 * to the <code>javax.net.ssl.SSLSocket.setEnabledCipherSuites</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.starttls.enable">mail.smtp.starttls.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If true, enables the use of the <code>STARTTLS</code> command (if
 * supported by the server) to switch the connection to a TLS-protected
 * connection before issuing any login commands.
 * If the server does not support STARTTLS, the connection continues without
 * the use of TLS; see the
 * <A HREF="#mail.smtp.starttls.required"><code>mail.smtp.starttls.required</code></A>
 * property to fail if STARTTLS isn't supported.
 * Note that an appropriate trust store must configured so that the client
 * will trust the server's certificate.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.starttls.required">mail.smtp.starttls.required</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If true, requires the use of the <code>STARTTLS</code> command.
 * If the server doesn't support the STARTTLS command, or the command
 * fails, the connect method will fail.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.proxy.host">mail.smtp.proxy.host</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the host name of an HTTP web proxy server that will be used for
 * connections to the mail server.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.proxy.port">mail.smtp.proxy.port</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the port number for the HTTP web proxy server.
 * Defaults to port 80.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.proxy.user">mail.smtp.proxy.user</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the user name to use to authenticate with the HTTP web proxy server.
 * By default, no authentication is done.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.proxy.password">mail.smtp.proxy.password</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the password to use to authenticate with the HTTP web proxy server.
 * By default, no authentication is done.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.socks.host">mail.smtp.socks.host</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the host name of a SOCKS5 proxy server that will be used for
 * connections to the mail server.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.socks.port">mail.smtp.socks.port</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the port number for the SOCKS5 proxy server.
 * This should only need to be used if the proxy server is not using
 * the standard port number of 1080.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.mailextension">mail.smtp.mailextension</A></TD>
 * <TD>String</TD>
 * <TD>
 * Extension string to append to the MAIL command.
 * The extension string can be used to specify standard SMTP
 * service extensions as well as vendor-specific extensions.
 * Typically the application should use the
 * {@link org.eclipse.angus.mail.smtp.SMTPTransport SMTPTransport}
 * method {@link org.eclipse.angus.mail.smtp.SMTPTransport#supportsExtension
 * supportsExtension}
 * to verify that the server supports the desired service extension.
 * See <A HREF="http://www.ietf.org/rfc/rfc1869.txt" TARGET="_top">RFC 1869</A>
 * and other RFCs that define specific extensions.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.userset">mail.smtp.userset</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use the RSET command instead of the NOOP command
 * in the {@link jakarta.mail.Transport#isConnected isConnected} method.
 * In some cases sendmail will respond slowly after many NOOP commands;
 * use of RSET avoids this sendmail issue.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.smtp.noop.strict">mail.smtp.noop.strict</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true (the default), insist on a 250 response code from the NOOP
 * command to indicate success.  The NOOP command is used by the
 * {@link jakarta.mail.Transport#isConnected isConnected} method to determine
 * if the connection is still alive.
 * Some older servers return the wrong response code on success, some
 * servers don't implement the NOOP command at all and so always return
 * a failure code.  Set this property to false to handle servers
 * that are broken in this way.
 * Normally, when a server times out a connection, it will send a 421
 * response code, which the client will see as the response to the next
 * command it issues.
 * Some servers send the wrong failure response code when timing out a
 * connection.
 * Do not set this property to false when dealing with servers that are
 * broken in this way.
 * </TD>
 * </TR>
 *
 * </TABLE>
 * <P>
 * In general, applications should not need to use the classes in this
 * package directly.  Instead, they should use the APIs defined by
 * <code>jakarta.mail</code> package (and subpackages).  Applications should
 * never construct instances of <code>SMTPTransport</code> directly.
 * Instead, they should use the
 * <code>Session</code> method <code>getTransport</code> to acquire an
 * appropriate <code>Transport</code> object.
 * </P>
 * <P>
 * In addition to printing debugging output as controlled by the
 * {@link jakarta.mail.Session Session} configuration,
 * the org.eclipse.angus.mail.smtp provider logs the same information using
 * {@link java.util.logging.Logger} as described in the following table:
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>SMTP Loggers</CAPTION>
 * <TR>
 * <TH>Logger Name</TH>
 * <TH>Logging Level</TH>
 * <TH>Purpose</TH>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.smtp</TD>
 * <TD>CONFIG</TD>
 * <TD>Configuration of the SMTPTransport</TD>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.smtp</TD>
 * <TD>FINE</TD>
 * <TD>General debugging output</TD>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.smtp.protocol</TD>
 * <TD>FINEST</TD>
 * <TD>Complete protocol trace</TD>
 * </TR>
 * </TABLE>
 * <P>
 * <strong>WARNING:</strong> The APIs unique to this package should be
 * considered <strong>EXPERIMENTAL</strong>.  They may be changed in the
 * future in ways that are incompatible with applications using the
 * current APIs.
 */
package org.eclipse.angus.mail.smtp;
