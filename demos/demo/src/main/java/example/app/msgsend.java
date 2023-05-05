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
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.Properties;

/**
 * Demo app that shows how to construct and send an RFC822
 * (singlepart) message.
 *
 * XXX - allow more than one recipient on the command line
 *
 * @author Max Spivak
 * @author Bill Shannon
 */

public class msgsend {

    public static void main(String[] argv) {
        String to, subject = null, from = null,
                cc = null, bcc = null, url = null;
        String mailhost = null;
        String mailer = "msgsend";
        String file = null;
        String protocol = null, host = null, user = null, password = null;
        String record = null;    // name of folder in which to record mail
        boolean debug = false;
        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in));
        int optind;

        /*
         * Process command line arguments.
         */
        for (optind = 0; optind < argv.length; optind++) {
            if (argv[optind].equals("-T")) {
                protocol = argv[++optind];
            } else if (argv[optind].equals("-H")) {
                host = argv[++optind];
            } else if (argv[optind].equals("-U")) {
                user = argv[++optind];
            } else if (argv[optind].equals("-P")) {
                password = argv[++optind];
            } else if (argv[optind].equals("-M")) {
                mailhost = argv[++optind];
            } else if (argv[optind].equals("-f")) {
                record = argv[++optind];
            } else if (argv[optind].equals("-a")) {
                file = argv[++optind];
            } else if (argv[optind].equals("-s")) {
                subject = argv[++optind];
            } else if (argv[optind].equals("-o")) { // originator
                from = argv[++optind];
            } else if (argv[optind].equals("-c")) {
                cc = argv[++optind];
            } else if (argv[optind].equals("-b")) {
                bcc = argv[++optind];
            } else if (argv[optind].equals("-L")) {
                url = argv[++optind];
            } else if (argv[optind].equals("-d")) {
                debug = true;
            } else if (argv[optind].equals("--")) {
                optind++;
                break;
            } else if (argv[optind].startsWith("-")) {
                System.out.println(
                        "Usage: msgsend [[-L store-url] | [-T prot] [-H host] [-U user] [-P passwd]]");
                System.out.println(
                        "\t[-s subject] [-o from-address] [-c cc-addresses] [-b bcc-addresses]");
                System.out.println(
                        "\t[-f record-mailbox] [-M transport-host] [-a attach-file] [-d] [address]");
                System.exit(1);
            } else {
                break;
            }
        }

        try {
            /*
             * Prompt for To and Subject, if not specified.
             */
            if (optind < argv.length) {
                // XXX - concatenate all remaining arguments
                to = argv[optind];
                System.out.println("To: " + to);
            } else {
                System.out.print("To: ");
                System.out.flush();
                to = in.readLine();
            }
            if (subject == null) {
                System.out.print("Subject: ");
                System.out.flush();
                subject = in.readLine();
            } else {
                System.out.println("Subject: " + subject);
            }

            /*
             * Initialize the Jakarta Mail Session.
             */
            Properties props = System.getProperties();
            // XXX - could use Session.getTransport() and Transport.connect()
            // XXX - assume we're using SMTP
            if (mailhost != null)
                props.put("mail.smtp.host", mailhost);

            // Get a Session object
            Session session = Session.getInstance(props, null);
            if (debug)
                session.setDebug(true);

            /*
             * Construct the message and send it.
             */
            Message msg = new MimeMessage(session);
            if (from != null)
                msg.setFrom(new InternetAddress(from));
            else
                msg.setFrom();

            msg.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(to, false));
            if (cc != null)
                msg.setRecipients(Message.RecipientType.CC,
                        InternetAddress.parse(cc, false));
            if (bcc != null)
                msg.setRecipients(Message.RecipientType.BCC,
                        InternetAddress.parse(bcc, false));

            msg.setSubject(subject);

            String text = collect(in);

            if (file != null) {
                // Attach the specified file.
                // We need a multipart message to hold the attachment.
                MimeBodyPart mbp1 = new MimeBodyPart();
                mbp1.setText(text);
                MimeBodyPart mbp2 = new MimeBodyPart();
                mbp2.attachFile(file);
                MimeMultipart mp = new MimeMultipart();
                mp.addBodyPart(mbp1);
                mp.addBodyPart(mbp2);
                msg.setContent(mp);
            } else {
                // If the desired charset is known, you can use
                // setText(text, charset)
                msg.setText(text);
            }

            msg.setHeader("X-Mailer", mailer);
            msg.setSentDate(new Date());

            // send the thing off
            Transport.send(msg);

            System.out.println("\nMail was sent successfully.");

            /*
             * Save a copy of the message, if requested.
             */
            if (record != null) {
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
                        store.connect(host, user, password);
                    else
                        store.connect();
                }

                // Get record Folder.  Create if it does not exist.
                Folder folder = store.getFolder(record);
                if (folder == null) {
                    System.err.println("Can't get record folder.");
                    System.exit(1);
                }
                if (!folder.exists())
                    folder.create(Folder.HOLDS_MESSAGES);

                Message[] msgs = new Message[1];
                msgs[0] = msg;
                folder.appendMessages(msgs);

                System.out.println("Mail was recorded successfully.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Read the body of the message until EOF.
     */
    public static String collect(BufferedReader in) throws IOException {
        String line;
        StringBuffer sb = new StringBuffer();
        while ((line = in.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
        }
        return sb.toString();
    }
}
