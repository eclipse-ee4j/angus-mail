/*
 * Copyright (c) 1997, 2024 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026 Benny Bottema (ai assisted). All rights reserved.
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

package org.eclipse.angus.mail.util;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * Creates connections only for the explicitly selected duplex SMTP backend.
 * Legacy socket setup remains in SocketFetcher. Custom factories retain their
 * transport and TLS configuration, including the sequential TLS handoff.
 * Public only for use by the separate provider modules.
 */
public final class NioSocketFetcher {
    private static final MailLogger logger = new MailLogger(
            NioSocketFetcher.class, "socket", "DEBUG NioSocketFetcher",
            PropUtil.getBooleanSystemProperty("mail.socket.debug", false), System.out);

    private NioSocketFetcher() {
    }

    /**
     * Opens a connection using duplex I/O when the physical socket and TLS
     * factory permit it. An explicit engine failure never changes trust.
     *
     * @param host destination host
     * @param port destination port
     * @param props connection properties
     * @param prefix property prefix
     * @param useSSL whether to establish implicit TLS
     * @return the connected socket
     * @exception IOException for connection or TLS failure
     */
    public static Socket getSocket(String host, int port, Properties props,
                                   String prefix, boolean useSSL) throws IOException {

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
        SocketFactory transportFactory = SocketFetcher.getTransportSocketFactory(props, prefix);
        boolean transportAttempted = false;

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
                    sf = SocketFetcher.getSocketFactory(sfClass);
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
                    sf = SocketFetcher.getSocketFactory(sfClass);
                    sfErr = "socket factory class " + sfClass;
                }
                sfPortName = ".socketFactory.port";
            }

            // if we now have a socket factory, use it
            if (sf != null || transportFactory != null) {
                if (sf != null)
                    sfPort = PropUtil.getIntProperty(props,
                            prefix + sfPortName, -1);

                // if port passed in via property isn't valid, use param
                if (sfPort == -1)
                    sfPort = port;
                if (transportFactory != null) {
                    transportAttempted = true;
                    socket = createSocketWithTransportFactory(localaddr, localport,
                            host, sfPort, cto, to, props, prefix, sf, useSSL,
                            true, transportFactory);
                } else {
                    socket = createSocket(localaddr, localport,
                            host, sfPort, cto, to, props, prefix, sf, useSSL,
                            true, null);
                }
            }
        } catch (EngineSetupException ex) {
            throw ex.failure;
        } catch (SocketTimeoutException sex) {
            throw sex;
        } catch (Exception ex) {
            if (!fb || transportAttempted) {
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
            try {
                if (transportFactory != null)
                    socket = createSocketWithTransportFactory(localaddr, localport,
                            host, port, cto, to, props, prefix, null, useSSL,
                            true, transportFactory);
                else
                    socket = createSocket(localaddr, localport,
                            host, port, cto, to, props, prefix, null, useSSL,
                            true, null);
            } catch (EngineSetupException ex) {
                throw ex.failure;
            }

        } else {
            if (to >= 0 && transportFactory == null) {
                if (logger.isLoggable(Level.FINEST))
                    logger.finest("set socket read timeout " + to);
                socket.setSoTimeout(to);
            }
        }

        return socket;
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
                                       SocketFactory sf, boolean useSSL,
                                       boolean useEngine,
                                       TransportSocketResources transportResources)
            throws IOException {
        Socket socket = transportResources == null ? null : transportResources.physical;

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
        } else if (transportResources == null && (socksHost =
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

        if (socket == null && sf != null && !(sf instanceof SSLSocketFactory))
            socket = sf.createSocket();
        if (socket == null) {
            if (socksHost != null) {
                socket = new Socket(
                        new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                                new InetSocketAddress(socksHost, socksPort)));
            } else if (useEngine || PropUtil.getBooleanProperty(props,
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
            if (transportResources != null)
                transportResources.writeTimeout = socket;
        }
        if (localaddr != null)
            socket.bind(new InetSocketAddress(localaddr, localport));
        try {
            logger.finest("connecting...");
            if (proxyHost != null)
                SocketFetcher.proxyConnect(socket, proxyHost, proxyPort,
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
            // ssl.trust only applies if no SSL socket factory instance was
            // specified; a socket factory class name, such as
            // javax.net.ssl.SSLSocketFactory, usually gives the default factory
            if (SocketFetcher.isSSLSocketFactoryInstance(sf, props, prefix))
                ssf = (SSLSocketFactory) sf;
            else if ((trusted = props.getProperty(prefix + ".ssl.trust")) != null) {
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
            if (useEngine && socket.getChannel() != null) {
                boolean explicitEngine = props.get(prefix + ".ssl.engineFactory") != null;
                DuplexSocket duplex = null;
                try {
                    SSLEngine engine = createSSLEngine(ssf, host, port, props, prefix);
                    if (engine != null) {
                        duplex = new DuplexSocket(socket, writeTimeout);
                        if (transportResources != null)
                            transportResources.layered = duplex;
                        configureSSLEngine(duplex, engine, host, props, prefix);
                        return duplex;
                    }
                } catch (IOException | RuntimeException | LinkageError ex) {
                    IOException failure = ex instanceof IOException ? (IOException) ex :
                            new IOException("Could not set up SSL engine for: " + host, ex);
                    if (duplex == null)
                        SocketFetcher.cleanupAndThrow(socket, failure);
                    else
                        duplex.closeOnFailure(failure);
                    if (explicitEngine && !(failure instanceof SocketTimeoutException))
                        throw new EngineSetupException(failure);
                    throw failure;
                }
            }
            socket = ssf.createSocket(socket, host, port, true);
            if (transportResources != null)
                transportResources.layered = socket;
            sf = ssf;
        }

        /*
         * No matter how we created the socket, if it turns out to be an
         * SSLSocket, configure it.
         */
        SocketFetcher.configureSSLSocket(socket, host, props, prefix, sf);

        if (useEngine && !(socket instanceof SSLSocket) &&
                socket.getChannel() != null) {
            socket = new DuplexSocket(socket, writeTimeout);
            if (transportResources != null)
                transportResources.layered = socket;
        }

        return socket;
    }

    /**
     * Own the supplied transport while the normal connection and TLS pipeline
     * runs.  An unsuccessful attempt must not leave it open or create a second
     * transport through fallback.
     */
    private static Socket createSocketWithTransportFactory(InetAddress localaddr,
            int localport, String host, int port, int cto, int to,
            Properties props, String prefix, SocketFactory sf, boolean useSSL,
            boolean useEngine, SocketFactory transportFactory) throws IOException {
        TransportSocketResources resources = new TransportSocketResources();
        try {
            Socket transportSocket = transportFactory.createSocket();
            resources.physical = transportSocket;
            if (transportSocket == null || transportSocket.isClosed() ||
                    transportSocket.isConnected() || transportSocket instanceof SSLSocket)
                throw new IOException(prefix + ".socketFactory.transport must return " +
                        "an open, unconnected, non-SSL socket");
            Socket socket = createSocket(localaddr, localport, host, port, cto,
                    to, props, prefix, sf, useSSL, useEngine, resources);
            if (socket == null)
                throw new IOException("Socket factory returned no socket");
            // A TLS factory can change the timeout while wrapping the transport.
            if (to >= 0)
                socket.setSoTimeout(to);
            return socket;
        } catch (EngineSetupException ex) {
            resources.closeAfterFailure(ex.failure);
            throw ex;
        } catch (IOException | RuntimeException | Error failure) {
            resources.closeAfterFailure(failure);
            throw failure;
        }
    }

    /** Retain layers whose own resources must retire even after the TCP socket closes. */
    private static final class TransportSocketResources {
        private Socket physical;
        private Socket writeTimeout;
        private Socket layered;

        private void closeAfterFailure(Throwable failure) {
            // This closes the plain transport and retires an internally owned
            // timer, without shutting down a caller-supplied scheduler.
            close(writeTimeout, false, failure);
            close(physical, true, failure);
            // Dispose of transport resources before the outer TLS wrapper.
            // Native TLS setup may already have closed it on a setup failure.
            if (layered != physical && layered != writeTimeout)
                close(layered, false, failure);
        }

        private static void close(Socket socket, boolean checkClosed,
                                  Throwable failure) {
            if (socket == null)
                return;
            try {
                if (!checkClosed || !socket.isClosed())
                    socket.close();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure)
                    failure.addSuppressed(cleanup);
            }
        }
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
                sf = SocketFetcher.getSocketFactory(sfClass);
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
                    sf = SocketFetcher.getSocketFactory(sfClass);
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

            if (socket instanceof DuplexSocket) {
                DuplexSocket duplex = (DuplexSocket) socket;
                try {
                    SSLEngine engine = createSSLEngine(ssf, host, port, props, prefix);
                    if (engine != null) {
                        configureSSLEngine(duplex, engine, host, props, prefix);
                        return duplex;
                    }
                } catch (IOException | RuntimeException | LinkageError ex) {
                    IOException failure = ex instanceof IOException ? (IOException) ex :
                            new IOException("Could not set up SSL engine for: " + host, ex);
                    throw duplex.closeOnFailure(failure);
                }
                socket = duplex.release();
            }
            socket = ssf.createSocket(socket, host, port, true);
            SocketFetcher.configureSSLSocket(socket, host, props, prefix, ssf);
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

    /** Resolves an engine without guessing a custom socket factory's trust. */
    private static SSLEngine createSSLEngine(SSLSocketFactory factory,
            String host, int port, Properties props, String prefix)
            throws IOException {
        Object hook = props.get(prefix + ".ssl.engineFactory");
        if (hook != null) {
            try {
                SSLEngine engine = EngineFactoryHook.create(hook, host, port, prefix);
                if (engine == null)
                    throw new IOException("SSL engine factory returned null");
                if (!host.equalsIgnoreCase(engine.getPeerHost()) ||
                        port != engine.getPeerPort())
                    throw new SSLException("SSL engine factory returned a different peer");
                return engine;
            } catch (RuntimeException | LinkageError ex) {
                throw new IOException("Could not create SSL engine", ex);
            }
        }
        if (factory.getClass() == MailSSLSocketFactory.class)
            return ((MailSSLSocketFactory) factory).createSSLEngine(host, port);
        // There is no portable way to recover the context behind an arbitrary
        // socket factory. Never substitute default trust or client credentials.
        if (props.get(prefix + ".ssl.socketFactory") != null ||
                props.getProperty(prefix + ".ssl.socketFactory.class") != null ||
                props.get(prefix + ".socketFactory") instanceof SSLSocketFactory ||
                props.getProperty(prefix + ".socketFactory.class") != null ||
                Security.getProperty("ssl.SocketFactory.provider") != null)
            return null;
        try {
            return SSLContext.getDefault().createSSLEngine(host, port);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not create default SSL engine", ex);
        }
    }

    /** Keeps the optional hook's Java function type out of ordinary setup. */
    private static final class EngineFactoryHook {
        @SuppressWarnings("unchecked")
        static SSLEngine create(Object hook, String host, int port, String prefix)
                throws IOException {
            if (!(hook instanceof BiFunction))
                throw new IOException("Invalid SSL engine factory: " + prefix);
            return ((BiFunction<String, Integer, SSLEngine>) hook).apply(host, port);
        }
    }

    /** Prevents legacy socket-factory fallback after an explicit engine fails. */
    private static final class EngineSetupException extends IOException {
        private static final long serialVersionUID = 1L;
        final IOException failure;

        EngineSetupException(IOException failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }
    }

    /** Applies the same TLS policy as configureSSLSocket to a channel engine. */
    private static void configureSSLEngine(DuplexSocket socket, SSLEngine engine,
            String host, Properties props, String prefix) throws IOException {
        try {
            engine.setUseClientMode(true);
            String protocols = props.getProperty(prefix + ".ssl.protocols");
            if (protocols != null)
                engine.setEnabledProtocols(SocketFetcher.stringArray(protocols));
            else {
                List<String> enabled = new ArrayList<>();
                for (String protocol : engine.getEnabledProtocols()) {
                    if (protocol != null && !protocol.startsWith("SSL"))
                        enabled.add(protocol);
                }
                engine.setEnabledProtocols(enabled.toArray(new String[0]));
            }
            String ciphers = props.getProperty(prefix + ".ssl.ciphersuites");
            if (ciphers != null)
                engine.setEnabledCipherSuites(SocketFetcher.stringArray(ciphers));
            SSLParameters parameters = engine.getSSLParameters();
            String identityAlgorithm =
                    SocketFetcher.endpointIdentificationAlgorithm(props, prefix, engine.getClass());
            parameters.setEndpointIdentificationAlgorithm(identityAlgorithm);
            engine.setSSLParameters(parameters);
            boolean needsVerifier = identityAlgorithm != null && !identityAlgorithm.equals(
                    engine.getSSLParameters().getEndpointIdentificationAlgorithm());
            HostnameVerifier verifier = null;
            if (needsVerifier) {
                verifier = SocketFetcher.getHostnameVerifier(props, prefix);
                if (verifier == null)
                    throw new SSLException("SSL engine does not retain server identity checking; " +
                            "configure " + prefix + ".ssl.hostnameverifier");
            }
            socket.startTLS(engine);
            if (identityAlgorithm != null)
                SocketFetcher.getMailHostnameVerifier().verify(host, engine.getSession());
            if (!needsVerifier)
                verifier = SocketFetcher.getHostnameVerifier(props, prefix);
            if (verifier != null && !verifier.verify(host, engine.getSession()))
                throw new SSLException("Server identity does not match " +
                        "authentication scheme: " + verifier + " DENY");
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError ex) {
            IOException failure = ex instanceof IOException ? (IOException) ex :
                    new IOException("Could not configure SSL engine for: " + host, ex);
            throw failure;
        }
    }
}
