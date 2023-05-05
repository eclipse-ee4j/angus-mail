/*
 * Copyright (c) 1997, 2023 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package example.app.internal;

import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.URLName;

import java.util.Properties;

/*
 * Demo app that exercises the namespace interfaces.
 * Show the namespaces supported by a store.
 *
 * @author Bill Shannon
 */

public class namespace {

    static String protocol;
    static String host = null;
    static String user = null;
    static String password = null;
    static String url = null;
    static int port = -1;
    static boolean debug = false;
    static String suser = "other";

    public static void main(String[] argv) {
        int msgnum = -1;
        int optind;

        for (optind = 0; optind < argv.length; optind++) {
            if (argv[optind].equals("-T")) {
                protocol = argv[++optind];
            } else if (argv[optind].equals("-H")) {
                host = argv[++optind];
            } else if (argv[optind].equals("-U")) {
                user = argv[++optind];
            } else if (argv[optind].equals("-P")) {
                password = argv[++optind];
            } else if (argv[optind].equals("-D")) {
                debug = true;
            } else if (argv[optind].equals("-L")) {
                url = argv[++optind];
            } else if (argv[optind].equals("-p")) {
                port = Integer.parseInt(argv[++optind]);
            } else if (argv[optind].equals("-u")) {
                suser = argv[++optind];
            } else if (argv[optind].equals("--")) {
                optind++;
                break;
            } else if (argv[optind].startsWith("-")) {
                System.out.println(
                        "Usage: namespace [-L url] [-T protocol] [-H host] [-p port] [-U user]");
                System.out.println(
                        "\t[-P password] [-u other-user] [-D]");
                System.exit(1);
            } else {
                break;
            }
        }

        try {
            // Get a Properties object
            Properties props = System.getProperties();

            // Get a Session object
            Session session = Session.getInstance(props, null);
            session.setDebug(debug);

            // Get a Store object
            Store store = null;
            if (url != null) {
                URLName urln = new URLName(url);
                store = session.getStore(urln);
                store.connect();
            } else {
                if (protocol != null)
                    store = session.getStore(protocol);
                else
                    store = session.getStore();

                // Connect
                if (host != null || user != null || password != null)
                    store.connect(host, port, user, password);
                else
                    store.connect();
            }

            printFolders("Personal", store.getPersonalNamespaces());
            printFolders("User \"" + suser + "\"",
                    store.getUserNamespaces(suser));
            printFolders("Shared", store.getSharedNamespaces());

            store.close();
        } catch (Exception ex) {
            System.out.println("Oops, got exception! " + ex.getMessage());
            ex.printStackTrace();
        }
        System.exit(0);
    }

    private static void printFolders(String name, Folder[] folders)
            throws MessagingException {
        System.out.println(name + " Namespace:");
        if (folders == null || folders.length == 0) {
            System.out.println("  <none>");
            return;
        }
        for (int i = 0; i < folders.length; i++) {
            String fn = folders[i].getFullName();
            if (fn.length() == 0)
                fn = "<default folder>";
            try {
                System.out.println("  " + fn +
                        ", delimiter \"" + folders[i].getSeparator() + "\"");
                Folder[] fl = folders[i].list();
                if (fl.length > 0) {
                    System.out.println("  Subfolders:");
                    for (int j = 0; j < fl.length; j++)
                        System.out.println("    " + fl[j].getFullName());
                }
            } catch (FolderNotFoundException ex) {
            }
        }
    }
}
