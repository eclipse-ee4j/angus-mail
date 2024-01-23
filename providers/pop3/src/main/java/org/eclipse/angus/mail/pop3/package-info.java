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
 * A POP3 protocol provider for the Jakarta Mail API
 * that provides access to a POP3 message store.
 * Refer to <A HREF="https://www.ietf.org/rfc/rfc1939.txt" TARGET="_top">
 * RFC 1939</A>
 * for more information.
 * <P>
 * The POP3 provider provides a Store object that contains a single Folder
 * named "INBOX". Due to the limitations of the POP3 protocol, many of
 * the Jakarta Mail API capabilities like event notification, folder management,
 * flag management, etc. are not allowed.  The corresponding methods throw
 * the MethodNotSupportedException exception; see below for details.
 * </P>
 * <P>
 * Note that Jakarta Mail does <strong>not</strong> include a local store into
 * which messages can be downloaded and stored.  See our
 * <A HREF="https://eclipse-ee4j.github.io/angus-mail/ThirdPartyProducts" TARGET="_top">
 * Third Party Products</A>
 * web page for availability of "mbox" and "MH" local store providers.
 * </P>
 * <P>
 * The POP3 provider is accessed through the Jakarta Mail APIs by using the protocol
 * name "pop3" or a URL of the form "pop3://user:password@host:port/INBOX".
 * </P>
 * <P>
 * POP3 supports only a single folder named "INBOX".
 * </P>
 * <P>
 * POP3 supports <strong>no</strong> permanent flags (see
 * {@link jakarta.mail.Folder#getPermanentFlags Folder.getPermanentFlags()}).
 * In particular, the <code>Flags.Flag.RECENT</code> flag will never be set
 * for POP3
 * messages.  It's up to the application to determine which messages in a
 * POP3 mailbox are "new".  There are several strategies to accomplish
 * this, depending on the needs of the application and the environment:
 * </P>
 * <UL>
 * <LI>
 * A simple approach would be to keep track of the newest
 * message seen by the application.
 * </LI>
 * <LI>
 * An alternative would be to keep track of the UIDs (see below)
 * of all messages that have been seen.
 * </LI>
 * <LI>
 * Another approach is to download <strong>all</strong> messages into a local
 * mailbox, so that all messages in the POP3 mailbox are, by
 * definition, new.
 * </LI>
 * </UL>
 * <P>
 * All approaches will require some permanent storage associated with the client.
 * </P>
 * <P>
 * POP3 does not support the <code>Folder.expunge()</code> method.  To delete and
 * expunge messages, set the <code>Flags.Flag.DELETED</code> flag on the messages
 * and close the folder using the <code>Folder.close(true)</code> method.  You
 * cannot expunge without closing the folder.
 * </P>
 * <P>
 * POP3 does not provide a "received date", so the <code>getReceivedDate</code>
 * method will return null.
 * It may be possible to examine other message headers (e.g., the
 * "Received" headers) to estimate the received date, but these techniques
 * are error-prone at best.
 * </P>
 * <P>
 * The POP3 provider supports the POP3 UIDL command, see
 * {@link org.eclipse.angus.mail.pop3.POP3Folder#getUID POP3Folder.getUID()}.
 * You can use it as follows:
 * </P>
 * <BLOCKQUOTE><PRE>
 * if (folder instanceof org.eclipse.angus.mail.pop3.POP3Folder) {
 * org.eclipse.angus.mail.pop3.POP3Folder pf =
 * (org.eclipse.angus.mail.pop3.POP3Folder)folder;
 * String uid = pf.getUID(msg);
 * if (uid != null)
 * ... // use it
 * }
 * </PRE></BLOCKQUOTE>
 * <P>
 * You can also pre-fetch all the UIDs for all messages like this:
 * </P>
 * <BLOCKQUOTE><PRE>
 * FetchProfile fp = new FetchProfile();
 * fp.add(UIDFolder.FetchProfileItem.UID);
 * folder.fetch(folder.getMessages(), fp);
 * </PRE></BLOCKQUOTE>
 * <P>
 * Then use the technique above to get the UID for each message.  This is
 * similar to the technique used with the UIDFolder interface supported by
 * IMAP, but note that POP3 UIDs are strings, not integers like IMAP
 * UIDs.  See the POP3 spec for details.
 * </P>
 * <P>
 * When the headers of a POP3 message are accessed, the POP3 provider uses
 * the TOP command to fetch all headers, which are then cached.  Use of the
 * TOP command can be disabled with the <CODE>mail.pop3.disabletop</CODE>
 * property, in which case the entire message content is fetched with the
 * RETR command.
 * </P>
 * <P>
 * When the content of a POP3 message is accessed, the POP3 provider uses
 * the RETR command to fetch the entire message.  Normally the message
 * content is cached in memory.  By setting the
 * <CODE>mail.pop3.filecache.enable</CODE> property, the message content
 * will instead be cached in a temporary file.  The file will be removed
 * when the folder is closed.  Caching message content in a file is generally
 * slower, but uses substantially less memory and may be helpful when dealing
 * with very large messages.
 * </P>
 * <P>
 * The {@link org.eclipse.angus.mail.pop3.POP3Message#invalidate POP3Message.invalidate}
 * method can be used to invalidate cached data without closing the folder.
 * Note that if the file cache is being used the data in the file will be
 * forgotten and fetched from the server if it's needed again, and stored again
 * in the file cache.
 * </P>
 * <P>
 * The POP3 CAPA command (defined by
 * <A HREF="https://www.ietf.org/rfc/rfc2449.txt" TARGET="_top">RFC 2449</A>)
 * will be used to determine the capabilities supported by the server.
 * Some servers don't implement the CAPA command, and some servers don't
 * return correct information, so various properties are available to
 * disable use of certain POP3 commands, including CAPA.
 * </P>
 * <P>
 * If the server advertises the PIPELINING capability (defined by
 * <A HREF="https://www.ietf.org/rfc/rfc2449.txt" TARGET="_top">RFC 2449</A>),
 * or the <CODE>mail.pop3.pipelining</CODE> property is set, the POP3
 * provider will send some commands in batches, which can significantly
 * improve performance and memory use.
 * Some servers that don't support the CAPA command or don't advertise
 * PIPELINING may still support pipelining; experimentation may be required.
 * </P>
 * <P>
 * If pipelining is supported and the connection is using
 * SSL, the USER and PASS commands will be sent as a batch.
 * (If SSL is not being used, the PASS command isn't sent
 * until the user is verified to avoid exposing the password
 * if the user name is bad.)
 * </P>
 * <P>
 * If pipelining is supported, when fetching a message with the RETR command,
 * the LIST command will be sent as well, and the result will be used to size
 * the I/O buffer, greatly reducing memory usage when fetching messages.
 * </P>
 * <A ID="properties"><STRONG>Properties</STRONG></A>
 * <P>
 * The POP3 protocol provider supports the following properties,
 * which may be set in the Jakarta Mail <code>Session</code> object.
 * The properties are always set as strings; the Type column describes
 * how the string is interpreted.  For example, use
 * </P>
 * <PRE>
 * props.put("mail.pop3.port", "888");
 * </PRE>
 * <P>
 * to set the <CODE>mail.pop3.port</CODE> property, which is of type int.
 * </P>
 * <P>
 * Note that if you're using the "pop3s" protocol to access POP3 over SSL,
 * all the properties would be named "mail.pop3s.*".
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>POP3 properties</CAPTION>
 * <TR>
 * <TH>Name</TH>
 * <TH>Type</TH>
 * <TH>Description</TH>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.user">mail.pop3.user</A></TD>
 * <TD>String</TD>
 * <TD>Default user name for POP3.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.host">mail.pop3.host</A></TD>
 * <TD>String</TD>
 * <TD>The POP3 server to connect to.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.port">mail.pop3.port</A></TD>
 * <TD>int</TD>
 * <TD>The POP3 server port to connect to, if the connect() method doesn't
 * explicitly specify one. Defaults to 110.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.connectiontimeout">mail.pop3.connectiontimeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket connection timeout value in milliseconds.
 * This timeout is implemented by java.net.Socket.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.timeout">mail.pop3.timeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket read timeout value in milliseconds.
 * This timeout is implemented by java.net.Socket.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.writetimeout">mail.pop3.writetimeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket write timeout value in milliseconds.
 * This timeout is implemented by using a
 * java.util.concurrent.ScheduledExecutorService per connection
 * that schedules a thread to close the socket if the timeout expires.
 * Thus, the overhead of using this timeout is one thread per connection.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.executor.writetimeout">mail.pop3.executor.writetimeout</A></TD>
 * <TD>java.util.concurrent.ScheduledExecutorService</TD>
 * <TD> Provides specific ScheduledExecutorService for mail.pop3.writetimeout option.
 * The value of mail.pop3.writetimeout shouldn't be a null.
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
 * <TD><A ID="mail.pop3.rsetbeforequit">mail.pop3.rsetbeforequit</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * Send a POP3 RSET command when closing the folder, before sending the
 * QUIT command.  Useful with POP3 servers that implicitly mark all
 * messages that are read as "deleted"; this will prevent such messages
 * from being deleted and expunged unless the client requests so.  Default
 * is false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.message.class">mail.pop3.message.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * Class name of a subclass of <code>org.eclipse.angus.mail.pop3.POP3Message</code>.
 * The subclass can be used to handle (for example) non-standard
 * Content-Type headers.  The subclass must have a public constructor
 * of the form <code>MyPOP3Message(Folder f, int msgno)
 * throws MessagingException</code>.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.localaddress">mail.pop3.localaddress</A></TD>
 * <TD>String</TD>
 * <TD>
 * Local address (host name) to bind to when creating the POP3 socket.
 * Defaults to the address picked by the Socket class.
 * Should not normally need to be set, but useful with multi-homed hosts
 * where it's important to pick a particular local address to bind to.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.localport">mail.pop3.localport</A></TD>
 * <TD>int</TD>
 * <TD>
 * Local port number to bind to when creating the POP3 socket.
 * Defaults to the port number picked by the Socket class.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.apop.enable">mail.pop3.apop.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use APOP instead of USER/PASS to login to the
 * POP3 server, if the POP3 server supports APOP.  APOP sends a
 * digest of the password rather than the clear text password.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.mechanisms">mail.pop3.auth.mechanisms</A></TD>
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
 * <TD><A ID="mail.pop3.auth.login.disable">mail.pop3.auth.login.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>USER</code> and <code>PASS</code>
 * commands.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.plain.disable">mail.pop3.auth.plain.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH PLAIN</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.digest-md5.disable">mail.pop3.auth.digest-md5.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH DIGEST-MD5</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.ntlm.disable">mail.pop3.auth.ntlm.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTH NTLM</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.ntlm.domain">mail.pop3.auth.ntlm.domain</A></TD>
 * <TD>String</TD>
 * <TD>
 * The NTLM authentication domain.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.ntlm.flags">mail.pop3.auth.ntlm.flags</A></TD>
 * <TD>int</TD>
 * <TD>
 * NTLM protocol-specific flags.
 * See <A HREF="https://curl.se/rfc/ntlm.html#theNtlmFlags" TARGET="_top">
 * https://curl.se/rfc/ntlm.html#theNtlmFlags</A> for details.
 * </TD>
 * </TR>
 *
 * <!--
 * <TR>
 * <TD><A ID="mail.pop3.auth.ntlm.unicode">mail.pop3.auth.ntlm.unicode</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * Set this to "true" if the username or password may use
 * Unicode UTF-8 encoded characters.  Default is "true".
 * Currently has no effect.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.ntlm.lmcompat">mail.pop3.auth.ntlm.lmcompat</A></TD>
 * <TD>int</TD>
 * <TD>
 * Sets the LM compatibility level, as described here:
 * <A HREF="https://curl.se/rfc/ntlm.html#ntlmVersion2" TARGET="_top">
 * https://curl.se/rfc/ntlm.html#ntlmVersion2</A>
 * Defaults to "3".  Currently not used.
 * </TD>
 * </TR>
 * -->
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.xoauth2.disable">mail.pop3.auth.xoauth2.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTHENTICATE XOAUTH2</code> command.
 * Because the OAuth 2.0 protocol requires a special access token instead of
 * a password, this mechanism is disabled by default.  Enable it by explicitly
 * setting this property to "false" or by setting the "mail.pop3.auth.mechanisms"
 * property to "XOAUTH2".</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.auth.xoauth2.two.line.authentication.format">mail.pop3.auth.xoauth2.two.line.authentication.format</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, splits authentication command on two lines.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.socketFactory">mail.pop3.socketFactory</A></TD>
 * <TD>SocketFactory</TD>
 * <TD>
 * If set to a class that implements the
 * <code>javax.net.SocketFactory</code> interface, this class
 * will be used to create POP3 sockets.  Note that this is an
 * instance of a class, not a name, and must be set using the
 * <code>put</code> method, not the <code>setProperty</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.socketFactory.class">mail.pop3.socketFactory.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, specifies the name of a class that implements the
 * <code>javax.net.SocketFactory</code> interface.  This class
 * will be used to create POP3 sockets.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.socketFactory.fallback">mail.pop3.socketFactory.fallback</A></TD>
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
 * <TD><A ID="mail.pop3.socketFactory.port">mail.pop3.socketFactory.port</A></TD>
 * <TD>int</TD>
 * <TD>
 * Specifies the port to connect to when using the specified socket
 * factory.
 * If not set, the default port will be used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.enable">mail.pop3.ssl.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use SSL to connect and use the SSL port by default.
 * Defaults to false for the "pop3" protocol and true for the "pop3s" protocol.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.checkserveridentity">mail.pop3.ssl.checkserveridentity</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to false, it does not check the server identity as specified by
 * <A HREF="https://www.ietf.org/rfc/rfc2595.txt" TARGET="_top">RFC 2595</A>.
 * These additional checks based on the content of the server's certificate
 * are intended to prevent man-in-the-middle attacks.
 * Defaults to true.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.trust">mail.pop3.ssl.trust</A></TD>
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
 * <TD><A ID="mail.pop3.ssl.socketFactory">mail.pop3.ssl.socketFactory</A></TD>
 * <TD>SSLSocketFactory</TD>
 * <TD>
 * If set to a class that extends the
 * <code>javax.net.ssl.SSLSocketFactory</code> class, this class
 * will be used to create POP3 SSL sockets.  Note that this is an
 * instance of a class, not a name, and must be set using the
 * <code>put</code> method, not the <code>setProperty</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.socketFactory.class">mail.pop3.ssl.socketFactory.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, specifies the name of a class that extends the
 * <code>javax.net.ssl.SSLSocketFactory</code> class.  This class
 * will be used to create POP3 SSL sockets.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.socketFactory.port">mail.pop3.ssl.socketFactory.port</A></TD>
 * <TD>int</TD>
 * <TD>
 * Specifies the port to connect to when using the specified socket
 * factory.
 * If not set, the default port will be used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.protocols">mail.pop3.ssl.protocols</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the SSL protocols that will be enabled for SSL connections.
 * The property value is a whitespace separated list of tokens acceptable
 * to the <code>javax.net.ssl.SSLSocket.setEnabledProtocols</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.ssl.ciphersuites">mail.pop3.ssl.ciphersuites</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the SSL cipher suites that will be enabled for SSL connections.
 * The property value is a whitespace separated list of tokens acceptable
 * to the <code>javax.net.ssl.SSLSocket.setEnabledCipherSuites</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.starttls.enable">mail.pop3.starttls.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If true, enables the use of the <code>STLS</code> command (if
 * supported by the server) to switch the connection to a TLS-protected
 * connection before issuing any login commands.
 * If the server does not support STARTTLS, the connection continues without
 * the use of TLS; see the
 * <A HREF="#mail.pop3.starttls.required"><code>mail.pop3.starttls.required</code></A>
 * property to fail if STARTTLS isn't supported.
 * Note that an appropriate trust store must configured so that the client
 * will trust the server's certificate.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.starttls.required">mail.pop3.starttls.required</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If true, requires the use of the <code>STLS</code> command.
 * If the server doesn't support the STLS command, or the command
 * fails, the connect method will fail.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.proxy.host">mail.pop3.proxy.host</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the host name of an HTTP web proxy server that will be used for
 * connections to the mail server.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.proxy.port">mail.pop3.proxy.port</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the port number for the HTTP web proxy server.
 * Defaults to port 80.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.proxy.user">mail.pop3.proxy.user</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the user name to use to authenticate with the HTTP web proxy server.
 * By default, no authentication is done.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.proxy.password">mail.pop3.proxy.password</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the password to use to authenticate with the HTTP web proxy server.
 * By default, no authentication is done.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.socks.host">mail.pop3.socks.host</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the host name of a SOCKS5 proxy server that will be used for
 * connections to the mail server.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.socks.port">mail.pop3.socks.port</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the port number for the SOCKS5 proxy server.
 * This should only need to be used if the proxy server is not using
 * the standard port number of 1080.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.disabletop">mail.pop3.disabletop</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, the POP3 TOP command will not be used to fetch
 * message headers.  This is useful for POP3 servers that don't
 * properly implement the TOP command, or that provide incorrect
 * information in the TOP command results.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.disablecapa">mail.pop3.disablecapa</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, the POP3 CAPA command will not be used to fetch
 * server capabilities.  This is useful for POP3 servers that don't
 * properly implement the CAPA command, or that provide incorrect
 * information in the CAPA command results.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.forgettopheaders">mail.pop3.forgettopheaders</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, the headers that might have been retrieved using
 * the POP3 TOP command will be forgotten and replaced by headers
 * retrieved as part of the POP3 RETR command.  Some servers, such
 * as some versions of Microsft Exchange and IBM Lotus Notes,
 * will return slightly different
 * headers each time the TOP or RETR command is used.  To allow the
 * POP3 provider to properly parse the message content returned from
 * the RETR command, the headers also returned by the RETR command
 * must be used.  Setting this property to true will cause these
 * headers to be used, even if they differ from the headers returned
 * previously as a result of using the TOP command.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.filecache.enable">mail.pop3.filecache.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, the POP3 provider will cache message data in a temporary
 * file rather than in memory.  Messages are only added to the cache when
 * accessing the message content.  Message headers are always cached in
 * memory (on demand).  The file cache is removed when the folder is closed
 * or the JVM terminates.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.filecache.dir">mail.pop3.filecache.dir</A></TD>
 * <TD>String</TD>
 * <TD>
 * If the file cache is enabled, this property can be used to override the
 * default directory used by the JDK for temporary files.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.cachewriteto">mail.pop3.cachewriteto</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * Controls the behavior of the
 * {@link org.eclipse.angus.mail.pop3.POP3Message#writeTo writeTo} method
 * on a POP3 message object.
 * If set to true, and the message content hasn't yet been cached,
 * and ignoreList is null, the message is cached before being written.
 * Otherwise, the message is streamed directly
 * to the output stream without being cached.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.keepmessagecontent">mail.pop3.keepmessagecontent</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * The content of a message is cached when it is first fetched.
 * Normally this cache uses a {@link java.lang.ref.SoftReference SoftReference}
 * to refer to the cached content.  This allows the cached content to be purged
 * if memory is low, in which case the content will be fetched again if it's
 * needed.
 * If this property is set to true, a hard reference to the cached content
 * will be kept, preventing the memory from being reused until the folder
 * is closed or the cached content is explicitly invalidated (using the
 * {@link org.eclipse.angus.mail.pop3.POP3Message#invalidate invalidate} method).
 * (This was the behavior in previous versions of Jakarta Mail.)
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.pop3.finalizecleanclose">mail.pop3.finalizecleanclose</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * When the finalizer for POP3Store or POP3Folder is called,
 * should the connection to the server be closed cleanly, as if the
 * application called the close method?
 * Or should the connection to the server be closed without sending
 * any commands to the server?
 * Defaults to false, the connection is not closed cleanly.
 * </TD>
 * </TR>
 *
 * </TABLE>
 * <P>
 * In general, applications should not need to use the classes in this
 * package directly.  Instead, they should use the APIs defined by
 * <code>jakarta.mail</code> package (and subpackages).  Applications should
 * never construct instances of <code>POP3Store</code> or
 * <code>POP3Folder</code> directly.  Instead, they should use the
 * <code>Session</code> method <code>getStore</code> to acquire an
 * appropriate <code>Store</code> object, and from that acquire
 * <code>Folder</code> objects.
 * </P>
 * <P>
 * In addition to printing debugging output as controlled by the
 * {@link jakarta.mail.Session Session} configuration,
 * the org.eclipse.angus.mail.pop3 provider logs the same information using
 * {@link java.util.logging.Logger} as described in the following table:
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>POP3 Loggers</CAPTION>
 * <TR>
 * <TH>Logger Name</TH>
 * <TH>Logging Level</TH>
 * <TH>Purpose</TH>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.pop3</TD>
 * <TD>CONFIG</TD>
 * <TD>Configuration of the POP3Store</TD>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.pop3</TD>
 * <TD>FINE</TD>
 * <TD>General debugging output</TD>
 * </TR>
 *
 * <TR>
 * <TD>org.eclipse.angus.mail.pop3.protocol</TD>
 * <TD>FINEST</TD>
 * <TD>Complete protocol trace</TD>
 * </TR>
 * </TABLE>
 *
 * <P>
 * <strong>WARNING:</strong> The APIs unique to this package should be
 * considered <strong>EXPERIMENTAL</strong>.  They may be changed in the
 * future in ways that are incompatible with applications using the
 * current APIs.
 */
package org.eclipse.angus.mail.pop3;
