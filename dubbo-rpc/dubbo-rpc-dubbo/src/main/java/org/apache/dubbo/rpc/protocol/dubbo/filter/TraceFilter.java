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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TraceFilter
 */
@Activate(group = CommonConstants.PROVIDER)
public class TraceFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TraceFilter.class);

    //最大跟踪次数key
    private static final String TRACE_MAX = "trace.max";
    //已经跟踪了多少次 key
    private static final String TRACE_COUNT = "trace.count";

    //缓存所有执行telnet trace命令的telnet客户端连接
    private static final ConcurrentMap<String, Set<Channel>> TRACERS = new ConcurrentHashMap<>();

    /**
     * 当telnet客户端执行trace命令的时候，处理telnet trace命令的TraceTelnetHandler 就会调用该方法
     * 开始对trace命令指定的服务或者方法进行跟踪
     *
     * type：需要跟踪的服务接口
     * metthod：需要跟踪的服务方法
     * channel：调用trace命令的telnet客户端连接
     * max: 最大跟踪次数，超过这个次数则停止跟踪
     *
     * */
    public static void addTracer(Class<?> type, String method, Channel channel, int max) {
        channel.setAttribute(TRACE_MAX, max);
        channel.setAttribute(TRACE_COUNT, new AtomicInteger());
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = TRACERS.computeIfAbsent(key, k -> new ConcurrentHashSet<>());
        channels.add(channel);
    }

    public static void removeTracer(Class<?> type, String method, Channel channel) {
        channel.removeAttribute(TRACE_MAX);
        channel.removeAttribute(TRACE_COUNT);
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = TRACERS.get(key);
        if (channels != null) {
            channels.remove(channel);
        }
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        long start = System.currentTimeMillis();
        //调用下一个Filter执行服务调用
        Result result = invoker.invoke(invocation);
        //统计服务调用时长
        long end = System.currentTimeMillis();
        if (TRACERS.size() > 0) {
            //构造trace key,在缓存TRACERS中查找是否有在执行trace命令的 telnet客户端
            String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
            Set<Channel> channels = TRACERS.get(key);
            if (channels == null || channels.isEmpty()) {
                key = invoker.getInterface().getName();
                channels = TRACERS.get(key);
            }
            if (CollectionUtils.isNotEmpty(channels)) {
                for (Channel channel : new ArrayList<>(channels)) {
                    if (channel.isConnected()) {
                        try {
                            //获取trace命令 指定的最大跟踪次数 默认为1
                            int max = 1;
                            Integer m = (Integer) channel.getAttribute(TRACE_MAX);
                            if (m != null) {
                                max = m;
                            }
                            //获取 目前已经跟踪多少次服务调用
                            int count = 0;
                            AtomicInteger c = (AtomicInteger) channel.getAttribute(TRACE_COUNT);
                            if (c == null) {
                                c = new AtomicInteger();
                                channel.setAttribute(TRACE_COUNT, c);
                            }
                            count = c.getAndIncrement();
                            if (count < max) {
                                //如果目前的跟踪次数 没有 超过最大跟踪次数，则将服务调用信息 返回给telnet客户端
                                String prompt = channel.getUrl().getParameter(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
                                channel.send("\r\n" + RpcContext.getContext().getRemoteAddress() + " -> "
                                        + invoker.getInterface().getName()
                                        + "." + invocation.getMethodName()
                                        + "(" + JSON.toJSONString(invocation.getArguments()) + ")" + " -> " + JSON.toJSONString(result.getValue())
                                        + "\r\nelapsed: " + (end - start) + " ms."
                                        + "\r\n\r\n" + prompt);
                            }
                            if (count >= max - 1) {
                                //如果已经达到最大跟踪次数的话  移除telnet客户端连接
                                channels.remove(channel);
                            }
                        } catch (Throwable e) {
                            channels.remove(channel);
                            logger.warn(e.getMessage(), e);
                        }
                    } else {
                        channels.remove(channel);
                    }
                }
            }
        }
        return result;
    }

}
