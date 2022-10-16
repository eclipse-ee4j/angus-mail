package com.sun.mail.test;

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
