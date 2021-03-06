package io.github.dbstarll.dubai.model.entity;

import io.github.dbstarll.dubai.model.entity.utils.PackageUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.CloneFailedException;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EntityFactory<E extends Entity> implements InvocationHandler, Serializable {
    private static final long serialVersionUID = 1190830425462840117L;

    private static final Map<Class<?>, Object> DEFAULT_VALUES = getDefaultValues();
    private static final ConcurrentMap<Class<?>, Map<String, Object>> DEFAULT_FIELDS = new ConcurrentHashMap<>();

    private final Class<E> entityClass;
    private final ConcurrentMap<String, Object> fields;

    private EntityFactory(Class<E> entityClass, Map<String, Object> fields) {
        this.entityClass = entityClass;
        this.fields = fields == null ? new ConcurrentHashMap<String, Object>() : new ConcurrentHashMap<>(fields);
        setDefaultValue(this.fields, getDefaultPrimitiveFields(entityClass));
    }

    private static Map<Class<?>, Object> getDefaultValues() {
        final Map<Class<?>, Object> values = new HashMap<Class<?>, Object>();
        values.put(Byte.TYPE, (byte) 0);
        values.put(Short.TYPE, (short) 0);
        values.put(Integer.TYPE, 0);
        values.put(Long.TYPE, 0L);
        values.put(Boolean.TYPE, false);
        values.put(Character.TYPE, '\u0000');
        values.put(Float.TYPE, 0.0f);
        values.put(Double.TYPE, 0.0d);
        return values;
    }

    private static Map<String, Object> getDefaultPrimitiveFields(Class<?> entityClass) {
        if (!DEFAULT_FIELDS.containsKey(entityClass)) {
            final Map<String, Object> fileds = new HashMap<String, Object>();
            for (Method method : entityClass.getMethods()) {
                final String fieldName = getWriteProperty(method);
                if (null != fieldName) {
                    final Class<?> fieldType = method.getParameterTypes()[0];
                    if (fieldType.isPrimitive()) {
                        fileds.put(fieldName, DEFAULT_VALUES.get(fieldType));
                    }
                }
            }
            DEFAULT_FIELDS.putIfAbsent(entityClass, fileds);
        }
        return DEFAULT_FIELDS.get(entityClass);
    }

    private static String getWriteProperty(Method method) {
        if (method.getReturnType() == Void.TYPE && method.getParameterTypes().length == 1
                && method.getName().startsWith("set")) {
            return getReplaceProperty(StringUtils.uncapitalize(method.getName().substring(3)));
        } else {
            return null;
        }
    }

    private static String getReadProperty(Method method) {
        if (method.getReturnType() != Void.TYPE) {
            if (method.getName().startsWith("get")) {
                return getReplaceProperty(StringUtils.uncapitalize(method.getName().substring(3)));
            } else if (method.getName().startsWith("is")) {
                return getReplaceProperty(StringUtils.uncapitalize(method.getName().substring(2)));
            }
        }
        return null;
    }

    private static String getReplaceProperty(String property) {
        return "id".equals(property) ? Entity.FIELD_NAME_ID : property;
    }

    private static void setDefaultValue(ConcurrentMap<String, Object> fields, Map<String, Object> values) {
        for (Entry<String, Object> entry : values.entrySet()) {
            fields.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        } else if (method.getDeclaringClass() == PojoFields.class) {
            return fields;
        }

        String property;
        final int argsLangth = ArrayUtils.getLength(args);
        if (argsLangth == 1 && StringUtils.isNotBlank(property = getWriteProperty(method))) {
            if (null == args[0]) {
                fields.remove(property);
            } else {
                fields.put(property, args[0]);
            }
            return null;
        } else if (argsLangth == 0) {
            if (StringUtils.isNotBlank(property = getReadProperty(method))) {
                return fields.get(property);
            } else if ("clone".equals(method.getName())) {
                return EntityFactory.newInstance(entityClass, fields);
            }
        }

        throw new UnsupportedOperationException(method.toString());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + entityClass.hashCode();
        result = prime * result + fields.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !Proxy.isProxyClass(obj.getClass())) {
            return false;
        }
        final InvocationHandler handler = Proxy.getInvocationHandler(obj);
        if (this == handler) {
            return true;
        }
        if (getClass() != handler.getClass()) {
            return false;
        }
        final EntityFactory<?> other = (EntityFactory<?>) handler;
        return entityClass.equals(other.entityClass) && fields.equals(other.fields);
    }

    @Override
    public String toString() {
        return entityClass.getName() + fields;
    }

    public static <E extends Entity> E newInstance(Class<E> entityClass) {
        return newInstance(entityClass, null);
    }

    /**
     * ????????????????????????.
     *
     * @param entityClass ?????????
     * @param fields      ?????????
     * @return ??????
     */
    @SuppressWarnings("unchecked")
    public static <E extends Entity> E newInstance(Class<E> entityClass, Map<String, Object> fields) {
        if (isEntityClass(entityClass)) {
            if (entityClass.isInterface()) {
                final Class<?> packageInterface = PackageUtils.getPackageInterface(entityClass, Package.class);
                return (E) Proxy.newProxyInstance(entityClass.getClassLoader(),
                        new Class[]{entityClass, PojoFields.class, EntityModifier.class, packageInterface},
                        new EntityFactory<>(entityClass, fields));
            } else {
                try {
                    return entityClass.newInstance();
                } catch (Throwable ex) {
                    throw new UnsupportedOperationException("Instantiation fails: " + entityClass, ex);
                }
            }
        } else {
            throw new UnsupportedOperationException("Invalid EntityClass: " + entityClass);
        }
    }

    /**
     * ??????????????????????????????.
     *
     * @param entityClass ?????????
     * @return ??????????????????????????????????????????true???????????????false
     */
    public static <E extends Entity> boolean isEntityClass(Class<E> entityClass) {
        if (!Modifier.isAbstract(entityClass.getModifiers())) {
            if (entityClass.getAnnotation(Table.class) != null) {
                try {
                    entityClass.getConstructor();
                } catch (NoSuchMethodException | SecurityException e) {
                    return false;
                }
                return true;
            }
        } else if (entityClass.isInterface()) {
            return entityClass.getAnnotation(Table.class) != null;
        }
        return false;
    }

    /**
     * Clone an entity.
     *
     * @param proxy the entity to clone, null returns null
     * @return the clone of entity
     */
    @SuppressWarnings("unchecked")
    public static <E extends Entity> E clone(E proxy) {
        try {
            if (proxy == null) {
                return null;
            } else if (proxy instanceof EntityModifier) {
                return (E) ((EntityModifier) proxy).clone();
            } else {
                return ObjectUtils.clone(proxy);
            }
        } catch (CloneNotSupportedException e) {
            throw new UnsupportedOperationException(e);
        } catch (CloneFailedException e) {
            throw new UnsupportedOperationException(e.getMessage(), e.getCause());
        }
    }

    /**
     * ?????????????????????????????????.
     *
     * @param proxy ????????????
     * @return ???????????????????????????
     */
    @SuppressWarnings("unchecked")
    public static <E extends Entity> Class<E> getEntityClass(E proxy) {
        if (Proxy.isProxyClass(proxy.getClass())) {
            final InvocationHandler handler = Proxy.getInvocationHandler(proxy);
            if (EntityFactory.class.isInstance(handler)) {
                return ((EntityFactory<E>) handler).entityClass;
            }
        }
        return (Class<E>) proxy.getClass();
    }

    /**
     * ??????????????????????????????.
     *
     * @param proxyClass ?????????
     * @return ????????????????????????
     */
    @SuppressWarnings("unchecked")
    public static <E extends Entity> Class<E> getEntityClass(Class<E> proxyClass) {
        Class<E> c = proxyClass;
        if (Proxy.isProxyClass(proxyClass)) {
            for (Class<?> i : proxyClass.getInterfaces()) {
                if (Entity.class.isAssignableFrom(i)) {
                    c = (Class<E>) i;
                }
            }
        }
        return c;
    }

    public interface PojoFields {
        Map<String, Object> fields();
    }
}
