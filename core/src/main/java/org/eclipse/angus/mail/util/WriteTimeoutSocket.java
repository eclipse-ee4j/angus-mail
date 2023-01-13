/*
 * Copyright (c) 2013, 2023 Oracle and/or its affiliates. All rights reserved.
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

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A special Socket that uses a ScheduledExecutorService to
 * implement timeouts for writes.  The write timeout is specified
 * (in milliseconds) when the WriteTimeoutSocket is created.
 *
 * @author	Bill Shannon
 */
public class WriteTimeoutSocket extends Socket {

    // delegate all operations to this socket
    private final Socket socket;
    // to schedule task to cancel write after timeout
    private final ScheduledExecutorService ses;
    // flag to indicate whether scheduled executor is provided from outside or
    // should be created here in constructor
    private final boolean isExternalSes;
    // the timeout, in milliseconds
    private final int timeout;

    public WriteTimeoutSocket(Socket socket, int timeout) throws IOException {
	this.socket = socket;
	// XXX - could share executor with all instances?
        this.ses = createScheduledThreadPool();
        this.isExternalSes = false;
	this.timeout = timeout;
    }

    public WriteTimeoutSocket(Socket socket, int timeout, ScheduledExecutorService ses) throws IOException {
        this.socket = socket;
        this.ses = ses;
        this.timeout = timeout;
        this.isExternalSes = true;
    }

    public WriteTimeoutSocket(int timeout) throws IOException {
	this(new Socket(), timeout);
    }

    public WriteTimeoutSocket(InetAddress address, int port, int timeout)
				throws IOException {
	this(timeout);
	socket.connect(new InetSocketAddress(address, port));
    }

    public WriteTimeoutSocket(InetAddress address, int port,
			InetAddress localAddress, int localPort, int timeout)
			throws IOException {
	this(timeout);
	socket.bind(new InetSocketAddress(localAddress, localPort));
	socket.connect(new InetSocketAddress(address, port));
    }

    public WriteTimeoutSocket(String host, int port, int timeout)
				throws IOException {
	this(timeout);
	socket.connect(new InetSocketAddress(host, port));
    }

    public WriteTimeoutSocket(String host, int port,
			InetAddress localAddress, int localPort, int timeout)
			throws IOException {
	this(timeout);
	socket.bind(new InetSocketAddress(localAddress, localPort));
	socket.connect(new InetSocketAddress(host, port));
    }

    // override all Socket methods and delegate to underlying Socket

    @Override
    public void connect(SocketAddress remote) throws IOException {
        socket.connect(remote, 0);
    }

    @Override
    public void connect(SocketAddress remote, int timeout) throws IOException {
	socket.connect(remote, timeout);
    }

    @Override
    public void bind(SocketAddress local) throws IOException {
	socket.bind(local);
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
	return socket.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
	return socket.getLocalSocketAddress();
    }

    @Override
    public void setPerformancePreferences(int connectionTime, int latency,
                                          int bandwidth) {
        socket.setPerformancePreferences(connectionTime, latency, bandwidth);
    }

    @Override
    public SocketChannel getChannel() {
	return socket.getChannel();
    }

    @Override
    public InetAddress getInetAddress() {
	return socket.getInetAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
	return socket.getLocalAddress();
    }

    @Override
    public int getPort() {
	return socket.getPort();
    }

    @Override
    public int getLocalPort() {
	return socket.getLocalPort();
    }

    @Override
    public InputStream getInputStream() throws IOException {
	return socket.getInputStream();
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
	// wrap the returned stream to implement write timeout
        return new TimeoutOutputStream(socket, ses, timeout);
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        socket.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return socket.getTcpNoDelay();
    }

    @Override
    public void setSoLinger(boolean on, int linger) throws SocketException {
        socket.setSoLinger(on, linger);
    }

    @Override
    public int getSoLinger() throws SocketException {
        return socket.getSoLinger();
    }

    @Override
    public void sendUrgentData(int data) throws IOException {
        socket.sendUrgentData(data);
    }

    @Override
    public void setOOBInline(boolean on) throws SocketException {
        socket.setOOBInline(on);
    }

    @Override
    public boolean getOOBInline() throws SocketException {
        return socket.getOOBInline();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
	socket.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    @Override
    public void setSendBufferSize(int size) throws SocketException {
        socket.setSendBufferSize(size);
    }

    @Override
    public int getSendBufferSize() throws SocketException {
        return socket.getSendBufferSize();
    }

    @Override
    public void setReceiveBufferSize(int size) throws SocketException {
        socket.setReceiveBufferSize(size);
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        return socket.getReceiveBufferSize();
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {
        socket.setKeepAlive(on);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        return socket.getKeepAlive();
    }

    @Override
    public void setTrafficClass(int tc) throws SocketException {
        socket.setTrafficClass(tc);
    }

    @Override
    public int getTrafficClass() throws SocketException {
        return socket.getTrafficClass();
    }

    @Override
    public void setReuseAddress(boolean on) throws SocketException {
        socket.setReuseAddress(on);
    }

    @Override
    public boolean getReuseAddress() throws SocketException {
        return socket.getReuseAddress();
    }

    @Override
    public void close() throws IOException {
	try {
	    socket.close();
	} finally {
        if (!isExternalSes)
	        ses.shutdownNow();
	}
    }

    @Override
    public void shutdownInput() throws IOException {
	socket.shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
	socket.shutdownOutput();
    }

    @Override
    public String toString() {
	return socket.toString();
    }

    @Override
    public boolean isConnected() {
        return socket.isConnected();
    }

    @Override
    public boolean isBound() {
        return socket.isBound();
    }

    @Override
    public boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    @Override
    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    /*
     * The following three methods were added to java.net.Socket in Java SE 9.
     * Since they're not supported on Android, and since we know that we
     * never use them in Jakarta Mail, we just stub them out here.
     */
    //@Override
    public <T> Socket setOption(SocketOption<T> so, T val) throws IOException {
	// socket.setOption(so, val);
	// return this;
	throw new UnsupportedOperationException("WriteTimeoutSocket.setOption");
    }

    //@Override
    public <T> T getOption(SocketOption<T> so) throws IOException {
	// return socket.getOption(so);
	throw new UnsupportedOperationException("WriteTimeoutSocket.getOption");
    }

    //@Override
    public Set<SocketOption<?>> supportedOptions() {
	// return socket.supportedOptions();
	return Collections.emptySet();
    }

    /**
     * KLUDGE for Android, which has this illegal non-Java Compatible method.
     *
     * @return	the FileDescriptor object
     */
    public FileDescriptor getFileDescriptor$() {
    	//The loop handles issues with non-public classes between 
    	//java.net.Socket and the actual socket type held in this object.
    	//Must inspect java.net.Socket to ensure compatiblity with old behavior.
    	for (Class<?> k = socket.getClass(); k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod("getFileDescriptor$");  
                if (FileDescriptor.class.isAssignableFrom(m.getReturnType())) {
                        //Skip setAccessible so non-public methods fail to invoke.
                    return (FileDescriptor) m.invoke(socket);
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private ScheduledThreadPoolExecutor createScheduledThreadPool() {
        ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
        // Without setting setRemoveOnCancelPolicy = true write methods will create garbage that would only be
        // reclaimed after the timeout.
        scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
        return scheduledThreadPoolExecutor;
    }
}


/**
 * An OutputStream that wraps the Socket's OutputStream and uses
 * the ScheduledExecutorService to schedule a task to close the
 * socket (aborting the write) if the timeout expires.
 */
class TimeoutOutputStream extends OutputStream {
    private static final String WRITE_TIMEOUT_MESSAGE = "Write timed out";
    private static final String CANNOT_GET_TIMEOUT_TASK_RESULT_MESSAGE = "Couldn't get result of timeout task";

    private final OutputStream os;
    private final ScheduledExecutorService ses;
    private final Callable<String> timeoutTask;
    private final int timeout;
    private byte[] b1;
    private final Socket socket;
    // Implement timeout with a scheduled task
    private ScheduledFuture<String> sf = null;

    public TimeoutOutputStream(Socket socket, ScheduledExecutorService ses, int timeout) throws IOException {
	this.os = socket.getOutputStream();
	this.ses = ses;
	this.timeout = timeout;
    this.socket = socket;
	timeoutTask = new Callable<String>() {
	    @Override
	    public String call() throws Exception {
            try {
                os.close();	// close the stream to abort the write
            } catch (Throwable t) {
                return t.toString();
            }
		    return WRITE_TIMEOUT_MESSAGE;
	    }
	};
    }

    @Override
    public synchronized void write(int b) throws IOException {
	if (b1 == null)
	    b1 = new byte[1];
	b1[0] = (byte)b;
	this.write(b1);
    }

    @Override
    public synchronized void write(byte[] bs, int off, int len)
				throws IOException {
	if ((off < 0) || (off > bs.length) || (len < 0) ||
	    ((off + len) > bs.length) || ((off + len) < 0)) {
	    throw new IndexOutOfBoundsException();
	} else if (len == 0) {
	    return;
	}

	try {
        try {
            if (timeout > 0)
                sf = ses.schedule(timeoutTask,
                    timeout, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            if (!socket.isClosed()) {
                throw new IOException("Write aborted due to timeout not enforced", ex);
            }
        }

        try {
            os.write(bs, off, len);
        } catch (IOException e) {
            if (sf != null && !sf.cancel(true)) {
                throw new IOException(handleTimeoutTaskResult(sf), e);
            }
            throw e;
        }
	} finally {
	    if (sf != null)
		    sf.cancel(true);
	    }
    }

    @Override
    public void close() throws IOException {
	    os.close();
        if (sf != null) {
            sf.cancel(true);
        }
    }

    private String handleTimeoutTaskResult(ScheduledFuture<String> sf)  {
        boolean wasInterrupted = Thread.interrupted();
        String exceptionMessage = null;
        try {
            return sf.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exceptionMessage = String.format("%s %s", e, ses.toString());
        } catch (CancellationException e) {
            exceptionMessage = e.toString();
        } catch (InterruptedException e) {
            wasInterrupted = true;
            exceptionMessage = e.toString();
        } catch (ExecutionException e) {
            exceptionMessage = e.getCause() == null ? e.toString() : e.getCause().toString();
        } catch (Exception e) {
            exceptionMessage = e.toString();
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }

        return String.format("%s. %s", CANNOT_GET_TIMEOUT_TASK_RESULT_MESSAGE, exceptionMessage);
    }
}
