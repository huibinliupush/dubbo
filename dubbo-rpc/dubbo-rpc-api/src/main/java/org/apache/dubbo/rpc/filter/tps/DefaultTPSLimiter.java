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
package org.apache.dubbo.rpc.filter.tps;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_RATE_KEY;
import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_INTERVAL_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_TPS_LIMIT_INTERVAL;

/**
 * DefaultTPSLimiter is a default implementation for tps filter. It is an in memory based implementation for storing
 * tps information. It internally use
 * 限流算法使用的是固定时间窗口
 * @see org.apache.dubbo.rpc.filter.TpsLimitFilter
 */
public class DefaultTPSLimiter implements TPSLimiter {

    //TPSLimiter的限流对象是以服务分组，版本，接口为维度的服务接口级别的限流
    //缓存每个接口的当前令牌桶内的令牌。key:serviceKey(interface:group:version)  value：令牌桶
    private final ConcurrentMap<String, StatItem> stats = new ConcurrentHashMap<String, StatItem>();

    @Override
    public boolean isAllowable(URL url, Invocation invocation) {
        //dubbo://192.168.1.101:20880/servicePathPrefix/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.1.101&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&metadata-type=remote&methods=sayHello,sayHelloAsync,wrapperReturnVoid,testBigDecimal&pid=40630&qos.port=22228&release=&side=provider&timestamp=1625209951357&tps=10&tps.interval=1000
        //只允许在interval时间段内RPC请求可以通过rate个

        //获取配置<dubbo:parameter key="tps" value="rate">
        int rate = url.getParameter(TPS_LIMIT_RATE_KEY, -1);
        //获取配置<dubbo:parameter key="tps.interval" value="interval">
        long interval = url.getParameter(TPS_LIMIT_INTERVAL_KEY, DEFAULT_TPS_LIMIT_INTERVAL);
        //serviceKey：{group}/{interfaceName}:{version}
        String serviceKey = url.getServiceKey();
        if (rate > 0) {
            //获取并初始化服务接口对应的令牌桶
            StatItem statItem = stats.get(serviceKey);
            if (statItem == null) {
                stats.putIfAbsent(serviceKey, new StatItem(serviceKey, rate, interval));
                statItem = stats.get(serviceKey);
            } else {
                //rate or interval has changed, rebuild
                if (statItem.getRate() != rate || statItem.getInterval() != interval) {
                    stats.put(serviceKey, new StatItem(serviceKey, rate, interval));
                    statItem = stats.get(serviceKey);
                }
            }
            //本次RPC请求尝试获取令牌
            return statItem.isAllowable();
        } else {
            //如果配置的令牌个数rate <= 0 那么就不对其进行限流
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {
                stats.remove(serviceKey);
            }
        }

        return true;
    }

}
