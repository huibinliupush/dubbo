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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Code generator for Adaptive class
 */
public class AdaptiveClassCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveClassCodeGenerator.class);

    private static final String CLASSNAME_INVOCATION = "org.apache.dubbo.rpc.Invocation";

    private static final String CODE_PACKAGE = "package %s;\n";

    private static final String CODE_IMPORTS = "import %s;\n";

    private static final String CODE_CLASS_DECLARATION = "public class %s$Adaptive implements %s {\n";

    private static final String CODE_METHOD_DECLARATION = "public %s %s(%s) %s {\n%s}\n";

    private static final String CODE_METHOD_ARGUMENT = "%s arg%d";

    private static final String CODE_METHOD_THROWS = "throws %s";

    private static final String CODE_UNSUPPORTED = "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n";

    private static final String CODE_URL_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"url == null\");\n%s url = arg%d;\n";

    private static final String CODE_EXT_NAME_ASSIGNMENT = "String extName = %s;\n";

    private static final String CODE_EXT_NAME_NULL_CHECK = "if(extName == null) "
                    + "throw new IllegalStateException(\"Failed to get extension (%s) name from url (\" + url.toString() + \") use keys(%s)\");\n";

    private static final String CODE_INVOCATION_ARGUMENT_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"invocation == null\"); "
                    + "String methodName = arg%d.getMethodName();\n";


    private static final String CODE_EXTENSION_ASSIGNMENT = "%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n";

    private static final String CODE_EXTENSION_METHOD_INVOKE_ARGUMENT = "arg%d";
    // 扩展接口（SPI）
    private final Class<?> type;
    // 默认扩展名
    private String defaultExtName;

    public AdaptiveClassCodeGenerator(Class<?> type, String defaultExtName) {
        // 扩展接口（SPI）
        this.type = type;
        // 默认扩展名
        this.defaultExtName = defaultExtName;
    }

    /**
     * test if given type has at least one method annotated with <code>Adaptive</code>
     */
    private boolean hasAdaptiveMethod() {
        return Arrays.stream(type.getMethods()).anyMatch(m -> m.isAnnotationPresent(Adaptive.class));
    }

    /**
     * generate and return class code
     */
    public String generate() {
        // no need to generate adaptive class since there's no adaptive method found.
        // 扩展接口中至少有一个方法标注了 Adaptive 注解才会在这里生成 adaptive class
        if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        StringBuilder code = new StringBuilder();
        // 获取扩展接口的 package name
        code.append(generatePackageInfo());
        // import org.apache.dubbo.common.extension.ExtensionLoader
        code.append(generateImports());
        // public class type.getSimpleName$Adaptive implements type.getCanonicalName {
        code.append(generateClassDeclaration());

        Method[] methods = type.getMethods();
        // 生成方法
        // 如果扩展接口方法未标注 Adaptive 注解，那么生成的方法体直接 throw new UnsupportedOperationException
        for (Method method : methods) {
            code.append(generateMethod(method));
        }
        code.append("}");

        if (logger.isDebugEnabled()) {
            logger.debug(code.toString());
        }
        return code.toString();
    }

    /**
     * generate package info
     */
    private String generatePackageInfo() {
        return String.format(CODE_PACKAGE, type.getPackage().getName());
    }

    /**
     * generate imports
     */
    private String generateImports() {
        return String.format(CODE_IMPORTS, ExtensionLoader.class.getName());
    }

    /**
     * generate class declaration
     */
    private String generateClassDeclaration() {
        // CanonicalName : 含包名
        return String.format(CODE_CLASS_DECLARATION, type.getSimpleName(), type.getCanonicalName());
    }

    /**
     * generate method not annotated with Adaptive with throwing unsupported exception
     */
    private String generateUnsupported(Method method) {
        return String.format(CODE_UNSUPPORTED, method, type.getName());
    }

    /**
     * get index of parameter with type URL
     */
    private int getUrlTypeIndex(Method method) {
        int urlTypeIndex = -1;
        Class<?>[] pts = method.getParameterTypes();
        for (int i = 0; i < pts.length; ++i) {
            if (pts[i].equals(URL.class)) {
                urlTypeIndex = i;
                break;
            }
        }
        return urlTypeIndex;
    }

    /**
     * generate method declaration
     */
    private String generateMethod(Method method) {
        // 含包名
        String methodReturnType = method.getReturnType().getCanonicalName();
        String methodName = method.getName();
        // 生成动态代理的方法体，核心就是获取 url , 根据 Adaptive 注解获取扩展参数，然后从 url 中获取扩展参数对应的扩展名
        // 根据扩展名到 ExtensionLoader 中获取对应扩展实现
        String methodContent = generateMethodContent(method);
        // 获取方法参数：%s arg%d —— 参数类型0 arg0 , 参数类型1 arg1 , ....  ,
        String methodArgs = generateMethodArguments(method);
        // 获取 throws 方法体
        String methodThrows = generateMethodThrows(method);
        // 生成方法
        return String.format(CODE_METHOD_DECLARATION, methodReturnType, methodName, methodArgs, methodThrows, methodContent);
    }

    /**
     * generate method arguments
     */
    private String generateMethodArguments(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length)
                        .mapToObj(i -> String.format(CODE_METHOD_ARGUMENT, pts[i].getCanonicalName(), i))
                        .collect(Collectors.joining(", "));
    }

    /**
     * generate method throws
     */
    private String generateMethodThrows(Method method) {
        Class<?>[] ets = method.getExceptionTypes();
        if (ets.length > 0) {
            String list = Arrays.stream(ets).map(Class::getCanonicalName).collect(Collectors.joining(", "));
            return String.format(CODE_METHOD_THROWS, list);
        } else {
            return "";
        }
    }

    /**
     * generate method URL argument null check
     */
    private String generateUrlNullCheck(int index) {
        return String.format(CODE_URL_NULL_CHECK, index, URL.class.getName(), index);
    }

    /**
     * generate method content
     */
    private String generateMethodContent(Method method) {
        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        if (adaptiveAnnotation == null) {
            // 如果扩展接口方法未标注 Adaptive 注解，那么生成的方法体直接 throw new UnsupportedOperationException
            return generateUnsupported(method);
        } else {
            // 方法参数中第几个参数是 URL
            int urlTypeIndex = getUrlTypeIndex(method);

            // found parameter in URL type
            if (urlTypeIndex != -1) {
                // Null Point check
                // 方法体添加 CODE_URL_NULL_CHECK ， 检查 url 参数不能为空
                code.append(generateUrlNullCheck(urlTypeIndex));
            } else {
                // did not find parameter in URL type

                // 比如：org.apache.dubbo.rpc.Protocol#export(org.apache.dubbo.rpc.Invoker)
                // export 方法中并没有直接传递 URL 的参数，但是 Invoker 中有 getUrl 方法可以获取 url
                code.append(generateUrlAssignmentIndirectly(method));
            }

            // 到这里为止，方法体中就有 url 参数了（或者是局部变量）

            // 获取方法 Adaptive 注解中的 value 数组
            // 也就是根据 URL 中的哪些参数自适应到对应的扩展实现
            String[] value = getMethodAdaptiveValue(adaptiveAnnotation);
            // 方法中是否包含 Invocation 参数
            boolean hasInvocation = hasInvocationArgument(method);
            // 方法体中添加 CODE_INVOCATION_ARGUMENT_NULL_CHECK
            // 判断 Invocation 参数不能为空，否则抛出异常
            // String methodName = Invocation.getMethodName();
            code.append(generateInvocationArgumentNullCheck(method));
            // 添加从 URL 中获取扩展名的方法体： String extName = getNameCode
            // 比如  @Adaptive({"client"，"transporter",)
            // String extName = url.getParameter("client", url.getParameter("transporter", "curator"));
            // 先获取 client 参数的值，没有在获取 transporter 参数的值， 还没有就用默认的扩展名 SPI 中标注的 curator
            code.append(generateExtNameAssignment(value, hasInvocation));
            // check extName == null? 检查上一步获取到的扩展名是否为空，为空则抛出异常
            code.append(generateExtNameNullCheck(value));
            // 添加自适应扩展的方法体，这里就是根据 extName 获取对应的扩展实现
            // type extension = ExtensionLoader.getExtensionLoader(type.class).getExtension(extName);
            code.append(generateExtensionAssignment());

            // return statement
            // return 调用具体的扩展实现类对应的方法，比如 DubboProtocol.export
            code.append(generateReturnAndInvocation(method));
        }

        return code.toString();
    }

    /**
     * generate code for variable extName null check
     */
    private String generateExtNameNullCheck(String[] value) {
        return String.format(CODE_EXT_NAME_NULL_CHECK, type.getName(), Arrays.toString(value));
    }

    /**
     * generate extName assigment code
     * 获取优先级最高的扩展名，Adaptive{"key1", "key2"}
     * 则优先从 URL 中的参数 key1 中获取对应的扩展名（方法级参数优先）
     *
     *  比如  @Adaptive({"client"，"transporter",)
     *  String extName = url.getParameter("client", url.getParameter("transporter", "curator"));
     *  先获取 client 参数的值，没有在获取 transporter 参数的值， 还没有就用默认的扩展名 SPI 中标注的 curator
     */
    private String generateExtNameAssignment(String[] value, boolean hasInvocation) {
        // TODO: refactor it
        String getNameCode = null;
        // value 表示需要从 url 中的哪些参数中获取扩展名
        for (int i = value.length - 1; i >= 0; --i) {
            // 从 Adaptive 注解配置的最后一个 value 开始处理（最低优先级）
            if (i == value.length - 1) {
                // SPI 注解中标注的默认扩展名
                if (null != defaultExtName) {
                    // 如果不是从 url 参数 protocol 中获取扩展名
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) { // 方法级配置优先
                            // 如果方法参数中包含 Invocation，那么则从 URL 中的方法参数中获取 value[i]（key）对应的值（扩展名）
                            // 方法参数中没有则从 URL 的 getParameter 中获取扩展名
                            // see : org.apache.dubbo.common.URL.getMethodParameter(java.lang.String, java.lang.String, java.lang.String)
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        }
                    } else {
                        // Url 中的 Protocol 参数作为扩展名
                        getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    }
                } else {
                    // SPI 注解中没有标注的默认扩展名
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        }
                    } else {
                        getNameCode = "url.getProtocol()";
                    }
                }
            } else {
                if (!"protocol".equals(value[i])) {
                    if (hasInvocation) {
                        getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                    } else {
                        getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    }
                } else {
                    getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
        }
        // 添加从 URL 中获取扩展名的方法体： String extName = getNameCode
        return String.format(CODE_EXT_NAME_ASSIGNMENT, getNameCode);
    }

    /**
     * @return
     */
    private String generateExtensionAssignment() {
        return String.format(CODE_EXTENSION_ASSIGNMENT, type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
    }

    /**
     * generate method invocation statement and return it if necessary
     */
    private String generateReturnAndInvocation(Method method) {
        String returnStatement = method.getReturnType().equals(void.class) ? "" : "return ";
        // arg%d 表示第几个参数
        String args = IntStream.range(0, method.getParameters().length)
                .mapToObj(i -> String.format(CODE_EXTENSION_METHOD_INVOKE_ARGUMENT, i))
                .collect(Collectors.joining(", "));

        return returnStatement + String.format("extension.%s(%s);\n", method.getName(), args);
    }

    /**
     * test if method has argument of type <code>Invocation</code>
     */
    private boolean hasInvocationArgument(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return Arrays.stream(pts).anyMatch(p -> CLASSNAME_INVOCATION.equals(p.getName()));
    }

    /**
     * generate code to test argument of type <code>Invocation</code> is null
     */
    private String generateInvocationArgumentNullCheck(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length).filter(i -> CLASSNAME_INVOCATION.equals(pts[i].getName()))
                        .mapToObj(i -> String.format(CODE_INVOCATION_ARGUMENT_NULL_CHECK, i, i))
                        .findFirst().orElse("");
    }

    /**
     * get value of adaptive annotation or if empty return splitted simple name
     */
    private String[] getMethodAdaptiveValue(Adaptive adaptiveAnnotation) {
        String[] value = adaptiveAnnotation.value();
        // value is not set, use the value generated from class name as the key
        // 如果没设置 value , 那么就根据扩展接口的 SimpleName 自动生成(驼峰转 .
        if (value.length == 0) {
            // org.apache.dubbo.xxx.YyyInvokerWrapper
            // splitName = yyy.invoker.wrapper
            String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
            value = new String[]{splitName};
        }
        return value;
    }

    /**
     * get parameter with type <code>URL</code> from method parameter:
     * <p>
     * test if parameter has method which returns type <code>URL</code>
     * <p>
     * if not found, throws IllegalStateException
     *
     * 比如：org.apache.dubbo.rpc.Protocol#export(org.apache.dubbo.rpc.Invoker)
     * export 方法中并没有直接传递 URL 的参数，但是 Invoker 中有 getUrl 方法可以获取 url
     */
    private String generateUrlAssignmentIndirectly(Method method) {
        // 获取方法所有的参数类型，试图从这些参数类型查找出一个能够返回 URL 方法的类型
        // 比如，Protocol 的 export 方法，它的参数中没有 URL
        // 但是其参数 Invoker 中有一个方法 getUrl 可以返回 URL
        Class<?>[] pts = method.getParameterTypes();
        // key: URL 的 get 方法名称。 value: 第几个方法参数中包含这个getURl方法
        // 以 Protocol#export 为例 ： key : getUrl , value : 0
        Map<String, Integer> getterReturnUrl = new HashMap<>();
        // find URL getter method
        for (int i = 0; i < pts.length; ++i) {
            // 挨个获取所有参数类型的方法
            for (Method m : pts[i].getMethods()) {
                String name = m.getName();
                // 方法需要满足： 1. get方法  2.没有参数 3. 返回类型是 URL
                if ((name.startsWith("get") || name.length() > 3)
                        && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() == URL.class) {
                    getterReturnUrl.put(name, i);
                }
            }
        }
        // adaptive class 中的方法参数中必须包含能够获取 url 的方法
        // 或者是 URL 直接通过方法参数来传递进来
        if (getterReturnUrl.size() <= 0) {
            // getter method not found, throw
            throw new IllegalStateException("Failed to create adaptive class for interface " + type.getName()
                    + ": not found url parameter or url attribute in parameters of method " + method.getName());
        }
        // 参数类型中直接就有 getUrl 方法，例如 Invoker
        Integer index = getterReturnUrl.get("getUrl");
        if (index != null) {
            // 添加方法体：
            // if (arg%d == null) throw new IllegalArgumentException (方法参数不能为 null)
            // if (arg%d.%s() == null) throw new IllegalArgumentException (参数 getUrl 方法不能为 null)
            // url = arg%d.%s() (调用参数方法获取 url)
            return generateGetUrlNullCheck(index, pts[index], "getUrl");
        } else {
            // 参数类型中有叫其他名字的获取 url 方法，比如  getXXXXX() 但返回类型是 URL
            Map.Entry<String, Integer> entry = getterReturnUrl.entrySet().iterator().next();
            return generateGetUrlNullCheck(entry.getValue(), pts[entry.getValue()], entry.getKey());
        }
    }

    /**
     * 1, test if argi is null
     * 2, test if argi.getXX() returns null
     * 3, assign url with argi.getXX()
     */
    private String generateGetUrlNullCheck(int index, Class<?> type, String method) {
        // Null point check
        StringBuilder code = new StringBuilder();
        code.append(String.format("if (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");\n",
                index, type.getName()));
        code.append(String.format("if (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");\n",
                index, method, type.getName(), method));

        code.append(String.format("%s url = arg%d.%s();\n", URL.class.getName(), index, method));
        return code.toString();
    }

}
