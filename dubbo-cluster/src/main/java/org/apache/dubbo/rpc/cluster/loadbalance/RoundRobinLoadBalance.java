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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    private static final int RECYCLE_PERIOD = 60000;

    protected static class WeightedRoundRobin {
        private int weight;
        private AtomicLong current = new AtomicLong(0);
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

        public void sel(int total) {
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }
    // methodKey -> (providerURL , WeightedRoundRobin)
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     *
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }
    /**
     * 2 6 3 7 (weight) ，total 18
     * round 1 : 2 6 3 7 (WeightedRoundRobin) , 选中 7 ， WeightedRoundRobin 变化：2  6  3  -11
     * round 2 ：4 12 6 -4 (WeightedRoundRobin)，选中 6 ， WeightedRoundRobin 变化：4 -6  6  -4
     * round 3 ：8 0 9 3 (WeightedRoundRobin)，选中 3 ， WeightedRoundRobin 变化：  8  0 -9   3
     * round 4 ：10 6 -6 10 (WeightedRoundRobin)，选中 2 ，WeightedRoundRobin 变化：-8 6 -6 10
     * round 5 ：-6 12 -3 17 (WeightedRoundRobin)，选中 7 ，WeightedRoundRobin 变化：-6 12 -3 -1
     * round 6 ：-4 18 0 6 (WeightedRoundRobin)，选中 6 ，WeightedRoundRobin 变化：-4 0 0 6
     * round 7 ：-2 6 3 13 (WeightedRoundRobin)，选中 7 ，WeightedRoundRobin 变化：-2 6 3 -5
     * round 8 ：0 12 6 2 (WeightedRoundRobin)，选中 6 ，WeightedRoundRobin 变化：0 -6 6 2
     * round 9 ：2 0 9 9 (WeightedRoundRobin)，选中 3 ，WeightedRoundRobin 变化：2 0 -9 9
     * round10 ：4 6 -6 16 (WeightedRoundRobin)，选中 7 ，WeightedRoundRobin 变化：4 6 -6 -2
     *
     * */

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        int totalWeight = 0;
        long maxCurrent = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        for (Invoker<T> invoker : invokers) {
            String identifyString = invoker.getUrl().toIdentityString();
            // 获取每个 provider 的权重
            int weight = getWeight(invoker, invocation);
            // 建立具体 providerUrl 到 权重的映射
            WeightedRoundRobin weightedRoundRobin = map.computeIfAbsent(identifyString, k -> {
                WeightedRoundRobin wrr = new WeightedRoundRobin();
                wrr.setWeight(weight);
                return wrr;
            });

            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            long cur = weightedRoundRobin.increaseCurrent();
            weightedRoundRobin.setLastUpdate(now);
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight;
        }
        // providerURL 会发生变化
        if (invokers.size() != map.size()) {
            // 如果 weightedRoundRobin 的最后更新时间已经超过了 RECYCLE_PERIOD 就删除
            // 考虑到 providerURL 会发生变化，一旦变化之后，旧的 providerURL 对应的 weightedRoundRobin 就要被清理
            map.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }
        if (selectedInvoker != null) {
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
