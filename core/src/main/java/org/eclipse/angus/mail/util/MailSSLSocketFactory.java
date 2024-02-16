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

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An SSL socket factory that makes it easier to specify trust.
 * This socket factory can be configured to trust all hosts or
 * trust a specific set of hosts, in which case the server's
 * certificate isn't verified.  Alternatively, a custom TrustManager
 * can be supplied. <p>
 *
 * An instance of this factory can be set as the value of the
 * <code>mail.&lt;protocol&gt;.ssl.socketFactory</code> property.
 *
 * @since JavaMail 1.4.2
 * @author Stephan Sann
 * @author Bill Shannon
 */
public class MailSSLSocketFactory extends SSLSocketFactory {

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Should all hosts be trusted?
     */
    private boolean trustAllHosts;

    /**
     * String-array of trusted hosts
     */
    private String[] trustedHosts = null;

    /**
     * Holds a SSLContext to get SSLSocketFactories from
     */
    private SSLContext sslcontext;

    /**
     * Holds the KeyManager array to use
     */
    private KeyManager[] keyManagers;

    /**
     * Holds the TrustManager array to use
     */
    private TrustManager[] trustManagers;

    /**
     * Holds the SecureRandom to use
     */
    private SecureRandom secureRandom;

    /**
     * Holds a SSLSocketFactory to pass all API-method-calls to
     */
    private SSLSocketFactory adapteeFactory = null;

    /**
     * Initializes a new MailSSLSocketFactory.
     *
     * @throws GeneralSecurityException for security errors
     */
    public MailSSLSocketFactory() throws GeneralSecurityException {
        this("TLS");
    }

    /**
     * Initializes a new MailSSLSocketFactory with a given protocol.
     * Normally the protocol will be specified as "TLS".
     *
     * @param protocol The protocol to use
     * @throws NoSuchAlgorithmException if given protocol is not supported
     * @throws GeneralSecurityException for security errors
     */
    public MailSSLSocketFactory(String protocol)
            throws GeneralSecurityException {

        // By default we do NOT trust all hosts.
        trustAllHosts = false;

        // Get an instance of an SSLContext.
        sslcontext = SSLContext.getInstance(protocol);

        // Default properties to init the SSLContext
        keyManagers = null;
        trustManagers = new TrustManager[]{new MailTrustManager()};
        secureRandom = null;

        // Assemble a default SSLSocketFactory to delegate all API-calls to.
        newAdapteeFactory();
    }


    /**
     * Gets an SSLSocketFactory based on the given (or default)
     * KeyManager array, TrustManager array and SecureRandom and
     * sets it to the instance var adapteeFactory.
     *
     * @throws KeyManagementException for key manager errors
     */
    private void newAdapteeFactory()
            throws KeyManagementException {
        lock.lock();
        try {
            sslcontext.init(keyManagers, trustManagers, secureRandom);
    
            // Get SocketFactory and save it in our instance var
            adapteeFactory = sslcontext.getSocketFactory();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the keyManagers
     */
    public KeyManager[] getKeyManagers() {
        lock.lock();
        try {
            return keyManagers.clone();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param keyManagers the keyManagers to set
     * @throws GeneralSecurityException for security errors
     */
    public void setKeyManagers(KeyManager... keyManagers)
            throws GeneralSecurityException {
        lock.lock();
        try {
            this.keyManagers = keyManagers.clone();
            newAdapteeFactory();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the secureRandom
     */
    public SecureRandom getSecureRandom() {
        lock.lock();
        try {
            return secureRandom;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param secureRandom the secureRandom to set
     * @throws GeneralSecurityException for security errors
     */
    public void setSecureRandom(SecureRandom secureRandom)
            throws GeneralSecurityException {
        lock.lock();
        try {
            this.secureRandom = secureRandom;
            newAdapteeFactory();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the trustManagers
     */
    public TrustManager[] getTrustManagers() {
        lock.lock();
        try {
            return trustManagers;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param trustManagers the trustManagers to set
     * @throws GeneralSecurityException for security errors
     */
    public void setTrustManagers(TrustManager... trustManagers)
            throws GeneralSecurityException {
        lock.lock();
        try {
            this.trustManagers = trustManagers;
            newAdapteeFactory();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return true if all hosts should be trusted
     */
    public boolean isTrustAllHosts() {
        lock.lock();
        try {
            return trustAllHosts;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param    trustAllHosts should all hosts be trusted?
     */
    public void setTrustAllHosts(boolean trustAllHosts) {
        lock.lock();
        try {
            this.trustAllHosts = trustAllHosts;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the trusted hosts
     */
    public String[] getTrustedHosts() {
        lock.lock();
        try {
            if (trustedHosts == null)
                return null;
            else
                return trustedHosts.clone();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param    trustedHosts the hosts to trust
     */
    public void setTrustedHosts(String... trustedHosts) {
        lock.lock();
        try {
            if (trustedHosts == null)
                this.trustedHosts = null;
            else
                this.trustedHosts = trustedHosts.clone();
        } finally {
            lock.unlock();
        }
    }

    /**
     * After a successful conection to the server, this method is
     * called to ensure that the server should be trusted.
     *
     * @param sslSocket SSLSocket connected to the server
     * @return true  if "trustAllHosts" is set to true OR the server
     * is contained in the "trustedHosts" array;
     * @param    server        name of the server we connected to
     */
    public boolean isServerTrusted(String server,
                                                SSLSocket sslSocket) {
        lock.lock();
        try {
            //System.out.println("DEBUG: isServerTrusted host " + server);

            // If "trustAllHosts" is set to true, we return true
            if (trustAllHosts)
                return true;

            // If the socket host is contained in the "trustedHosts" array,
            // we return true
            if (trustedHosts != null)
                return Arrays.asList(trustedHosts).contains(server); // ignore case?

            // If we get here, trust of the server was verified by the trust manager
            return true;
        } finally {
            lock.unlock();
        }
    }


    // SocketFactory methods

    /* (non-Javadoc)
     * @see javax.net.ssl.SSLSocketFactory#createSocket(java.net.Socket,
     *						java.lang.String, int, boolean)
     */
    @Override
    public Socket createSocket(Socket socket, String s, int i,
                                            boolean flag) throws IOException {
        lock.lock();
        try {
            return adapteeFactory.createSocket(socket, s, i, flag);
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.ssl.SSLSocketFactory#getDefaultCipherSuites()
     */
    @Override
    public String[] getDefaultCipherSuites() {
        lock.lock();
        try {
            return adapteeFactory.getDefaultCipherSuites();
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.ssl.SSLSocketFactory#getSupportedCipherSuites()
     */
    @Override
    public String[] getSupportedCipherSuites() {
        lock.lock();
        try {
            return adapteeFactory.getSupportedCipherSuites();
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.SocketFactory#createSocket()
     */
    @Override
    public Socket createSocket() throws IOException {
        lock.lock();
        try {
            return adapteeFactory.createSocket();
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.SocketFactory#createSocket(java.net.InetAddress, int,
     *						java.net.InetAddress, int)
     */
    @Override
    public Socket createSocket(InetAddress inetaddress, int i,
                                            InetAddress inetaddress1, int j) throws IOException {
        lock.lock();
        try {
            return adapteeFactory.createSocket(inetaddress, i, inetaddress1, j);
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.SocketFactory#createSocket(java.net.InetAddress, int)
     */
    @Override
    public Socket createSocket(InetAddress inetaddress, int i)
            throws IOException {
        lock.lock();
        try {
            return adapteeFactory.createSocket(inetaddress, i);
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.SocketFactory#createSocket(java.lang.String, int,
     *						java.net.InetAddress, int)
     */
    @Override
    public Socket createSocket(String s, int i,
                                            InetAddress inetaddress, int j)
            throws IOException, UnknownHostException {
        lock.lock();
        try {
            return adapteeFactory.createSocket(s, i, inetaddress, j);
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see javax.net.SocketFactory#createSocket(java.lang.String, int)
     */
    @Override
    public Socket createSocket(String s, int i)
            throws IOException, UnknownHostException {
        lock.lock();
        try {
            return adapteeFactory.createSocket(s, i);
        } finally {
            lock.unlock();
        }
    }


    // inner classes

    /**
     * A default Trustmanager.
     *
     * @author Stephan Sann
     */
    private class MailTrustManager implements X509TrustManager {

        /**
         * A TrustManager to pass method calls to
         */
        private X509TrustManager adapteeTrustManager = null;

        /**
         * Initializes a new TrustManager instance.
         */
        private MailTrustManager() throws GeneralSecurityException {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
            tmf.init((KeyStore) null);
            adapteeTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];
        }

        /* (non-Javadoc)
         * @see javax.net.ssl.X509TrustManager#checkClientTrusted(
         *		java.security.cert.X509Certificate[], java.lang.String)
         */
        @Override
        public void checkClientTrusted(X509Certificate[] certs, String authType)
                throws CertificateException {
            if (!(isTrustAllHosts() || getTrustedHosts() != null))
                adapteeTrustManager.checkClientTrusted(certs, authType);
        }

        /* (non-Javadoc)
         * @see javax.net.ssl.X509TrustManager#checkServerTrusted(
         *		java.security.cert.X509Certificate[], java.lang.String)
         */
        @Override
        public void checkServerTrusted(X509Certificate[] certs, String authType)
                throws CertificateException {

            if (!(isTrustAllHosts() || getTrustedHosts() != null))
                adapteeTrustManager.checkServerTrusted(certs, authType);
        }

        /* (non-Javadoc)
         * @see javax.net.ssl.X509TrustManager#getAcceptedIssuers()
         */
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return adapteeTrustManager.getAcceptedIssuers();
        }
    }
}
