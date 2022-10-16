/*
 * Copyright (c) 2021, 2022 Oracle and/or its affiliates. All rights reserved.
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
 * An IMAP protocol provider for the Jakarta Mail API
 * that provides access to an IMAP message store.
 * Both the IMAP4 and IMAP4rev1 protocols are supported.
 * Refer to <A HREF="http://www.ietf.org/rfc/rfc3501.txt" TARGET="_top">
 * RFC 3501</A>
 * for more information.
 * The IMAP protocol provider also supports many IMAP extensions (described below).
 * Note that the server needs to support these extensions (and not all servers do)
 * in order to use the support in the IMAP provider.
 * You can query the server for support of these extensions using the
 * {@link com.sun.mail.imap.IMAPStore#hasCapability IMAPStore hasCapability}
 * method using the capability name defined by the extension
 * (see the appropriate RFC) after connecting to the server.
 * <br>
 * <STRONG>UIDPLUS Support</STRONG>
 * <P>
 * The IMAP UIDPLUS extension
 * (<A HREF="http://www.ietf.org/rfc/rfc4315.txt" TARGET="_top">RFC 4315</A>)
 * is supported via the IMAPFolder methods
 * {@link com.sun.mail.imap.IMAPFolder#addMessages addMessages},
 * {@link com.sun.mail.imap.IMAPFolder#appendUIDMessages appendUIDMessages}, and
 * {@link com.sun.mail.imap.IMAPFolder#copyUIDMessages copyUIDMessages}.
 * </P>
 * <STRONG>MOVE Support</STRONG>
 * <P>
 * The IMAP MOVE extension
 * (<A HREF="http://www.ietf.org/rfc/rfc6851.txt" TARGET="_top">RFC 6851</A>)
 * is supported via the IMAPFolder methods
 * {@link com.sun.mail.imap.IMAPFolder#moveMessages moveMessages} and
 * {@link com.sun.mail.imap.IMAPFolder#moveUIDMessages moveUIDMessages}.
 * </P>
 * <STRONG>SASL Support</STRONG>
 * <P>
 * The IMAP protocol provider can use SASL
 * (<A HREF="http://www.ietf.org/rfc/rfc4422.txt" TARGET="_top">RFC 4422</A>)
 * authentication mechanisms on systems that support the
 * <CODE>javax.security.sasl</CODE> APIs.
 * The SASL-IR
 * (<A HREF="http://www.ietf.org/rfc/rfc4959.txt" TARGET="_top">RFC 4959</A>)
 * capability is also supported.
 * In addition to the SASL mechanisms that are built into
 * the SASL implementation, users can also provide additional
 * SASL mechanisms of their own design to support custom authentication
 * schemes.  See the
 * <A HREF="http://download.oracle.com/javase/6/docs/technotes/guides/security/sasl/sasl-refguide.html" TARGET="_top">
 * Java SASL API Programming and Deployment Guide</A> for details.
 * Note that the current implementation doesn't support SASL mechanisms
 * that provide their own integrity or confidentiality layer.
 * </P>
 * <STRONG>OAuth 2.0 Support</STRONG>
 * <P>
 * Support for OAuth 2.0 authentication via the
 * <A HREF="https://developers.google.com/gmail/xoauth2_protocol" TARGET="_top">
 * XOAUTH2 authentication mechanism</A> is provided either through the SASL
 * support described above or as a built-in authentication mechanism in the
 * IMAP provider.
 * The OAuth 2.0 Access Token should be passed as the password for this mechanism.
 * See <A HREF="https://eclipse-ee4j.github.io/mail/OAuth2" TARGET="_top">
 * OAuth2 Support</A> for details.
 * </P>
 * <STRONG>Connection Pool</STRONG>
 * <P>
 * A connected IMAPStore maintains a pool of IMAP protocol objects for
 * use in communicating with the IMAP server. The IMAPStore will create
 * the initial AUTHENTICATED connection and seed the pool with this
 * connection. As folders are opened and new IMAP protocol objects are
 * needed, the IMAPStore will provide them from the connection pool,
 * or create them if none are available. When a folder is closed,
 * its IMAP protocol object is returned to the connection pool if the
 * pool is not over capacity.
 * </P>
 * <P>
 * A mechanism is provided for timing out idle connection pool IMAP
 * protocol objects. Timed out connections are closed and removed (pruned)
 * from the connection pool.
 * </P>
 * <P>
 * The connected IMAPStore object may or may not maintain a separate IMAP
 * protocol object that provides the store a dedicated connection to the
 * IMAP server. This is provided mainly for compatibility with previous
 * implementations of the IMAP protocol provider.
 * </P>
 * <STRONG>QUOTA Support</STRONG>
 * <P>
 * The IMAP QUOTA extension
 * (<A HREF="http://www.ietf.org/rfc/rfc2087.txt" TARGET="_top">RFC 2087</A>)
 * is supported via the
 * {@link jakarta.mail.QuotaAwareStore QuotaAwareStore} interface implemented by
 * {@link com.sun.mail.imap.IMAPStore IMAPStore}, and the
 * {@link com.sun.mail.imap.IMAPFolder#getQuota IMAPFolder getQuota} and
 * {@link com.sun.mail.imap.IMAPFolder#setQuota IMAPFolder setQuota} methods.
 * <STRONG>ACL Support</STRONG>
 * <P>
 * The IMAP ACL extension
 * (<A HREF="http://www.ietf.org/rfc/rfc2086.txt" TARGET="_top">RFC 2086</A>)
 * is supported via the
 * {@link com.sun.mail.imap.Rights Rights} class and the IMAPFolder methods
 * {@link com.sun.mail.imap.IMAPFolder#getACL getACL},
 * {@link com.sun.mail.imap.IMAPFolder#addACL addACL},
 * {@link com.sun.mail.imap.IMAPFolder#removeACL removeACL},
 * {@link com.sun.mail.imap.IMAPFolder#addRights addRights},
 * {@link com.sun.mail.imap.IMAPFolder#removeRights removeRights},
 * {@link com.sun.mail.imap.IMAPFolder#listRights listRights}, and
 * {@link com.sun.mail.imap.IMAPFolder#myRights myRights}.
 * </P>
 * <STRONG>SORT Support</STRONG>
 * <P>
 * The IMAP SORT extension
 * (<A HREF="http://www.ietf.org/rfc/rfc5256.txt" TARGET="_top">RFC 5256</A>)
 * is supported via the
 * {@link com.sun.mail.imap.SortTerm SortTerm} class and the IMAPFolder
 * {@link com.sun.mail.imap.IMAPFolder#getSortedMessages getSortedMessages}
 * methods.
 * </P>
 * <STRONG>CONDSTORE and QRESYNC Support</STRONG>
 * <P>
 * Basic support is provided for the IMAP CONDSTORE
 * (<A HREF="http://www.ietf.org/rfc/rfc4551.txt" TARGET="_top">RFC 4551</A>)
 * and QRESYNC
 * (<A HREF="http://www.ietf.org/rfc/rfc5162.txt" TARGET="_top">RFC 5162</A>)
 * extensions for the purpose of resynchronizing a folder after offline operation.
 * Of course, the server must support these extensions.
 * Use of these extensions is enabled by using the new
 * {@link com.sun.mail.imap.IMAPFolder#open(int, com.sun.mail.imap.ResyncData)
 * IMAPFolder open} method and supplying an appropriate
 * {@link com.sun.mail.imap.ResyncData ResyncData} instance.
 * Using
 * {@link com.sun.mail.imap.ResyncData#CONDSTORE ResyncData.CONDSTORE}
 * enables the CONDSTORE extension, which allows you to discover the
 * modification sequence number (modseq) of messages using the
 * {@link com.sun.mail.imap.IMAPMessage#getModSeq IMAPMessage getModSeq}
 * method and the
 * {@link com.sun.mail.imap.IMAPFolder#getHighestModSeq
 * IMAPFolder getHighestModSeq} method.
 * Using a
 * {@link com.sun.mail.imap.ResyncData ResyncData} instance with appropriate
 * values also allows the server to report any changes in messages since the last
 * resynchronization.
 * The changes are reported as a list of
 * {@link jakarta.mail.event.MailEvent MailEvent} instances.
 * The special
 * {@link com.sun.mail.imap.MessageVanishedEvent MessageVanishedEvent} reports on
 * UIDs of messages that have been removed since the last resynchronization.
 * A
 * {@link jakarta.mail.event.MessageChangedEvent MessageChangedEvent} reports on
 * changes to flags of messages.
 * For example:
 * </P>
 * <PRE>
 * 	Folder folder = store.getFolder("whatever");
 * 	IMAPFolder ifolder = (IMAPFolder)folder;
 * 	List&lt;MailEvent&gt; events = ifolder.open(Folder.READ_WRITE,
 * 		    new ResyncData(prevUidValidity, prevModSeq));
 * 	for (MailEvent ev : events) {
 * 	    if (ev instanceOf MessageChangedEvent) {
 * 		// process flag changes
 *                } else if (ev instanceof MessageVanishedEvent) {
 * 		// process messages that were removed
 *            }
 * 	}
 * </PRE>
 * <P>
 * See the referenced RFCs for more details on these IMAP extensions.
 * </P>
 * <STRONG>WITHIN Search Support</STRONG>
 * <P>
 * The IMAP WITHIN search extension
 * (<A HREF="http://www.ietf.org/rfc/rfc5032.txt" TARGET="_top">RFC 5032</A>)
 * is supported via the
 * {@link com.sun.mail.imap.YoungerTerm YoungerTerm} and
 * {@link com.sun.mail.imap.OlderTerm OlderTerm}
 * {@link jakarta.mail.search.SearchTerm SearchTerms}, which can be used as follows:
 * </P>
 * <PRE>
 * 	// search for messages delivered in the last day
 * 	Message[] msgs = folder.search(new YoungerTerm(24 * 60 * 60));
 * </PRE>
 * <STRONG>LOGIN-REFERRAL Support</STRONG>
 * <P>
 * The IMAP LOGIN-REFERRAL extension
 * (<A HREF="http://www.ietf.org/rfc/rfc2221.txt" TARGET="_top">RFC 2221</A>)
 * is supported.
 * If a login referral is received when connecting or when authentication fails, a
 * {@link com.sun.mail.imap.ReferralException ReferralException} is thrown.
 * A referral can also occur when login succeeds.  By default, no exception is
 * thrown in this case.  To force an exception to be thrown and the authentication
 * to fail, set the <code>mail.imap.referralexception</code> property to "true".
 * </P>
 * <STRONG>COMPRESS Support</STRONG>
 * <P>
 * The IMAP COMPRESS extension
 * (<A HREF="http://www.ietf.org/rfc/rfc4978.txt" TARGET="_top">RFC 4978</A>)
 * is supported.
 * If the server supports the extension and the
 * <code>mail.imap.compress.enable</code> property is set to "true",
 * compression will be enabled.
 * </P>
 * <STRONG>UTF-8 Support</STRONG>
 * <P>
 * The IMAP UTF8 extension
 * (<A HREF="http://www.ietf.org/rfc/rfc6855.txt" TARGET="_top">RFC 6855</A>)
 * is supported.
 * If the server supports the extension, the client will enable use of UTF-8,
 * allowing use of UTF-8 in IMAP protocol strings such as folder names.
 * </P>
 * <A ID="properties"><STRONG>Properties</STRONG></A>
 * <P>
 * The IMAP protocol provider supports the following properties,
 * which may be set in the Jakarta Mail <code>Session</code> object.
 * The properties are always set as strings; the Type column describes
 * how the string is interpreted.  For example, use
 * </P>
 * <PRE>
 * 	props.put("mail.imap.port", "888");
 * </PRE>
 * <P>
 * to set the <CODE>mail.imap.port</CODE> property, which is of type int.
 * </P>
 * <P>
 * Note that if you're using the "imaps" protocol to access IMAP over SSL,
 * all the properties would be named "mail.imaps.*".
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>IMAP properties</CAPTION>
 * <TR>
 * <TH>Name</TH>
 * <TH>Type</TH>
 * <TH>Description</TH>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.user">mail.imap.user</A></TD>
 * <TD>String</TD>
 * <TD>Default user name for IMAP.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.host">mail.imap.host</A></TD>
 * <TD>String</TD>
 * <TD>The IMAP server to connect to.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.port">mail.imap.port</A></TD>
 * <TD>int</TD>
 * <TD>The IMAP server port to connect to, if the connect() method doesn't
 * explicitly specify one. Defaults to 143.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.partialfetch">mail.imap.partialfetch</A></TD>
 * <TD>boolean</TD>
 * <TD>Controls whether the IMAP partial-fetch capability should be used.
 * Defaults to true.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.fetchsize">mail.imap.fetchsize</A></TD>
 * <TD>int</TD>
 * <TD>Partial fetch size in bytes. Defaults to 16K.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.peek">mail.imap.peek</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use the IMAP PEEK option when fetching body parts,
 * to avoid setting the SEEN flag on messages.
 * Defaults to false.
 * Can be overridden on a per-message basis by the
 * {@link com.sun.mail.imap.IMAPMessage#setPeek setPeek}
 * method on IMAPMessage.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ignorebodystructuresize">mail.imap.ignorebodystructuresize</A></TD>
 * <TD>boolean</TD>
 * <TD>The IMAP BODYSTRUCTURE response includes the exact size of each body part.
 * Normally, this size is used to determine how much data to fetch for each
 * body part.
 * Some servers report this size incorrectly in some cases; this property can
 * be set to work around such server bugs.
 * If this property is set to true, this size is ignored and data is fetched
 * until the server reports the end of data.
 * This will result in an extra fetch if the data size is a multiple of the
 * block size.
 * Defaults to false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.connectiontimeout">mail.imap.connectiontimeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket connection timeout value in milliseconds.
 * This timeout is implemented by java.net.Socket.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.timeout">mail.imap.timeout</A></TD>
 * <TD>int</TD>
 * <TD>Socket read timeout value in milliseconds.
 * This timeout is implemented by java.net.Socket.
 * Default is infinite timeout.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.writetimeout">mail.imap.writetimeout</A></TD>
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
 * <TD><A ID="mail.imap.executor.writetimeout">mail.imap.executor.writetimeout</A></TD>
 * <TD>java.util.concurrent.ScheduledExecutorService</TD>
 * <TD> Provides specific ScheduledExecutorService for mail.imap.writetimeout option.
 * The value of mail.imap.writetimeout shouldn't be a null.
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
 *
 * <TR>
 * <TD><A ID="mail.imap.statuscachetimeout">mail.imap.statuscachetimeout</A></TD>
 * <TD>int</TD>
 * <TD>Timeout value in milliseconds for cache of STATUS command response.
 * Default is 1000 (1 second).  Zero disables cache.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.appendbuffersize">mail.imap.appendbuffersize</A></TD>
 * <TD>int</TD>
 * <TD>
 * Maximum size of a message to buffer in memory when appending to an IMAP
 * folder.  If not set, or set to -1, there is no maximum and all messages
 * are buffered.  If set to 0, no messages are buffered.  If set to (e.g.)
 * 8192, messages of 8K bytes or less are buffered, larger messages are
 * not buffered.  Buffering saves cpu time at the expense of short term
 * memory usage.  If you commonly append very large messages to IMAP
 * mailboxes you might want to set this to a moderate value (1M or less).
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.connectionpoolsize">mail.imap.connectionpoolsize</A></TD>
 * <TD>int</TD>
 * <TD>Maximum number of available connections in the connection pool.
 * Default is 1.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.connectionpooltimeout">mail.imap.connectionpooltimeout</A></TD>
 * <TD>int</TD>
 * <TD>Timeout value in milliseconds for connection pool connections.  Default
 * is 45000 (45 seconds).</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.separatestoreconnection">mail.imap.separatestoreconnection</A></TD>
 * <TD>boolean</TD>
 * <TD>Flag to indicate whether to use a dedicated store connection for store
 * commands.  Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.allowreadonlyselect">mail.imap.allowreadonlyselect</A></TD>
 * <TD>boolean</TD>
 * <TD>If false, attempts to open a folder read/write will fail
 * if the SELECT command succeeds but indicates that the folder is READ-ONLY.
 * This sometimes indicates that the folder contents can'tbe changed, but
 * the flags are per-user and can be changed, such as might be the case for
 * public shared folders.  If true, such open attempts will succeed, allowing
 * the flags to be changed.  The <code>getMode</code> method on the
 * <code>Folder</code> object will return <code>Folder.READ_ONLY</code>
 * in this case even though the <code>open</code> method specified
 * <code>Folder.READ_WRITE</code>.  Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.mechanisms">mail.imap.auth.mechanisms</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, lists the authentication mechanisms to consider, and the order
 * in which to consider them.  Only mechanisms supported by the server and
 * supported by the current implementation will be used.
 * The default is <code>"PLAIN LOGIN NTLM"</code>, which includes all
 * the authentication mechanisms supported by the current implementation
 * except XOAUTH2.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.login.disable">mail.imap.auth.login.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the non-standard <code>AUTHENTICATE LOGIN</code>
 * command, instead using the plain <code>LOGIN</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.plain.disable">mail.imap.auth.plain.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTHENTICATE PLAIN</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.ntlm.disable">mail.imap.auth.ntlm.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTHENTICATE NTLM</code> command.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.ntlm.domain">mail.imap.auth.ntlm.domain</A></TD>
 * <TD>String</TD>
 * <TD>
 * The NTLM authentication domain.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.ntlm.flags">mail.imap.auth.ntlm.flags</A></TD>
 * <TD>int</TD>
 * <TD>
 * NTLM protocol-specific flags.
 * See <A HREF="http://curl.haxx.se/rfc/ntlm.html#theNtlmFlags" TARGET="_top">
 * http://curl.haxx.se/rfc/ntlm.html#theNtlmFlags</A> for details.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.auth.xoauth2.disable">mail.imap.auth.xoauth2.disable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, prevents use of the <code>AUTHENTICATE XOAUTH2</code> command.
 * Because the OAuth 2.0 protocol requires a special access token instead of
 * a password, this mechanism is disabled by default.  Enable it by explicitly
 * setting this property to "false" or by setting the "mail.imap.auth.mechanisms"
 * property to "XOAUTH2".</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.proxyauth.user">mail.imap.proxyauth.user</A></TD>
 * <TD>String</TD>
 * <TD>If the server supports the PROXYAUTH extension, this property
 * specifies the name of the user to act as.  Authenticate to the
 * server using the administrator's credentials.  After authentication,
 * the IMAP provider will issue the <code>PROXYAUTH</code> command with
 * the user name specified in this property.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.localaddress">mail.imap.localaddress</A></TD>
 * <TD>String</TD>
 * <TD>
 * Local address (host name) to bind to when creating the IMAP socket.
 * Defaults to the address picked by the Socket class.
 * Should not normally need to be set, but useful with multi-homed hosts
 * where it's important to pick a particular local address to bind to.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.localport">mail.imap.localport</A></TD>
 * <TD>int</TD>
 * <TD>
 * Local port number to bind to when creating the IMAP socket.
 * Defaults to the port number picked by the Socket class.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.sasl.enable">mail.imap.sasl.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, attempt to use the javax.security.sasl package to
 * choose an authentication mechanism for login.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.sasl.mechanisms">mail.imap.sasl.mechanisms</A></TD>
 * <TD>String</TD>
 * <TD>
 * A space or comma separated list of SASL mechanism names to try
 * to use.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.sasl.authorizationid">mail.imap.sasl.authorizationid</A></TD>
 * <TD>String</TD>
 * <TD>
 * The authorization ID to use in the SASL authentication.
 * If not set, the authentication ID (user name) is used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.sasl.realm">mail.imap.sasl.realm</A></TD>
 * <TD>String</TD>
 * <TD>The realm to use with SASL authentication mechanisms that
 * require a realm, such as DIGEST-MD5.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.sasl.usecanonicalhostname">mail.imap.sasl.usecanonicalhostname</A></TD>
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
 * <TD><A ID="mail.imap.sasl.xgwtrustedapphack.enable">mail.imap.sasl. xgwtrustedapphack.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, enables a workaround for a bug in the Novell Groupwise
 * XGWTRUSTEDAPP SASL mechanism, when that mechanism is being used.
 * Defaults to true.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.socketFactory">mail.imap.socketFactory</A></TD>
 * <TD>SocketFactory</TD>
 * <TD>
 * If set to a class that implements the
 * <code>javax.net.SocketFactory</code> interface, this class
 * will be used to create IMAP sockets.  Note that this is an
 * instance of a class, not a name, and must be set using the
 * <code>put</code> method, not the <code>setProperty</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.socketFactory.class">mail.imap.socketFactory.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, specifies the name of a class that implements the
 * <code>javax.net.SocketFactory</code> interface.  This class
 * will be used to create IMAP sockets.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.socketFactory.fallback">mail.imap.socketFactory.fallback</A></TD>
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
 * <TD><A ID="mail.imap.socketFactory.port">mail.imap.socketFactory.port</A></TD>
 * <TD>int</TD>
 * <TD>
 * Specifies the port to connect to when using the specified socket
 * factory.
 * If not set, the default port will be used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.usesocketchannels">mail.imap.usesocketchannels</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use SocketChannels instead of Sockets for connecting
 * to the server.  Required if using the IdleManager.
 * Ignored if a socket factory is set.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.enable">mail.imap.ssl.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true, use SSL to connect and use the SSL port by default.
 * Defaults to false for the "imap" protocol and true for the "imaps" protocol.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.checkserveridentity">mail.imap.ssl.checkserveridentity</A></TD>
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
 * <TD><A ID="mail.imap.ssl.trust">mail.imap.ssl.trust</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, and a socket factory hasn't been specified, enables use of a
 * {@link com.sun.mail.util.MailSSLSocketFactory MailSSLSocketFactory}.
 * If set to "*", all hosts are trusted.
 * If set to a whitespace separated list of hosts, those hosts are trusted.
 * Otherwise, trust depends on the certificate the server presents.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.socketFactory">mail.imap.ssl.socketFactory</A></TD>
 * <TD>SSLSocketFactory</TD>
 * <TD>
 * If set to a class that extends the
 * <code>javax.net.ssl.SSLSocketFactory</code> class, this class
 * will be used to create IMAP SSL sockets.  Note that this is an
 * instance of a class, not a name, and must be set using the
 * <code>put</code> method, not the <code>setProperty</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.socketFactory.class">mail.imap.ssl.socketFactory.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * If set, specifies the name of a class that extends the
 * <code>javax.net.ssl.SSLSocketFactory</code> class.  This class
 * will be used to create IMAP SSL sockets.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.socketFactory.port">mail.imap.ssl.socketFactory.port</A></TD>
 * <TD>int</TD>
 * <TD>
 * Specifies the port to connect to when using the specified socket
 * factory.
 * If not set, the default port will be used.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.protocols">mail.imap.ssl.protocols</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the SSL protocols that will be enabled for SSL connections.
 * The property value is a whitespace separated list of tokens acceptable
 * to the <code>javax.net.ssl.SSLSocket.setEnabledProtocols</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.ssl.ciphersuites">mail.imap.ssl.ciphersuites</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the SSL cipher suites that will be enabled for SSL connections.
 * The property value is a whitespace separated list of tokens acceptable
 * to the <code>javax.net.ssl.SSLSocket.setEnabledCipherSuites</code> method.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.starttls.enable">mail.imap.starttls.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>If true, enables the use of the <code>STARTTLS</code> command (if
 * supported by the server) to switch the connection to a TLS-protected
 * connection before issuing any login commands.
 * If the server does not support STARTTLS, the connection continues without
 * the use of TLS; see the
 * <A HREF="#mail.imap.starttls.required"><code>mail.imap.starttls.required</code></A>
 * property to fail if STARTTLS isn't supported.
 * Note that an appropriate trust store must configured so that the client
 * will trust the server's certificate.
 * Default is false.</TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.starttls.required">mail.imap.starttls.required</A></TD>
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
 * <TD><A ID="mail.imap.proxy.host">mail.imap.proxy.host</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the host name of an HTTP web proxy server that will be used for
 * connections to the mail server.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.proxy.port">mail.imap.proxy.port</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the port number for the HTTP web proxy server.
 * Defaults to port 80.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.proxy.user">mail.imap.proxy.user</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the user name to use to authenticate with the HTTP web proxy server.
 * By default, no authentication is done.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.proxy.password">mail.imap.proxy.password</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the password to use to authenticate with the HTTP web proxy server.
 * By default, no authentication is done.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.socks.host">mail.imap.socks.host</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the host name of a SOCKS5 proxy server that will be used for
 * connections to the mail server.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.socks.port">mail.imap.socks.port</A></TD>
 * <TD>string</TD>
 * <TD>
 * Specifies the port number for the SOCKS5 proxy server.
 * This should only need to be used if the proxy server is not using
 * the standard port number of 1080.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.minidletime">mail.imap.minidletime</A></TD>
 * <TD>int</TD>
 * <TD>
 * Applications typically call the idle method in a loop.  If another
 * thread termiantes the IDLE command, it needs a chance to do its
 * work before another IDLE command is issued.  The idle method enforces
 * a delay to prevent thrashing between the IDLE command and regular
 * commands.  This property sets the delay in milliseconds.  If not
 * set, the default is 10 milliseconds.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.enableresponseevents">mail.imap.enableresponseevents</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * Enable special IMAP-specific events to be delivered to the Store's
 * <code>ConnectionListener</code>.  If true, IMAP OK, NO, BAD, or BYE responses
 * will be sent as <code>ConnectionEvent</code>s with a type of
 * <code>IMAPStore.RESPONSE</code>.  The event's message will be the
 * raw IMAP response string.
 * By default, these events are not sent.
 * NOTE: This capability is highly experimental and likely will change
 * in future releases.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.enableimapevents">mail.imap.enableimapevents</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * Enable special IMAP-specific events to be delivered to the Store's
 * <code>ConnectionListener</code>.  If true, unsolicited responses
 * received during the Store's <code>idle</code> method will be sent
 * as <code>ConnectionEvent</code>s with a type of
 * <code>IMAPStore.RESPONSE</code>.  The event's message will be the
 * raw IMAP response string.
 * By default, these events are not sent.
 * NOTE: This capability is highly experimental and likely will change
 * in future releases.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.throwsearchexception">mail.imap.throwsearchexception</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true and a {@link jakarta.mail.search.SearchTerm SearchTerm}
 * passed to the
 * {@link jakarta.mail.Folder#search Folder.search}
 * method is too complex for the IMAP protocol, throw a
 * {@link jakarta.mail.search.SearchException SearchException}.
 * For example, the IMAP protocol only supports less-than and greater-than
 * comparisons for a {@link jakarta.mail.search.SizeTerm SizeTerm}.
 * If false, the search will be done locally by fetching the required
 * message data and comparing it locally.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.folder.class">mail.imap.folder.class</A></TD>
 * <TD>String</TD>
 * <TD>
 * Class name of a subclass of <code>com.sun.mail.imap.IMAPFolder</code>.
 * The subclass can be used to provide support for additional IMAP commands.
 * The subclass must have public constructors of the form
 * <code>public MyIMAPFolder(String fullName, char separator, IMAPStore store,
 * Boolean isNamespace)</code> and
 * <code>public MyIMAPFolder(ListInfo li, IMAPStore store)</code>
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.closefoldersonstorefailure">mail.imap.closefoldersonstorefailure</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * In some cases, a failure of the Store connection indicates a failure of the
 * server, and all Folders associated with that Store should also be closed.
 * In other cases, a Store connection failure may be a transient failure, and
 * Folders may continue to operate normally.
 * If this property is true (the default), failures in the Store connection cause
 * all associated Folders to be closed.
 * Set this property to false to better handle transient failures in the Store
 * connection.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.finalizecleanclose">mail.imap.finalizecleanclose</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * When the finalizer for IMAPStore is called,
 * should the connection to the server be closed cleanly, as if the
 * application called the close method?
 * Or should the connection to the server be closed without sending
 * any commands to the server?
 * Defaults to false, the connection is not closed cleanly.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.referralexception">mail.imap.referralexception</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true and an IMAP login referral is returned when the authentication
 * succeeds, fail the connect request and throw a
 * {@link com.sun.mail.imap.ReferralException ReferralException}.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.compress.enable">mail.imap.compress.enable</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If set to true and the IMAP server supports the COMPRESS=DEFLATE extension,
 * compression will be enabled.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.compress.level">mail.imap.compress.level</A></TD>
 * <TD>int</TD>
 * <TD>
 * The compression level to be used, in the range -1 to 9.
 * See the {@link java.util.zip.Deflater Deflater} class for details.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.compress.strategy">mail.imap.compress.strategy</A></TD>
 * <TD>int</TD>
 * <TD>
 * The compression strategy to be used, in the range 0 to 2.
 * See the {@link java.util.zip.Deflater Deflater} class for details.
 * </TD>
 * </TR>
 *
 * <TR>
 * <TD><A ID="mail.imap.reusetagprefix">mail.imap.reusetagprefix</A></TD>
 * <TD>boolean</TD>
 * <TD>
 * If true, always use "A" for the IMAP command tag prefix.
 * If false, the IMAP command tag prefix is different for each connection,
 * from "A" through "ZZZ" and then wrapping around to "A".
 * Applications should never need to set this.
 * Defaults to false.
 * </TD>
 * </TR>
 *
 * </TABLE>
 * <P>
 * In general, applications should not need to use the classes in this
 * package directly.  Instead, they should use the APIs defined by the
 * <code>jakarta.mail</code> package (and subpackages).  Applications should
 * never construct instances of <code>IMAPStore</code> or
 * <code>IMAPFolder</code> directly.  Instead, they should use the
 * <code>Session</code> method <code>getStore</code> to acquire an
 * appropriate <code>Store</code> object, and from that acquire
 * <code>Folder</code> objects.
 * </P>
 * <STRONG>Loggers</STRONG>
 * <P>
 * In addition to printing debugging output as controlled by the
 * {@link jakarta.mail.Session Session} configuration,
 * the com.sun.mail.imap provider logs the same information using
 * {@link java.util.logging.Logger} as described in the following table:
 * </P>
 * <TABLE BORDER="1">
 * <CAPTION>IMAP Loggers</CAPTION>
 * <TR>
 * <TH>Logger Name</TH>
 * <TH>Logging Level</TH>
 * <TH>Purpose</TH>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap</TD>
 * <TD>CONFIG</TD>
 * <TD>Configuration of the IMAPStore</TD>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap</TD>
 * <TD>FINE</TD>
 * <TD>General debugging output</TD>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap.connectionpool</TD>
 * <TD>CONFIG</TD>
 * <TD>Configuration of the IMAP connection pool</TD>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap.connectionpool</TD>
 * <TD>FINE</TD>
 * <TD>Debugging output related to the IMAP connection pool</TD>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap.messagecache</TD>
 * <TD>CONFIG</TD>
 * <TD>Configuration of the IMAP message cache</TD>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap.messagecache</TD>
 * <TD>FINE</TD>
 * <TD>Debugging output related to the IMAP message cache</TD>
 * </TR>
 *
 * <TR>
 * <TD>com.sun.mail.imap.protocol</TD>
 * <TD>FINEST</TD>
 * <TD>Complete protocol trace</TD>
 * </TR>
 * </TABLE>
 *
 * <STRONG>WARNING</STRONG>
 * <P>
 * <strong>WARNING:</strong> The APIs unique to this package should be
 * considered <strong>EXPERIMENTAL</strong>.  They may be changed in the
 * future in ways that are incompatible with applications using the
 * current APIs.
 */
package com.sun.mail.imap;
