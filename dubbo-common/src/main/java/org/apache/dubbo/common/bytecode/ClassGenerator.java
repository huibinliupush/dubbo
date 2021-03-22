/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.bytecode;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClassGenerator
 */
public final class ClassGenerator {

    private static final AtomicLong CLASS_NAME_COUNTER = new AtomicLong(0);
    private static final String SIMPLE_NAME_TAG = "<init>";

    //ClassPool的缓存，一个ClassLoader对应一个ClassPool
    private static final Map<ClassLoader, ClassPool> POOL_MAP = new ConcurrentHashMap<ClassLoader, ClassPool>(); //ClassLoader - ClassPool

    //CtClass的容器（hashTable key为class name），字节码修改的地方
    /**
     *  A ClassPool object is a container of CtClass objects. Once a CtClass object is created,
     *  it is recorded in a ClassPool for ever.
     *  This is because a compiler may need to access the CtClass object later
     *  when it compiles source code that refers to the class represented by that CtClass.
     *
     * */
    private ClassPool mPool;

    //class file的抽像
    /**
     * The class Javassist.CtClass is an abstract representation of a class file. A CtClass (compile-time class) object is a handle for dealing with a class file
     * CtClass provides methods for introspection. The introspective ability of Javassist is compatible with that of the Java reflection API.
     * CtClass provides getName(), getSuperclass(), getMethods(), and so on.
     * CtClass also provides methods for modifying a class definition.
     * It allows to add a new field, constructor, and method. Instrumenting a method body is also possible.
     * */
    private CtClass mCtc;
    //包装类名称
    private String mClassName;
    //包装类的父类
    private String mSuperClass;
    //包装类接口集合
    private Set<String> mInterfaces;
    //包装类字段集合
    private List<String> mFields;
    //包装类构造器集合(包括构造器的方法体)
    private List<String> mConstructors;
    //方法（包括方法签名和方法体）集合
    private List<String> mMethods;
    private Map<String, Method> mCopyMethods; // <method desc,method instance>
    private Map<String, Constructor<?>> mCopyConstructors; // <constructor desc,constructor instance>
    //是否为代理类生成的默认构造方法
    private boolean mDefaultConstructor = false;

    private ClassGenerator() {
    }

    private ClassGenerator(ClassPool pool) {
        mPool = pool;
    }

    public static ClassGenerator newInstance() {
        return new ClassGenerator(getClassPool(Thread.currentThread().getContextClassLoader()));
    }

    public static ClassGenerator newInstance(ClassLoader loader) {
        return new ClassGenerator(getClassPool(loader));
    }

    public static boolean isDynamicClass(Class<?> cl) {
        return ClassGenerator.DC.class.isAssignableFrom(cl);
    }

    /**
     * Class search path
     * The default ClassPool returned by a static method ClassPool.getDefault() searches the same path that the underlying JVM (Java virtual machine) has.
     * If a program is running on a web application server such as JBoss and Tomcat,
     * the ClassPool object may not be able to find user classes
     * since such a web application server uses multiple class loaders as well as the system class loader.
     * In that case, an additional class path must be registered to the ClassPool. Suppose that pool refers to a ClassPool object:
     *
     * pool.insertClassPath(new ClassClassPath(this.getClass()));
     * This statement registers the class path that was used for loading the class of the object that this refers to.
     * You can use any Class object as an argument instead of this.getClass().
     * The class path used for loading the class represented by that Class object is registered.
     *
     * You can register a directory name as the class search path. For example, the following code adds a directory /usr/local/javalib to the search path:
     *
     * ClassPool pool = ClassPool.getDefault();
     * pool.insertClassPath("/usr/local/javalib");
     *
     * */
    public static ClassPool getClassPool(ClassLoader loader) {
        if (loader == null) {
            return ClassPool.getDefault();
        }

        ClassPool pool = POOL_MAP.get(loader);
        if (pool == null) {
            pool = new ClassPool(true);
            //@see Class search path 通过classPath寻找加载类
            pool.appendClassPath(new LoaderClassPath(loader));
            POOL_MAP.put(loader, pool);
        }
        return pool;
    }

    private static String modifier(int mod) {
        StringBuilder modifier = new StringBuilder();
        if (Modifier.isPublic(mod)) {
            modifier.append("public");
        } else if (Modifier.isProtected(mod)) {
            modifier.append("protected");
        } else if (Modifier.isPrivate(mod)) {
            modifier.append("private");
        }

        if (Modifier.isStatic(mod)) {
            modifier.append(" static");
        }
        if (Modifier.isVolatile(mod)) {
            modifier.append(" volatile");
        }

        return modifier.toString();
    }

    public String getClassName() {
        return mClassName;
    }

    public ClassGenerator setClassName(String name) {
        mClassName = name;
        return this;
    }

    public ClassGenerator addInterface(String cn) {
        if (mInterfaces == null) {
            mInterfaces = new HashSet<String>();
        }
        mInterfaces.add(cn);
        return this;
    }

    public ClassGenerator addInterface(Class<?> cl) {
        return addInterface(cl.getName());
    }

    public ClassGenerator setSuperClass(String cn) {
        mSuperClass = cn;
        return this;
    }

    public ClassGenerator setSuperClass(Class<?> cl) {
        mSuperClass = cl.getName();
        return this;
    }

    public ClassGenerator addField(String code) {
        if (mFields == null) {
            mFields = new ArrayList<String>();
        }
        mFields.add(code);
        return this;
    }

    public ClassGenerator addField(String name, int mod, Class<?> type) {
        return addField(name, mod, type, null);
    }

    public ClassGenerator addField(String name, int mod, Class<?> type, String def) {
        StringBuilder sb = new StringBuilder();
        sb.append(modifier(mod)).append(' ').append(ReflectUtils.getName(type)).append(' ');
        sb.append(name);
        if (StringUtils.isNotEmpty(def)) {
            sb.append('=');
            sb.append(def);
        }
        sb.append(';');
        return addField(sb.toString());
    }

    public ClassGenerator addMethod(String code) {
        if (mMethods == null) {
            mMethods = new ArrayList<String>();
        }
        mMethods.add(code);
        return this;
    }

    public ClassGenerator addMethod(String name, int mod, Class<?> rt, Class<?>[] pts, String body) {
        return addMethod(name, mod, rt, pts, null, body);
    }

    public ClassGenerator addMethod(String name, int mod, Class<?> rt, Class<?>[] pts, Class<?>[] ets,
                                    String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(modifier(mod)).append(' ').append(ReflectUtils.getName(rt)).append(' ').append(name);
        sb.append('(');
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ReflectUtils.getName(pts[i]));
            sb.append(" arg").append(i);
        }
        sb.append(')');
        if (ArrayUtils.isNotEmpty(ets)) {
            sb.append(" throws ");
            for (int i = 0; i < ets.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ReflectUtils.getName(ets[i]));
            }
        }
        sb.append('{').append(body).append('}');
        return addMethod(sb.toString());
    }

    public ClassGenerator addMethod(Method m) {
        addMethod(m.getName(), m);
        return this;
    }

    public ClassGenerator addMethod(String name, Method m) {
        String desc = name + ReflectUtils.getDescWithoutMethodName(m);
        addMethod(':' + desc);
        if (mCopyMethods == null) {
            mCopyMethods = new ConcurrentHashMap<String, Method>(8);
        }
        mCopyMethods.put(desc, m);
        return this;
    }

    public ClassGenerator addConstructor(String code) {
        if (mConstructors == null) {
            mConstructors = new LinkedList<String>();
        }
        mConstructors.add(code);
        return this;
    }

    public ClassGenerator addConstructor(int mod, Class<?>[] pts, String body) {
        return addConstructor(mod, pts, null, body);
    }

    public ClassGenerator addConstructor(int mod, Class<?>[] pts, Class<?>[] ets, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(modifier(mod)).append(' ').append(SIMPLE_NAME_TAG);
        sb.append('(');
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ReflectUtils.getName(pts[i]));
            sb.append(" arg").append(i);
        }
        sb.append(')');
        if (ArrayUtils.isNotEmpty(ets)) {
            sb.append(" throws ");
            for (int i = 0; i < ets.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ReflectUtils.getName(ets[i]));
            }
        }
        sb.append('{').append(body).append('}');
        return addConstructor(sb.toString());
    }

    public ClassGenerator addConstructor(Constructor<?> c) {
        String desc = ReflectUtils.getDesc(c);
        addConstructor(":" + desc);
        if (mCopyConstructors == null) {
            mCopyConstructors = new ConcurrentHashMap<String, Constructor<?>>(4);
        }
        mCopyConstructors.put(desc, c);
        return this;
    }

    public ClassGenerator addDefaultConstructor() {
        mDefaultConstructor = true;
        return this;
    }

    public ClassPool getClassPool() {
        return mPool;
    }

    public Class<?> toClass() {
        return toClass(ClassUtils.getClassLoader(ClassGenerator.class),
                getClass().getProtectionDomain());
    }

    public Class<?> toClass(ClassLoader loader, ProtectionDomain pd) {
        if (mCtc != null) {
            //防止ClassPool内存泄漏，调用detach()方法 删除CtClass
            /**
             * You must not call any method on that CtClass object after detach() is called.
             * However, you can call get() on ClassPool to make a new instance of CtClass representing the same class.
             * If you call get(), the ClassPool reads a class file again and newly creates a CtClass object,
             * which is returned by get().
             *
             * */
            mCtc.detach();
        }
        long id = CLASS_NAME_COUNTER.getAndIncrement();
        try {
            //get() in ClassPool searches this hash table to find a CtClass object associated with the specified key.
            // If such a CtClass object is not found, get() reads a class file to construct a new CtClass object,
            // which is recorded in the hash table and then returned as the resulting value of get().
            //从ClassPool中加载指定的父类
            CtClass ctcs = mSuperClass == null ? null : mPool.get(mSuperClass);
            //缺省类名生成规则
            if (mClassName == null) {
                mClassName = (mSuperClass == null || javassist.Modifier.isPublic(ctcs.getModifiers())
                        ? ClassGenerator.class.getName() : mSuperClass + "$sc") + id;
            }
            //生成Wrapper包装类的CtClass
            //To define a new class from scratch, makeClass() must be called on a ClassPool.
            mCtc = mPool.makeClass(mClassName);
            if (mSuperClass != null) {
                mCtc.setSuperclass(ctcs);
            }
            //Wrapper类是动态生成的类  设置动态类标识DC.class
            mCtc.addInterface(mPool.get(DC.class.getName())); // add dynamic class tag.
            if (mInterfaces != null) {
                for (String cl : mInterfaces) {
                    mCtc.addInterface(mPool.get(cl));
                }
            }
            /**
             * Javassist also allows the users to create a new field.
             *
             * CtClass point = ClassPool.getDefault().get("Point");
             * CtField f = new CtField(CtClass.intType, "z", point);
             * point.addField(f);
             * This program adds a field named z to class Point.
             *
             * If the initial value of the added field must be specified, the program shown above must be modified into:
             *
             * CtClass point = ClassPool.getDefault().get("Point");
             * CtField f = new CtField(CtClass.intType, "z", point);
             * point.addField(f, "0");    // initial value is 0.
             * Now, the method addField() receives the second parameter, which is the source text representing an expression computing the initial value. This source text can be any Java expression if the result type of the expression matches the type of the field. Note that an expression does not end with a semi colon (;).
             *
             * Furthermore, the above code can be rewritten into the following simple code:
             *
             * CtClass point = ClassPool.getDefault().get("Point");
             * CtField f = CtField.make("public int z = 0;", point);
             * point.addField(f);
             *
             * */
            //CtField是类中字段的抽像
            if (mFields != null) {
                for (String code : mFields) {
                    mCtc.addField(CtField.make(code, mCtc));
                }
            }
            //CtNewMethod是类中方法的抽像
            if (mMethods != null) {
                for (String code : mMethods) {
                    if (code.charAt(0) == ':') {
                        //去掉开头:  重新设置方法
                        mCtc.addMethod(CtNewMethod.copy(getCtMethod(mCopyMethods.get(code.substring(1))),
                                code.substring(1, code.indexOf('(')), mCtc, null));
                    } else {
                        mCtc.addMethod(CtNewMethod.make(code, mCtc));
                    }
                }
            }
            //创建默认构造器
            if (mDefaultConstructor) {
                mCtc.addConstructor(CtNewConstructor.defaultConstructor(mCtc));
            }
            //设置构造器
            if (mConstructors != null) {
                for (String code : mConstructors) {
                    if (code.charAt(0) == ':') {
                        mCtc.addConstructor(CtNewConstructor
                                .copy(getCtConstructor(mCopyConstructors.get(code.substring(1))), mCtc, null));
                    } else {
                        String[] sn = mCtc.getSimpleName().split("\\$+"); // inner class name include $.
                        mCtc.addConstructor(
                                CtNewConstructor.make(code.replaceFirst(SIMPLE_NAME_TAG, sn[sn.length - 1]), mCtc));
                    }
                }
            }
            //利用Javassit动态生成Wrapper类
            return mCtc.toClass(loader, pd);
        } catch (RuntimeException e) {
            throw e;
        } catch (NotFoundException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (CannotCompileException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void release() {
        if (mCtc != null) {
            mCtc.detach();
        }
        if (mInterfaces != null) {
            mInterfaces.clear();
        }
        if (mFields != null) {
            mFields.clear();
        }
        if (mMethods != null) {
            mMethods.clear();
        }
        if (mConstructors != null) {
            mConstructors.clear();
        }
        if (mCopyMethods != null) {
            mCopyMethods.clear();
        }
        if (mCopyConstructors != null) {
            mCopyConstructors.clear();
        }
    }

    private CtClass getCtClass(Class<?> c) throws NotFoundException {
        return mPool.get(c.getName());
    }

    private CtMethod getCtMethod(Method m) throws NotFoundException {
        return getCtClass(m.getDeclaringClass())
                .getMethod(m.getName(), ReflectUtils.getDescWithoutMethodName(m));
    }

    private CtConstructor getCtConstructor(Constructor<?> c) throws NotFoundException {
        return getCtClass(c.getDeclaringClass()).getConstructor(ReflectUtils.getDesc(c));
    }

    public static interface DC {

    } // dynamic class tag interface.
}