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

package org.apache.dubbo.common.threadpool.support.eager;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EagerThreadPoolExecutor
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

    /**
     * task count
     */
    private final AtomicInteger submittedTaskCount = new AtomicInteger(0);

    public EagerThreadPoolExecutor(int corePoolSize,
                                   int maximumPoolSize,
                                   long keepAliveTime,
                                   TimeUnit unit, TaskQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    /**
     * @return current tasks which are executed
     */
    public int getSubmittedTaskCount() {
        return submittedTaskCount.get();
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        submittedTaskCount.decrementAndGet();
    }

    /**
     * 首先常规线程池的执行逻辑是：
     * 1. 当线程池中的线程数小于 corePoolSize 的时候，向线程池提交一个任务就会创建一个线程来执行
     * 注意这种情况下，即使 core thread 处于空闲状态，但只要线程池中的 thread 个数小于 corePoolSize 就会创建新的线程
     *
     * 2. 当线程池中的 thread 个数达到 corePoolSize 的时候，首先会尝试将任务 offer 到队列 queue 中
     * 然后空闲线程从 queue 中 take 任务执行
     *
     * 3. 如果队列 queue 已满，offer 失败，但此时线程池中的 thread 个数小于 maximumPoolSize
     * 则会创建一个新的线程（not core）来执行新的任务
     *
     * 4. 如果此时 queue 也满了，thread 个数也已经达到了 maximumPoolSize，那么就会 reject, 执行 RejectedExecutionHandler
     *
     * EagerThreadPool 的执行逻辑：
     *
     * 1. 首先和常规线程池一样，只要 thread 个数小于 corePoolSize 的时候，不管 core thread 是否空闲，都会创建
     * 一个新的线程来执行任务。
     *
     * 2. 这里是最大的不同，当 thread 个数达到 corePoolSize 的时候，EagerThreadPool 会判断是否有空闲的 thread
     * 如果有空闲的 thread，那么就放入 queue 中由空闲的 thread 执行
     * 如果没有空闲的 thread , 那么就会创建新的线程直接执行（Eager的体现），注意这里是不会放入 queue 中的
     *
     * 如何判断 thread 是否空闲 ？
     *
     * EagerThreadPoolExecutor 设计了一个 submittedTaskCount，表示当前线程池正在处理的任务个数
     * 如果 submittedTaskCount < PoolSize（线程池当前的线程个数）, 说明此时有 thread 空闲
     * 那么就将任务放入 queue 中由空闲的 thread 执行
     *
     * 如果没有 thread 空闲，但是此时线程池中 thread 个数小于 MaximumPoolSize ， 那么就创建一个新的线程执行
     * see : org.apache.dubbo.common.threadpool.support.eager.TaskQueue#offer(java.lang.Runnable)
     *
     * 如果此时线程池中的 thread 个数已经达到 MaximumPoolSize，并且没有空闲，那么就会将任务放入 queue 中
     * 等待空闲 thread 执行
     * */
    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        // do not increment in method beforeExecute!
        submittedTaskCount.incrementAndGet();
        try {
            super.execute(command);
        } catch (RejectedExecutionException rx) {
            // retry to offer the task into queue.
            final TaskQueue queue = (TaskQueue) super.getQueue();
            try {
                if (!queue.retryOffer(command, 0, TimeUnit.MILLISECONDS)) {
                    submittedTaskCount.decrementAndGet();
                    throw new RejectedExecutionException("Queue capacity is full.", rx);
                }
            } catch (InterruptedException x) {
                submittedTaskCount.decrementAndGet();
                throw new RejectedExecutionException(x);
            }
        } catch (Throwable t) {
            // decrease any way
            submittedTaskCount.decrementAndGet();
            throw t;
        }
    }
}
