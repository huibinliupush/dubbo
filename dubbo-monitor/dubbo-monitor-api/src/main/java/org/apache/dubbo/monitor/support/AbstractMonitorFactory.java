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
package org.apache.dubbo.monitor.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;

/**
 * AbstractMonitorFactory. (SPI, Singleton, ThreadSafe)
 */
public abstract class AbstractMonitorFactory implements MonitorFactory {
    private static final Logger logger = LoggerFactory.getLogger(AbstractMonitorFactory.class);

    /**
     * The lock for getting monitor center
     */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * The monitor centers Map<RegistryAddress, Registry>
     *
     *  一个监控中心对应一个monitor,监控中心就是一个dubbo服务MonitorService 可以注册在不同注册中心
     *  缓存不同注册中心中得监控中心对应的监控monitor
     */
    private static final Map<String, Monitor> MONITORS = new ConcurrentHashMap<String, Monitor>();

    //缓存创建Monitor的异步结果   在MonitorListener中获取使用，并删除。
    private static final Map<String, CompletableFuture<Monitor>> FUTURES = new ConcurrentHashMap<String, CompletableFuture<Monitor>>();

    /**
     * The monitor create executor
     * 异步创建monitor的线程池
     */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(0, 10, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new NamedThreadFactory("DubboMonitorCreator", true));

    public static Collection<Monitor> getMonitors() {
        return Collections.unmodifiableCollection(MONITORS.values());
    }

    @Override
    public Monitor getMonitor(URL url) {
        //dubbo://127.0.0.1:2181/org.apache.dubbo.monitor.MonitorService?application=demo-provider&dubbo=2.0.2&extra-keys=interface,key1,key2&interface=org.apache.dubbo.monitor.MonitorService&metadata-type=remote&pid=29125&protocol=registry&qos.port=22228&refer=application%3Ddemo-provider%26dubbo%3D2.0.2%26interface%3Dorg.apache.dubbo.monitor.MonitorService%26interval%3D100%26metadata-type%3Dremote%26pid%3D29125%26qos.port%3D22228%26register.ip%3D192.168.1.101%26timestamp%3D1624950208377&registry=zookeeper&simplified=true&timestamp=1624950208276
        url = url.setPath(MonitorService.class.getName()).addParameter(INTERFACE_KEY, MonitorService.class.getName());
        //dubbo://127.0.0.1:2181/org.apache.dubbo.monitor.MonitorService
        String key = url.toServiceStringWithoutResolving();
        Monitor monitor = MONITORS.get(key);
        Future<Monitor> future = FUTURES.get(key);
        if (monitor != null || future != null) {
            return monitor;
        }

        LOCK.lock();
        try {
            monitor = MONITORS.get(key);
            future = FUTURES.get(key);
            if (monitor != null || future != null) {
                return monitor;
            }

            final URL monitorUrl = url;
            //异步创建monitor
            final CompletableFuture<Monitor> completableFuture = CompletableFuture.supplyAsync(() -> AbstractMonitorFactory.this.createMonitor(monitorUrl));
            FUTURES.put(key, completableFuture);
            //异步获取创建结果
            completableFuture.thenRunAsync(new MonitorListener(key), EXECUTOR);

            return null;
        } finally {
            // unlock
            LOCK.unlock();
        }
    }

    protected abstract Monitor createMonitor(URL url);


    class MonitorListener implements Runnable {

        private String key;

        public MonitorListener(String key) {
            this.key = key;
        }

        @Override
        public void run() {
            try {
                //从缓存中获取异步创建monitor的future
                CompletableFuture<Monitor> completableFuture = AbstractMonitorFactory.FUTURES.get(key);
                //将创建出来的Monitor放入缓存中
                AbstractMonitorFactory.MONITORS.put(key, completableFuture.get());
                AbstractMonitorFactory.FUTURES.remove(key);
            } catch (InterruptedException e) {
                logger.warn("Thread was interrupted unexpectedly, monitor will never be got.");
                AbstractMonitorFactory.FUTURES.remove(key);
            } catch (ExecutionException e) {
                logger.warn("Create monitor failed, monitor data will not be collected until you fix this problem. ", e);
            }
        }
    }

}
