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
package org.apache.dubbo.common.threadpool.support.cached;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ALIVE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CORE_THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_ALIVE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CORE_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_QUEUES;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.QUEUES_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * This thread pool is self-tuned. Thread will be recycled after idle for one minute, and new thread will be created for
 * the upcoming request.
 *
 * @see java.util.concurrent.Executors#newCachedThreadPool()
 */
public class CachedThreadPool implements ThreadPool {
    /**
     * 首先我们来看客户端的发送 request 方向：
     *
     * 1. 如果是异步发送，那么客户端用到的就是这里的 CachedThreadPool（request future 中绑定的 executor）
     * IO 线程负责发送 request , 然后回调 heartbeatHandler,以及 HeaderExchange 中设置 furture send time
     *
     * 当 response 回来的时候，使用 CachedThreadPool 通知 request future
     *
     * 2. 如果是同步发送，那么 request future 中绑定的 executor 则是 ThreadLessExecutor
     * 由 ThreadLessExecutor 负责处理 response 响应，也就是业务线程
     *
     * 现在 dubbo 的实现都是一个 port 对应一个 CachedThreadPool，也就是一条连接（client）对应一个 CachedThreadPool
     * 但其实 CachedThreadPool 应该跟着进程走，而不是端口，由一个 CachedThreadPool 处理进程所有的 client 请求响应
     *
     * 想了想，不过 dubbo 的这种设计也挺好，还是按照 client(连接) 分开比较好，这样并发度还能高一些 ？
     * */
    @Override
    public Executor getExecutor(URL url) {
        String name = url.getParameter(THREAD_NAME_KEY, DEFAULT_THREAD_NAME);
        int cores = url.getParameter(CORE_THREADS_KEY, DEFAULT_CORE_THREADS);
        int threads = url.getParameter(THREADS_KEY, Integer.MAX_VALUE);
        int queues = url.getParameter(QUEUES_KEY, DEFAULT_QUEUES);
        // Thread will be recycled after idle for one minute
        int alive = url.getParameter(ALIVE_KEY, DEFAULT_ALIVE); // 60s
        // queues = 0 , Executor 对应 SynchronousQueue
        // queues < 0 , Executor 对应 LinkedBlockingQueue
        // queues > 0 , Executor 对应 LinkedBlockingQueue(queues)

        // cores = 0 , threads = MAX_VALUE , alive = 60s

        // IO 线程 offser 任务，如果没有 dubbo 线程 take ，那么就创建 dubbo 线程，空闲存活 60s
        return new ThreadPoolExecutor(cores, threads, alive, TimeUnit.MILLISECONDS,
                queues == 0 ? new SynchronousQueue<Runnable>() :
                        (queues < 0 ? new LinkedBlockingQueue<Runnable>()
                                : new LinkedBlockingQueue<Runnable>(queues)),
                new NamedInternalThreadFactory(name, true), new AbortPolicyWithReport(name, url));
    }
}
