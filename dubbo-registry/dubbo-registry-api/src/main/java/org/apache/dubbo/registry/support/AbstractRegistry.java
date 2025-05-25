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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.ACCEPTS_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTRY_FILESAVE_SYNC_KEY;
import static org.apache.dubbo.registry.Constants.REGISTRY__LOCAL_FILE_CACHE_ENABLED;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 */
public abstract class AbstractRegistry implements Registry {

    // URL address separator, used in file cache, service provider URL separation
    private static final char URL_SEPARATOR = ' ';
    // URL address separated regular expression for parsing the service provider URL list in the file cache
    private static final String URL_SPLIT = "\\s+";
    // Max times to retry to save properties to local cache file
    //更新本地缓存文件最大重试次数
    private static final int MAX_RETRY_TIMES_SAVE_PROPERTIES = 3;
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    // Local disk cache, where the special key value.registries records the list of registry centers, and the others are the list of notified service providers
    /**
     * properties 是一个 KV 结构，其中 Key 是当前节点URL的serviceKey:{group}/{interfaceName}:{version}，
     * Value 是对应的订阅category（providers,configurators,routers）分类下的所有URL列表(用空格分隔)
     * 包含了所有 Category（例如，providers、routes、configurators 等） 下的 URL 这些Url用空格分隔。
     * properties 中有一个特殊的 Key 值为 org.apache.dubbo.registry.RegistryService，对应的 Value 是注册中心URL列表(用空格分隔)。
     *
     * 订阅者（向注册中心订阅了监听数据）都会有本地缓存文件，用来缓存注册中心中订阅的数据
     * 这里需要广义的理解订阅者，只要像注册中心订阅了数据就是订阅者，不止是consumer会订阅provider端URL
     * provider端也会订阅数据  比如 订阅configurators目录下的动态配置数据。provider端也是订阅者 也会有本地文件缓存 存放configurators目录下的动态配置URL
     * consumer端 订阅的数据就比较多 包括providers目录下的服务提供者URL。 routers目录下的路由规则URL 。 configurators目录下的动态配置URL
     *
     * properties 保存了注册中心中的所有数据，所有服务，每个服务下的 providers,configurators,routers 分类下的所有 URL
     * */
    private final Properties properties = new Properties();
    // File cache timing writing
    //异步更新本地文件缓存 线程池
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
    // Is it synchronized to save the file
    private boolean syncSaveFile;
    private final AtomicLong lastCacheChanged = new AtomicLong();
    //更新本地缓存文件重试次数
    private final AtomicInteger savePropertiesRetryTimes = new AtomicInteger();
    //注册过的URL缓存集合
    private final Set<URL> registered = new ConcurrentHashSet<>();
    //缓存订阅的URL和其对应的监听器（该URL是条件URL，表示需要订阅符合条件的URL）
    //key：订阅的URL条件 value: 相应URL的监听器
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<>();

    //缓存注册中心通知过来的订阅URL对应的URL全量变动URLs。
    //key: 订阅的URL  value：category下的urls集合（key: category value: 对应分类下的Urls集合）
    private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<>();
    private URL registryUrl;
    // Local disk cache file
    private File file;

    public AbstractRegistry(URL url) {
        //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&extra-keys=interface,key1,key2&interface=org.apache.dubbo.registry.RegistryService&metadata-type=remote&pid=16904&qos.port=22222&simplified=true&timestamp=1617176178590
        setUrl(url);
        if (url.getParameter(REGISTRY__LOCAL_FILE_CACHE_ENABLED, true)) {
            // Start file save timer
            syncSaveFile = url.getParameter(REGISTRY_FILESAVE_SYNC_KEY, false);
            //C:\Users\liuhuibin/.dubbo/dubbo-registry-demo-provider-127.0.0.1-2181.cache
            String defaultFilename = System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(APPLICATION_KEY) + "-" + url.getAddress().replaceAll(":", "-") + ".cache";
            //<dubbo:registry file = ""> 指定缓存文件名称 两个注册中心不能使用同一文件存储
            String filename = url.getParameter(FILE_KEY, defaultFilename);
            File file = null;
            if (ConfigUtils.isNotEmpty(filename)) {
                //创建缓存文件
                file = new File(filename);
                //parentFile: C:\Users\liuhuibin\.dubbo
                if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                    if (!file.getParentFile().mkdirs()) {
                        throw new IllegalArgumentException("Invalid registry cache file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                    }
                }
            }
            //C:\Users\liuhuibin\.dubbo\dubbo-registry-demo-provider-127.0.0.1-2181.cache
            this.file = file;
            // When starting the subscription center,
            // we need to read the local cache file for future Registry fault tolerance processing.
            //加载注册中心的缓存文件到JVM内存中
            loadProperties();
            //通知订阅注册中心URL的监听器，notify registry event
            notify(url.getBackupUrls());
        }
    }

    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            List<URL> result = new ArrayList<>(1);
            result.add(url.setProtocol(EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return Collections.unmodifiableSet(registered);
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return Collections.unmodifiableMap(subscribed);
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return Collections.unmodifiableMap(notified);
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    public void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        try {
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            try (RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
                 FileChannel channel = raf.getChannel()) {
                //获取文件锁
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                }
                // Save
                try {
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    // 这里会创建一个新的 FileOutputStream，所以每次都是用 properties 全量覆盖 cachefile
                    try (FileOutputStream outputFile = new FileOutputStream(file)) {
                        //将properties中的缓存数据 更新到本地缓存文件中
                        properties.store(outputFile, "Dubbo Registry Cache");
                    }
                } finally {
                    lock.release();
                }
            }
        } catch (Throwable e) {
            //更新本地缓存文件重试次数 + 1
            savePropertiesRetryTimes.incrementAndGet();
            //是否超过最大重试次数 默认为3次，如果超过 停止重试
            if (savePropertiesRetryTimes.get() >= MAX_RETRY_TIMES_SAVE_PROPERTIES) {
                logger.warn("Failed to save registry cache file after retrying " + MAX_RETRY_TIMES_SAVE_PROPERTIES + " times, cause: " + e.getMessage(), e);
                savePropertiesRetryTimes.set(0);
                return;
            }
            // 检查是否发生并发修改，如果有其他线程已经保存过新的通知，则放弃旧的通知
            if (version < lastCacheChanged.get()) {
                savePropertiesRetryTimes.set(0);
                return;
            } else {
                //异步重试
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry cache file, will retry, cause: " + e.getMessage(), e);
        }
    }

    private void loadProperties() {
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                //将缓存文件加载进内存中
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry cache file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry cache file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }

    public List<URL> getCacheUrls(URL url) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            //{group}/{interfaceName}:{version}
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            if (StringUtils.isNotEmpty(key) && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && StringUtils.isNotEmpty(value)) {
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }

    @Override
    public List<URL> lookup(URL url) {
        List<URL> result = new ArrayList<>();
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        if (CollectionUtils.isNotEmptyMap(notifiedUrls)) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    if (!EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        } else {
            final AtomicReference<List<URL>> reference = new AtomicReference<>();
            // reference::set 的实例方法引用，listener 就是 set 方法，只要参数和返回值一样就可以
            // 相当于 new 一个 NotifyListener，里边的 notify 方法中调用 reference::set
            NotifyListener listener = reference::set;
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (CollectionUtils.isNotEmpty(urls)) {
                for (URL u : urls) {
                    if (!EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    @Override
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.computeIfAbsent(url, n -> new ConcurrentHashSet<>());
        // 这里的 NotifyListener 为 RegistryDirectory
        // see : org.apache.dubbo.registry.integration.RegistryDirectory.subscribe
        listeners.add(listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    protected void recover() throws Exception {
        // register
        // 将注册过的URL重新在注册一遍
        Set<URL> recoverRegistered = new HashSet<>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                register(url);
            }
        }
        // subscribe
        // 将订阅过的URL 重新在订阅一遍 恢复对订阅URL的监听
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    subscribe(url, listener);
                }
            }
        }
    }

    /**
     * 参数为需要通知的Url变动内容
     * */
    protected void notify(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        //遍历订阅的URL集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            // 订阅的条件 URL
            URL url = entry.getKey();
            //将传入的urls（需要通知的URL内容） 与 已经订阅的Url进行匹配，如果匹配成功说明传入的Url就是我们订阅的url
            //匹配成功的话  说明我们订阅的url内容发生了变化  变化的信息存放在参数urls里。
            //匹配不成功，说明我们没有订阅相关的Url
            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }

            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                for (NotifyListener listener : listeners) {
                    try {
                        //通知订阅url的监听器，传入订阅信息变更的内容（参数urls）
                        notify(url, listener, filterEmpty(url, urls));
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * Notify changes from the Provider side.
     *
     * @param url      consumer side url 订阅的URL
     * @param listener listener
     * @param urls     provider latest urls 订阅的变动内容全量
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((CollectionUtils.isEmpty(urls))
                && !ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }
        // keep every provider's category.
        //对通知urls的整理，按照category分类。key:url对应的category  value: 分类下urls列表
        Map<String, List<URL>> result = new HashMap<>();
        for (URL u : urls) {
            //判断订阅端订阅的URL是否订阅了注册中心通知来的URL所属分类
            //比如 订阅端订阅的是providers分类  通知过来的是routers分类 那么就忽略（不是订阅端订阅的内容）
            if (UrlUtils.isMatch(url, u)) {
                String category = u.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
                List<URL> categoryList = result.computeIfAbsent(category, k -> new ArrayList<>());
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }

        //将分类下的urls缓存在ConcurrentMap<URL, Map<String, List<URL>>> notified集合中
        //缓存notified结构： key: 订阅的URL  value：category下的urls集合（key: category value: 对应分类下的Urls集合）
        Map<String, List<URL>> categoryNotified = notified.computeIfAbsent(url, u -> new ConcurrentHashMap<>());
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            String category = entry.getKey();
            List<URL> categoryList = entry.getValue();
            //订阅URL的变动数据按照分类缓存起来
            categoryNotified.put(category, categoryList);
            //按照分类 通知监听器相应的变动URL
            listener.notify(categoryList);
            // We will update our cache file after each notification.
            // When our Registry has a subscribe failure due to network jitter, we can return at least the existing cache URL.
            //更新缓存
            saveProperties(url);
        }
    }

    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            StringBuilder buf = new StringBuilder();
            //从缓存notified中获取订阅URL对应的通知数据
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                for (List<URL> us : categoryNotified.values()) {
                    //遍历所有通知URL,用空格隔开
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        buf.append(u.toFullString());
                    }
                }
            }
            //保存到内存中：key:{group}/{interfaceName}:{version} value: 所有通知过来的URL列表 用空格隔开
            properties.setProperty(url.getServiceKey(), buf.toString());
            //增加缓存版本号。CAS更新缓存
            long version = lastCacheChanged.incrementAndGet();
            if (syncSaveFile) {
                //同步更新本地缓存文件
                doSaveProperties(version);
            } else {
                //异步更新本地缓存文件
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }
        Set<URL> destroyRegistered = new HashSet<>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<>(getRegistered())) {
                if (url.getParameter(DYNAMIC_KEY, true)) {
                    try {
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        AbstractRegistryFactory.removeDestroyedRegistry(this);
    }

    /**
    * <dubbo:registry accepts = ''> 参数accepts指定 注册中心可以接受注册的协议列表 用逗号分隔
    * */
    protected boolean acceptable(URL urlToRegistry) {
        String pattern = registryUrl.getParameter(ACCEPTS_KEY);
        if (StringUtils.isEmpty(pattern)) {
            return true;
        }

        return Arrays.stream(COMMA_SPLIT_PATTERN.split(pattern))
                .anyMatch(p -> p.equalsIgnoreCase(urlToRegistry.getProtocol()));
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}
