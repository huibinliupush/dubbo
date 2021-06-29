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
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.monitor.Constants.COUNT_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.INPUT_KEY;
import static org.apache.dubbo.rpc.Constants.OUTPUT_KEY;
/**
 * MonitorFilter. (SPI, Singleton, ThreadSafe)
 */
@Activate(group = {PROVIDER, CONSUMER})
public class MonitorFilter implements Filter, Filter.Listener {

    private static final Logger logger = LoggerFactory.getLogger(MonitorFilter.class);
    private static final String MONITOR_FILTER_START_TIME = "monitor_filter_start_time";

    /**
     * 缓存当前服务接口的 正在处理的并发请求数
     * The Concurrent counter
     */
    private final ConcurrentMap<String, AtomicInteger> concurrents = new ConcurrentHashMap<String, AtomicInteger>();

    /**
     * The MonitorFactory
     */
    private MonitorFactory monitorFactory;

    //SPI注入
    public void setMonitorFactory(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }


    /**
     * The invocation interceptor,it will collect the invoke data about this invocation and send it to monitor center
     *
     * @param invoker    service
     * @param invocation invocation.
     * @return {@link Result} the invoke result
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        //如果provider端或者consumer端配置了监控中心 <dubbo:monitor protocol="registry" interval="100"/>
        //providerUrl中就会包含monitorUrl  在serviceConfig构建ProviderUrl时设置
        if (invoker.getUrl().hasParameter(MONITOR_KEY)) {
            //设置该次RPC请求的监控开始时间
            invocation.put(MONITOR_FILTER_START_TIME, System.currentTimeMillis());
            //将请求服务方法的当前并发数加1
            getConcurrent(invoker, invocation).incrementAndGet(); // count up
        }
        //执行服务调用
        return invoker.invoke(invocation); // proceed invocation chain
    }

    // concurrent counter
    private AtomicInteger getConcurrent(Invoker<?> invoker, Invocation invocation) {
        //获取服务方法的正在处理的当前并发数
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
        return concurrents.computeIfAbsent(key, k -> new AtomicInteger());
    }

    @Override
    public void onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        //针对配置监控中心的服务情况
        if (invoker.getUrl().hasParameter(MONITOR_KEY)) {
            //收集这次RPC调用信息
            collect(invoker, invocation, result, RpcContext.getContext().getRemoteHost(), (long) invocation.get(MONITOR_FILTER_START_TIME), false);
            //RPC请求处理完成后，当前服务方法的当前并发数减1
            getConcurrent(invoker, invocation).decrementAndGet(); // count down
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        //针对配置监控中心的服务情况
        if (invoker.getUrl().hasParameter(MONITOR_KEY)) {
            //收集这次RPC调用异常信息
            collect(invoker, invocation, null, RpcContext.getContext().getRemoteHost(), (long) invocation.get(MONITOR_FILTER_START_TIME), true);
            //RPC请求处理异常后，当前服务方法的当前并发数减1
            getConcurrent(invoker, invocation).decrementAndGet(); // count down
        }
    }

    /**
     * The collector logic, it will be handled by the default monitor
     *
     * @param invoker
     * @param invocation
     * @param result     the invoke result
     * @param remoteHost the remote host address
     * @param start      the timestamp the invoke begin
     * @param error      if there is an error on the invoke
     */
    private void collect(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        try {
            //dubbo://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&extra-keys=interface,key1,key2&metadata-type=remote&pid=26552&protocol=registry&qos.port=22228&refer=application%3Ddemo-provider%26dubbo%3D2.0.2%26interface%3Dorg.apache.dubbo.monitor.MonitorService%26interval%3D100%26metadata-type%3Dremote%26pid%3D26552%26qos.port%3D22228%26register.ip%3D192.168.1.101%26timestamp%3D1624892427644&registry=zookeeper&simplified=true&timestamp=1624892427553
            URL monitorUrl = invoker.getUrl().getUrlParameter(MONITOR_KEY);
            //这里根据monitorUrl的协议头dubbo会加载到DubboMonitor（用于缓存统计信息，定时发送给监控中心）
            Monitor monitor = monitorFactory.getMonitor(monitorUrl);
            if (monitor == null) {
                return;
            }
            //创建统计信息，这里会将统计信息全部放在statisticsURL
            //count://192.168.1.101:20880/org.apache.dubbo.demo.DemoService/sayHello?application=demo-provider&concurrent=1&consumer=192.168.1.101&elapsed=46788&group=&input=249&interface=org.apache.dubbo.demo.DemoService&method=sayHello&output=&success=1&version=
            URL statisticsURL = createStatisticsUrl(invoker, invocation, result, remoteHost, start, error);
            //将RPC调用的统计信息  传递给 dubboMonitor缓存，准备定时发送给监控中心
            monitor.collect(statisticsURL);
        } catch (Throwable t) {
            logger.warn("Failed to monitor count service " + invoker.getUrl() + ", cause: " + t.getMessage(), t);
        }
    }

    /**
     * Create statistics url
     *
     * @param invoker
     * @param invocation
     * @param result
     * @param remoteHost
     * @param start
     * @param error
     * @return
     */
    private URL createStatisticsUrl(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        // ---- service statistics ----
        //服务调用耗时
        long elapsed = System.currentTimeMillis() - start; // invocation cost
        //当前服务方法正在处理的 并发数
        int concurrent = getConcurrent(invoker, invocation).get(); // current concurrent count
        //当前应用名
        String application = invoker.getUrl().getParameter(APPLICATION_KEY);
        //服务名
        String service = invoker.getInterface().getName(); // service name
        //服务接口方法
        String method = RpcUtils.getMethodName(invocation); // method name
        //服务分组
        String group = invoker.getUrl().getParameter(GROUP_KEY);
        //服务版本
        String version = invoker.getUrl().getParameter(VERSION_KEY);

        //设置本地端口信息和远端服务地址信息
        int localPort;
        String remoteKey, remoteValue;
        if (CONSUMER_SIDE.equals(invoker.getUrl().getParameter(SIDE_KEY))) {
            // ---- for service consumer ----
            localPort = 0;
            remoteKey = MonitorService.PROVIDER;
            remoteValue = invoker.getUrl().getAddress();
        } else {
            // ---- for service provider ----
            localPort = invoker.getUrl().getPort();
            remoteKey = MonitorService.CONSUMER;
            remoteValue = remoteHost;
        }
        String input = "", output = "";
        //设置RPC调用传入参数字节大小
        if (invocation.getAttachment(INPUT_KEY) != null) {
            input = invocation.getAttachment(INPUT_KEY);
        }
        //设置RPC调用返回结果的字节大小
        if (result != null && result.getAttachment(OUTPUT_KEY) != null) {
            output = result.getAttachment(OUTPUT_KEY);
        }

        //将这些统计信息生成 StatisticsUrl
        return new URL(COUNT_PROTOCOL, NetUtils.getLocalHost(), localPort, service + PATH_SEPARATOR + method, MonitorService.APPLICATION, application, MonitorService.INTERFACE, service, MonitorService.METHOD, method, remoteKey, remoteValue, error ? MonitorService.FAILURE : MonitorService.SUCCESS, "1", MonitorService.ELAPSED, String.valueOf(elapsed), MonitorService.CONCURRENT, String.valueOf(concurrent), INPUT_KEY, input, OUTPUT_KEY, output, GROUP_KEY, group, VERSION_KEY, version);
    }


}
