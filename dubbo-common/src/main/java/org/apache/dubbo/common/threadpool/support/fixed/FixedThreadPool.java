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
package org.apache.dubbo.common.threadpool.support.fixed;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_QUEUES;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.QUEUES_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * Creates a thread pool that reuses a fixed number of threads
 *
 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
 */
public class FixedThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {
        // 设置线程池的名字：org.apache.dubbo.remoting.transport.netty4.NettyServer.NettyServer
        String name = url.getParameter(THREAD_NAME_KEY, DEFAULT_THREAD_NAME);
        int threads = url.getParameter(THREADS_KEY, DEFAULT_THREADS);
        int queues = url.getParameter(QUEUES_KEY, DEFAULT_QUEUES);
        // 默认 200 个线程，默认 SynchronousQueue （queues = 0）
        // queues < 0 :  无界LinkedBlockingQueue
        // queues > 0 :  有界LinkedBlockingQueue

        // https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/concurrent/SynchronousQueue.html
        // IO 线程提交任务，提交一个任务创建一个 dubbo 线程，直到创建好 200 个 dubbo 线程
        // IO 线程调用 boolean offer(E e) 向 SynchronousQueue 中添加元素，如果此时正好有 dubbo 线程在调用  take() 等待
        // 那么 IO 线程中的 offer 返回 true，dubbo 线程直接 take() 走任务
        // 如果此时 dubbo 线程全部在执行任务，那么 IO 线程的 offer 返回 false, 直接执行 AbortPolicyWithReport

        // 如果没有 IO 线程通过 offer 提交任务，那么 dubbo 线程就会在 take 方法上阻塞等待


/**
 *         但是这里请注意 IO 线程永远不会在这里阻塞，因为使用的是 offer , 没有 dubbo 线程等待就返回 false
 *         但 IO 线程会执行 AbortPolicyWithReport 中的 dump 操作
 * */
        // SynchronousQueue 的所有操作都是无锁的，只不过 take 不到会将自己阻塞
        // LinkedBlockingQueue 的所有操作是要加锁的，offer 的时候也要加锁判断队列容量，成功返回 true, 失败返回 false
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                queues == 0 ? new SynchronousQueue<Runnable>() :
                        (queues < 0 ? new LinkedBlockingQueue<Runnable>() // 阻塞队列的 offer 操作也是要加锁的
                                : new LinkedBlockingQueue<Runnable>(queues)),
                new NamedInternalThreadFactory(name, true), new AbortPolicyWithReport(name, url));

        // 线程池中向队列添加元素用的都是 offer , 避免阻塞提交线程
        // 线程池中的线程从队列中获取元素都是用的 take , 获取不到就阻塞
    }

}
