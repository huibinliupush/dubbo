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
package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * https://cn.dubbo.apache.org/zh-cn/blog/2020/05/18/dubbo-java-2.7.5-%E5%8A%9F%E8%83%BD%E8%A7%A3%E6%9E%90/
 * see: org.apache.dubbo.remoting.transport.dispatcher.WrappedChannelHandler#getPreferredExecutorService(java.lang.Object)
 *
 * 用于其他的consumer线程模型 (DirectChannelHandler)，decode,headerExchanger,dubboRequetHandler 直接在用户线程上执行
 * see : org.apache.dubbo.remoting.transport.dispatcher.direct.DirectChannelHandler#received(org.apache.dubbo.remoting.Channel, java.lang.Object)
 * org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker#invoke(org.apache.dubbo.rpc.Invocation)
 *
 * The most important difference between this Executor and other normal Executor is that this one doesn't manage
 * any thread.
 *
 * Tasks submitted to this executor through {@link #execute(Runnable)} will not get scheduled to a specific thread, though normal executors always do the schedule.
 * Those tasks are stored in a blocking queue and will only be executed when a thread calls {@link #waitAndDrain()}, the thread executing the task
 * is exactly the same as the one calling waitAndDrain.
 */
public class ThreadlessExecutor extends AbstractExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(ThreadlessExecutor.class.getName());

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    private ExecutorService sharedExecutor;

    private CompletableFuture<?> waitingFuture;

    private boolean finished = false;

    private volatile boolean waiting = true;

    private final Object lock = new Object();

    public ThreadlessExecutor(ExecutorService sharedExecutor) {
        this.sharedExecutor = sharedExecutor;
    }

    public CompletableFuture<?> getWaitingFuture() {
        return waitingFuture;
    }

    public void setWaitingFuture(CompletableFuture<?> waitingFuture) {
        this.waitingFuture = waitingFuture;
    }

    public boolean isWaiting() {
        return waiting;
    }

    /**
     * Waits until there is a task, executes the task and all queued tasks (if there're any). The task is either a normal
     * response or a timeout response.
     *
     * 调用线程（业务）负责执行 queue 里边的 task
     * see : org.apache.dubbo.rpc.AsyncRpcResult#get()
     * org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker#invoke(org.apache.dubbo.rpc.Invocation)
     */
    public void waitAndDrain() throws InterruptedException {
        /**
         * Usually, {@link #waitAndDrain()} will only get called once. It blocks for the response for the first time,
         * once the response (the task) reached and being executed waitAndDrain will return, the whole request process
         * then finishes. Subsequent calls on {@link #waitAndDrain()} (if there're any) should return immediately.
         *
         * There's no need to worry that {@link #finished} is not thread-safe. Checking and updating of
         * 'finished' only appear in waitAndDrain, since waitAndDrain is binding to one RPC call (one thread), the call
         * of it is totally sequential.
         */
        if (finished) {
            return;
        }
        // 调用线程这里阻塞等待 task
        Runnable runnable = queue.take();

        synchronized (lock) {
            waiting = false;
            // 调用线程执行
            runnable.run();
        }

        runnable = queue.poll();
        while (runnable != null) {
            try {
                runnable.run();
            } catch (Throwable t) {
                logger.info(t);

            }
            runnable = queue.poll();
        }
        // mark the status of ThreadlessExecutor as finished.
        finished = true;
    }

    public long waitAndDrain(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        /*long startInMs = System.currentTimeMillis();
        Runnable runnable = queue.poll(timeout, unit);
        if (runnable == null) {
            throw new TimeoutException();
        }
        runnable.run();
        long elapsedInMs = System.currentTimeMillis() - startInMs;
        long timeLeft = timeout - elapsedInMs;
        if (timeLeft < 0) {
            throw new TimeoutException();
        }
        return timeLeft;*/
        throw new UnsupportedOperationException();
    }

    /**
     * If the calling thread is still waiting for a callback task, add the task into the blocking queue to wait for schedule.
     * Otherwise, submit to shared callback executor directly.
     *
     * DirectChannelHandler 会将 decodeHandler, headerExchangerHandler , dubboRequestHandler 里的任务交给用户线程执行
     * see : org.apache.dubbo.remoting.transport.dispatcher.direct.DirectChannelHandler#received(org.apache.dubbo.remoting.Channel, java.lang.Object)
     * org.apache.dubbo.remoting.transport.dispatcher.all.AllChannelHandler#received(org.apache.dubbo.remoting.Channel, java.lang.Object)
     *
     * 用户线程在 org.apache.dubbo.rpc.AsyncRpcResult#get() 上调用 waitAndDrain 等待 task 中的任务
     * 远端的响应结果到来之后，会向 task 添加反序列化等任务，等待线程被唤醒执行（前提是线程模型是 DirectChannelHandler）
     *
     * org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker#invoke(org.apache.dubbo.rpc.Invocation)
     *
     * @param runnable
     */


    /**
     *    1. 同步请求线程会在 AsyncToSyncInvoker#invoke 中进行等待，调用 waitAndDrain 方法阻塞在 ThreadLessExecutor 上
     *
     *    2. AllChannelHandler 中会通过 responseId 拿到对应的 future, 获取 future 中的 ThreadLessExecutor
     *       然后向 ThreadLessExecutor 添加 decodeHandler, headerExchangerHandler , dubboRequestHandler 里的任务交给用户线程执行
     *       org.apache.dubbo.remoting.transport.dispatcher.all.AllChannelHandler#received(org.apache.dubbo.remoting.Channel, java.lang.Object)
     *
     *
     *    3. 同步请求线程从 ThreadLessExecutor 中被唤醒（waitAndDrain），因为 AllChannelHandler 已经添加了任务，然后同步请求线程执行任务
     *       org.apache.dubbo.remoting.exchange.support.DefaultFuture#received(org.apache.dubbo.remoting.Channel, org.apache.dubbo.remoting.exchange.Response, boolean)
     *       在 DefaultFuture#received 中 complete future
     *
 *        需要注意的是，一次同步请求，就会创建一个 ThreadLessExecutor，用于同步线程等待响应结果，并执行反序列化，receive 等操作
     *
     * */
    @Override
    public void execute(Runnable runnable) {
        synchronized (lock) {
            if (!waiting) {
                // 没有线程 wait ,交给  sharedExecutor 执行
                sharedExecutor.execute(runnable);
            } else {
                // 有线程正在 waiting,将任务放入 queue 中,由等待线程执行
                queue.add(runnable);
            }
        }
    }

    /**
     * tells the thread blocking on {@link #waitAndDrain()} to return, despite of the current status, to avoid endless waiting.
     */
    public void notifyReturn(Throwable t) {
        // an empty runnable task.
        execute(() -> {
            waitingFuture.completeExceptionally(t);
        });
    }

    /**
     * The following methods are still not supported
     */

    @Override
    public void shutdown() {
        shutdownNow();
    }

    @Override
    public List<Runnable> shutdownNow() {
        notifyReturn(new IllegalStateException("Consumer is shutting down and this call is going to be stopped without " +
                "receiving any result, usually this is called by a slow provider instance or bad service implementation."));
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
    }
}
