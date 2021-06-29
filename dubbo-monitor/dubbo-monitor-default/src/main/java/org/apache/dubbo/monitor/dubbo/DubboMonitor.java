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
package org.apache.dubbo.monitor.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.rpc.Invoker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_PROTOCOL;

/**
 * DubboMonitor
 */
public class DubboMonitor implements Monitor {

    private static final Logger logger = LoggerFactory.getLogger(DubboMonitor.class);

    /**
     * The length of the array which is a container of the statistics
     * 服务接口调用统计信息的种类 一共10种统计信息类型
     */
    private static final int LENGTH = 10;

    /**
     * The timer for sending statistics
     * 定时发送RPC调用统计信息给监控中心
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(3, new NamedThreadFactory("DubboMonitorSendTimer", true));

    /**
     * The future that can cancel the <b>scheduledExecutorService</b>
     * 用于销毁时 取消定时发送任务
     */
    private final ScheduledFuture<?> sendFuture;

    //DubboMonitor是监控中心MonitorService的consumer，这里是它的invoker
    private final Invoker<MonitorService> monitorInvoker;

    //远端监控中心代理，本质是一个dubbo服务引用，监控中心本质上也是一个dubbo服务 dubbo协议暴露MonitorService服务接口
    private final MonitorService monitorService;

    //负责缓存所有服务接口的调用统计信息，key：服务接口的统计模型代表一个服务接口的统计信息   value：long型数组 保存RPC调用的统计信息
    private final ConcurrentMap<Statistics, AtomicReference<long[]>> statisticsMap = new ConcurrentHashMap<Statistics, AtomicReference<long[]>>();

    public DubboMonitor(Invoker<MonitorService> monitorInvoker, MonitorService monitorService) {
        //DubboMonitor相当于监控中心服务MonitorSerivce的consumer
        this.monitorInvoker = monitorInvoker;
        this.monitorService = monitorService;
        // The time interval for timer <b>scheduledExecutorService</b> to send data
        // 由配置<dubbo:monitor  interval="">设置 默认1分钟
        final long monitorInterval = monitorInvoker.getUrl().getPositiveParameter("interval", 60000);
        // collect timer for collecting statistics data
        sendFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                // collect data
                send();
            } catch (Throwable t) {
                logger.error("Unexpected error occur at send statistic, cause: " + t.getMessage(), t);
            }
        }, monitorInterval, monitorInterval, TimeUnit.MILLISECONDS);
    }

    public void send() {
        if (logger.isDebugEnabled()) {
            logger.debug("Send statistics to monitor " + getUrl());
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        //将<dubbo:monitor  interval="">  interval时间间隔内的各个服务接口调用统计信息发送给监控中心MonitorService
        for (Map.Entry<Statistics, AtomicReference<long[]>> entry : statisticsMap.entrySet()) {
            // get statistics data
            //获取代表服务接口的统计模型
            Statistics statistics = entry.getKey();
            //获取服务接口调用的统计数据
            AtomicReference<long[]> reference = entry.getValue();
            long[] numbers = reference.get();
            long success = numbers[0];
            long failure = numbers[1];
            long input = numbers[2];
            long output = numbers[3];
            long elapsed = numbers[4];
            long concurrent = numbers[5];
            long maxInput = numbers[6];
            long maxOutput = numbers[7];
            long maxElapsed = numbers[8];
            long maxConcurrent = numbers[9];
            String protocol = getUrl().getParameter(DEFAULT_PROTOCOL);

            // 将interval时间间隔内的  服务接口调用统计信息 生成statisticUrl
            //count://192.168.1.101:20880/org.apache.dubbo.demo.DemoService/sayHello?application=demo-provider&concurrent=1&consumer=192.168.1.101&dubbo=2.0.2&elapsed=0&failure=0&group=&input=249&interface=org.apache.dubbo.demo.DemoService&max.concurrent=1&max.elapsed=0&max.input=249&max.output=0&method=sayHello&output=0&success=1&timestamp=1624949295408&version=
            URL url = statistics.getUrl()
                    .addParameters(MonitorService.TIMESTAMP, timestamp,
                            MonitorService.SUCCESS, String.valueOf(success),
                            MonitorService.FAILURE, String.valueOf(failure),
                            MonitorService.INPUT, String.valueOf(input),
                            MonitorService.OUTPUT, String.valueOf(output),
                            MonitorService.ELAPSED, String.valueOf(elapsed),
                            MonitorService.CONCURRENT, String.valueOf(concurrent),
                            MonitorService.MAX_INPUT, String.valueOf(maxInput),
                            MonitorService.MAX_OUTPUT, String.valueOf(maxOutput),
                            MonitorService.MAX_ELAPSED, String.valueOf(maxElapsed),
                            MonitorService.MAX_CONCURRENT, String.valueOf(maxConcurrent),
                            DEFAULT_PROTOCOL, protocol
                    );
            //将时间间隔interval内的，服务接口调用统计信息 发送给监控中心
            monitorService.collect(url);

            // 重置服务接口的统计信息 开启下一轮的统计信息收集
            long[] current;
            long[] update = new long[LENGTH];
            do {
                current = reference.get();
                if (current == null) {
                    update[0] = 0;
                    update[1] = 0;
                    update[2] = 0;
                    update[3] = 0;
                    update[4] = 0;
                    update[5] = 0;
                } else {
                    update[0] = current[0] - success;
                    update[1] = current[1] - failure;
                    update[2] = current[2] - input;
                    update[3] = current[3] - output;
                    update[4] = current[4] - elapsed;
                    update[5] = current[5] - concurrent;
                }
            } while (!reference.compareAndSet(current, update));
        }
    }

    @Override
    public void collect(URL url) {
        // data to collect from url
        //从statisticsURL中解析出RPC调用的统计信息

        //RPC调用是成功还是失败
        int success = url.getParameter(MonitorService.SUCCESS, 0);
        int failure = url.getParameter(MonitorService.FAILURE, 0);

        //RPC调用传入的字节大小 以及 返回的字节大小
        int input = url.getParameter(MonitorService.INPUT, 0);
        int output = url.getParameter(MonitorService.OUTPUT, 0);

        //RPC调用的耗时
        int elapsed = url.getParameter(MonitorService.ELAPSED, 0);
        //当前服务接口处理的并发数
        int concurrent = url.getParameter(MonitorService.CONCURRENT, 0);
        // init atomic reference
        //利用statisticsURL生成统计模型Staistics
        Statistics statistics = new Statistics(url);

        //获取本次RPC调用对应的统计信息  long数组
        AtomicReference<long[]> reference = statisticsMap.computeIfAbsent(statistics, k -> new AtomicReference<>());
        // use CompareAndSet to sum
        long[] current;
        long[] update = new long[LENGTH];
        do {
            current = reference.get();
            if (current == null) {
                //初始化统计信息
                update[0] = success;
                update[1] = failure;
                update[2] = input;
                update[3] = output;
                update[4] = elapsed;
                update[5] = concurrent;
                update[6] = input;
                update[7] = output;
                update[8] = elapsed;
                update[9] = concurrent;
            } else {
                //合并时间间隔内的统计信息  时间间隔为<dubbo:monitor  interval="">设置的参数
                //时间间隔interval内  RPC调用的总体成功次数 和 失败次数
                update[0] = current[0] + success;
                update[1] = current[1] + failure;

                //时间间隔interval内  RPC调用的总的传入字节大小 和 总的返回字节大小
                update[2] = current[2] + input;
                update[3] = current[3] + output;

                //时间间隔interval内  RPC调用的总的耗时
                update[4] = current[4] + elapsed;

                //时间间隔interval内  统计的服务接口平均的并发量
                update[5] = (current[5] + concurrent) / 2;

                //时间间隔interval内  RPC调用传入的最大字节大小
                update[6] = current[6] > input ? current[6] : input;
                //时间间隔interval内 RPC调用返回的最大字节大小
                update[7] = current[7] > output ? current[7] : output;
                //时间间隔interval内  RPC调用的最大耗时
                update[8] = current[8] > elapsed ? current[8] : elapsed;
                //时间间隔interval内  RPC调用的最大并发量
                update[9] = current[9] > concurrent ? current[9] : concurrent;
            }
        } while (!reference.compareAndSet(current, update));
    }

    @Override
    public List<URL> lookup(URL query) {
        return monitorService.lookup(query);
    }

    @Override
    public URL getUrl() {
        return monitorInvoker.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return monitorInvoker.isAvailable();
    }

    @Override
    public void destroy() {
        try {
            ExecutorUtil.cancelScheduledFuture(sendFuture);
        } catch (Throwable t) {
            logger.error("Unexpected error occur at cancel sender timer, cause: " + t.getMessage(), t);
        }
        monitorInvoker.destroy();
    }

}
