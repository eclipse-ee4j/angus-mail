/*
 * Copyright (c) 1996, 2023 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package example.app.internal;

import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.util.Properties;

/**
 * Demo app that exercises the Message interfaces.
 * List information about folders.
 *
 * @author John Mani
 * @author Bill Shannon
 */

public class folderlist {
    static String protocol = null;
    static String host = null;
    static String user = null;
    static String password = null;
    static String url = null;
    static String root = null;
    static String pattern = "%";
    static boolean recursive = false;
    static boolean verbose = false;
    static boolean debug = false;

    public static void main(String argv[]) throws Exception {
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
            } else if (argv[optind].equals("-L")) {
                url = argv[++optind];
            } else if (argv[optind].equals("-R")) {
                root = argv[++optind];
            } else if (argv[optind].equals("-r")) {
                recursive = true;
            } else if (argv[optind].equals("-v")) {
                verbose = true;
            } else if (argv[optind].equals("-D")) {
                debug = true;
            } else if (argv[optind].equals("--")) {
                optind++;
                break;
            } else if (argv[optind].startsWith("-")) {
                System.out.println(
                        "Usage: folderlist [-T protocol] [-H host] [-U user] [-P password] [-L url]");
                System.out.println(
                        "\t[-R root] [-r] [-v] [-D] [pattern]");
                System.exit(1);
            } else {
                break;
            }
        }
        if (optind < argv.length)
            pattern = argv[optind];

        // Get a Properties object
        Properties props = System.getProperties();

        // Get a Session object
        Session session = Session.getInstance(props, null);
        session.setDebug(debug);

        // Get a Store object
        Store store = null;
        Folder rf = null;
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
                store.connect(host, user, password);
            else
                store.connect();
        }

        // List namespace
        if (root != null)
            rf = store.getFolder(root);
        else
            rf = store.getDefaultFolder();

        dumpFolder(rf, false, "");
        if ((rf.getType() & Folder.HOLDS_FOLDERS) != 0) {
            Folder[] f = rf.list(pattern);
            for (int i = 0; i < f.length; i++)
                dumpFolder(f[i], recursive, "    ");
        }

        store.close();
    }

    static void dumpFolder(Folder folder, boolean recurse, String tab)
            throws Exception {
        System.out.println(tab + "Name:      " + folder.getName());
        System.out.println(tab + "Full Name: " + folder.getFullName());
        System.out.println(tab + "URL:       " + folder.getURLName());

        if (verbose) {
            if (!folder.isSubscribed())
                System.out.println(tab + "Not Subscribed");

            if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                if (folder.hasNewMessages())
                    System.out.println(tab + "Has New Messages");
                System.out.println(tab + "Total Messages:  " +
                        folder.getMessageCount());
                System.out.println(tab + "New Messages:    " +
                        folder.getNewMessageCount());
                System.out.println(tab + "Unread Messages: " +
                        folder.getUnreadMessageCount());
            }
            if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0)
                System.out.println(tab + "Is Directory");

            /*
             * Demonstrate use of IMAP folder attributes
             * returned by the IMAP LIST response.
             */
            if (folder instanceof IMAPFolder) {
                IMAPFolder f = (IMAPFolder) folder;
                String[] attrs = f.getAttributes();
                if (attrs != null && attrs.length > 0) {
                    System.out.println(tab + "IMAP Attributes:");
                    for (int i = 0; i < attrs.length; i++)
                        System.out.println(tab + "    " + attrs[i]);
                }
            }
        }

        System.out.println();

        if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0) {
            if (recurse) {
                Folder[] f = folder.list();
                for (int i = 0; i < f.length; i++)
                    dumpFolder(f[i], recurse, tab + "    ");
            }
        }
    }
}
