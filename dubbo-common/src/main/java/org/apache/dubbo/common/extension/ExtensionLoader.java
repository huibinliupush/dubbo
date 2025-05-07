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
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li> 依赖注入，支持注入 spring bean，自动注入其他扩展 SPI 的自适应实现
 * <li>auto wrap extension in wrapper </li>   切面（支持依赖注入）
 * <li>default extension is an adaptive instance</li> 自适应, 默认的扩展是一个自适应扩展实现（同样支持依赖注入）
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);
    // 按照逗号分隔字符，忽略逗号前后的空白
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    // ExtensionLoader 缓存。 key : 对应的扩展接口（SPI标注）  value : 对应的 ExtensionLoader
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>(64);
    // 缓存扩展实现类与对应扩展实现类的实例（注意这里的实例是纯实例，未被 wrapper 包装，未依赖注入）
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>(64);
    // 对应的扩展接口
    private final Class<?> type;
    // ExtensionFactory#AdaptiveExtension ——  AdaptiveExtensionFactory
    // 在依赖注入阶段，利用 objectFactory 获取 SPI ,spring bean
    private final ExtensionFactory objectFactory;
    // key : 扩展实现类 ， value : 扩展名称
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();
    // 缓存扩展接口 type 对应的所有扩展类 class
    // 其中 @Adaptive 标注的自适应实现，以及 wrapper 类不会缓存在这里（另外单独缓存）
    // key : 扩展名（SPI 文件配置）  value : 扩展类
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    //缓存所有对应扩展实现类中标注的@Activate注解集合。  key：扩展名  value:对应扩展实现类上标注的@Active注解
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    // 缓存扩展名到对应扩展实现类实例（已被 wrappper 包装，已经完成依赖注入）之间的映射
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    // type 接口的自适应扩展实现 ——  AdaptiveExtension 实例(自适应实现也会依赖注入,但没有 wrapper)
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    // 扩展接口 type 对应的扩展实现类上标注了 Adaptive 注解（同样需要再 SPI 文件中配置），表示 type 的自适应扩展类，缓存在这里
    // 若没有 Adaptive 注解标注的手动实现，那么就会动态实现自适应扩展，动态实现之后也会缓存在这里
    private volatile Class<?> cachedAdaptiveClass = null;
    // 默认的扩展实现类 key，从 SPI 注解中提取。
    // SPI 配置文件中的 key
    private String cachedDefaultName;
    // 在 createAdaptiveExtension 的过程中如果发生异常，保存在这里
    private volatile Throwable createAdaptiveInstanceError;
    /**
     * 扩展接口 type 的切面实现类（Wrapper）全部缓存在这里，
     * 扩展类中有一个只包含扩展接口 type 参数的构造函数，就是 wrapper 类
     *
     * 切面的实现，比如 Protocol 扩展接口的切面实现类
*              filter=org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper
*              listener=org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper
     *
     * ProxyFactory 扩展接口的切面实现类
                stub=org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper
     *
     * */
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();
    // 利用传统的 SPI ServiceLoader 加载 LoadingStrategy 扩展
    // META-INF/services/org.apache.dubbo.common.extension.LoadingStrategy

    // org.apache.dubbo.common.extension.DubboInternalLoadingStrategy
    // org.apache.dubbo.common.extension.DubboLoadingStrategy
    // org.apache.dubbo.common.extension.ServicesLoadingStrategy
    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        // 获取 LoadingStrategy 的 ServiceLoader，通过 JDK SPI 加载 LoadingStrategy 扩展
        // 将 ServiceLoader 的 spliterator 转换为 stream

        // 传统 SPI 的加载路径： /META-INF/services
        return stream(load(LoadingStrategy.class).spliterator(), false)
                .sorted()
                .toArray(LoadingStrategy[]::new);
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    // 创建扩展接口 type 对应的 ExtensionLoader
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        // ExtensionFactory#AdaptiveExtension ——  AdaptiveExtensionFactory
        // 如果该 ExtensionLoader 本身就是 ExtensionFactory.class 的扩展 loader,那么这里就是 null
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    // type 是一个被 SPI 标注的扩展接口
    // 获取 type 对应的 ExtensionLoader
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        // 必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        // 必须被 SPI 标注
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    public static void destroyAll() {
        EXTENSION_INSTANCES.forEach((_type, instance) -> {
            if (instance instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) instance;
                try {
                    lifecycle.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + lifecycle, e);
                }
            }
        });
    }

    /**
     * 优先使用当前线程的 ContextClassLoader
     * 其次是 ExtensionLoader.class 对应的 ClassLoader
     * 最后 SystemClassLoader（最顶层 classloader）
     *
     * */
    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        //从Url中取出对应的扩展名称集合
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> activateExtensions = new ArrayList<>();
        List<String> names = values == null ? new ArrayList<>(0) : asList(values);
        //扩展名称中不包含 -default（代表使所用标注@Activate注解的dubbo内置扩展或者自定义扩展全部失效）
        //加载缺省扩展点实现
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            getExtensionClasses();
            //处理所有标注@Activate注解的扩展实现，将满足激活条件的扩展实现加入到activateExtensions集合中
            //遍历所有扩展实现类中标注逇@Activate注解结合集合 由此可见default表示所有标注@Activate注解的扩展类实现 包括dubbo内置和自定义扩展
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                //对应扩展名
                String name = entry.getKey();
                //对应扩展实现类中标注的@Active注解
                Object activate = entry.getValue();

                //@Activate注解中定义的group,value属性
                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }

                /**
                 * 判断标注该@Activate注解的扩展实现类 是否应该被激活
                 * 激活条件：
                 * 1. 方法指定加载的group是否与`@Activate注解`中group属性指定的扩展分组匹配。
                 * 2. 该扩展名未在`<dubbo:provider filter="....."/>` 和 `<dubbo:service filter="...." />`中配置。因为这里着重处理的是加载dubbo缺省的`Filter扩展`，并不包括在dubbo配置专门指定的`Filter扩展`。
                 * 3. `<dubbo:provider filter="....."/>` 和 `<dubbo:service filter="...." />`配置中不包含 `-当前扩展名`（代表该扩展名对应的扩展实现 失效）
                 * 4. isActive方法判断@Activate注解中value属性中指定的key:value是否存在url中 如果存在则激活
                 * */
                if (isMatchGroup(group, activateGroup)
                        && !names.contains(name)
                        && !names.contains(REMOVE_VALUE_PREFIX + name)
                        && isActive(activateValue, url)) {
                    //满足激活条件
                    activateExtensions.add(getExtension(name));
                }
            }
            //根据注解@Activate中得before,after,order等排序属性 来对所有标注@Activate注解并且满足激活条件的
            //扩展实现排序
            activateExtensions.sort(ActivateComparator.COMPARATOR);
        }

        //下面开始处理dubbo配置中配置的 自定义扩展
        List<T> loadedExtensions = new ArrayList<>();
        //遍历dubbo配置中配置的所有扩展名称集合
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            //扩展名不包含 - 或者 -扩展名 代表该扩展 生效  否则该扩展失效
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {

                //如果遍历到default，需要将配置在default之前的扩展名对应的扩展实现
                // 放在已经激活的所有标注@Activate注解扩展实现的前面
                //当前activateExtensions集合存放的是所有满足激活条件的缺省扩展default
                if (DEFAULT_KEY.equals(name)) {
                    if (!loadedExtensions.isEmpty()) {
                        activateExtensions.addAll(0, loadedExtensions);
                        loadedExtensions.clear();
                    }
                } else {
                    loadedExtensions.add(getExtension(name));
                }
            }
        }
        if (!loadedExtensions.isEmpty()) {
            //将配置在default之后的扩展实现 放入activateExtensions集合的最后
            // 这里可以看出，自定义扩展实现是默认放在缺省扩展之后的
            activateExtensions.addAll(loadedExtensions);
        }

        /**
         * 通过这段源码，我们可以看到即使有些Filter扩展不满足上述@Activate注解中配置的激活条件，但是只要在dubbo配置中配置了，也会被加载到。
         * */

        //返回所有被激活的扩展实现
        return activateExtensions;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * url参数中是否出现了指定的key或者以.key结尾的参数key
     * 如果@Activate中指定了key-value 则检查url参数key中得value是否与指定的key-value相同
     * @Activate(value="key1:value1, key2:value2")
     * */
    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // @Active(value="key1:value1, key2:value2")
            String keyValue = null;
            //@Activate(value="key:value")注解中得value属性指定的是key:value形式
            if (key.contains(":")) {
                String[] arr = key.split(":");
                //解析出value属性中指定的key
                key = arr[0];
                //解析出value
                keyValue = arr[1];
            }

            //如果url中存在@Activate注解value属性中指定的key或者以.key为结尾的参数名
            //并且url中相应key对应的value值 与 指定的keyValue相同 那么标注该@Activate注解的扩展实现被激活
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ((keyValue != null && keyValue.equals(v)) || (keyValue == null && ConfigUtils.isNotEmpty(v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        // 通过扩展名到缓存中获取对应扩展实现类实例
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public List<T> getLoadedExtensionInstances() {
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

//    public T getPrioritizedExtensionInstance() {
//        Set<String> supported = getSupportedExtensions();
//
//        Set<T> instances = new HashSet<>();
//        Set<T> prioritized = new HashSet<>();
//        for (String s : supported) {
//
//        }
//
//    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        // 创建对应扩展实现类的实例 Holder
        final Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 创建对应扩展实现类的实例(已经被 wrapper 包装，已经完成依赖注入)
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        // 获取所有的扩展名（不包括 Adaptive 以及 wrapper 扩展）
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        sort(instances, Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 获取扩展接口 type 的自适应扩展实现 AdaptiveExtension（类型为 type）
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                        createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        // 创建自适应扩展实现，自适应实现也会依赖注入，但没有 wrapper
                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        // 保存创建过程中发生的异常
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        // 获取扩展接口对应所有扩展实现类
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            // 获取对应扩展类实例（注意这里的实例是纯实例，未被 wrapper 包装，未依赖注入）
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 依赖注入引用的其他扩展类的 AdaptiveExtension
            // 比如 ZookeeperRegistryFactory 这个扩展会自动依赖注入 ZookeeperTransporter 扩展（AdaptiveExtension）
            // see : org.apache.dubbo.registry.zookeeper.ZookeeperRegistryFactory.setZookeeperTransporter
            // 也可以依赖注入 spring bean
            injectExtension(instance);
            // 切面实现，用扩展接口对应的 wrapper 类挨个包装 instance
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    // wrapperClasses 也支持依赖注入（其他扩展，spring bean）
                    // ProtocolFilterWrapper -> ProtocolListenerWrapper -> DubboProtocal(或其他扩展)
                    // 无论是 Wrapper 是具体的扩展均支持依赖注入

                    // Wrapper 类的特点是有一个参数为扩展接口 type 的构造函数
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            // 如果是扩展类继承了 Lifecycle 则调用 lifecycle.initialize()
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    // instance 为要进行依赖注入的扩展实现类实例，比如：ZookeeperRegistryFactory
    // 就会在这里依赖注入 ZookeeperTransporter 扩展
    // 也可以依赖注入 spring bean
    private T injectExtension(T instance) {
        // AdaptiveExtensionFactory
        if (objectFactory == null) {
            return instance;
        }

        try {
            // 获取扩展类的全部 set 方法，比如 setZookeeperTransporter
            // set 方法参数必须是需要注入的扩展接口
            for (Method method : instance.getClass().getMethods()) {
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 */
                if (method.getAnnotation(DisableInject.class) != null) {
                    continue;
                }
                // 获取要依赖注入的扩展类型，如 ZookeeperTransporter（SPI 接口）
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    // 获取需要依赖注入的属性类型，比如 set 方法 setZookeeperTransporter
                    // property = zookeeperTransporter

                    // 如果依赖注入的是 spring bean ， 这里的 property 应该是 beanid
                    String property = getSetterProperty(method);
                    // 需要依赖注入的扩展接口 pt 的 AdaptiveExtension
                    // 这里依赖注入的也可以是 spring bean，property 为 beanid
                    Object object = objectFactory.getExtension(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                            + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }
    // 获取扩展接口 type 对应的所有扩展实现类
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 加载所有的扩展类
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        // 提取 SPI 注解中的默认扩展名
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        // org.apache.dubbo.common.extension.DubboInternalLoadingStrategy
        // org.apache.dubbo.common.extension.DubboLoadingStrategy
        // org.apache.dubbo.common.extension.ServicesLoadingStrategy （SPI 原生）
        for (LoadingStrategy strategy : strategies) {
            loadDirectory(extensionClasses, strategy.directory(), type.getName(), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
            // 兼容 com.alibaba
            loadDirectory(extensionClasses, strategy.directory(), type.getName().replace("org.apache", "com.alibaba"), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
        }

        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }
        // 获取默认的扩展名称， 也就是 SPI 文件中的 key = 扩展类名，中的 key
        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            // 按照逗号分隔 value
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) { // 只能设置一个默认的扩展名（SPI 注解）
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        loadDirectory(extensionClasses, dir, type, false, false);
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type,
                               boolean extensionLoaderClassLoaderFirst, boolean overridden, String... excludedPackages) {
        // type 对应的 SPI 文件路径
        // META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            /**
             * 优先使用当前线程的 ContextClassLoader
             * 其次是 ExtensionLoader.class 对应的 ClassLoader
             * 最后 SystemClassLoader（最顶层 classloader）
             *
             * */
            ClassLoader classLoader = findClassLoader();

            // try to load from ExtensionLoader's ClassLoader first
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }

            if (urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    // 加载指定扩展接口 SPI 文件资源（可能有多个针对同一扩展接口的 SPI 文件）
                    // 比如在 dubbo 框架内部配置了一些 Filter 内置扩展  dubbo.internal 路径
                    // 业务工程中又配置了一些自定义 Filter 扩展         dubbo 路径
                    // 这时就出现了针对同一接口的多个 SPI 配置文件

                    // 同一路径下也有可能出现多个 SPI 配置文件（同一扩展接口）
                    // 比如在不同的子工程下，针对同一扩展接口配置 SPI
                    // 比如扩展接口 EventListener，在多个子工程下面都会出现（比如 dubbp-registry , dubbo-config 都有）
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            if (urls != null) {
                while (urls.hasMoreElements()) {
                    // 加载多个 SPI 文件，将其中配置的扩展类加载进内存
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL, overridden, excludedPackages);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader,
                              java.net.URL resourceURL, boolean overridden, String... excludedPackages) {
        try {
            // 读取 SPI 文件 : META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                // 一行一行的读取 SPI 文件中的配置
                while ((line = reader.readLine()) != null) {
                    // 提取 # 注释前面的内容
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    // 一行完整的配置：
                    // fixed=org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                // 提取扩展 key
                                name = line.substring(0, i).trim();
                                // 提取扩展实现类名
                                line = line.substring(i + 1).trim();
                            }
                            // 不能加载 excludedPackages 指定的包名下的类
                            if (line.length() > 0 && !isExcluded(line, excludedPackages)) {
                                // 加载扩展类
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name, overridden);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) throws NoSuchMethodException {
        // 扩展实现类必须继承指定的扩展接口 type
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        // @Adaptive 标注在类上表示该类是 type 的自适应扩展实现 AdaptiveExtension
        // org.apache.dubbo.common.compiler.support.AdaptiveCompiler
        // 手动实现的自适应扩展
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) { // 扩展类中有一个只包含扩展接口 type 参数的构造函数，就是 wrapper 类
            /**
             * 切面的实现，比如 Protocol 扩展接口的切面实现类
             *  filter=org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper
 *              listener=org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper
             *
             * ProxyFactory 扩展接口的切面实现类
                stub=org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper
             *
             * */
            cacheWrapperClass(clazz);
        } else {
            clazz.getConstructor();
            // 如果 SPI 文件中没有指定 key , 那么就取扩展实现类的前缀
            if (StringUtils.isEmpty(name)) {
                // 取 classSimpleName 与 type classSimpleName 前面不同的部分
                // classSimpleName : xx.yy.zz
                // type            : yy.zz
                // name = xx
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            // SPI 文件中指定的扩展名称支持多个。 用逗号分隔
            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 如果扩展实现类上标注了 Active 注解，那么缓存扩展名 name 到其 Active 注解的映射关系
                // Active 里指定了对应扩展的激活条件
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // 建立扩展实现类 class 与扩展名称 name 之间的映射
                    cacheName(clazz, n);
                    // 建立扩展名称 name 与扩展实现类 class 之间的映射
                    // overridden 指定如果 extensionClasses 已经存在对应的扩展 name , 是否覆盖之前的缓存
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getName()
                    + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 自适应实现也会依赖注入，但没有 wrapper
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        // 加载所有的 SPI 扩展实现
        getExtensionClasses();
        // 标注了 Adaptive 注解的扩展实现类
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 如果没有标注 Adaptive 的扩展实现类，那么就动态创建自适应扩展实现类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    private Class<?> createAdaptiveExtensionClass() {
        // 生成 javassist  code
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        ClassLoader classLoader = findClassLoader();
        // AdaptiveExtension 会有依赖注入，但没有 wrapper
        // AdaptiveCompiler
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
