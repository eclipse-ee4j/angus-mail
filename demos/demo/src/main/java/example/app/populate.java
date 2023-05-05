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
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;

/*
 * Copy folder hierarchies between different Stores. This is a useful
 * utility to populate new (and possibly empty) mail stores. Specify
 * both the source and destination folders as URLs.
 *
 * @author John Mani
 * @author Bill Shannon
 */

public class populate {

    static boolean force = false;
    static boolean skipSpecial = false;
    static boolean clear = false;
    static boolean dontPreserveFlags = false;
    static boolean warn = false;

    public static void main(String[] argv) {
        String srcURL = null;
        String dstURL = null;
        boolean debug = false;

        int optind;

        for (optind = 0; optind < argv.length; optind++) {
            if (argv[optind].equals("-s")) {
                srcURL = argv[++optind];
            } else if (argv[optind].equals("-d")) {
                dstURL = argv[++optind];
            } else if (argv[optind].equals("-D")) {
                debug = true;
            } else if (argv[optind].equals("-f")) {
                force = true;
            } else if (argv[optind].equals("-S")) {
                skipSpecial = true;
            } else if (argv[optind].equals("-c")) {
                clear = true;
            } else if (argv[optind].equals("-P")) {
                dontPreserveFlags = true;
            } else if (argv[optind].equals("-W")) {
                warn = true;
            } else if (argv[optind].equals("--")) {
                optind++;
                break;
            } else if (argv[optind].startsWith("-")) {
                printUsage();
                System.exit(1);
            } else {
                break;
            }
        }

        try {

            if (srcURL == null || dstURL == null) {
                printUsage();
                System.exit(1);
            }

            Session session = Session.getInstance(
                    System.getProperties(), null);
            session.setDebug(debug);

            // Get source folder
            URLName srcURLName = new URLName(srcURL);
            Folder srcFolder;
            // Check if the source URL has a folder specified. If
            // not, we use the default folder
            if (srcURLName.getFile() == null) {
                Store s = session.getStore(srcURLName);
                s.connect();
                srcFolder = s.getDefaultFolder();
            } else {
                srcFolder = session.getFolder(new URLName(srcURL));
                if (!srcFolder.exists()) {
                    System.out.println("source folder does not exist");
                    srcFolder.getStore().close();
                    System.exit(1);
                }
            }

            // Set up destination folder
            URLName dstURLName = new URLName(dstURL);
            Folder dstFolder;
            // Check if the destination URL has a folder specified. If
            // not, we use the source folder name
            if (dstURLName.getFile() == null) {
                Store s = session.getStore(dstURLName);
                s.connect();
                dstFolder = s.getFolder(srcFolder.getName());
            } else
                dstFolder = session.getFolder(dstURLName);

            if (clear && dstFolder.exists()) {
                if (!dstFolder.delete(true)) {
                    System.out.println("couldn't delete " +
                            dstFolder.getFullName());
                    return;
                }
            }
            copy(srcFolder, dstFolder);

            // Close the respective stores.
            srcFolder.getStore().close();
            dstFolder.getStore().close();

        } catch (MessagingException mex) {
            System.out.println(mex.getMessage());
            mex.printStackTrace();
        }
    }

    private static void copy(Folder src, Folder dst)
            throws MessagingException {
        System.out.println("Populating " + dst.getFullName());

        Folder ddst = dst;
        Folder[] srcFolders = null;
        if ((src.getType() & Folder.HOLDS_FOLDERS) != 0)
            srcFolders = src.list();
        boolean srcHasChildren = srcFolders != null && srcFolders.length > 0;

        if (!dst.exists()) {
            // Create it.
            boolean dstHoldsOnlyFolders = false;
            if (!dst.create(src.getType())) {
                // might not be able to create a folder that holds both
                if (!dst.create(srcHasChildren ? Folder.HOLDS_FOLDERS :
                        Folder.HOLDS_MESSAGES)) {
                    // might only be able to create one type (Gmail)
                    if (!dst.create(Folder.HOLDS_MESSAGES)) {
                        System.out.println("couldn't create " +
                                dst.getFullName());
                        return;
                    }
                }
                dstHoldsOnlyFolders = srcHasChildren;
            }

            // Copy over any messges from src to dst
            if ((src.getType() & Folder.HOLDS_MESSAGES) != 0) {
                src.open(Folder.READ_ONLY);
                if (dstHoldsOnlyFolders) {
                    if (src.getMessageCount() > 0) {
                        System.out.println("Unable to copy messages from " +
                                src.getFullName() + " to " + dst.getFullName() +
                                " because destination holds only folders");
                    }
                } else
                    copyMessages(src, dst);
                src.close(false);
            }
        } else {
            System.out.println(dst.getFullName() + " already exists");
            // Copy over any messges from src to dst
            if (force && (src.getType() & Folder.HOLDS_MESSAGES) != 0) {
                src.open(Folder.READ_ONLY);
                copyMessages(src, dst);
                src.close(false);
            }
        }

        // Copy over subfolders
        if (srcHasChildren) {
            for (int i = 0; i < srcFolders.length; i++) {
                // skip special directories?
                if (skipSpecial) {
                    if (srcFolders[i].getName().equals("SCCS") ||
                            srcFolders[i].getName().equals("Drafts") ||
                            srcFolders[i].getName().equals("Trash") ||
                            srcFolders[i].getName().equals("Shared Folders"))
                        continue;
                }
                copy(srcFolders[i], dst.getFolder(srcFolders[i].getName()));
            }
        }
    }

    /**
     * Copy message from src to dst.  If dontPreserveFlags is set
     * we first copy the messages to memory, clear all the flags,
     * and then copy to the destination.
     */
    private static void copyMessages(Folder src, Folder dst)
            throws MessagingException {
        Message[] msgs = src.getMessages();
        if (dontPreserveFlags) {
            for (int i = 0; i < msgs.length; i++) {
                MimeMessage m = new MimeMessage((MimeMessage) msgs[i]);
                m.setFlags(m.getFlags(), false);
                msgs[i] = m;
            }
        }
        if (warn) {
            // have to copy messages one at a time
            for (int i = 0; i < msgs.length; i++) {
                try {
                    src.copyMessages(new Message[]{msgs[i]}, dst);
                } catch (MessagingException mex) {
                    System.out.println("WARNING: Copy of message " + (i + 1) +
                            " from " + src.getFullName() +
                            " to " + dst.getFullName() +
                            " failed: " + mex.toString());
                }
            }
        } else
            src.copyMessages(msgs, dst);
    }

    private static void printUsage() {
        System.out.println("populate [-D] [-f] [-S] [-c] " +
                "-s source_url -d dest_url");
        System.out.println("URLs are of the form: " +
                "protocol://username:password@hostname/foldername");
        System.out.println("The destination URL does not need a foldername," +
                " in which case, the source foldername is used");
    }
}
