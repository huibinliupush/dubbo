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
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.retry.FailedNotifiedTask;
import org.apache.dubbo.registry.retry.FailedRegisteredTask;
import org.apache.dubbo.registry.retry.FailedSubscribedTask;
import org.apache.dubbo.registry.retry.FailedUnregisteredTask;
import org.apache.dubbo.registry.retry.FailedUnsubscribedTask;
import org.apache.dubbo.remoting.Constants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RETRY_PERIOD;
import static org.apache.dubbo.registry.Constants.REGISTRY_RETRY_PERIOD_KEY;

/**
 * FailbackRegistry. (SPI, Prototype, ThreadSafe)
 */
public abstract class FailbackRegistry extends AbstractRegistry {

    /*  retry task map */
    //注册失败的URL缓存集合  key:注册失败的URL  value：重试注册任务
    private final ConcurrentMap<URL, FailedRegisteredTask> failedRegistered = new ConcurrentHashMap<URL, FailedRegisteredTask>();
    //取消注册失败的URL缓存集合 key:取消注册的url  value：重试取消注册任务
    private final ConcurrentMap<URL, FailedUnregisteredTask> failedUnregistered = new ConcurrentHashMap<URL, FailedUnregisteredTask>();
    //订阅失败URL缓存集合 key:Holder(url,listener) value：重试订阅任务
    private final ConcurrentMap<Holder, FailedSubscribedTask> failedSubscribed = new ConcurrentHashMap<Holder, FailedSubscribedTask>();
    //取消订阅失败URL缓存集合 key:Holder(url,listener) value：重试取消订阅任务
    private final ConcurrentMap<Holder, FailedUnsubscribedTask> failedUnsubscribed = new ConcurrentHashMap<Holder, FailedUnsubscribedTask>();
    //订阅URL通知失败缓存集合  key:Holder(订阅url,listener) value：重试通知任务
    private final ConcurrentMap<Holder, FailedNotifiedTask> failedNotified = new ConcurrentHashMap<Holder, FailedNotifiedTask>();

    /**
     * The time in milliseconds the retryExecutor will wait
     */
    private final int retryPeriod;

    // Timer for failure retry, regular check if there is a request for failure, and if there is, an unlimited retry
    private final HashedWheelTimer retryTimer;

    public FailbackRegistry(URL url) {
        super(url);
        this.retryPeriod = url.getParameter(REGISTRY_RETRY_PERIOD_KEY, DEFAULT_REGISTRY_RETRY_PERIOD);

        // since the retry task will not be very much. 128 ticks is enough.
        retryTimer = new HashedWheelTimer(new NamedThreadFactory("DubboRegistryRetryTimer", true), retryPeriod, TimeUnit.MILLISECONDS, 128);
    }

    public void removeFailedRegisteredTask(URL url) {
        failedRegistered.remove(url);
    }

    public void removeFailedUnregisteredTask(URL url) {
        failedUnregistered.remove(url);
    }

    public void removeFailedSubscribedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedSubscribed.remove(h);
    }

    public void removeFailedUnsubscribedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedUnsubscribed.remove(h);
    }

    public void removeFailedNotifiedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedNotified.remove(h);
    }

    private void addFailedRegistered(URL url) {
        //如果注册失败URL对应的重试注册任务已经存在，那么就不需要再添加了
        FailedRegisteredTask oldOne = failedRegistered.get(url);
        if (oldOne != null) {
            return;
        }
        //创建重试注册URL的重试任务
        FailedRegisteredTask newTask = new FailedRegisteredTask(url, this);
        //将重试任务添加到 注册失败重试任务缓存集合中
        oldOne = failedRegistered.putIfAbsent(url, newTask);
        //如果是第一次添加 则将任务放入时间轮中开始定时重试（防止并发添加重试任务）
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedRegistered(URL url) {
        //取消失败重试注册任务
        FailedRegisteredTask f = failedRegistered.remove(url);
        if (f != null) {
            f.cancel();
        }
    }

    private void addFailedUnregistered(URL url) {
        FailedUnregisteredTask oldOne = failedUnregistered.get(url);
        if (oldOne != null) {
            return;
        }
        FailedUnregisteredTask newTask = new FailedUnregisteredTask(url, this);
        oldOne = failedUnregistered.putIfAbsent(url, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedUnregistered(URL url) {
        //取消失败重试取消注册任务
        FailedUnregisteredTask f = failedUnregistered.remove(url);
        if (f != null) {
            f.cancel();
        }
    }

    protected void addFailedSubscribed(URL url, NotifyListener listener) {
        //包装订阅url与相应监听器
        Holder h = new Holder(url, listener);
        //添加重试订阅URL任务
        FailedSubscribedTask oldOne = failedSubscribed.get(h);
        if (oldOne != null) {
            return;
        }
        FailedSubscribedTask newTask = new FailedSubscribedTask(url, this, listener);
        //第一次添加的话 则开启时间轮重试任务
        oldOne = failedSubscribed.putIfAbsent(h, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedSubscribed(URL url, NotifyListener listener) {
        //包装订阅url与相应监听器
        Holder h = new Holder(url, listener);
        //取消重试订阅URL的任务
        FailedSubscribedTask f = failedSubscribed.remove(h);
        if (f != null) {
            f.cancel();
        }
        //取消 重试取消订阅URL的任务
        removeFailedUnsubscribed(url, listener);
        //取消订阅URL通知失败 重试任务
        removeFailedNotified(url, listener);
    }

    private void addFailedUnsubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedUnsubscribedTask oldOne = failedUnsubscribed.get(h);
        if (oldOne != null) {
            return;
        }
        FailedUnsubscribedTask newTask = new FailedUnsubscribedTask(url, this, listener);
        oldOne = failedUnsubscribed.putIfAbsent(h, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedUnsubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedUnsubscribedTask f = failedUnsubscribed.remove(h);
        if (f != null) {
            f.cancel();
        }
    }

    private void addFailedNotified(URL url, NotifyListener listener, List<URL> urls) {
        //增加失败重试 订阅通知任务
        Holder h = new Holder(url, listener);
        FailedNotifiedTask newTask = new FailedNotifiedTask(url, listener);
        FailedNotifiedTask f = failedNotified.putIfAbsent(h, newTask);
        if (f == null) {
            // never has a retry task. then start a new task for retry.
            //重试通知任务中添加 通知的变更URL
            newTask.addUrlToRetry(urls);
            //开启重试通知任务
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        } else {
            // just add urls which needs retry.
            //重试通知任务中添加 通知的变更URL
            newTask.addUrlToRetry(urls);
        }
    }

    private void removeFailedNotified(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedNotifiedTask f = failedNotified.remove(h);
        if (f != null) {
            f.cancel();
        }
    }

    ConcurrentMap<URL, FailedRegisteredTask> getFailedRegistered() {
        return failedRegistered;
    }

    ConcurrentMap<URL, FailedUnregisteredTask> getFailedUnregistered() {
        return failedUnregistered;
    }

    ConcurrentMap<Holder, FailedSubscribedTask> getFailedSubscribed() {
        return failedSubscribed;
    }

    ConcurrentMap<Holder, FailedUnsubscribedTask> getFailedUnsubscribed() {
        return failedUnsubscribed;
    }

    ConcurrentMap<Holder, FailedNotifiedTask> getFailedNotified() {
        return failedNotified;
    }

    @Override
    public void register(URL url) {
        //<dubbo:registry accepts = ''> 参数accepts指定 注册中心registryUrl可以接受注册的协议列表 用逗号分隔
        if (!acceptable(url)) {
            logger.info("URL " + url + " will not be registered to Registry. Registry " + url + " does not accept service of this protocol type.");
            return;
        }
        //缓存registerUrl
        super.register(url);
        //如果有正在重试该URL注册的任务，则取消掉
        removeFailedRegistered(url);
        //如果有正在重试取消注册该URL的任务，则取消掉
        removeFailedUnregistered(url);
        try {
            // Sending a registration request to the server side
            //调用子类方法，实现具体的注册逻辑
            doRegister(url);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            //是否需要检查注册中心连通性，如果需要检查 注册失败时会直接抛出异常IllegalStateException
            //不需要检查 则会在后台自动重试
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to register " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                //打印异常信息 并进行重试
                logger.error("Failed to register " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            // 将注册失败的URL 添加到注册失败重试任务缓存集合中（key为注册失败的URL  value:为重试任务）
            // 开启重试注册任务
            addFailedRegistered(url);
        }
    }

    @Override
    public void reExportRegister(URL url) {
        if (!acceptable(url)) {
            logger.info("URL " + url + " will not be registered to Registry. Registry " + url + " does not accept service of this protocol type.");
            return;
        }
        super.register(url);
        removeFailedRegistered(url);
        removeFailedUnregistered(url);
        try {
            // Sending a registration request to the server side
            doRegister(url);
        } catch (Exception e) {
            if (!(e instanceof SkipFailbackWrapperException)) {
                throw new IllegalStateException("Failed to register (re-export) " + url + " to registry " + getUrl().getAddress() + ", cause: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void unregister(URL url) {
        //从注册URL缓存中删除 url
        super.unregister(url);
        //如果有正在重试该URL注册的任务，则取消掉
        removeFailedRegistered(url);
        //如果有正在重试取消注册该URL的任务 ，则取消掉
        removeFailedUnregistered(url);
        try {
            // Sending a cancellation request to the server side
            //调用子类方法，执行具体的取消注册逻辑
            doUnregister(url);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            //是否需要检查注册中心连通性，如果需要检查 注册失败时会直接抛出异常IllegalStateException
            //不需要检查 则会在后台自动重试
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unregister " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unregister " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            //开启重试取消注册URL任务
            addFailedUnregistered(url);
        }
    }

    @Override
    public void reExportUnregister(URL url) {
        super.unregister(url);
        removeFailedRegistered(url);
        removeFailedUnregistered(url);
        try {
            // Sending a cancellation request to the server side
            doUnregister(url);
        } catch (Exception e) {
            if (!(e instanceof SkipFailbackWrapperException)) {
                throw new IllegalStateException("Failed to unregister(re-export) " + url + " to registry " + getUrl().getAddress() + ", cause: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        //缓存订阅的URL与监听器
        super.subscribe(url, listener);
        //如果有正在重试订阅URL的任务 则取消掉
        removeFailedSubscribed(url, listener);
        try {
            // Sending a subscription request to the server side
            //调用子类方法，执行具体的订阅URL逻辑
            doSubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;
            //如果订阅失败，则从本地缓存中获取订阅数据（比如providerUrl列表 router,configuators等）
            List<URL> urls = getCacheUrls(url);
            if (CollectionUtils.isNotEmpty(urls)) {
                //首次订阅 全量通知
                notify(url, listener, urls);
                logger.error("Failed to subscribe " + url + ", Using cached list: " + urls + " from cache file: " + getUrl().getParameter(FILE_KEY, System.getProperty("user.home") + "/dubbo-registry-" + url.getHost() + ".cache") + ", cause: " + t.getMessage(), t);
            } else {
                // If the startup detection is opened, the Exception is thrown directly.
                //是否检查注册中心的连通性
                boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                        && url.getParameter(Constants.CHECK_KEY, true);
                //是否跳过失败重试
                boolean skipFailback = t instanceof SkipFailbackWrapperException;
                //如果需要检查注册中心连通性或者跳过失败重试 则 直接抛出异常IllegalStateException
                if (check || skipFailback) {
                    if (skipFailback) {
                        t = t.getCause();
                    }
                    throw new IllegalStateException("Failed to subscribe " + url + ", cause: " + t.getMessage(), t);
                } else {
                    logger.error("Failed to subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
                }
            }

            // Record a failed registration request to a failed list, retry regularly
            //添加订阅失败重试任务
            addFailedSubscribed(url, listener);
        }
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        //订阅缓存中删除URL中对应的监听器
        super.unsubscribe(url, listener);
        //取消订阅失败重试任务
        removeFailedSubscribed(url, listener);
        try {
            // Sending a canceling subscription request to the server side
            //调用子类方法，执行具体的取消订阅逻辑
            doUnsubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true);
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unsubscribe " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unsubscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            //添加重试取消订阅任务
            addFailedUnsubscribed(url, listener);
        }
    }

    @Override
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        try {
            //调用父类AbstractRegistry#notify 缓存变更数据
            doNotify(url, listener, urls);
        } catch (Exception t) {
            // Record a failed registration request to a failed list, retry regularly
            //订阅通知失败，添加订阅失败重试通知任务
            addFailedNotified(url, listener, urls);
            logger.error("Failed to notify for subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
        }
    }

    protected void doNotify(URL url, NotifyListener listener, List<URL> urls) {
        super.notify(url, listener, urls);
    }

    @Override
    protected void  recover() throws Exception {
        // register
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                //利用重试任务  重新注册URL
                addFailedRegistered(url);
            }
        }
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    //利用重试任务  重新订阅URL
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        retryTimer.stop();
    }

    // ==== Template method ====

    public abstract void doRegister(URL url);

    public abstract void doUnregister(URL url);

    public abstract void doSubscribe(URL url, NotifyListener listener);

    public abstract void doUnsubscribe(URL url, NotifyListener listener);

    static class Holder {

        private final URL url;

        private final NotifyListener notifyListener;

        Holder(URL url, NotifyListener notifyListener) {
            if (url == null || notifyListener == null) {
                throw new IllegalArgumentException();
            }
            this.url = url;
            this.notifyListener = notifyListener;
        }

        @Override
        public int hashCode() {
            return url.hashCode() + notifyListener.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Holder) {
                Holder h = (Holder) obj;
                return this.url.equals(h.url) && this.notifyListener.equals(h.notifyListener);
            } else {
                return false;
            }
        }
    }
}
