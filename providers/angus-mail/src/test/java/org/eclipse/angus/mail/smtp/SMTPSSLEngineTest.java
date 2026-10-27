/*
 * Copyright (c) 2026 Benny Bottema (ai-assisted). All rights reserved.
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

package org.eclipse.angus.mail.smtp;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Optional loopback comparison of SSLEngine and SSLSocket scheduling. Every
 * case runs in a child JVM so its diagnostic key limit cannot affect other
 * tests, and a stalled socket cannot prevent the test runner from finishing.
 * Run with -Dangus.sslengine.prototype=true. See the test resource README.
 */
@RunWith(Parameterized.class)
public class SMTPSSLEngineTest {
    private static Path output;
    private final String mode;
    private final boolean implicit;
    private final String scenario;
    private final int mailReply;
    private final int rcptReply;
    private final int ioChunk;

    public SMTPSSLEngineTest(String mode, boolean implicit, String scenario,
            int mailReply, int rcptReply, int ioChunk) {
        this.mode = mode;
        this.implicit = implicit;
        this.scenario = scenario;
        this.mailReply = mailReply;
        this.rcptReply = rcptReply;
        this.ioChunk = ioChunk;
    }

    @Parameterized.Parameters(name = "{0}-implicit{1}-{2}-mail{3}-rcpt{4}-chunk{5}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (boolean implicit : new boolean[]{false, true}) {
            for (String mode : new String[]{"sequential", "engine", "transport", "angus"}) {
                cases.add(new Object[]{mode, implicit, "pressure", 65536, 512, 65536});
                cases.add(new Object[]{mode, implicit, "pressure", 32768, 512, 65536});
                cases.add(new Object[]{mode, implicit, "pressure", 512, 65536, 65536});
            }
            cases.add(new Object[]{"engine", implicit, "pressure", 65536, 512, 37});
            for (String mode : new String[]{"engine", "transport"}) {
                for (String scenario : new String[]{"disconnect", "silent", "post-content",
                        "wrong-host", "untrusted"})
                    cases.add(new Object[]{mode, implicit, scenario, 32768, 512, 65536});
            }
        }
        String modes = System.getProperty("angus.sslengine.modes", "");
        if (!modes.isEmpty())
            cases.removeIf(test -> !Arrays.asList(modes.split(",")).contains(test[0]));
        return cases;
    }

    @BeforeClass
    public static void prepare() throws Exception {
        Assume.assumeTrue("Enable with -Dangus.sslengine.prototype=true",
                Boolean.getBoolean("angus.sslengine.prototype"));
        Path root = Paths.get("target", "sslengine-prototype").toAbsolutePath();
        Files.createDirectories(root);
        output = Files.createTempDirectory(root, "run-");
        System.out.println("SSLEngine prototype logs: " + output);
    }

    @Test
    public void testExchange() throws Exception {
        String readerClasses = System.getProperty("angus.sslengine.readerClasses", "");
        if (mode.equals("angus"))
            Assume.assumeTrue("Reader-thread comparison requires its compiled provider",
                    !readerClasses.isEmpty());

        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        if ((mode.equals("angus") || mode.equals("sequential")) && !readerClasses.isEmpty()) {
            Path classes = Paths.get(readerClasses).toAbsolutePath();
            assertTrue("Not an Angus provider classes directory: " + classes,
                    Files.isRegularFile(classes.resolve(
                            "org/eclipse/angus/mail/smtp/SMTPTransport.class")));
            classpath = classes + File.pathSeparator + classpath;
        }
        URL security = SMTPSSLEngineTest.class.getResource("sslengine-keyupdate.security");
        assertNotNull("Missing diagnostic TLS properties", security);
        String java = Paths.get(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        String name = mode + "-" + (implicit ? "implicit" : "starttls") + "-" +
                scenario + "-mail" + mailReply + "-rcpt" + rcptReply + "-chunk" + ioChunk;
        Path log = output.resolve(name + ".log");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-ea");
        command.add("-cp");
        command.add(classpath);
        command.add("-Djava.security.properties=" + security.toExternalForm());
        command.add("-Djavax.net.debug=ssl:handshake");
        command.add("-Dprobe.mailReply=" + mailReply);
        command.add("-Dprobe.rcptReply=" + rcptReply);
        command.add("-Dprobe.ioChunk=" + ioChunk);
        command.add(SMTPSSLEngineProbe.class.getName());
        command.add(mode);
        command.add(Boolean.toString(implicit));
        command.add(scenario);
        Process child = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue("Child did not exit within 45 seconds; see " + log,
                    child.waitFor(45, TimeUnit.SECONDS));
            assertEquals("Child failed; see " + log, 0, child.exitValue());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            // This process belongs to this test. No other JVM is terminated.
            if (child.isAlive()) {
                child.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try {
                    assertTrue("Child survived forced cleanup; see " + log,
                            child.waitFor(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    interrupted = true;
                    throw failure;
                } finally {
                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
            }
        }
        String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        String expected = scenario.equals("pressure") ? "PASS" : "EXPECTED_FAILURE";
        boolean stalled = mode.equals("angus") && text.contains("ENGINE-RESULT outcome=STALL ");
        assertTrue("Unexpected result; see " + log,
                text.contains("ENGINE-RESULT outcome=" + expected + " ") || stalled);
        if (scenario.equals("pressure") && !stalled) {
            assertTrue("No outgoing TLS key update recorded; see " + log,
                    text.contains("Produced KeyUpdate"));
            assertTrue("No incoming TLS key update recorded; see " + log,
                    text.contains("Consuming KeyUpdate"));
        }
        // Scheduling and OS socket buffers vary. Report blocked-write progress
        // and comparison stalls rather than asserting a particular frequency.
        for (String line : text.split("\\R")) {
            if (line.startsWith("ENGINE-RUNTIME") || line.startsWith("ENGINE-METRICS") ||
                    line.startsWith("ENGINE-RESULT"))
                System.out.println(line);
        }
    }
}
