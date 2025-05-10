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
package org.apache.dubbo.common.compiler.support;


import javassist.CtClass;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavassistCompiler. (SPI, Singleton, ThreadSafe)
 * https://www.javassist.org/tutorial/tutorial.html
 */
public class JavassistCompiler extends AbstractCompiler {
    /**
     * import\s+([\w\.\*]+);\n
     *
     * 1. import 表示精确匹配 import 关键字
     * 2. \s 表示多个空白字符（空格、制表符等），+ 表示匹配一个或者多个
     * 3. ([\w\.\*]+)  圆括号表示正则表达式中的捕获组，我们需要捕获提取圆括号中匹配的内容
     *      3.1 \w：匹配字母、数字、下划线（等价于 [a-zA-Z0-9_]）。
     *      3.2 \. 和 \*：匹配字面字符 . 和 *（包分隔符和通配符）。
     *      3.3 +：至少匹配一次，允许连续字符（如 java.util.List
 *     4. ;\n 结束符：匹配分号 ; 和换行符 \n。
     *
     * 用于匹配 import 后面的包名
     * */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+([\\w\\.\\*]+);\n");
    /**
     * \s+extends\s+([\w\.]+)[^\{]*\{\n
     *
     * 1. \s+ 匹配一个或者多个空白字符 ， 精确匹配 extends ， \s+ 匹配一个或者多个空白字符
     * 2. ([\w\.]+) 捕获组
     * 3. [^\{]：匹配任何非 { 的字符。*：匹配零次或多次。
     *      场景： 忽略泛型参数（如 extends Base<T> 中的 <T>）。
     *            忽略 implements 等其他修饰符（如 extends A implements B 中的 implements B）。
     * 4. \{\n 匹配类体开始的 { 和换行符 \n。
     *
     * 用于匹配提取 extends 后面的父类
     *
     * */
    private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\s+extends\\s+([\\w\\.]+)[^\\{]*\\{\n");
    /**
     * \s+implements\s+([\w\.]+)\s*\{\n
     *
     * 1. ([\w\.]+) 捕获组，提取 implements 后面的接口名
     * 匹配 public class MyClass implements com.example.MyInterface {\n → 提取 com.example.MyInterface
     * 但这种方式只能匹配单接口，若类实现多个接口（如 implements A, B），仅捕获第一个接口 A。
     * 多接口匹配：\s+implements\s+([\w\.,\s]+)\s*\{\n
     *
     * 2. \s* 匹配接口名称后的 零个或多个空白字符
     *
     * 3. \{\n 匹配类体开始的 { 和换行符 \n。
     *
     * */
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\s+implements\\s+([\\w\\.]+)\\s*\\{\n");

    /**
     * \n(private|public|protected)\s+
     * 根据关键字分隔方法体或者字段，分隔之后的方法或者字段没有 public 等关键字，后续需要重新加上
     * */
    private static final Pattern METHODS_PATTERN = Pattern.compile("\n(private|public|protected)\\s+");

    private static final Pattern FIELD_PATTERN = Pattern.compile("[^\n]+=[^\n]+;");
    // https://www.javassist.org/tutorial/tutorial.html
    @Override
    public Class<?> doCompile(String name, String source) throws Throwable {
        // see : org.apache.dubbo.common.bytecode.Wrapper.makeWrapper
        CtClassBuilder builder = new CtClassBuilder();
        // 带 package name 的 class name
        builder.setClassName(name);

        // process imported classes
        // 动态扩展类只会 import org.apache.dubbo.common.extension.ExtensionLoader
        // 剩下类型全部用的 CanonicalName(全限定名) 的形式，不需要额外 import
        // String 类型不用 import

        /**
         * 这样全限定名，那么 ConcurrentHashMap 就无需额外 import
         * java.util.concurrent.ConcurrentHashMap<String, String> map = new java.util.concurrent.ConcurrentHashMap<>();
         *
         * */
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            builder.addImports(matcher.group(1).trim());
        }

        // process extended super class
        matcher = EXTENDS_PATTERN.matcher(source);
        if (matcher.find()) {
            builder.setSuperClassName(matcher.group(1).trim());
        }

        // process implemented interfaces
        matcher = IMPLEMENTS_PATTERN.matcher(source);
        if (matcher.find()) {
            String[] ifaces = matcher.group(1).trim().split("\\,");
            Arrays.stream(ifaces).forEach(i -> builder.addInterface(i.trim()));
        }

        // process constructors, fields, methods
        // 去掉 {} ， 获取整个类的 body
        String body = source.substring(source.indexOf('{') + 1, source.length() - 1);
        // 分隔出方法字符串（去掉关键字 public 等关键字）
        String[] methods = METHODS_PATTERN.split(body);
        String className = ClassUtils.getSimpleClassName(name);
        Arrays.stream(methods).map(String::trim).filter(m -> !m.isEmpty()).forEach(method -> {
            if (method.startsWith(className)) {
                // 构造函数
                builder.addConstructor("public " + method);
            } else if (FIELD_PATTERN.matcher(method).matches()) {
                // 匹配到字段 Class a = b;
                builder.addField("private " + method);
            } else {
                // 方法体
                builder.addMethod("public " + method);
            }
        });

        // compile
        ClassLoader classLoader = org.apache.dubbo.common.utils.ClassUtils.getCallerClassLoader(getClass());
        // https://www.javassist.org/tutorial/tutorial2.html
        CtClass cls = builder.build(classLoader);
        return cls.toClass(classLoader, JavassistCompiler.class.getProtectionDomain());

        /**
         * https://www.javassist.org/tutorial/tutorial2.html
         *
         * 将 class 文件写到 target 目录下，方便调试查看
         * String filePath = JavassistProxyUtils.class.getResource("/").getPath() + JavassistProxyUtils.class.getPackage().toString().substring("package ".length()).replaceAll("\\.", "/");
         * ctClass.writeFile(filePath);
         *
         * */
    }

}
