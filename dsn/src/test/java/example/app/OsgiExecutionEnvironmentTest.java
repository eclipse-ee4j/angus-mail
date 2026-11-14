/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
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

package example.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The osgi.ee requirement in the bundle manifest must match the Java
 * version the classes were compiled for.
 */
public class OsgiExecutionEnvironmentTest {

    private static final Pattern EE =
            Pattern.compile("osgi\\.ee;filter:=\"\\(&\\(osgi\\.ee=JavaSE\\)\\(version=([0-9.]+)\\)\\)\"");

    @Test
    public void manifestMatchesBytecodeVersion() throws Exception {
        Path classes = Paths.get("target", "classes");

        Manifest mf;
        try (InputStream in = Files.newInputStream(classes.resolve("META-INF/MANIFEST.MF"))) {
            mf = new Manifest(in);
        }
        String requires = mf.getMainAttributes().getValue("Require-Capability");
        assertNotNull("Require-Capability missing", requires);
        Matcher m = EE.matcher(requires);
        if (!m.find()) {
            throw new AssertionError("no osgi.ee requirement in " + requires);
        }

        int major;
        Path clazz = classes.resolve("org/eclipse/angus/mail/dsn/DeliveryStatus.class");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(clazz))) {
            in.readInt();
            in.readUnsignedShort();
            major = in.readUnsignedShort();
        }
        int release = major - 44;
        String expected = release <= 8 ? "1." + release : String.valueOf(release);

        assertEquals("osgi.ee version vs. class file version " + major, expected, m.group(1));
    }
}
