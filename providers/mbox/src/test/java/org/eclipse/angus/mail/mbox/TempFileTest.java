/*
 * Copyright (c) 2009, 2024 Oracle and/or its affiliates. All rights reserved.
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

package org.eclipse.angus.mail.mbox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Test that the message cache temp file is created owner-only.
 */
public final class TempFileTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testCacheFileIsOwnerOnly() throws Exception {
        assumeTrue("requires a POSIX file system",
                FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"));

        File dir = folder.newFolder();
        TempFile tf = new TempFile(dir);
        try {
            File[] created = dir.listFiles((d, n) ->
                    n.startsWith("mbox.") && n.endsWith(".mbox"));
            assertEquals(1, created.length);
            Path p = created[0].toPath();
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            assertEquals("cache file must not be readable by group or others",
                    Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE),
                    perms);
        } finally {
            tf.close();
        }
    }
}
