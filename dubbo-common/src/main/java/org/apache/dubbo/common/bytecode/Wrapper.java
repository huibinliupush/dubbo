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

import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Wrapper.
 */
public abstract class Wrapper {
    //Wrapper的缓存 key:要进行包装的类（服务实现类ref） value：包装类Wrapper
    private static final Map<Class<?>, Wrapper> WRAPPER_MAP = new ConcurrentHashMap<Class<?>, Wrapper>(); //class wrapper map
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String[] OBJECT_METHODS = new String[]{"getClass", "hashCode", "toString", "equals"};
    private static final Wrapper OBJECT_WRAPPER = new Wrapper() {
        @Override
        public String[] getMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getDeclaredMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getPropertyNames() {
            return EMPTY_STRING_ARRAY;
        }

        @Override
        public Class<?> getPropertyType(String pn) {
            return null;
        }

        @Override
        public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public boolean hasProperty(String name) {
            return false;
        }

        @Override
        public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException {
            if ("getClass".equals(mn)) {
                return instance.getClass();
            }
            if ("hashCode".equals(mn)) {
                return instance.hashCode();
            }
            if ("toString".equals(mn)) {
                return instance.toString();
            }
            if ("equals".equals(mn)) {
                if (args.length == 1) {
                    return instance.equals(args[0]);
                }
                throw new IllegalArgumentException("Invoke method [" + mn + "] argument number error.");
            }
            throw new NoSuchMethodException("Method [" + mn + "] not found.");
        }
    };
    private static AtomicLong WRAPPER_CLASS_COUNTER = new AtomicLong(0);

    /**
     * get wrapper.
     *
     * @param c Class instance.
     * @return Wrapper instance(not null).
     */
    public static Wrapper getWrapper(Class<?> c) {
        //是否继承ClassGenerator.DC.class接口
        //ClassGenerator.DC.class 动态类的标识
        while (ClassGenerator.isDynamicClass(c)) // can not wrapper on dynamic class.
        {
            c = c.getSuperclass();
        }

        //如果需要包装的对象为Object.class 则返回Object的包装类（不重要）
        if (c == Object.class) {
            return OBJECT_WRAPPER;
        }

        //缓存里获取Wrapper类，如果没有则调用makeWrapper创建并缓存
        return WRAPPER_MAP.computeIfAbsent(c, key -> makeWrapper(key));
    }

    private static Wrapper makeWrapper(Class<?> c) {
        if (c.isPrimitive()) {
            throw new IllegalArgumentException("Can not create wrapper for primitive type: " + c);
        }

        //org.apache.dubbo.demo.provider.DemoServiceImpl
        String name = c.getName();
        //获取classloader
        ClassLoader cl = ClassUtils.getClassLoader(c);

        //构建setPropertyValue方法体 负责设置类中public字段的值 参数1：具体实现类 ，参数2：设置的public字段名字 ，参数3：设置public属性值
        StringBuilder c1 = new StringBuilder("public void setPropertyValue(Object o, String n, Object v){ ");
        //构建getPropertyValue方法体 负责获取类中public字段的值 参数1：具体实现类 ，参数2：设置的public字段名字
        StringBuilder c2 = new StringBuilder("public Object getPropertyValue(Object o, String n){ ");
        //构建invokeMethod方法体  负责代理执行类中所有public方法  参数1：代理类  参数2：要执行的方法名 参数3：方法参数类型集合 参数4：方法参数值
        StringBuilder c3 = new StringBuilder("public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws " + InvocationTargetException.class.getName() + "{ ");

        /**
         *     public void setPropertyValue(Object o, String n, Object v){
         *         org.apache.dubbo.demo.provider.DemoServiceImpl w;
         *         try{
         *             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl)$1);
         *         }catch(Throwable e){
         *             throw new IllegalArgumentException(e);
         *         }
         *
         * */
        c1.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        /**
         *     public Object getPropertyValue(Object o, String n) {
         *         org.apache.dubbo.demo.provider.DemoServiceImpl w;
         *         try {
         *             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl) $1);
         *         } catch (Throwable e) {
         *             throw new IllegalArgumentException(e);
         *         }
         *     }
         *
         * */
        c2.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        /**
         *     public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException {
         *         org.apache.dubbo.demo.provider.DemoServiceImpl w;
         *         try {
         *             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl) $1);
         *         } catch (Throwable e) {
         *             throw new IllegalArgumentException(e);
         *         }
         *     }
         *
         * */
        c3.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");

        //存储类中属性名称 和 对应类型的映射
        Map<String, Class<?>> pts = new HashMap<>(); // <property name, property types>
        //存储 方法描述（反射过去methodDesc）和 对应 方法 的 映射
        Map<String, Method> ms = new LinkedHashMap<>(); // <method desc, Method instance>
        //方法名称集合
        List<String> mns = new ArrayList<>(); // method names.
        //declaring method names集合
        List<String> dmns = new ArrayList<>(); // declaring method names.

        // get all public field.
        //如果类中含有public字段 则设置setPropertyValue，getPropertyValue方法体
        for (Field f : c.getFields()) {
            String fn = f.getName();
            Class<?> ft = f.getType();
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            // if( $2.equals("name") ) { w.name = (java.lang.String) $3; return;}
            // if( $2.equals("age") ) { w.age = ((Number) $3).intValue(); return;}
            c1.append(" if( $2.equals(\"").append(fn).append("\") ){ w.").append(fn).append("=").append(arg(ft, "$3")).append("; return; }");
            // if( $2.equals("name") ) { return ($w)w.name; }
            c2.append(" if( $2.equals(\"").append(fn).append("\") ){ return ($w)w.").append(fn).append("; }");
            pts.put(fn, ft);
        }
        // 支持方法继承
        Method[] methods = c.getMethods();
        // get all public method.
        //设置invokeMethod方法体
        boolean hasMethod = hasMethods(methods);
        if (hasMethod) {
            c3.append(" try{");
            for (Method m : methods) {
                //ignore Object's method.忽略object父类的方法
                // 找到方法的是在哪个类中实现的
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }

                String mn = m.getName();
                //在invokeMethod方法中增加匹配方法的代码
                //if条件罗列每一个方法:。if条件需要能精确匹配到每一个唯一方法（区分重载方法）
                // 匹配条件：1：方法名与参数指定的方法名相同。2：方法参数类型个数与参数指定的方法参数类型个数相同，3重载方法的判断（参数类型必须一致）
                // 生成方法名是否相同判断语句：if ( "sayHello".equals( $2 )
                c3.append(" if( \"").append(mn).append("\".equals( $2 ) ");
                int len = m.getParameterTypes().length;
                // 生成方法参数个数与运行时传入参数个数是否相同判断语句 ：&& $3.length == 2
                c3.append(" && ").append(" $3.length == ").append(len);

                //判断该方法是否为重写方法
                //匹配条件：1：类中存在方法名相同但是method不同的方法 2：如果是重载方法 需要匹配到真正的方法 参数类型和参数中指定的方法参数类型必须一致

                /**
                 *             if( "wrapperReturnVoid".equals( $2 )  &&  $3.length == 1 &&  $3[0].getName().equals("java.lang.Integer") ) {
                 *                 return ($w)w.wrapperReturnVoid((java.lang.Integer)$4[0]);
                 *             }
                 *
                 *             if( "wrapperReturnVoid".equals( $2 )  &&  $3.length == 1 &&  $3[0].getName().equals("java.lang.String") ) {
                 *                 w.wrapperReturnVoid((java.lang.String)$4[0]); return null;
                 *             }
                 *
                 * */
                boolean override = false;
                for (Method m2 : methods) {
                    if (m != m2 && m.getName().equals(m2.getName())) {
                        override = true;
                        break;
                    }
                }
                //如果方法名相同 那么就要判断方法参数类型是否一致
                if (override) {
                    if (len > 0) {
                        for (int l = 0; l < len; l++) {
                            //生成运行时传入的参数类型 与 方法签名中的类型 是否全部相同 判断语句
                            // && $3[0].getName().equals("java.lang.Integer")
                            // && $3[1].getName().equals("java.lang.String")
                            c3.append(" && ").append(" $3[").append(l).append("].getName().equals(\"")
                                    .append(m.getParameterTypes()[l].getName()).append("\")");
                        }
                    }
                }

                c3.append(" ) { ");

                //根据返回类型的不同 拼接 调用目标方法代码
                if (m.getReturnType() == Void.TYPE) {
                    // w.sayHello((java.lang.Integer)$4[0], (java.lang.String)$4[1]); return null;
                    c3.append(" w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");").append(" return null;");
                } else {
                    //($w) w.sayHello((java.lang.String) $4[0]);
                    c3.append(" return ($w)w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");");
                }

                c3.append(" }");

                mns.add(mn);
                // 找到方法的是在哪个类中实现的
                if (m.getDeclaringClass() == c) {
                    dmns.add(mn);
                }
                ms.put(ReflectUtils.getDesc(m), m);
            }
            c3.append(" } catch(Throwable e) { ");
            c3.append("     throw new java.lang.reflect.InvocationTargetException(e); ");
            c3.append(" }");
        }

        c3.append(" throw new " + NoSuchMethodException.class.getName() + "(\"Not found method \\\"\"+$2+\"\\\" in class " + c.getName() + ".\"); }");

        // deal with get/set method.
        //设置setPropertyValue，getPropertyValue方法体
        //get方法会委托给wrapper类的getPropertyValue 方法中调用get方法
        //set方法会委托给wrapper类的setPropertyValue 方法中调用set方法
        Matcher matcher;
        for (Map.Entry<String, Method> entry : ms.entrySet()) {
            String md = entry.getKey();
            Method method = entry.getValue();
            if ((matcher = ReflectUtils.GETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                //从方法名中解析属性
                String pn = propertyName(matcher.group(1));
                //get相关的方法 委托给wrapper类中的getPropertyValue方法
                //if( $2.equals("name") ) { return ($w).w.getName(); }
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
            } else if ((matcher = ReflectUtils.IS_HAS_CAN_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                //从方法名中解析属性
                String pn = propertyName(matcher.group(1));
                //is，has,can开头的相关的方法 委托给wrapper类中的getPropertyValue方法
                //if( $2.equals("dream") ) { return ($w).w.hasDream(); }
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
            } else if ((matcher = ReflectUtils.SETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                Class<?> pt = method.getParameterTypes()[0];
                //从方法名中解析属性
                String pn = propertyName(matcher.group(1));
                //set相关的方法委托给wrapper类中的setPropertyValue方法
                //if( $2.equals("name") ) { w.setName((java.lang.String)$3); return; }
                c1.append(" if( $2.equals(\"").append(pn).append("\") ){ w.").append(method.getName()).append("(").append(arg(pt, "$3")).append("); return; }");
                pts.put(pn, pt);
            }
        }
        c1.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class " + c.getName() + ".\"); }");
        c2.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class " + c.getName() + ".\"); }");

        //最终生成的方法体如下：
        /**
         *      public void setPropertyValue(Object o, String n, Object v){
         *         org.apache.dubbo.demo.provider.DemoServiceImpl w;
         *         try{
         *             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl)$1);
         *         }catch(Throwable e){
         *             throw new IllegalArgumentException(e);
         *         }
         *
         *         if( $2.equals("wrapperField") ){
         *             w.setWrapperField((java.lang.String)$3);
         *             return;
         *         }
         *         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \""+$2+"\" field or setter method in class org.apache.dubbo.demo.provider.DemoServiceImpl.");
         *     }
         * */


        /**
         *      public Object getPropertyValue(Object o, String n){
         *         org.apache.dubbo.demo.provider.DemoServiceImpl w;
         *         try{
         *             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl)$1);
         *         }catch(Throwable e){
         *             throw new IllegalArgumentException(e);
         *         }
         *
         *         if( $2.equals("wrapperField") ){
         *             return ($w)w.getWrapperField();
         *         }
         *
         *         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \""+$2+"\" field or setter method in class org.apache.dubbo.demo.provider.DemoServiceImpl.");
         *     }
         * */

        /**
         *   public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException{
         *
         *         org.apache.dubbo.demo.provider.DemoServiceImpl w;
         *
         *         try{
         *             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl)$1);
         *         }catch(Throwable e){
         *             throw new IllegalArgumentException(e);
         *         }
         *
         *         try{
         *             if( "wrapperReturnVoid".equals( $2 )  &&  $3.length == 1 &&  $3[0].getName().equals("java.lang.Integer") ) {
         *                 return ($w)w.wrapperReturnVoid((java.lang.Integer)$4[0]);
         *             }
         *
         *             if( "wrapperReturnVoid".equals( $2 )  &&  $3.length == 1 &&  $3[0].getName().equals("java.lang.String") ) {
         *                 w.wrapperReturnVoid((java.lang.String)$4[0]); return null;
         *             }
         *
         *             if( "setWrapperField".equals( $2 )  &&  $3.length == 1 ) {
         *                 w.setWrapperField((java.lang.String)$4[0]);
         *                 return null;
         *             }
         *
         *             if( "sayHello".equals( $2 )  &&  $3.length == 1 ) {
         *                 return ($w)w.sayHello((java.lang.String)$4[0]);
         *             }
         *
         *             if( "sayHelloAsync".equals( $2 )  &&  $3.length == 1 ) {
         *                 return ($w)w.sayHelloAsync((java.lang.String)$4[0]);
         *             }
         *
         *             if( "getWrapperField".equals( $2 )  &&  $3.length == 0 ) {
         *                 return ($w)w.getWrapperField();
         *             }
         *         } catch(Throwable e) {
         *             throw new java.lang.reflect.InvocationTargetException(e);
         *         }
         *
         *         throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \""+$2+"\" in class org.apache.dubbo.demo.provider.DemoServiceImpl.");
         *     }
         * */
        // make class
        long id = WRAPPER_CLASS_COUNTER.getAndIncrement();
        //创建类生成器
        ClassGenerator cc = ClassGenerator.newInstance(cl);
        //设置类名org.apache.dubbo.common.bytecode.Wrapper1
        cc.setClassName((Modifier.isPublic(c.getModifiers()) ? Wrapper.class.getName() : c.getName() + "$sw") + id);
        //设置父类
        cc.setSuperClass(Wrapper.class);
        //是否为代理类生成的默认构造方法
        cc.addDefaultConstructor();
        //设置类的字段信息
        cc.addField("public static String[] pns;"); // property name array.
        cc.addField("public static " + Map.class.getName() + " pts;"); // property type map.
        cc.addField("public static String[] mns;"); // all method name array.
        cc.addField("public static String[] dmns;"); // declared method name array.
        for (int i = 0, len = ms.size(); i < len; i++) {
            cc.addField("public static Class[] mts" + i + ";"); // 类中第 i 个方法的参数类型集合
        }

        //设置Wrapper子类的 方法体
        cc.addMethod("public String[] getPropertyNames(){ return pns; }");
        cc.addMethod("public boolean hasProperty(String n){ return pts.containsKey($1); }");
        cc.addMethod("public Class getPropertyType(String n){ return (Class)pts.get($1); }");
        cc.addMethod("public String[] getMethodNames(){ return mns; }");
        cc.addMethod("public String[] getDeclaredMethodNames(){ return dmns; }");
        //设置setPropertyValue方法
        cc.addMethod(c1.toString());
        //设置getPropertyValue方法
        cc.addMethod(c2.toString());
        //设置invokeMethod方法
        cc.addMethod(c3.toString());

        try {
            //加载创建的class
            Class<?> wc = cc.toClass();
            // setup static field.
            //设置静态属性
            wc.getField("pts").set(null, pts);
            wc.getField("pns").set(null, pts.keySet().toArray(new String[0]));
            wc.getField("mns").set(null, mns.toArray(new String[0]));
            wc.getField("dmns").set(null, dmns.toArray(new String[0]));
            int ix = 0;
            for (Method m : ms.values()) {
                wc.getField("mts" + ix++).set(null, m.getParameterTypes());
            }
            //返回包装了ref的Wrapper实例
            return (Wrapper) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            cc.release();
            ms.clear();
            mns.clear();
            dmns.clear();
        }
    }

    private static String arg(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (cl == Boolean.TYPE) {
                return "((Boolean)" + name + ").booleanValue()";
            }
            if (cl == Byte.TYPE) {
                return "((Byte)" + name + ").byteValue()";
            }
            if (cl == Character.TYPE) {
                return "((Character)" + name + ").charValue()";
            }
            if (cl == Double.TYPE) {
                return "((Number)" + name + ").doubleValue()";
            }
            if (cl == Float.TYPE) {
                return "((Number)" + name + ").floatValue()";
            }
            if (cl == Integer.TYPE) {
                return "((Number)" + name + ").intValue()";
            }
            if (cl == Long.TYPE) {
                return "((Number)" + name + ").longValue()";
            }
            if (cl == Short.TYPE) {
                return "((Number)" + name + ").shortValue()";
            }
            throw new RuntimeException("Unknown primitive type: " + cl.getName());
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    private static String args(Class<?>[] cs, String name) {
        int len = cs.length;
        if (len == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arg(cs[i], name + "[" + i + "]"));
        }
        return sb.toString();
    }

    private static String propertyName(String pn) {
        return pn.length() == 1 || Character.isLowerCase(pn.charAt(1)) ? Character.toLowerCase(pn.charAt(0)) + pn.substring(1) : pn;
    }

    private static boolean hasMethods(Method[] methods) {
        if (methods == null || methods.length == 0) {
            return false;
        }
        for (Method m : methods) {
            if (m.getDeclaringClass() != Object.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * get property name array.
     *
     * @return property name array.
     */
    abstract public String[] getPropertyNames();

    /**
     * get property type.
     *
     * @param pn property name.
     * @return Property type or nul.
     */
    abstract public Class<?> getPropertyType(String pn);

    /**
     * has property.
     *
     * @param name property name.
     * @return has or has not.
     */
    abstract public boolean hasProperty(String name);

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @return value.
     */
    abstract public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @param pv       property value.
     */
    abstract public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @return value array.
     */
    public Object[] getPropertyValues(Object instance, String[] pns) throws NoSuchPropertyException, IllegalArgumentException {
        Object[] ret = new Object[pns.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = getPropertyValue(instance, pns[i]);
        }
        return ret;
    }

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @param pvs      property value array.
     */
    public void setPropertyValues(Object instance, String[] pns, Object[] pvs) throws NoSuchPropertyException, IllegalArgumentException {
        if (pns.length != pvs.length) {
            throw new IllegalArgumentException("pns.length != pvs.length");
        }

        for (int i = 0; i < pns.length; i++) {
            setPropertyValue(instance, pns[i], pvs[i]);
        }
    }

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getMethodNames();

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getDeclaredMethodNames();

    /**
     * has method.
     *
     * @param name method name.
     * @return has or has not.
     */
    public boolean hasMethod(String name) {
        for (String mn : getMethodNames()) {
            if (mn.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * invoke method.
     *
     * @param instance instance.
     * @param mn       method name.
     * @param types
     * @param args     argument array.
     * @return return value.
     */
    abstract public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException, InvocationTargetException;
}
