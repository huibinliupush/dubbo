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
import org.apache.dubbo.rpc.support.RpcUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ConsistentHashLoadBalance
 * 内存消耗太大了
 *
 * 每一个 method 对应一个 ConsistentHashSelector
 * ConsistentHashSelector 中 每一个 invoker 对应 HASH_NODES 个节点
 *
 * HASH_NODES 默认为 100 ，假设有 10 个 invokers 那么一个 method 就对应 10 * 100 个
 * 如果一个接口 service 有 10 个方法，那么这一个 service 对应 10 * 10 * 100
 * 而一个应用往往会有多个 service
 *
 * 所以对于 consistenthash 负载均衡算法，只能针对特定的 method 进行配置
 *
 * 根据调用参数路由到固定的 invoker, 相同的调用参数一定路由到相同的 invoker 中
 *
 * 一致性 Hash 负载均衡可以让参数相同的请求每次都路由到相同的服务节点上
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    /**
     * Hash nodes name
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * Hash arguments name
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";
    // 方法名与 ConsistentHashSelector 的映射
    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String methodName = RpcUtils.getMethodName(invocation);
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        // using the hashcode of list to compute the hash only pay attention to the elements in the list
        int invokersHashCode = invokers.hashCode();
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        // invokers 的信息变化了，就要重新创建 ConsistentHashSelector（里边包装了 invokers）
        if (selector == null || selector.identityHashCode != invokersHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, invokersHashCode));
            // 每个 method 对应一个 ConsistentHashSelector
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        return selector.select(invocation);
    }
    // 每一个方法对应一个 ConsistentHashSelector
    private static final class ConsistentHashSelector<T> {

        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        private final int replicaNumber;
        // invokers 的 hashcode
        private final int identityHashCode;
        // 要对方法中的那几个参数取 hash 值
        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            // 默认 100 个节点
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 每一个 invoker 在 virtualInvokers 中有 replicaNumber 个副本
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // address0 到 address39
                    byte[] digest = md5(address + i);
                    for (int h = 0; h < 4; h++) {
                        // 0 - 3
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            // 通过方法参数取 hash 值，具体取哪些参数由 argumentIndex 决定，可通过 hash.arguments 进行配置多个逗号分隔
            String key = toKey(invocation.getArguments());
            byte[] digest = md5(key);
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            // 从 virtualInvokers 中获取 key 等于 hash 的或者 key 比 hash 大的（least greater）
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();
        }
        // 对 MD5 进行加盐取 hash
        // digest 为 16 个字节 提取某 4 个字节生成一个 32 位整数
        // number = 0 提到 0 到 3 个字节
        // number = 1 提到 4 到 7 个字节
        // number = 0 提到 8 到 11 个字节
        // number = 0 提到 12 到 16 个字节
        // digest是一个字节数组，number参数可能用来选择不同的四字节块。
        // 比如当number=0时，取digest[0]到digest[3]，当number=1时，取digest[4]到digest[7]，
        // 依此类推。每个字节通过位操作组合成一个32位的值。

        // 类型提升需要转换为无符号
        // byte 转换为 int（转换后前面 24 位全部是符号位，负数为1整数为0） , 需要 & 0xFF 只保留低 8 位 转换为无符号整数
        // int 转换为 long , 需要 & 0xFFFFFFFFL 只保留低 32 位 转换为无符号整数

        // 在Java中，byte类型是8位有符号整数（取值范围为-128 ~ 127），
        // 而其他语言（如C/C++）的byte可能是无符号的。当将byte转换为更大的整数类型（如int或long）时，
        // Java会进行符号扩展（Sign Extension），可能导致数值错误。& 0xFF的核心作用就是屏蔽符号位，
        // 强制转换为无符号值
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}
