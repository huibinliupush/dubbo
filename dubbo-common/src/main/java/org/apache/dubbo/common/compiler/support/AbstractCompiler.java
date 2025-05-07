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

import org.apache.dubbo.common.compiler.Compiler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract compiler. (SPI, Prototype, ThreadSafe)
 */
public abstract class AbstractCompiler implements Compiler {
    /**
     * package\s+([$_a-zA-Z][$_a-zA-Z0-9\.]*);
     *
     * package org.apache.tools.ant; → 提取 org.apache.tools.ant
     *
     * 捕获组：([$_a-zA-Z][$_a-zA-Z0-9\.]*)
     * 首字符：必须为 $、_、字母（a-zA-Z）。
     * 后续字符：可包含 $、_、字母、数字（0-9）和包分隔符 .
     * 长度限制：至少一个字符（首字符），后续字符数量不限（* 表示零次或多次）。
     *
     * */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([$_a-zA-Z][$_a-zA-Z0-9\\.]*);");
    /**
     *  class\s+([$_a-zA-Z][$_a-zA-Z0-9]*)\s+
     *
     *  final class _DatabaseHelper123 { ... } → 提取 _DatabaseHelper123
     *
     *  捕获组 ([$_a-zA-Z][$_a-zA-Z0-9]*)
     *  首字符：必须为 $、_ 或字母（a-zA-Z）。
     *  后续字符：可包含 $、_、字母和数字（0-9）。
     *  长度限制：至少一个字符（首字符），后续字符数量不限（* 表示零次或多次）
     *
     *  \s+  若类名后直接跟随 {（如 class MyClass{），\s+ 无法匹配，导致整个正则表达式失败
     * */
    private static final Pattern CLASS_PATTERN = Pattern.compile("class\\s+([$_a-zA-Z][$_a-zA-Z0-9]*)\\s+");

    @Override
    public Class<?> compile(String code, ClassLoader classLoader) {
        code = code.trim();
        // 匹配 package
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        String pkg;
        if (matcher.find()) {
            pkg = matcher.group(1);
        } else {
            pkg = "";
        }
        // 匹配动态代理类名
        matcher = CLASS_PATTERN.matcher(code);
        String cls;
        if (matcher.find()) {
            cls = matcher.group(1);
        } else {
            throw new IllegalArgumentException("No such class name in " + code);
        }
        // 带有 package name，首先尝试去加载动态代理类，首次一般都会加载失败
        // 失败之后 doCompile
        String className = pkg != null && pkg.length() > 0 ? pkg + "." + cls : cls;
        try {
            return Class.forName(className, true, org.apache.dubbo.common.utils.ClassUtils.getCallerClassLoader(getClass()));
        } catch (ClassNotFoundException e) {
            if (!code.endsWith("}")) {
                throw new IllegalStateException("The java code not endsWith \"}\", code: \n" + code + "\n");
            }
            try {
                return doCompile(className, code);
            } catch (RuntimeException t) {
                throw t;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to compile class, cause: " + t.getMessage() + ", class: " + className + ", code: \n" + code + "\n, stack: " + ClassUtils.toString(t));
            }
        }
    }

    protected abstract Class<?> doCompile(String name, String source) throws Throwable;

}
