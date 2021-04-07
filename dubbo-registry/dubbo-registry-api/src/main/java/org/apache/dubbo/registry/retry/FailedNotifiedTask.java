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

package org.apache.dubbo.registry.retry;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FailedNotifiedTask
 */
public final class FailedNotifiedTask extends AbstractRetryTask {

    private static final String NAME = "retry notify";

    private final NotifyListener listener;
    //缓存需要通知的订阅urls,需要在重试任务中通知给NotifyListener
    private final List<URL> urls = new CopyOnWriteArrayList<>();

    public FailedNotifiedTask(URL url, NotifyListener listener) {
        super(url, null, NAME);
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        this.listener = listener;
    }

    public void addUrlToRetry(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        this.urls.addAll(urls);
    }

    public void removeRetryUrl(List<URL> urls) {
        this.urls.removeAll(urls);
    }

    @Override
    protected void doRetry(URL url, FailbackRegistry registry, Timeout timeout) {
        //需要通知的订阅urls缓存不为空，则通知给NotifyListener
        if (CollectionUtils.isNotEmpty(urls)) {
            listener.notify(urls);
            //通知完 就清空urls缓存
            urls.clear();
        }
        //每次执行完，都会将重试通知任务 重新放入时间轮中发起 新一轮重试
        //这里比较特殊，因为重试通知任务是需要一直保持的，没有通知则空执行一次，有通知 则执行通知。
        //为什么需要一直保持通知重试任务？直到达到最大重试次数才主动停止，不然只会被动停止FailbackRegistry.subscribe
        //只所以需要重试，是因为通知已经失败了，注册中心可能随时有变更，随时需要通知，那么下次通知很大可能也会失败
        //不可能像其他重试任务一样，每次通知失败创建一个任务（通知频率可能会很高）。
        //所以在发生通知失败的场景下，创建了一个重试通知的任务，需要一直保持在时间轮里，方便下次变更通知失败直接重试。
        //org.apache.dubbo.registry.support.FailbackRegistry.subscribe中会清理FailedNotifiedTask任务
        reput(timeout, retryPeriod);
    }
}
