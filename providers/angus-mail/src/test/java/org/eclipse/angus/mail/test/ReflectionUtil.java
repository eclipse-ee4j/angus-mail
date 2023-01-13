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

package org.eclipse.angus.mail.test;

import java.lang.reflect.Field;

public final class ReflectionUtil {
    private ReflectionUtil() {
        throw new UnsupportedOperationException();
    }

    public static Field setFieldValue(Object object,
                                      String fieldName,
                                      Object valueTobeSet) throws NoSuchFieldException, IllegalAccessException {
        Field field = getField(object.getClass(), fieldName);
        field.setAccessible(true);
        field.set(object, valueTobeSet);
        return field;
    }

    public static Object getPrivateFieldValue(Object object,
                                              String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field field = getField(object.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Field getField(Class mClass, String fieldName) throws NoSuchFieldException {
        try {
            return mClass.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class superClass = mClass.getSuperclass();
            if (superClass == null) {
                throw e;
            } else {
                return getField(superClass, fieldName);
            }
        }
    }
}
