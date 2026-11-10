/*
 * Copyright (c) 2025 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.test;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SingleThreadServer implements AutoCloseable {

    private int port;
    private ServerSocket server;
    private Socket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;
    private Map<String, String> predefinedResponses = new HashMap<>();

    public void start() {
        try {
            executor = Executors.newSingleThreadExecutor();
            server = new ServerSocket(0);
            port = server.getLocalPort();
            executor.submit(() -> {
                try {
                    serverSocket = server.accept();
                    System.out.println("Connected to " + serverSocket.getRemoteSocketAddress());
                    System.out.println("Server > * OK IMAP4rev1 Service Ready\r\n");
                    writeInServer("* OK IMAP4rev1 Service Ready\r\n");
                    running = true;
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while (running && (bytesRead = serverSocket.getInputStream().read(buffer)) != -1) {
                        String data = new String(buffer, 0, bytesRead);
                        System.out.println("Client > " + data);
                        String response = predefinedResponses.get(data);
                        if (response != null) {
                            System.out.println("Server > " + response);
                            writeInServer(response);
                        }
                    }
                } catch (IOException e1) {
                    throw new UncheckedIOException(e1);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void prepareResponse(String clientSends, String serverResponds) {
        predefinedResponses.put(clientSends, serverResponds);
    }

    public void stop() {
        if (running) {
            running = false;
            close(serverSocket);
            serverSocket = null;
            close(server);
            server = null;
            if (executor != null) {
                executor.shutdown();
                executor = null;
            }
        }
    }

    private void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {}
        }
    }

    private void writeInServer(String data) {
        try {
            OutputStream out = serverSocket.getOutputStream();
            out.write(data.getBytes());
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int getPort() {
        return port;
    }
    
    @Override
    public void close() throws Exception {
        stop();
    }
}
