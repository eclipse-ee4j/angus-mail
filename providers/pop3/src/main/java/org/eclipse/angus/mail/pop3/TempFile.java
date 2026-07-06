/*
 * Copyright (c) 2010, 2023 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.pop3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * A temporary file used to cache POP3 messages.
 */
class TempFile {

    private File file;    // the temp file name
    private WritableSharedFile sf;

    /**
     * Create a temp file in the specified directory (if not null).
     * The file will be deleted when the JVM exits.
     */
    public TempFile(File dir) throws IOException {
        file = createTempFile("pop3.", ".mbox", dir);
        file.deleteOnExit();
        sf = new WritableSharedFile(file);
    }

    /**
     * Create the cache file with owner-only permissions so that the
     * cached message content isn't readable by other local users.  On
     * file systems without POSIX permissions the platform default is
     * used.
     */
    private static File createTempFile(String prefix, String suffix, File dir)
            throws IOException {
        try {
            FileAttribute<?> attr = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"));
            if (dir != null)
                return Files.createTempFile(
                        dir.toPath(), prefix, suffix, attr).toFile();
            return Files.createTempFile(prefix, suffix, attr).toFile();
        } catch (UnsupportedOperationException ex) {
            if (dir != null)
                return Files.createTempFile(
                        dir.toPath(), prefix, suffix).toFile();
            return Files.createTempFile(prefix, suffix).toFile();
        }
    }

    /**
     * Return a stream for appending to the temp file.
     */
    public AppendStream getAppendStream() throws IOException {
        return sf.getAppendStream();
    }

    /**
     * Close and remove this temp file.
     */
    public void close() {
        try {
            sf.close();
        } catch (IOException ex) {
            // ignore it
        }
        file.delete();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
