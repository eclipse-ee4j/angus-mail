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
import javax.net.ssl.SSLPeerUnverifiedException;
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
            props = new Properties();

        int connectionTimeout = PropUtil.getIntProperty(props,
                prefix + ".connectiontimeout", -1);
        int readTimeout = PropUtil.getIntProperty(props, prefix + ".timeout", -1);
        InetAddress localAddress = resolveLocalAddress(props, prefix);
        int localPort = PropUtil.getIntProperty(props, prefix + ".localport", 0);
        boolean fallbackAllowed = PropUtil.getBooleanProperty(props,
                prefix + ".socketFactory.fallback", true);
        SocketFactory transportFactory = SocketFetcher.getTransportSocketFactory(props, prefix);
        SocketFactorySelection selection = new SocketFactorySelection();
        Socket socket = null;
        boolean transportAttempted = false;
        try {
            selection.selectForConnection(props, prefix, useSSL);
            if (selection.factory != null || transportFactory != null) {
                selection.selectPort(props, prefix, port);
                transportAttempted = transportFactory != null;
                socket = openSocket(localAddress, localPort, host, selection.port,
                        connectionTimeout, readTimeout, props, prefix,
                        selection.factory, useSSL, transportFactory);
            }
        } catch (EngineSetupException ex) {
            throw ex.failure;
        } catch (SocketTimeoutException ex) {
            throw ex;
        } catch (Exception ex) {
            if (!fallbackAllowed || transportAttempted)
                throw socketFactoryFailure(ex, "Using " + selection.description,
                        host, selection.port, connectionTimeout);
        }

        if (socket == null) {
            try {
                socket = openSocket(localAddress, localPort, host, port,
                        connectionTimeout, readTimeout, props, prefix,
                        null, useSSL, transportFactory);
            } catch (EngineSetupException ex) {
                throw ex.failure;
            }
        } else if (readTimeout >= 0 && transportFactory == null) {
            // A configured factory can change the timeout while layering TLS.
            setReadTimeout(socket, readTimeout);
        }
        return socket;
    }

    private static InetAddress resolveLocalAddress(Properties props, String prefix)
            throws IOException {
        String address = props.getProperty(prefix + ".localaddress", null);
        return address == null ? null : InetAddress.getByName(address);
    }

    private static void setReadTimeout(Socket socket, int timeout)
            throws IOException {
        if (logger.isLoggable(Level.FINEST))
            logger.finest("set socket read timeout " + timeout);
        socket.setSoTimeout(timeout);
    }

    private static IOException socketFactoryFailure(Exception failure,
            String description, String host, int port, int timeout) {
        if (failure instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) failure).getTargetException();
            if (cause instanceof Exception)
                failure = (Exception) cause;
        }
        if (failure instanceof IOException)
            return (IOException) failure;
        return new SocketConnectException(description, failure, host, port, timeout);
    }

    /** Retains lookup context even when reflective factory construction fails. */
    private static final class SocketFactorySelection {
        private SocketFactory factory;
        private String description = "unknown socket factory";
        private String portProperty;
        private int port = -1;

        void selectForConnection(Properties props, String prefix, boolean useSSL)
                throws ReflectiveOperationException {
            if (useSSL) {
                selectConfiguredFactory(props, prefix + ".ssl.socketFactory",
                        "SSL socket factory");
                portProperty = ".ssl.socketFactory.port";
            }
            if (factory == null) {
                selectConfiguredFactory(props, prefix + ".socketFactory",
                        "socket factory");
                portProperty = ".socketFactory.port";
            }
        }

        void selectPort(Properties props, String prefix, int requestedPort) {
            if (factory != null)
                port = PropUtil.getIntProperty(props, prefix + portProperty, -1);
            if (port == -1)
                port = requestedPort;
        }

        SSLSocketFactory selectForStartTLS(Properties props, String prefix)
                throws ReflectiveOperationException, IOException {
            selectConfiguredFactory(props, prefix + ".ssl.socketFactory",
                    "SSL socket factory");
            if (!(factory instanceof SSLSocketFactory))
                selectConfiguredFactory(props, prefix + ".socketFactory",
                        "socket factory");
            if (!(factory instanceof SSLSocketFactory)) {
                String trusted = props.getProperty(prefix + ".ssl.trust");
                if (trusted != null) {
                    factory = createTrustedSocketFactory(trusted);
                    description = "mail SSL socket factory";
                } else {
                    factory = SSLSocketFactory.getDefault();
                    description = "default SSL socket factory";
                }
            }
            return (SSLSocketFactory) factory;
        }

        private void selectConfiguredFactory(Properties props, String key,
                String label) throws ReflectiveOperationException {
            Object candidate = props.get(key);
            if (candidate instanceof SocketFactory) {
                factory = (SocketFactory) candidate;
                description = label + " instance " + factory;
            }
            if (factory == null) {
                String factoryClass = props.getProperty(key + ".class");
                factory = SocketFetcher.getSocketFactory(factoryClass);
                description = label + " class " + factoryClass;
            }
        }
    }

    private static Socket openSocket(InetAddress localAddress, int localPort,
            String host, int port, int connectionTimeout, int readTimeout,
            Properties props, String prefix, SocketFactory factory, boolean useSSL,
            SocketFactory transportFactory) throws IOException {
        if (transportFactory != null)
            return createSocketWithTransportFactory(localAddress, localPort,
                    host, port, connectionTimeout, readTimeout, props, prefix,
                    factory, useSSL, transportFactory);
        return createSocket(localAddress, localPort, host, port, connectionTimeout,
                readTimeout, props, prefix, factory, useSSL, null);
    }

    private static Socket createSocket(InetAddress localAddress, int localPort,
            String host, int port, int connectionTimeout, int readTimeout,
            Properties props, String prefix, SocketFactory factory, boolean useSSL,
            TransportSocketResources resources) throws IOException {
        if (logger.isLoggable(Level.FINEST))
            logger.finest("create socket: prefix " + prefix +
                    ", localaddr " + localAddress + ", localport " + localPort +
                    ", host " + host + ", port " + port +
                    ", connection timeout " + connectionTimeout + ", timeout " + readTimeout +
                    ", socket factory " + factory + ", useSSL " + useSSL);

        SocketRoute route = new SocketRoute(props, prefix, resources == null);
        Socket socket = createTransportSocket(factory, route, resources);
        int writeTimeout = PropUtil.getIntProperty(props, prefix + ".writetimeout", -1);
        socket = configureTimeouts(socket, readTimeout, writeTimeout, props, prefix, resources);
        if (localAddress != null)
            socket.bind(new InetSocketAddress(localAddress, localPort));
        route.connect(socket, host, port, connectionTimeout);
        return configureConnectedSocket(socket, host, port, writeTimeout,
                props, prefix, factory, useSSL, resources);
    }

    private static Socket createTransportSocket(SocketFactory factory,
            SocketRoute route, TransportSocketResources resources) throws IOException {
        Socket socket = resources == null ? null : resources.physical;
        if (socket == null && factory != null && !(factory instanceof SSLSocketFactory))
            socket = factory.createSocket();
        return socket == null ? route.createSocket() : socket;
    }

    private static Socket configureTimeouts(Socket socket, int readTimeout,
            int writeTimeout, Properties props, String prefix,
            TransportSocketResources resources) throws IOException {
        if (readTimeout >= 0)
            setReadTimeout(socket, readTimeout);
        if (writeTimeout != -1) {
            if (logger.isLoggable(Level.FINEST))
                logger.finest("set socket write timeout " + writeTimeout);
            ScheduledExecutorService executor = PropUtil.getScheduledExecutorServiceProperty(props,
                    prefix + ".executor.writetimeout");
            socket = executor == null ? new WriteTimeoutSocket(socket, writeTimeout) :
                    new WriteTimeoutSocket(socket, writeTimeout, executor);
            if (resources != null)
                resources.writeTimeout = socket;
        }
        return socket;
    }

    /** Keeps route selection and proxy diagnostics out of connection orchestration. */
    private static final class SocketRoute {
        private String proxyHost;
        private String proxyUser;
        private String proxyPassword;
        private int proxyPort = 80;
        private String socksHost;
        private int socksPort = 1080;
        private String description;

        SocketRoute(Properties props, String prefix, boolean allowSocks) {
            proxyHost = props.getProperty(prefix + ".proxy.host", null);
            proxyUser = props.getProperty(prefix + ".proxy.user", null);
            proxyPassword = props.getProperty(prefix + ".proxy.password", null);
            if (proxyHost != null)
                configureHttpProxy(props, prefix);
            else if (allowSocks) {
                socksHost = props.getProperty(prefix + ".socks.host", null);
                if (socksHost != null)
                    configureSocksProxy(props, prefix);
            }
        }

        private void configureHttpProxy(Properties props, String prefix) {
            int separator = proxyHost.indexOf(':');
            if (separator >= 0) {
                try {
                    proxyPort = Integer.parseInt(proxyHost.substring(separator + 1));
                } catch (NumberFormatException ex) {
                    // Keep the default for a malformed inline port.
                }
                proxyHost = proxyHost.substring(0, separator);
            }
            proxyPort = PropUtil.getIntProperty(props, prefix + ".proxy.port", proxyPort);
            description = "Using web proxy host, port: " + proxyHost + ", " + proxyPort;
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("web proxy host " + proxyHost + ", port " + proxyPort);
                if (proxyUser != null)
                    logger.finer("web proxy user " + proxyUser + ", password " +
                            (proxyPassword == null ? "<null>" : "<non-null>"));
            }
        }

        private void configureSocksProxy(Properties props, String prefix) {
            int separator = socksHost.indexOf(':');
            if (separator >= 0) {
                try {
                    socksPort = Integer.parseInt(socksHost.substring(separator + 1));
                } catch (NumberFormatException ex) {
                    // Keep the default for a malformed inline port.
                }
                socksHost = socksHost.substring(0, separator);
            }
            socksPort = PropUtil.getIntProperty(props, prefix + ".socks.port", socksPort);
            description = "Using SOCKS host, port: " + socksHost + ", " + socksPort;
            if (logger.isLoggable(Level.FINER))
                logger.finer("socks host " + socksHost + ", port " + socksPort);
        }

        Socket createSocket() throws IOException {
            if (socksHost != null)
                return new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new InetSocketAddress(socksHost, socksPort)));
            logger.finer("using SocketChannels");
            return SocketChannel.open().socket();
        }

        void connect(Socket socket, String host, int port, int timeout) throws IOException {
            try {
                logger.finest("connecting...");
                if (proxyHost != null)
                    SocketFetcher.proxyConnect(socket, proxyHost, proxyPort,
                            proxyUser, proxyPassword, host, port, timeout);
                else if (timeout >= 0)
                    socket.connect(new InetSocketAddress(host, port), timeout);
                else
                    socket.connect(new InetSocketAddress(host, port));
                logger.finest("success!");
            } catch (IOException ex) {
                logger.log(Level.FINEST, "connection failed", ex);
                throw new SocketConnectException(description, ex, host, port, timeout);
            }
        }
    }

    private static Socket configureConnectedSocket(Socket socket, String host,
            int port, int writeTimeout, Properties props, String prefix,
            SocketFactory factory, boolean useSSL, TransportSocketResources resources)
            throws IOException {
        if ((useSSL || factory instanceof SSLSocketFactory) &&
                !(socket instanceof SSLSocket)) {
            SSLSocketFactory sslFactory = selectTlsSocketFactory(factory, props, prefix);
            Socket duplex = tryStartTlsEngine(socket, sslFactory, host, port,
                    writeTimeout, props, prefix, resources);
            if (duplex != null)
                return duplex;
            socket = sslFactory.createSocket(socket, host, port, true);
            if (resources != null)
                resources.layered = socket;
            factory = sslFactory;
        }
        SocketFetcher.configureSSLSocket(socket, host, props, prefix, factory);
        if (!(socket instanceof SSLSocket) && socket.getChannel() != null) {
            socket = new DuplexSocket(socket, writeTimeout, port);
            if (resources != null)
                resources.layered = socket;
        }
        return socket;
    }

    private static SSLSocketFactory selectTlsSocketFactory(SocketFactory factory,
            Properties props, String prefix) throws IOException {
        if (SocketFetcher.isSSLSocketFactoryInstance(factory, props, prefix))
            return (SSLSocketFactory) factory;
        String trusted = props.getProperty(prefix + ".ssl.trust");
        if (trusted != null)
            return createTrustedSocketFactory(trusted);
        return factory instanceof SSLSocketFactory ? (SSLSocketFactory) factory :
                (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    private static SSLSocketFactory createTrustedSocketFactory(String trusted)
            throws IOException {
        try {
            MailSSLSocketFactory factory = new MailSSLSocketFactory();
            if (trusted.equals("*"))
                factory.setTrustAllHosts(true);
            else
                factory.setTrustedHosts(trusted.split("\\s+"));
            return factory;
        } catch (GeneralSecurityException ex) {
            IOException failure = new IOException("Can't create MailSSLSocketFactory");
            failure.initCause(ex);
            throw failure;
        }
    }

    private static Socket tryStartTlsEngine(Socket socket, SSLSocketFactory factory,
            String host, int port, int writeTimeout, Properties props, String prefix,
            TransportSocketResources resources) throws IOException {
        SocketChannel channel = socket.getChannel();
        if (channel == null)
            return null;
        boolean explicitEngine = props.get(prefix + ".ssl.engineFactory") != null;
        DuplexSocket duplex = null;
        try {
            SSLEngine engine = createSSLEngine(factory, host, port, props, prefix);
            if (engine == null)
                return null;
            duplex = new DuplexSocket(socket, writeTimeout, port);
            if (resources != null)
                resources.layered = duplex;
            configureSSLEngine(duplex, engine, host, props, prefix);
            return duplex;
        } catch (IOException | RuntimeException | LinkageError ex) {
            IOException failure = ex instanceof IOException ? (IOException) ex :
                    new IOException("Could not set up SSL engine for: " + host, ex);
            if (duplex == null)
                closeFailedSocket(socket, channel, failure);
            else
                duplex.closeOnFailure(failure);
            if (explicitEngine && !(failure instanceof SocketTimeoutException))
                throw new EngineSetupException(failure);
            throw failure;
        } catch (Error failure) {
            if (duplex == null)
                closeFailedSocket(socket, channel, failure);
            else
                duplex.abort(failure);
            throw failure;
        }
    }

    /**
     * Own the supplied transport while the normal connection and TLS pipeline
     * runs.  An unsuccessful attempt must not leave it open or create a second
     * transport through fallback.
     */
    private static Socket createSocketWithTransportFactory(InetAddress localaddr,
            int localport, String host, int port, int cto, int to,
            Properties props, String prefix, SocketFactory sf, boolean useSSL,
            SocketFactory transportFactory) throws IOException {
        TransportSocketResources resources = new TransportSocketResources();
        try {
            Socket transportSocket = transportFactory.createSocket();
            resources.physical = transportSocket;
            if (transportSocket == null || transportSocket.isClosed() ||
                    transportSocket.isConnected() || transportSocket instanceof SSLSocket)
                throw new IOException(prefix + ".socketFactory.transport must return " +
                        "an open, unconnected, non-SSL socket");
            Socket socket = createSocket(localaddr, localport, host, port, cto,
                    to, props, prefix, sf, useSSL, resources);
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
        SocketFactorySelection selection = new SocketFactorySelection();
        try {
            SSLSocketFactory factory = selection.selectForStartTLS(props, prefix);
            return upgradeToTls(socket, factory, host, port, props, prefix);
        } catch (Exception ex) {
            if (ex instanceof InvocationTargetException) {
                Throwable cause = ((InvocationTargetException) ex).getTargetException();
                if (cause instanceof Exception)
                    ex = (Exception) cause;
            }
            if (ex instanceof IOException)
                throw (IOException) ex;
            IOException failure = new IOException(
                    "Exception in startTLS using " + selection.description +
                            ": host, port: " + host + ", " + port + "; Exception: " + ex);
            failure.initCause(ex);
            throw failure;
        }
    }

    private static Socket upgradeToTls(Socket socket, SSLSocketFactory factory,
            String host, int port, Properties props, String prefix) throws IOException {
        if (socket instanceof DuplexSocket) {
            DuplexSocket duplex = (DuplexSocket) socket;
            try {
                SSLEngine engine = createSSLEngine(factory, host,
                        duplex.getPeerPort(), props, prefix);
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
        socket = factory.createSocket(socket, host, port, true);
        SocketFetcher.configureSSLSocket(socket, host, props, prefix, factory);
        return socket;
    }

    private static void closeFailedSocket(Socket socket, SocketChannel channel,
            Throwable failure) {
        try (SocketChannel physical = channel) {
            socket.close();
        } catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup instanceof Error && !(cleanup instanceof LinkageError)) {
                if (cleanup != failure)
                    cleanup.addSuppressed(failure);
                throw (Error) cleanup;
            }
            if (cleanup != failure)
                failure.addSuppressed(cleanup);
        }
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
                checkServerIdentity(SocketFetcher.getMailHostnameVerifier(), host, engine);
            if (!needsVerifier)
                verifier = SocketFetcher.getHostnameVerifier(props, prefix);
            if (verifier != null)
                checkServerIdentity(verifier, host, engine);
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError ex) {
            IOException failure = ex instanceof IOException ? (IOException) ex :
                    new IOException("Could not configure SSL engine for: " + host, ex);
            throw failure;
        }
    }

    private static void checkServerIdentity(HostnameVerifier verifier,
            String host, SSLEngine engine) throws SSLPeerUnverifiedException {
        if (!verifier.verify(host, engine.getSession()))
            throw new SSLPeerUnverifiedException("Server identity does not match " +
                    "authentication scheme: " + verifier + " DENY");
    }
}
