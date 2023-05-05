/*
 * Copyright (c) 2009, 2023 Oracle and/or its affiliates. All rights reserved.
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

import org.eclipse.angus.mail.test.ReflectionUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TimeoutOutputStreamTest {
    private List<ServerSocket> serverSockets = new ArrayList<>();
    private List<Socket> sockets = new ArrayList<>();
    private List<ScheduledExecutorService> scheduledExecutorServices = new ArrayList<>();

    @After
    public void tearDown() {
        scheduledExecutorServices.forEach(this::close);
        scheduledExecutorServices.clear();
        serverSockets.forEach(this::close);
        serverSockets.clear();
        sockets.forEach(this::close);
        sockets.clear();
    }

    @Test
    public void testWriteSesWithRemoveOnCancelPolicyTrue() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes(true);
        BlockingQueue<Runnable> queue = ses.getQueue();
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 5000);

        timeoutOutputStream.write(new byte[]{0}, 0, 1);

        Assert.assertEquals(0, queue.size());
        Assert.assertTrue(getScheduledFeature(timeoutOutputStream).isCancelled());
    }

    @Test
    public void testWriteSesWithRemoveOnCancelPolicyFalse() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        BlockingQueue<Runnable> queue = ses.getQueue();
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 5000);

        timeoutOutputStream.write(new byte[]{0}, 0, 1);

        Assert.assertEquals(1, queue.size());
        ScheduledFuture<String> sf = (ScheduledFuture<String>) queue.peek();
        Assert.assertTrue(sf.isCancelled());
    }

    @Test
    public void testWriteSesWithRemoveOnCancelPolicyFalseWithoutTimeout() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        BlockingQueue<Runnable> queue = ses.getQueue();
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 0);

        timeoutOutputStream.write(new byte[]{0}, 0, 1);

        Assert.assertEquals(0, queue.size());
        Assert.assertNull(getScheduledFeature(timeoutOutputStream));
    }

    @Test
    public void testWriteRejectedExecutionException() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        ses.shutdownNow();
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);

        IOException expectedException =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("Write aborted due to timeout not enforced", expectedException.getMessage());
        Assert.assertFalse(socket.isClosed());
    }

    @Test
    public void testWriteSwallowRejectedExecutionException() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        ses.shutdownNow();
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        socket.close();

        IOException expectedException =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("Socket closed", expectedException.getMessage());
        Assert.assertTrue(socket.isClosed());
    }

    @Test
    public void testSocketClosedAfterWrite() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 0);

        timeoutOutputStream.write(new byte[]{0}, 0, 1);

        Assert.assertFalse(socket.isClosed());
        Assert.assertNull(getScheduledFeature(timeoutOutputStream));
    }

    @Test
    public void testWriteSocketNoTimeoutWithIOException() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        TimeoutOutputStream timeoutOutputStream =
                new TimeoutOutputStream(socket, ses, 0);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(0), new IOException("an error")));

        IOException expectedException = Assert.assertThrows(IOException.class,
                () -> timeoutOutputStream.write(new byte[]{1}, 0, 1));

        Assert.assertEquals("an error", expectedException.getMessage());
    }

    @Test
    public void testWriteSocketClosedByWriteTimeout() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        TimeoutOutputStream timeoutOutputStream =
                new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getSlowOutputStream(socket.getOutputStream(), Duration.ofSeconds(1), null));

        IOException expectedException = Assert.assertThrows(IOException.class,
                () -> timeoutOutputStream.write(new byte[]{1}, 0, 1));

        Assert.assertEquals("Write timed out", expectedException.getMessage());
        Assert.assertTrue(socket.isClosed());
    }

    @Test
    public void testWriteSocketClosedByWriteTimeoutWithException() throws Exception {
        Socket socket = createSocket();
        ScheduledThreadPoolExecutor ses = createSes();
        TimeoutOutputStream timeoutOutputStream =
                new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getSlowOutputStream(socket.getOutputStream(), Duration.ofSeconds(1), new RuntimeException("Unknown error")));

        IOException expectedException = Assert.assertThrows(IOException.class,
                () -> timeoutOutputStream.write(new byte[]{1}, 0, 1));

        Assert.assertEquals("java.lang.RuntimeException: Unknown error", expectedException.getMessage());
        Assert.assertTrue(socket.isClosed());
    }

    @Test
    public void testHandleTimeoutTaskResultCancellationException() throws Exception {
        Socket socket = createSocket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        this.scheduledExecutorServices.add(ses);
        CancellationException e = new CancellationException("An exception happened");
        ses.setCancellationException(e);
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(1), new IOException("any error")));

        IOException exception =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("java.io.IOException: Couldn't get result of timeout task. java.util.concurrent"
                + ".CancellationException: An exception happened", exception.toString());
        Assert.assertEquals("java.io.IOException: any error", exception.getCause().toString());
    }

    @Test
    public void testHandleTimeoutTaskResultTimeoutException() throws Exception {
        Socket socket = createSocket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        this.scheduledExecutorServices.add(ses);
        TimeoutException e = new TimeoutException("An exception happened");
        ses.setTimeoutException(e);
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(1), new IOException("any error")));

        IOException exception =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertTrue(exception.toString().startsWith(
                "java.io.IOException: Couldn't get result of timeout task. java"
                        + ".util.concurrent.TimeoutException: An exception happened"));
        Assert.assertTrue(exception.toString().contains("CustomScheduledThreadPoolExecutor"));
        Assert.assertEquals("java.io.IOException: any error", exception.getCause().toString());
    }

    @Test
    public void testHandleTimeoutTaskResultExecutionException() throws Exception {
        Socket socket = createSocket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        this.scheduledExecutorServices.add(ses);
        ExecutionException e = new ExecutionException(new RuntimeException("Random exception"));
        ses.setExecutionException(e);
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(1), new IOException("any error")));

        IOException exception =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("java.io.IOException: Couldn't get result of timeout task."
                + " java.lang.RuntimeException: Random exception", exception.toString());
        Assert.assertEquals("java.io.IOException: any error", exception.getCause().toString());
    }

    @Test
    public void testHandleTimeoutTaskResultInterruptedException() throws Exception {
        Socket socket = createSocket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        this.scheduledExecutorServices.add(ses);
        InterruptedException e = new InterruptedException("An exception happened");
        ses.setInterruptedException(e);
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(1), new IOException("any error")));

        IOException exception =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("java.io.IOException: Couldn't get result of timeout task. "
                + "java.lang.InterruptedException: An exception happened", exception.toString());
        Assert.assertEquals("java.io.IOException: any error", exception.getCause().toString());
    }

    @Test
    public void testHandleTimeoutTaskResultRuntimeException() throws Exception {
        Socket socket = createSocket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        this.scheduledExecutorServices.add(ses);
        RuntimeException e = new RuntimeException("An exception happened");
        ses.setRuntimeException(e);
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(1), new IOException("any error")));

        IOException exception =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("java.io.IOException: Couldn't get result of timeout task. "
                + "java.lang.RuntimeException: An exception happened", exception.toString());
        Assert.assertEquals("java.io.IOException: any error", exception.getCause().toString());
    }

    @Test
    public void testHandleTimeoutTaskResultWithNoException() throws Exception {
        Socket socket = createSocket();
        CustomScheduledThreadPoolExecutor ses = new CustomScheduledThreadPoolExecutor(1);
        this.scheduledExecutorServices.add(ses);
        TimeoutOutputStream timeoutOutputStream = new TimeoutOutputStream(socket, ses, 1);
        ReflectionUtil.setFieldValue(timeoutOutputStream, "os",
                getOutputStreamIOException(socket.getOutputStream(), Duration.ofSeconds(1), new IOException("any error")));

        IOException exception =
                Assert.assertThrows(IOException.class, () -> timeoutOutputStream.write(new byte[]{0}, 0, 1));

        Assert.assertEquals("java.io.IOException: some result", exception.toString());
    }

    private int getRandomFreePort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        serverSockets.add(serverSocket);
        int freePort = serverSocket.getLocalPort();

        return freePort;
    }

    private OutputStream getSlowOutputStream(OutputStream os, Duration delay, RuntimeException exception) {
        OutputStream outputStream = new OutputStream() {
            @Override
            public void write(int i) throws IOException {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                os.write(i);
            }

            @Override
            public void close() throws IOException {
                os.close();
                if (exception != null) {
                    throw exception;
                }
            }
        };

        return outputStream;
    }

    private OutputStream getOutputStreamIOException(OutputStream os, Duration delay, IOException exception) {
        OutputStream outputStream = new OutputStream() {
            @Override
            public void write(int i) throws IOException {
                throw exception;
            }

            @Override
            public void close() throws IOException {
                os.close();
            }
        };

        return outputStream;
    }

    private void close(ServerSocket serverSocket) {
        if (serverSocket.isClosed()) {
            return;
        }

        try {
            serverSocket.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private void close(Socket socket) {
        if (socket.isClosed()) {
            return;
        }

        try {
            socket.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private void close(ScheduledExecutorService ses) {
        if (ses.isTerminated()) {
            return;
        }

        try {
            ses.shutdownNow();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private Socket createSocket() throws IOException {
        int port = getRandomFreePort();
        Socket socket = new Socket("localhost", port);
        sockets.add(socket);
        return socket;
    }

    private ScheduledThreadPoolExecutor createSes(boolean removeOnCancelPolicy) {
        ScheduledThreadPoolExecutor ses = createSes();
        ses.setRemoveOnCancelPolicy(removeOnCancelPolicy);
        return ses;
    }

    private ScheduledFuture<String> getScheduledFeature(
            TimeoutOutputStream timeoutOutputStream) {
        try {
            ScheduledFuture<String> sf =
                    (ScheduledFuture<String>) ReflectionUtil.getPrivateFieldValue(timeoutOutputStream, "sf");
            return sf;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't extract scheduled feature", e);
        }
    }

    private ScheduledThreadPoolExecutor createSes() {
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(1);
        scheduledExecutorServices.add(ses);
        return ses;
    }

    private static final class CustomScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        private ScheduledFeatureMock scheduledFuture = new ScheduledFeatureMock();

        public CustomScheduledThreadPoolExecutor(int corePoolSize) {
            super(corePoolSize);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                               long delay, TimeUnit unit) {
            return (ScheduledFuture) scheduledFuture;
        }

        public void setCancellationException(CancellationException cancellationException) {
            scheduledFuture.setCancellationException(cancellationException);
        }

        public void setInterruptedException(InterruptedException interruptedException) {
            scheduledFuture.setInterruptedException(interruptedException);
        }

        public void setExecutionException(ExecutionException executionException) {
            scheduledFuture.setExecutionException(executionException);
        }

        public void setTimeoutException(TimeoutException timeoutException) {
            scheduledFuture.setTimeoutException(timeoutException);
        }

        public void setRuntimeException(RuntimeException runtimeException) {
            scheduledFuture.setRuntimeException(runtimeException);
        }
    }

    private static final class ScheduledFeatureMock implements ScheduledFuture<String> {
        private CancellationException cancellationException;
        private InterruptedException interruptedException;
        private ExecutionException executionException;
        private TimeoutException timeoutException;
        private RuntimeException runtimeException;

        @Override
        public long getDelay(TimeUnit timeUnit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed delayed) {
            return 0;
        }

        @Override
        public boolean cancel(boolean b) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public String get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public String get(long l, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
            if (cancellationException != null) {
                throw cancellationException;
            }
            if (interruptedException != null) {
                throw interruptedException;
            }

            if (executionException != null) {
                throw executionException;
            }

            if (timeoutException != null) {
                throw timeoutException;
            }

            if (runtimeException != null) {
                throw runtimeException;
            }

            return "some result";
        }

        public void setCancellationException(CancellationException cancellationException) {
            this.cancellationException = cancellationException;
        }

        public void setInterruptedException(InterruptedException interruptedException) {
            this.interruptedException = interruptedException;
        }

        public void setExecutionException(ExecutionException executionException) {
            this.executionException = executionException;
        }

        public void setTimeoutException(TimeoutException timeoutException) {
            this.timeoutException = timeoutException;
        }

        public void setRuntimeException(RuntimeException runtimeException) {
            this.runtimeException = runtimeException;
        }
    }
}
