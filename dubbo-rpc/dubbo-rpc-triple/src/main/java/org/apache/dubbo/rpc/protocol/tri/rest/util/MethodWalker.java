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
package org.apache.dubbo.rpc.protocol.tri.rest.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class MethodWalker {
    // service 的父类，接口，包括 service 本身
    private final Set<Class<?>> classes = new LinkedHashSet<>();
    // key 为 method , value list 中缓存的是 service , 父类 ， 接口类中的 method
    // 方便查看 spring mvc 注解具体标注在哪个方法上
    private final Map<Key, List<Method>> methodsMap = new HashMap<>();

    public void walk(Class<?> clazz, BiConsumer<Set<Class<?>>, Consumer<Consumer<List<Method>>>> visitor) {
        if (clazz.getName().contains("$$")) {
            clazz = clazz.getSuperclass();
        }
        // clazz 是暴露 service 的实现类
        // 遍历类的继承关系，挨个搜寻每个类的方法（serviceImpl , 父类 ， 接口）
        // 将各个类中的 method 缓存在 methodsMap 中
        // 后续会从 methodsMap 中提取相应的 spring mvc 相关注解
        // 因为我们支持将注解标注在继承关系中的任意类或者接口中，所以这里需要收集所有继承类的所有方法
        // 后续会查找 mvc 注解到底被标注在了继承关系的哪里
        walkHierarchy(clazz);

        visitor.accept(classes, consumer -> {
            for (Map.Entry<Key, List<Method>> entry : methodsMap.entrySet()) {
                // 挨个处理 service 中的方法，为每个方法注册 rest request mapping
                // List<Method> 中 method 存放的顺序为： serviceImpl -> 父类 -> 接口 的相关 method
                consumer.accept(entry.getValue());
            }
        });
    }

    private void walkHierarchy(Class<?> clazz) {
        // clazz 为 service 的实现类
        // clazz.getDeclaredAnnotations() 获取 clazz 类上标注的注解，比如 @DubboService ， @RequestMapping 等
        // @RequestMapping 既可以在 service 接口上标注也可以在实现类上标注
        if (classes.isEmpty() || clazz.getDeclaredAnnotations().length > 0) {
            // 从 serviceImpl 开始按照顺序向上依次缓存其父类（只有一个），接口（可能多个）
            classes.add(clazz);
        }
        // 首先获取 serviceImpl 中的所有方法
        for (Method method : clazz.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            // 只能是非 static 的 public 方法
            if ((modifiers & (Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC) {
                methodsMap
                        .computeIfAbsent(Key.of(method), k -> new ArrayList<>())
                        .add(method);
            }
        }
        Class<?> superClass = clazz.getSuperclass();//继承的父类，只有一个，不允许多继承
        if (superClass != null && superClass != Object.class) {
            walkHierarchy(superClass);
        }
        for (Class<?> itf : clazz.getInterfaces()) {
            walkHierarchy(itf);
        }
    }

    private static final class Key {
        private final String name;
        private final Class<?>[] parameterTypes;

        private Key(String name, Class<?>[] parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes;
        }

        private static Key of(Method method) {
            return new Key(method.getName(), method.getParameterTypes());
        }

        @Override
        @SuppressWarnings({"EqualsWhichDoesntCheckParameterClass", "EqualsDoesntCheckParameterClass"})
        public boolean equals(Object obj) {
            Key key = (Key) obj;
            return name.equals(key.name) && Arrays.equals(parameterTypes, key.parameterTypes);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            for (Class<?> type : parameterTypes) {
                result = 31 * result + type.hashCode();
            }
            return result;
        }

        @Override
        public String toString() {
            return name + Arrays.toString(parameterTypes);
        }
    }
}
