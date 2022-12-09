/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.mail.nativeimage;

import jakarta.mail.Provider;
import jakarta.mail.Service;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.text.MessageFormat;
import java.util.ServiceLoader;
import java.util.function.Supplier;

public class AngusMailFeature implements Feature {

    private static final boolean ENABLED = getOption("angus.mail.native-image.enable", true);
    private static final boolean DEBUG = getOption("angus.mail.native-image.trace", false);

    /**
     * Default constructor.
     */
    public AngusMailFeature() {
    }

    @Override
    public boolean isInConfiguration(Feature.IsInConfigurationAccess access) {
        return ENABLED;
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        final ServiceLoader<? extends Provider> providers = ServiceLoader.load(Provider.class);
        for (Provider p : providers) {
            @SuppressWarnings({"unchecked"})
            Class<? extends Service> pc = (Class<? extends Service>) access.findClassByName(p.getClassName());
            if (pc != null) {
                log(() -> MessageFormat.format("Registering {0}", pc));
                RuntimeReflection.register(pc);
                try {
                    RuntimeReflection.register(pc.getConstructor(Session.class, URLName.class));
                } catch (NoSuchMethodException e) {
                    log(() -> MessageFormat.format("\tno constructor for {0}", pc));
                }
            } else {
                log(() -> MessageFormat.format("Class '{0}' for provider '{1}' not found", p.getClassName(), p.getClass().getName()));
            }
        }
    }

    private static void log(Supplier<String> msg) {
        if (DEBUG) {
            System.out.println(msg.get());
        }
    }

    private static boolean getOption(String name, boolean def) {
        String prop = System.getProperty(name);
        if (prop == null) {
            return def;
        }
        return Boolean.parseBoolean(name);
    }

}
