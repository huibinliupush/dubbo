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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_FILTER_KEY;

/**
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {
    //自适应Protocol扩展
    private final Protocol protocol;
    //spi自动注入Protocol扩展
    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    /**
     *   default表示所有标注@Activate注解的扩展类实现 包括dubbo内置和自定义扩展
     *   default 集合中的扩展点顺序是按照 order 排序
     *   而配置中的扩展点顺序是严格按照配置顺序
     * */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        //provider端：这里指registry层传递进来的初始invoker:InvokerDelegate
        Invoker<T> last = invoker;
        //根据invoker中的url中参数service.filter(provider端) / reference.filter(consumer端)配置的自定义filter和框架默认的filters 加载所有配置的filter过滤器
        //provider端自定义过滤器可以在配置<dubbo:service filter=""/>或者<dubbo:provider filter="" />中进行配置，多个用逗号分隔，default代表默认过滤器集合
        //consumer端自定义过滤器可以在配置<dubbo:reference filter=""/>或者<dubbo:consumer filter="" />中进行配置，多个用逗号分隔，default代表默认过滤器集合
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);

        //按照定义的filter顺序构建filter链，优先级高的filter在链表的前面，链表的末端为registry传入的invokerDelegate
        //filter顺序可以通过@Activate(order=优先级)定义，数值越小优先级越高，也可以在xml等配置中的filter字段配置顺序。
        if (!filters.isEmpty()) {
            //从链表末端开始向前构建filter链
            for (int i = filters.size() - 1; i >= 0; i--) {
                //构建filter链当前节点
                final Filter filter = filters.get(i);
                //链表当前节点的下一个节点
                //next暂存的是优先级低的filter，构建在链表节点的后边
                final Invoker<T> next = last;
                //链表的节点类型为Invoker，这里用invoker来包裹装饰Filter,因为Invoker是dubbo框架中的执行模型，代表一个可执行体
                //构建当前filter节点
                last = new Invoker<T>() {

                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        Result asyncResult;
                        try {
                            //调用当前filter，在filter中决定是否调用下一级filter（决定是否让请求继续沿着filter链向下传递还是直接中断返回）
                            asyncResult = filter.invoke(next, invocation);
                        } catch (Exception e) { // filter 的自身逻辑执行异常
                            //回调filter监听器的onError方法
                            if (filter instanceof ListenableFilter) {
                                ListenableFilter listenableFilter = ((ListenableFilter) filter);
                                try {
                                    // 每次创建新的 Filter.Listener
                                    // see : org.apache.dubbo.demo.provider.filter.YourProjectFilter
                                    Filter.Listener listener = listenableFilter.listener(invocation);
                                    if (listener != null) {
                                        listener.onError(e, invoker, invocation);
                                    }
                                } finally {
                                    listenableFilter.removeListener(invocation);
                                }
                            } else if (filter instanceof Filter.Listener) {
                                // 本 filter 执行异常或者整个 Filter 链中有异常都会回调
                                Filter.Listener listener = (Filter.Listener) filter;
                                listener.onError(e, invoker, invocation);
                            }
                            // 这里可以看出 filter 链中的异常是可以传播的
                            throw e;
                        } finally {

                        }
                        //后面的Filter执行异常
                        //如果请求正常返回结果则回调filter监听器的onResponse方法，
                        //如果请求执行过程中发生异常则回调filter监听器的onError方法，（整个 Filter 链中有异常都会回调）

                        // 在同一个 RpcContext 中调用该回调函数 (r, t) ->{}
                        // see : org.apache.dubbo.rpc.AsyncRpcResult.whenCompleteWithContext
                        return asyncResult.whenCompleteWithContext((r, t) -> {
                            if (filter instanceof ListenableFilter) {
                                ListenableFilter listenableFilter = ((ListenableFilter) filter);
                                Filter.Listener listener = listenableFilter.listener(invocation);
                                try {
                                    if (listener != null) {
                                        //当前Filter正常返回，但需要检查Filter责任链后边的节点执行是否发生异常
                                        //异常会封装在Result模型中
                                        if (t == null) {
                                            listener.onResponse(r, invoker, invocation);
                                        } else {
                                            listener.onError(t, invoker, invocation);
                                        }
                                    }
                                } finally {
                                    listenableFilter.removeListener(invocation);
                                }
                            } else if (filter instanceof Filter.Listener) {
                                Filter.Listener listener = (Filter.Listener) filter;
                                if (t == null) {
                                    listener.onResponse(r, invoker, invocation);
                                } else {
                                    listener.onError(t, invoker, invocation);
                                }
                            }
                        });
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }
        //返回filter链头部节点
        return last;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        //如果invoke中URL的协议头为registry或者service-discovery-registry则直接调用下一个ProtocolWrapper
        if (UrlUtils.isRegistry(invoker.getUrl())) {
            //调用Protocol自适应扩展
            return protocol.export(invoker);
        }
        //构建拦截器Filter链，将构建后的filer链的头部invoker传入Protocol自适应扩展export方法中执行对应扩展的服务暴露逻辑
        return protocol.export(buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
        // consumerContextFilter , FutureFilter , monitorFilter (ActiveLimitFilter)
        return buildInvokerChain(protocol.refer(type, url), REFERENCE_FILTER_KEY, CommonConstants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

}
