/*
 * Copyright (c) 1997, 2024 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.util;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSession;

/**
 * This class is used to get Sockets. Depending on the arguments passed
 * it will either return a plain java.net.Socket or dynamically load
 * the SocketFactory class specified in the classname param and return
 * a socket created by that SocketFactory.
 *
 * @author Max Spivak
 * @author Bill Shannon
 */
public class SocketFetcher {

    private static MailLogger logger = new MailLogger(
            SocketFetcher.class,
            "socket",
            "DEBUG SocketFetcher",
            PropUtil.getBooleanSystemProperty("mail.socket.debug", false),
            System.out);

    // No one should instantiate this class.
    private SocketFetcher() {
    }

    /**
     * This method returns a Socket.  Properties control the use of
     * socket factories and other socket characteristics.  The properties
     * used are:
     * <ul>
     * <li> <i>prefix</i>.socketFactory
     * <li> <i>prefix</i>.socketFactory.class
     * <li> <i>prefix</i>.socketFactory.fallback
     * <li> <i>prefix</i>.socketFactory.port
     * <li> <i>prefix</i>.ssl.socketFactory
     * <li> <i>prefix</i>.ssl.socketFactory.class
     * <li> <i>prefix</i>.ssl.socketFactory.port
     * <li> <i>prefix</i>.timeout
     * <li> <i>prefix</i>.connectiontimeout
     * <li> <i>prefix</i>.localaddress
     * <li> <i>prefix</i>.localport
     * <li> <i>prefix</i>.usesocketchannels
     * </ul> <p>
     * If we're making an SSL connection, the ssl.socketFactory
     * properties are used first, if set. <p>
     *
     * If the socketFactory property is set, the value is an
     * instance of a SocketFactory class, not a string.  The
     * instance is used directly.  If the socketFactory property
     * is not set, the socketFactory.class property is considered.
     * (Note that the SocketFactory property must be set using the
     * <code>put</code> method, not the <code>setProperty</code>
     * method.) <p>
     *
     * If the socketFactory.class property isn't set, the socket
     * returned is an instance of java.net.Socket connected to the
     * given host and port. If the socketFactory.class property is set,
     * it is expected to contain a fully qualified classname of a
     * javax.net.SocketFactory subclass.  In this case, the class is
     * dynamically instantiated and a socket created by that
     * SocketFactory is returned. <p>
     *
     * If the socketFactory.fallback property is set to false, don't
     * fall back to using regular sockets if the socket factory fails. <p>
     *
     * The socketFactory.port specifies a port to use when connecting
     * through the socket factory.  If unset, the port argument will be
     * used.  <p>
     *
     * If the connectiontimeout property is set, the timeout is passed
     * to the socket connect method. <p>
     *
     * If the timeout property is set, it is used to set the socket timeout.
     * <p>
     *
     * If the localaddress property is set, it's used as the local address
     * to bind to.  If the localport property is also set, it's used as the
     * local port number to bind to. <p>
     *
     * If the usesocketchannels property is set, and we create the Socket
     * ourself, and the selection of other properties allows, create a
     * SocketChannel and get the Socket from it.  This allows us to later
     * retrieve the SocketChannel from the Socket and use it with Select.
     *
     * @param host   The host to connect to
     * @param port   The port to connect to at the host
     * @param props  Properties object containing socket properties
     * @param prefix Property name prefix, e.g., "mail.imap"
     * @param useSSL use the SSL socket factory as the default
     * @return the Socket
     * @exception IOException    for I/O errors
     */
    public static Socket getSocket(String host, int port, Properties props,
                                   String prefix, boolean useSSL)
            throws IOException {

        if (logger.isLoggable(Level.FINER))
            logger.finer("getSocket" + ", host " + host + ", port " + port +
                    ", prefix " + prefix + ", useSSL " + useSSL);
        if (prefix == null)
            prefix = "socket";
        if (props == null)
            props = new Properties();    // empty
        int cto = PropUtil.getIntProperty(props,
                prefix + ".connectiontimeout", -1);
        Socket socket = null;
        String localaddrstr = props.getProperty(prefix + ".localaddress", null);
        InetAddress localaddr = null;
        if (localaddrstr != null)
            localaddr = InetAddress.getByName(localaddrstr);
        int localport = PropUtil.getIntProperty(props,
                prefix + ".localport", 0);

        boolean fb = PropUtil.getBooleanProperty(props,
                prefix + ".socketFactory.fallback", true);

        int sfPort = -1;
        String sfErr = "unknown socket factory";
        int to = PropUtil.getIntProperty(props, prefix + ".timeout", -1);
        try {
            /*
             * If using SSL, first look for SSL-specific class name or
             * factory instance.
             */
            SocketFactory sf = null;
            String sfPortName = null;
            if (useSSL) {
                Object sfo = props.get(prefix + ".ssl.socketFactory");
                if (sfo instanceof SocketFactory) {
                    sf = (SocketFactory) sfo;
                    sfErr = "SSL socket factory instance " + sf;
                }
                if (sf == null) {
                    String sfClass =
                            props.getProperty(prefix + ".ssl.socketFactory.class");
                    sf = getSocketFactory(sfClass);
                    sfErr = "SSL socket factory class " + sfClass;
                }
                sfPortName = ".ssl.socketFactory.port";
            }

            if (sf == null) {
                Object sfo = props.get(prefix + ".socketFactory");
                if (sfo instanceof SocketFactory) {
                    sf = (SocketFactory) sfo;
                    sfErr = "socket factory instance " + sf;
                }
                if (sf == null) {
                    String sfClass =
                            props.getProperty(prefix + ".socketFactory.class");
                    sf = getSocketFactory(sfClass);
                    sfErr = "socket factory class " + sfClass;
                }
                sfPortName = ".socketFactory.port";
            }

            // if we now have a socket factory, use it
            if (sf != null) {
                sfPort = PropUtil.getIntProperty(props,
                        prefix + sfPortName, -1);

                // if port passed in via property isn't valid, use param
                if (sfPort == -1)
                    sfPort = port;
                socket = createSocket(localaddr, localport,
                        host, sfPort, cto, to, props, prefix, sf, useSSL);
            }
        } catch (SocketTimeoutException sex) {
            throw sex;
        } catch (Exception ex) {
            if (!fb) {
                if (ex instanceof InvocationTargetException) {
                    Throwable t =
                            ((InvocationTargetException) ex).getTargetException();
                    if (t instanceof Exception)
                        ex = (Exception) t;
                }
                if (ex instanceof IOException)
                    throw (IOException) ex;
                throw new SocketConnectException("Using " + sfErr, ex,
                        host, sfPort, cto);
            }
        }

        if (socket == null) {
            socket = createSocket(localaddr, localport,
                    host, port, cto, to, props, prefix, null, useSSL);

        } else {
            if (to >= 0) {
                if (logger.isLoggable(Level.FINEST))
                    logger.finest("set socket read timeout " + to);
                socket.setSoTimeout(to);
            }
        }

        return socket;
    }

    public static Socket getSocket(String host, int port, Properties props,
                                   String prefix) throws IOException {
        return getSocket(host, port, props, prefix, false);
    }

    /**
     * Create a socket with the given local address and connected to
     * the given host and port.  Use the specified connection timeout
     * and read timeout.
     * If a socket factory is specified, use it.  Otherwise, use the
     * SSLSocketFactory if useSSL is true.
     */
    private static Socket createSocket(InetAddress localaddr, int localport,
                                       String host, int port, int cto, int to,
                                       Properties props, String prefix,
                                       SocketFactory sf, boolean useSSL)
            throws IOException {
        Socket socket = null;

        if (logger.isLoggable(Level.FINEST))
            logger.finest("create socket: prefix " + prefix +
                    ", localaddr " + localaddr + ", localport " + localport +
                    ", host " + host + ", port " + port +
                    ", connection timeout " + cto + ", timeout " + to +
                    ", socket factory " + sf + ", useSSL " + useSSL);

        String proxyHost = props.getProperty(prefix + ".proxy.host", null);
        String proxyUser = props.getProperty(prefix + ".proxy.user", null);
        String proxyPassword = props.getProperty(prefix + ".proxy.password", null);
        int proxyPort = 80;
        String socksHost = null;
        int socksPort = 1080;
        String err = null;

        if (proxyHost != null) {
            int i = proxyHost.indexOf(':');
            if (i >= 0) {
                try {
                    proxyPort = Integer.parseInt(proxyHost.substring(i + 1));
                } catch (NumberFormatException ex) {
                    // ignore it
                }
                proxyHost = proxyHost.substring(0, i);
            }
            proxyPort = PropUtil.getIntProperty(props,
                    prefix + ".proxy.port", proxyPort);
            err = "Using web proxy host, port: " + proxyHost + ", " + proxyPort;
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("web proxy host " + proxyHost + ", port " + proxyPort);
                if (proxyUser != null)
                    logger.finer("web proxy user " + proxyUser + ", password " +
                            (proxyPassword == null ? "<null>" : "<non-null>"));
            }
        } else if ((socksHost =
                props.getProperty(prefix + ".socks.host", null)) != null) {
            int i = socksHost.indexOf(':');
            if (i >= 0) {
                try {
                    socksPort = Integer.parseInt(socksHost.substring(i + 1));
                } catch (NumberFormatException ex) {
                    // ignore it
                }
                socksHost = socksHost.substring(0, i);
            }
            socksPort = PropUtil.getIntProperty(props,
                    prefix + ".socks.port", socksPort);
            err = "Using SOCKS host, port: " + socksHost + ", " + socksPort;
            if (logger.isLoggable(Level.FINER))
                logger.finer("socks host " + socksHost + ", port " + socksPort);
        }

        if (sf != null && !(sf instanceof SSLSocketFactory))
            socket = sf.createSocket();
        if (socket == null) {
            if (socksHost != null) {
                socket = new Socket(
                        new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                                new InetSocketAddress(socksHost, socksPort)));
            } else if (PropUtil.getBooleanProperty(props,
                    prefix + ".usesocketchannels", false)) {
                logger.finer("using SocketChannels");
                socket = SocketChannel.open().socket();
            } else
                socket = new Socket();
        }
        if (to >= 0) {
            if (logger.isLoggable(Level.FINEST))
                logger.finest("set socket read timeout " + to);
            socket.setSoTimeout(to);
        }
        int writeTimeout = PropUtil.getIntProperty(props,
                prefix + ".writetimeout", -1);
        if (writeTimeout != -1) {    // wrap original
            if (logger.isLoggable(Level.FINEST))
                logger.finest("set socket write timeout " + writeTimeout);
            ScheduledExecutorService executorService = PropUtil.getScheduledExecutorServiceProperty(props,
                    prefix + ".executor.writetimeout");
            socket = executorService == null ?
                    new WriteTimeoutSocket(socket, writeTimeout) :
                    new WriteTimeoutSocket(socket, writeTimeout, executorService);
        }
        if (localaddr != null)
            socket.bind(new InetSocketAddress(localaddr, localport));
        try {
            logger.finest("connecting...");
            if (proxyHost != null)
                proxyConnect(socket, proxyHost, proxyPort,
                        proxyUser, proxyPassword, host, port, cto);
            else if (cto >= 0)
                socket.connect(new InetSocketAddress(host, port), cto);
            else
                socket.connect(new InetSocketAddress(host, port));
            logger.finest("success!");
        } catch (IOException ex) {
            logger.log(Level.FINEST, "connection failed", ex);
            throw new SocketConnectException(err, ex, host, port, cto);
        }

        /*
         * If we want an SSL connection and we didn't get an SSLSocket,
         * wrap our plain Socket with an SSLSocket.
         */
        if ((useSSL || sf instanceof SSLSocketFactory) &&
                !(socket instanceof SSLSocket)) {
            String trusted;
            SSLSocketFactory ssf;
            if ((trusted = props.getProperty(prefix + ".ssl.trust")) != null) {
                try {
                    MailSSLSocketFactory msf = new MailSSLSocketFactory();
                    if (trusted.equals("*"))
                        msf.setTrustAllHosts(true);
                    else
                        msf.setTrustedHosts(trusted.split("\\s+"));
                    ssf = msf;
                } catch (GeneralSecurityException gex) {
                    IOException ioex = new IOException(
                            "Can't create MailSSLSocketFactory");
                    ioex.initCause(gex);
                    throw ioex;
                }
            } else if (sf instanceof SSLSocketFactory)
                ssf = (SSLSocketFactory) sf;
            else
                ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = ssf.createSocket(socket, host, port, true);
            sf = ssf;
        }

        /*
         * No matter how we created the socket, if it turns out to be an
         * SSLSocket, configure it.
         */
        configureSSLSocket(socket, host, props, prefix, sf);

        return socket;
    }

    /**
     * Return a socket factory of the specified class.
     */
    private static SocketFactory getSocketFactory(String sfClass)
            throws ClassNotFoundException,
            NoSuchMethodException,
            IllegalAccessException,
            InvocationTargetException {
        if (sfClass == null || sfClass.length() == 0)
            return null;

        // dynamically load the class

        ClassLoader cl = getContextClassLoader();
        Class<?> clsSockFact = null;
        if (cl != null) {
            try {
                clsSockFact = Class.forName(sfClass, false, cl);
            } catch (ClassNotFoundException cex) {
            }
        }
        if (clsSockFact == null)
            clsSockFact = Class.forName(sfClass);
        // get & invoke the getDefault() method
        Method mthGetDefault = clsSockFact.getMethod("getDefault"
        );
        SocketFactory sf = (SocketFactory)
                mthGetDefault.invoke(new Object(), new Object[]{});
        return sf;
    }

    /**
     * Start TLS on an existing socket.
     * Supports the "STARTTLS" command in many protocols.
     * This version for compatibility with possible third party code
     * that might've used this API even though it shouldn't.
     *
     * @param    socket    the existing socket
     * @return the wrapped Socket
     * @exception IOException    for I/O errors
     * @deprecated
     */
    @Deprecated
    public static Socket startTLS(Socket socket) throws IOException {
        return startTLS(socket, new Properties(), "socket");
    }

    /**
     * Start TLS on an existing socket.
     * Supports the "STARTTLS" command in many protocols.
     * This version for compatibility with possible third party code
     * that might've used this API even though it shouldn't.
     *
     * @param    socket    the existing socket
     * @param    props    the properties
     * @param    prefix    the property prefix
     * @return the wrapped Socket
     * @exception IOException    for I/O errors
     * @deprecated
     */
    @Deprecated
    public static Socket startTLS(Socket socket, Properties props,
                                  String prefix) throws IOException {
        InetAddress a = socket.getInetAddress();
        String host = a.getHostName();
        return startTLS(socket, host, props, prefix);
    }

    /**
     * Start TLS on an existing socket.
     * Supports the "STARTTLS" command in many protocols.
     *
     * @param    socket    the existing socket
     * @param    host    the host the socket is connected to
     * @param    props    the properties
     * @param    prefix    the property prefix
     * @return the wrapped Socket
     * @exception IOException    for I/O errors
     */
    public static Socket startTLS(Socket socket, String host, Properties props,
                                  String prefix) throws IOException {
        int port = socket.getPort();
        if (logger.isLoggable(Level.FINER))
            logger.finer("startTLS host " + host + ", port " + port);

        String sfErr = "unknown socket factory";
        try {
            SSLSocketFactory ssf = null;
            SocketFactory sf = null;

            // first, look for an SSL socket factory
            Object sfo = props.get(prefix + ".ssl.socketFactory");
            if (sfo instanceof SocketFactory) {
                sf = (SocketFactory) sfo;
                sfErr = "SSL socket factory instance " + sf;
            }
            if (sf == null) {
                String sfClass =
                        props.getProperty(prefix + ".ssl.socketFactory.class");
                sf = getSocketFactory(sfClass);
                sfErr = "SSL socket factory class " + sfClass;
            }
            if (sf != null && sf instanceof SSLSocketFactory)
                ssf = (SSLSocketFactory) sf;

            // next, look for a regular socket factory that happens to be
            // an SSL socket factory
            if (ssf == null) {
                sfo = props.get(prefix + ".socketFactory");
                if (sfo instanceof SocketFactory) {
                    sf = (SocketFactory) sfo;
                    sfErr = "socket factory instance " + sf;
                }
                if (sf == null) {
                    String sfClass =
                            props.getProperty(prefix + ".socketFactory.class");
                    sf = getSocketFactory(sfClass);
                    sfErr = "socket factory class " + sfClass;
                }
                if (sf != null && sf instanceof SSLSocketFactory)
                    ssf = (SSLSocketFactory) sf;
            }

            // finally, use the default SSL socket factory
            if (ssf == null) {
                String trusted;
                if ((trusted = props.getProperty(prefix + ".ssl.trust")) !=
                        null) {
                    try {
                        MailSSLSocketFactory msf = new MailSSLSocketFactory();
                        if (trusted.equals("*"))
                            msf.setTrustAllHosts(true);
                        else
                            msf.setTrustedHosts(trusted.split("\\s+"));
                        ssf = msf;
                        sfErr = "mail SSL socket factory";
                    } catch (GeneralSecurityException gex) {
                        IOException ioex = new IOException(
                                "Can't create MailSSLSocketFactory");
                        ioex.initCause(gex);
                        throw ioex;
                    }
                } else {
                    ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    sfErr = "default SSL socket factory";
                }
            }

            socket = ssf.createSocket(socket, host, port, true);
            configureSSLSocket(socket, host, props, prefix, ssf);
        } catch (Exception ex) {
            if (ex instanceof InvocationTargetException) {
                Throwable t =
                        ((InvocationTargetException) ex).getTargetException();
                if (t instanceof Exception)
                    ex = (Exception) t;
            }
            if (ex instanceof IOException)
                throw (IOException) ex;
            // wrap anything else before sending it on
            IOException ioex = new IOException(
                    "Exception in startTLS using " + sfErr +
                            ": host, port: " +
                            host + ", " + port +
                            "; Exception: " + ex);
            ioex.initCause(ex);
            throw ioex;
        }
        return socket;
    }

    /**
     * Configure the SSL options for the socket (if it's an SSL socket),
     * based on the mail.<protocol>.ssl.protocols and
     * mail.<protocol>.ssl.ciphersuites properties.
     * Check the identity of the server as specified by the
     * mail.<protocol>.ssl.checkserveridentity property.
     */
    private static void configureSSLSocket(Socket socket, String host,
                                           Properties props, String prefix, SocketFactory sf)
            throws IOException {
        if (!(socket instanceof SSLSocket))
            return;
        SSLSocket sslsocket = (SSLSocket) socket;

        String protocols = props.getProperty(prefix + ".ssl.protocols", null);
        if (protocols != null)
            sslsocket.setEnabledProtocols(stringArray(protocols));
        else {
            /*
             * The UW IMAP server insists on at least the TLSv1
             * protocol for STARTTLS, and won't accept the old SSLv2
             * or SSLv3 protocols.  Here we enable only the non-SSL
             * protocols.  XXX - this should probably be parameterized.
             */
            String[] prots = sslsocket.getEnabledProtocols();
            if (logger.isLoggable(Level.FINER))
                logger.finer("SSL enabled protocols before " +
                        Arrays.asList(prots));
            List<String> eprots = new ArrayList<>();
            for (int i = 0; i < prots.length; i++) {
                if (prots[i] != null && !prots[i].startsWith("SSL"))
                    eprots.add(prots[i]);
            }
            sslsocket.setEnabledProtocols(
                    eprots.toArray(new String[0]));
        }
        String ciphers = props.getProperty(prefix + ".ssl.ciphersuites", null);
        if (ciphers != null)
            sslsocket.setEnabledCipherSuites(stringArray(ciphers));
        if (logger.isLoggable(Level.FINER)) {
            logger.finer("SSL enabled protocols after " +
                    Arrays.asList(sslsocket.getEnabledProtocols()));
            logger.finer("SSL enabled ciphers after " +
                    Arrays.asList(sslsocket.getEnabledCipherSuites()));
        }

        try {
            /*
             * Check server identity and trust.
             * See: JDK-8062515 and JDK-7192189
             */
            if (PropUtil.getBooleanProperty(props,
                    prefix + ".ssl.checkserveridentity", true)) {
                // LDAP requires the same regex handling as we need
                String eia = "LDAPS";
                SSLParameters params = sslsocket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm(eia);
                sslsocket.setSSLParameters(params);

                if (logger.isLoggable(Level.FINER)) {
                    logger.log(Level.FINER,
                    "Checking {0} using endpoint identification algorithm {1}",
                        new Object[]{params.getServerNames(), eia});
                }
            }
        } catch (RuntimeException re) {
            throw cleanupAndThrow(sslsocket,
                new IOException("Unable to check server idenitity", re));
        }

        /*
         * Force the handshake to be done now so that we can report any
         * errors (e.g., certificate errors) to the caller of the startTLS
         * method.
         */
        try {
            sslsocket.startHandshake();
        } catch (IOException ioe) {
            throw cleanupAndThrow(sslsocket,ioe);
        }

        /*
         * Check server identity and trust with user provided checks.
         */
        try {
            checkServerIdentity(getHostnameVerifier(props, prefix),
                    host, sslsocket);
        } catch (IOException ioe) {
            throw cleanupAndThrow(sslsocket,ioe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError re) {
            throw cleanupAndThrow(sslsocket,
                    new IOException("Unable to check server idenitity for: "
                            + host, re));
        }

        if (sf instanceof MailSSLSocketFactory) {
            MailSSLSocketFactory msf = (MailSSLSocketFactory) sf;
            if (!msf.isServerTrusted(host, sslsocket)) {
                throw cleanupAndThrow(sslsocket,
                        new IOException("Server is not trusted: " + host));
            }
        }
    }

    private static IOException cleanupAndThrow(Socket socket, IOException ife) {
        try {
            socket.close();
        } catch (Throwable thr) {
            if (isRecoverable(thr)) {
                ife.addSuppressed(thr);
            } else {
                thr.addSuppressed(ife);
                if (thr instanceof Error) {
                    throw (Error) thr;
                }
                if (thr instanceof RuntimeException) {
                    throw (RuntimeException) thr;
                }
                throw new RuntimeException("unexpected exception", thr);
            }
        }
        return ife;
    }

    private static boolean isRecoverable(Throwable t) {
        return (t instanceof Exception) || (t instanceof LinkageError);
    }

    /**
     * Check the server from the Socket connection against the server name(s)
     * using the given HostnameVerifier.  All hostname verifier implementations
     * are allowed to throw unchecked exceptions.
     *
     * @param hnv the HostnameVerifier or null if allowing all.
     * @param server name of the server expected
     * @param sslSocket SSLSocket connected to the server.  Caller is expected
     * to close the socket on error.
     * @exception IOException if we can't verify identity of server
     * @throws RuntimeException caused by invoking the verifier
     */
    private static void checkServerIdentity(HostnameVerifier hnv,
                            String server, SSLSocket sslSocket)
                            throws IOException {
        if (logger.isLoggable(Level.FINER)) {
            //Only expose the toString of the HostnameVerifier to the logger
            //and not a direct reference to the HostnameVerifier
            logger.log(Level.FINER, "Using HostnameVerifier: {0}",
                    Objects.toString(hnv));
        }

        if (hnv == null) {
            return;
        }

        // Check against the server name(s) as expressed in server certificate
        if (!hnv.verify(server, sslSocket.getSession())) {
            throw new IOException("Server is not trusted: " + server);
        }
    }

    private static X509Certificate getX509Certificate(
            java.security.cert.Certificate[] certChain) throws IOException {
        if (certChain == null || certChain.length == 0) {
            throw new SSLPeerUnverifiedException(
                    Arrays.toString(certChain));
        }

        java.security.cert.Certificate first = certChain[0];
        if (first instanceof X509Certificate) {
            return (X509Certificate) first;
        }

        //Only metadata about the cert is shown in the message
        throw new SSLPeerUnverifiedException(first == null ? "null"
                : (first.getClass().getName() + " " + first.getType()));
    }


   /**
    * Return an instance of {@link HostnameVerifier}.
    *
    * This method assumes the {@link HostnameVerifier} class provides an
    * accessible default constructor to instantiate the instance.
    *
    * @param fqcn the class name of the {@link HostnameVerifier}
    * @return the {@link HostnameVerifier} or null
    * @throws ClassCastException if hostnameverifier is not a {@link HostnameVerifier}
    * @throws ReflectiveOperationException if unable to construct a {@link HostnameVerifier}
    */
   private static HostnameVerifier getHostnameVerifier(Properties props, String prefix)
           throws ReflectiveOperationException {

        //Custom object is used before factory.
        HostnameVerifier hvn = (HostnameVerifier)
                                props.get(prefix + ".ssl.hostnameverifier");
        if (hvn != null) {
            return hvn;
        }

        String fqcn = props.getProperty(prefix + ".ssl.hostnameverifier.class");
        if (fqcn == null || fqcn.isEmpty()) {
            return null;
        }

        //Handle all aliases names
        if ("any".equals(fqcn)) { //legacy behavior
            return JdkHostnameChecker.or(MailHostnameVerifier.of());
        }

        if ("sun.security.util.HostnameChecker".equals(fqcn)
                || JdkHostnameChecker.class.getSimpleName().equals(fqcn)) {
            return JdkHostnameChecker.of();
        }

        if (MailHostnameVerifier.class.getSimpleName().equals(fqcn)) {
            return MailHostnameVerifier.of();
        }

        //Handle the fully qualified class name
        Class<? extends HostnameVerifier> verifierClass = null;
        ClassLoader ccl = getContextClassLoader();

        // Attempt to load the class from the context class loader.
        if (ccl != null) {
            try {
                verifierClass = Class.forName(fqcn, false, ccl)
                        .asSubclass(HostnameVerifier.class);
            } catch (ClassNotFoundException | RuntimeException cnfe) {
                logger.log(Level.FINER,
                        "Context class loader could not find: " + fqcn, cnfe);
            }
        }

        //Try calling class loader
        if (verifierClass == null) {
            try {
                verifierClass = Class.forName(fqcn)
                    .asSubclass(HostnameVerifier.class);
            } catch (ClassNotFoundException | RuntimeException cnfe) {
                logger.log(Level.FINER,
                        "Calling class loader could not find: " + fqcn, cnfe);
            }
        }

        //Try system class loader
        if (verifierClass == null) {
            try {
                verifierClass = Class.forName(fqcn, false,
                        ClassLoader.getSystemClassLoader())
                    .asSubclass(HostnameVerifier.class);
            } catch (ClassNotFoundException | RuntimeException cnfe) {
                logger.log(Level.FINER,
                        "System class loader could not find: " + fqcn, cnfe);
            }
        }

        if (verifierClass != null) {
            return verifierClass.getConstructor().newInstance();
        }

        throw new ClassNotFoundException(fqcn);
   }

    /**
     * Does the server we're expecting to connect to match the
     * given name from the server's certificate?
     *
     * @param    server        name of the server expected
     * @param    name        name from the server's certificate
     */
    private static boolean matchServer(String server, String name) {
        if (logger.isLoggable(Level.FINER))
            logger.finer("match server " + server + " with " + name);
        if (name.startsWith("*.")) {
            // match "foo.example.com" with "*.example.com"
            String tail = name.substring(2);
            if (tail.length() == 0)
                return false;
            int off = server.length() - tail.length();
            if (off < 1)
                return false;
            // if tail matches and is preceeded by "."
            return server.charAt(off - 1) == '.' &&
                    server.regionMatches(true, off, tail, 0, tail.length());
        } else
            return server.equalsIgnoreCase(name);
    }

    /**
     * Use the HTTP CONNECT protocol to connect to a
     * site through an HTTP proxy server. <p>
     *
     * Protocol is roughly:
     * <pre>
     * CONNECT <host>:<port> HTTP/1.1
     * Host: <host>:<port>
     * <blank line>
     *
     * HTTP/1.1 200 Connect established
     * <headers>
     * <blank line>
     * </pre>
     */
    private static void proxyConnect(Socket socket,
                                     String proxyHost, int proxyPort,
                                     String proxyUser, String proxyPassword,
                                     String host, int port, int cto)
            throws IOException {
        if (logger.isLoggable(Level.FINE))
            logger.fine("connecting through proxy " +
                    proxyHost + ":" + proxyPort + " to " +
                    host + ":" + port);
        if (cto >= 0)
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), cto);
        else
            socket.connect(new InetSocketAddress(proxyHost, proxyPort));
        PrintStream os = new PrintStream(socket.getOutputStream(), false,
                StandardCharsets.UTF_8.name());
        StringBuilder requestBuilder = new StringBuilder();
        requestBuilder.append("CONNECT ").append(host).append(":").append(port).
                append(" HTTP/1.1\r\n");
        requestBuilder.append("Host: ").append(host).append(":").append(port).
                append("\r\n");
        if (proxyUser != null && proxyPassword != null) {
            byte[] upbytes = (proxyUser + ':' + proxyPassword).
                    getBytes(StandardCharsets.UTF_8);
            String proxyHeaderValue = new String(
                    Base64.getEncoder().encode(upbytes),
                    StandardCharsets.US_ASCII);
            requestBuilder.append("Proxy-Authorization: Basic ").
                    append(proxyHeaderValue).append("\r\n");
        }
        requestBuilder.append("Proxy-Connection: keep-alive\r\n\r\n");
        os.print(requestBuilder.toString());
        os.flush();
        StringBuilder errorLine = new StringBuilder();
        if (!readProxyResponse(socket.getInputStream(), errorLine)) {
            try {
                socket.close();
            } catch (IOException ioex) {
                // ignored
            }
            ConnectException ex = new ConnectException(
                    "connection through proxy " +
                            proxyHost + ":" + proxyPort + " to " +
                            host + ":" + port + " failed: " + errorLine.toString());
            logger.log(Level.FINE, "connect failed", ex);
            throw ex;
        }
    }

    static boolean readProxyResponse(InputStream input, StringBuilder errorLine) throws IOException {
        LineInputStream r = new LineInputStream(input, true);

        String line;
        boolean first = true;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0) {
                // End of HTTP response
                break;
            }
            logger.finest(line);
            if (first) {
                StringTokenizer st = new StringTokenizer(line);
                String http = st.nextToken();
                String code = st.nextToken();
                if (!code.equals("200")) {
                    errorLine.append(line);
                    return false;
                }
                first = false;
            }
        }
        return true;
    }

    /**
     * Parse a string into whitespace separated tokens
     * and return the tokens in an array.
     */
    private static String[] stringArray(String s) {
        StringTokenizer st = new StringTokenizer(s);
        List<String> tokens = new ArrayList<>();
        while (st.hasMoreTokens())
            tokens.add(st.nextToken());
        return tokens.toArray(new String[0]);
    }

    /**
     * Convenience method to get our context class loader.
     * Assert any privileges we might have and then call the
     * Thread.getContextClassLoader method.
     */
    private static ClassLoader getContextClassLoader() {
        return
                AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                    @Override
                    public ClassLoader run() {
                        ClassLoader cl = null;
                        try {
                            cl = Thread.currentThread().getContextClassLoader();
                        } catch (SecurityException ex) {
                        }
                        return cl;
                    }
                });
    }

    /**
     * Check the server from the Socket connection against the server name(s)
     * as expressed in the server certificate (RFC 2595 check).
     * We implement a crude version of the same checks ourselves.
     */
    private static final class MailHostnameVerifier implements HostnameVerifier {

        static HostnameVerifier of() {
            return new MailHostnameVerifier();
        }

        private MailHostnameVerifier() {
        }

        @Override
        public boolean verify(String server, SSLSession ssls) {
            X509Certificate cert = null;
            try {
                cert = getX509Certificate(ssls.getPeerCertificates());
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer("matchCert server "
                            + server + ", cert " + cert);
                }
                /*
                 * Check each of the subjectAltNames.
                 * XXX - only checks DNS names, should also handle
                 * case where server name is a literal IP address
                 */
                Collection<List<?>> names = cert.getSubjectAlternativeNames();
                if (names != null) {
                    boolean foundName = false;
                    for (Iterator<List<?>> it = names.iterator(); it.hasNext(); ) {
                        List<?> nameEnt = it.next();
                        Integer type = (Integer) nameEnt.get(0);
                        if (type.intValue() == 2) {    // 2 == dNSName
                            foundName = true;
                            String name = (String) nameEnt.get(1);
                            if (logger.isLoggable(Level.FINER))
                                logger.finer("found name: " + name);
                            if (matchServer(server, name))
                                return true;
                        }
                    }
                    if (foundName)    // found a name, but no match
                        return false;
                }
            } catch (CertificateParsingException ignore) {
                logger.log(Level.FINEST, server, ignore);
            } catch (IOException spue) {
                throw new UncheckedIOException(spue);
            }

            if (cert == null) {
                throw new UncheckedIOException(
                        new SSLPeerUnverifiedException("null"));
            }

            // XXX - following is a *very* crude parse of the name and ignores
            //	 all sorts of important issues such as quoting
            Pattern p = Pattern.compile("CN=([^,]*)");
            Matcher m = p.matcher(cert.getSubjectX500Principal().getName());
            if (m.find() && matchServer(server, m.group(1).trim()))
                return true;

            return false;
        }
    }

    /**
     * Check the server from the Socket connection against the server name(s)
     * as expressed in the server certificate (RFC 2595 check).  This is a
     * reflective adapter class for the sun.security.util.HostnameChecker,
     * which exists in Sun's JDK starting with 1.4.1.  Validation is using LDAPS
     * (RFC 2830) host name checking.
     *
     * This class will print --illegal-access=warn console warnings on JDK9
     * and may require: -add-opens 'java.base/sun.security.util=ALL-UNNAMED'
     * or --add-opens 'java.base/sun.security.util=jakarta.mail' depending on
     * how this class has been packaged.
     * It is preferred to set mail.<protocol>.ssl.endpointidentitycheck property
     * to 'LDAPS' instead of using this verifier.  This adapter will be removed
     * in a future release of Angus Mail when there is no reason to keep this
     * for compatibility sake.
     *
     * See: JDK-8062515 - Migrate use of sun.security.** to supported API
     */
    private static final class JdkHostnameChecker implements HostnameVerifier {
        private final HostnameVerifier or;

        static HostnameVerifier of() {
            return or((n, s) -> { return false; });
        }

        static HostnameVerifier or(HostnameVerifier or) {
            return new JdkHostnameChecker(or);
        }

        private JdkHostnameChecker(final HostnameVerifier or) {
            this.or = Objects.requireNonNull(or);
        }

        @Override
        public boolean verify(String server, SSLSession ssls) {
            try {
                X509Certificate cert = getX509Certificate(
                        ssls.getPeerCertificates());

                if (logger.isLoggable(Level.FINER)) {
                    logger.finer("matchCert server "
                            + server + ", cert " + cert);
                }

                Class<?> hnc = Class.forName("sun.security.util.HostnameChecker");
                // invoke HostnameChecker.getInstance(HostnameChecker.TYPE_LDAP)
                // HostnameChecker.TYPE_LDAP == 2
                // LDAP requires the same regex handling as we need
                Method getInstance = hnc.getMethod("getInstance",
                        byte.class);
                Object hostnameChecker = getInstance
                        .invoke((Object) null, (byte) 2);

                // invoke hostnameChecker.match( server, cert)
                logger.finer("using sun.security.util.HostnameChecker");
                Method match = hnc.getMethod("match",
                        String.class, X509Certificate.class);
                match.invoke(hostnameChecker, server, cert);
                return true;
            } catch (IOException | ReflectiveOperationException roe) {
                logger.log(Level.FINER, "HostnameChecker FAIL", roe);
                try {
                    if (or.verify(server, ssls)) {
                        return true;
                    }
                } catch (Throwable t) {
                    if (t != roe)
                        t.addSuppressed(roe);
                    throw t;
                }

                //Report real reason rather than just failing to verify
                Throwable cause = roe;
                if (roe instanceof InvocationTargetException)
                    cause = roe.getCause();
                if (cause == null)
                    cause = roe;
                if (cause instanceof RuntimeException)
                    throw (RuntimeException) cause;
                if (cause instanceof Error)
                    throw (Error) cause;

                String msg = "Failed then denied by " + this.toString();
                if (cause instanceof IOException)
                    throw new UncheckedIOException(msg, (IOException) cause);
                throw new UndeclaredThrowableException(cause, msg);
            }
        }

        @Override
        public String toString() {
            return "[" + getClass().getSimpleName() +", " + or + "]";
        }
    }
}
