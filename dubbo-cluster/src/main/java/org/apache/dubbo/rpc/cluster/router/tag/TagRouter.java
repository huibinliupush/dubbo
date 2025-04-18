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
package org.apache.dubbo.rpc.cluster.router.tag;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRouterRule;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRuleParser;

import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;

/**
 * TagRouter, "application.tag-router"
 *
 * 标签路由是一套严格隔离的流量体系，对于同一个应用而言，一旦打了标签则这部分地址子集就被隔离出来，
 * 只有带有对应标签的请求流量可以访问这个地址子集，这部分地址不再接收没有标签或者具有不同标签的流量。
 *
 * 标签路由的作用域是提供者应用，消费者应用无需配置标签路由规则
 *
 * 一个提供者应用内的所有服务只能有一条分组规则，不会有服务 A 使用一条路由规则、服务 B 使用另一条路由规则的情况出现。
 */
public class TagRouter extends AbstractRouter implements ConfigurationListener {
    public static final String NAME = "TAG_ROUTER";
    private static final int TAG_ROUTER_DEFAULT_PRIORITY = 100;
    private static final Logger logger = LoggerFactory.getLogger(TagRouter.class);
    private static final String RULE_SUFFIX = ".tag-router";
    // dynamic tag 路由规则，由配置中心动态指定
    // static tag 是 providerUrl 中指定的 tag, consumer tag 静态匹配 provider tag (不需要配置中心，本来就有)
    private TagRouterRule tagRouterRule;
    // tag 路由对应的 provider 端的 remote.application
    // 在第一次拉取 invokers 的时候通过 notify 方法设置
    private String application;

    public TagRouter(URL url) {
        // consumerURL
        super(url);
        this.priority = TAG_ROUTER_DEFAULT_PRIORITY;
    }

    // config center 中的 provider.tag-router 文件发生变化就会通知到这里
    // 注意 tag 是针对 provider 进行的，所以 tag 路由是属于 provider 端的
    @Override
    public synchronized void process(ConfigChangedEvent event) {
        if (logger.isDebugEnabled()) {
            logger.debug("Notification of tag rule, change type is: " + event.getChangeType() + ", raw rule is:\n " +
                    event.getContent());
        }

        try {
            // 不用标签的时候，把标签规则删除就可以了（只适用于动态 tag）
            // 这里不会影响 provider 的静态 tag
            if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
                this.tagRouterRule = null;
            } else {
                this.tagRouterRule = TagRuleParser.parse(event.getContent());
            }
        } catch (Exception e) {
            logger.error("Failed to parse the raw tag router rule and it will not take effect, please check if the " +
                    "rule matches with the template, the raw rule is:\n ", e);
        }
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }

        // since the rule can be changed by config center, we should copy one to use.
        // 没有配置动态 tag 路由（配置中心）的情况，走静态 tag 路由
        final TagRouterRule tagRouterRuleCopy = tagRouterRule;
        if (tagRouterRuleCopy == null || !tagRouterRuleCopy.isValid() || !tagRouterRuleCopy.isEnabled()) {
            // 静态匹配 providerUrl 中的 dubbo.tag，如果 consumer 未设置 tag , 则原样返回远没有被 tag 的 invokers
            // 只要一个 provider 被 tag 了。那么正常的流量就不能访问他了，只能特定的 tag 流量才可以访问
            return filterUsingStaticTag(invokers, url, invocation);
        }
        // 动态配置了路由 tag 的情况：动态 tag 路由与静态 tag 路由同时存在，有匹配动态 tag , 没有在匹配静态 tag
        List<Invoker<T>> result = invokers;
        // 先从 invocation Attachment 中获取 dubbo.tag 值，如果不存在则从 consumerUrl 中获取 dubbo.tag
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);

        // if we are requesting for a Provider with a specific tag
        // consumer 指定了 tag
        if (StringUtils.isNotEmpty(tag)) {
            // 获取 consumer tag 对应的 provider addresses
            List<String> addresses = tagRouterRuleCopy.getTagnameToAddresses().get(tag);
            // filter by dynamic tag group first
            if (CollectionUtils.isNotEmpty(addresses)) {
                // 获取和 tag address 匹配的 provider 地址
                result = filterInvoker(invokers, invoker -> addressMatches(invoker.getUrl(), addresses));
                // if result is not null OR it's null but force=true, return result directly
                if (CollectionUtils.isNotEmpty(result) || tagRouterRuleCopy.isForce()) {
                    // 如果 force 强制执行，返回空的 invokers
                    return result;
                }
            } else {
                // dynamic tag group doesn't have any item about the requested app OR it's null after filtered by
                // dynamic tag group but force=false. check static tag

                // dynamic tag 对应的 address 为空，那么就走 static tag
                result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            }
            // If there's no tagged providers that can match the current tagged request. force.tag is set by default
            // to false, which means it will invoke any providers without a tag unless it's explicitly disallowed.

            // dynamic 和 static 都没有匹配到，如果 force 强制执行，返回空的 invokers
            if (CollectionUtils.isNotEmpty(result) || isForceUseTag(invocation)) {
                return result;
            }
            // FAILOVER: return all Providers without any tags.
            else {
                // 返回所有没有 tag 的 invokers
                // 携带 Tag 的请求可以降级访问到无 Tag 的 Provider，但不携带 Tag 的请求永远无法访问到带有 Tag 的 Provider

                // 过滤出没有 dynamic tag 的 invokers
                List<Invoker<T>> tmp = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(),
                        tagRouterRuleCopy.getAddresses())); // 过滤出没有 dynamic tag 的 invokers

                // 过滤出没有 static tag 的 invokers
                return filterInvoker(tmp, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            // List<String> addresses = tagRouterRule.filter(providerApp);
            // return all addresses in dynamic tag group.

            // consumer 没有指定了 tag ，那么流量就只能走没有 tag 的 invokers
            // provider 一旦被 tag , 就从正常的流量池中隔离了，只有特定 tag 的流量才能访问到对应 tag 的 providers
            List<String> addresses = tagRouterRuleCopy.getAddresses();
            if (CollectionUtils.isNotEmpty(addresses)) {
                result = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(), addresses));
                // 1. all addresses are in dynamic tag group, return empty list.
                if (CollectionUtils.isEmpty(result)) {
                    return result;
                }
                // 2. if there are some addresses that are not in any dynamic tag group, continue to filter using the
                // static tag group.
            }
            // 动态 tag 过滤完之后，就要进行静态 tag 的过滤
            return filterInvoker(result, invoker -> {
                String localTag = invoker.getUrl().getParameter(TAG_KEY);
                return StringUtils.isEmpty(localTag) || !tagRouterRuleCopy.getTagNames().contains(localTag);
            });
        }
    }

    /**
     * If there's no dynamic tag rule being set, use static tag in URL.
     * <p>
     * A typical scenario is a Consumer using version 2.7.x calls Providers using version 2.6.x or lower,
     * the Consumer should always respect the tag in provider URL regardless of whether a dynamic tag rule has been set to it or not.
     * <p>
     * TODO, to guarantee consistent behavior of interoperability between 2.6- and 2.7+, this method should has the same logic with the TagRouter in 2.6.x.
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> filterUsingStaticTag(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        List<Invoker<T>> result = invokers;
        // Dynamic param
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            // 过滤出与 consumer 指定的 tag 相同的 provider
            result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            if (CollectionUtils.isEmpty(result) && !isForceUseTag(invocation)) {
                // 如果不是强制使用 tag , 那么就返回所有没有 tag 的 invokers
                // 携带 Tag 的请求可以降级访问到无 Tag 的 Provider，但不携带 Tag 的请求永远无法访问到带有 Tag 的 Provider
                result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            // 如果 consumer 没有设置 tag , 那么就返回所有没有 tag invokers
            result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        }
        // 只要一个 provider 被 tag 了。那么正常的流量就不能访问他了，只能特定的 tag 流量才可以访问
        return result;
    }

    @Override
    public boolean isRuntime() {
        return tagRouterRule != null && tagRouterRule.isRuntime();
    }

    @Override
    public boolean isForce() {
        // FIXME
        return tagRouterRule != null && tagRouterRule.isForce();
    }

    private boolean isForceUseTag(Invocation invocation) {
        return Boolean.valueOf(invocation.getAttachment(FORCE_USE_TAG, url.getParameter(FORCE_USE_TAG, "false")));
    }

    private <T> List<Invoker<T>> filterInvoker(List<Invoker<T>> invokers, Predicate<Invoker<T>> predicate) {
        return invokers.stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    private boolean addressMatches(URL url, List<String> addresses) {
        return addresses != null && checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean addressNotMatches(URL url, List<String> addresses) {
        return addresses == null || !checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean checkAddressMatch(List<String> addresses, String host, int port) {
        for (String address : addresses) {
            try {
                if (NetUtils.matchIpExpression(address, host, port)) {
                    return true;
                }
                if ((ANYHOST_VALUE + ":" + port).equals(address)) {
                    return true;
                }
            } catch (UnknownHostException e) {
                logger.error("The format of ip address is invalid in tag route. Address :" + address, e);
            } catch (Exception e) {
                logger.error("The format of ip address is invalid in tag route. Address :" + address, e);
            }
        }
        return false;
    }

    public void setApplication(String app) {
        this.application = app;
    }

    // see : org.apache.dubbo.rpc.cluster.RouterChain.setInvokers
    // RegistryDirectory 初始拉取所有 invokers , 以及后续 invokers 发生变化都会通过这里
    @Override
    public <T> void notify(List<Invoker<T>> invokers) {
        if (CollectionUtils.isEmpty(invokers)) {
            return;
        }

        Invoker<T> invoker = invokers.get(0);
        URL url = invoker.getUrl();
        // 获取远程 providerURL 的 remote.application
        String providerApplication = url.getParameter(CommonConstants.REMOTE_APPLICATION_KEY);

        if (StringUtils.isEmpty(providerApplication)) {
            logger.error("TagRouter must getConfig from or subscribe to a specific application, but the application " +
                    "in this TagRouter is not specified.");
            return;
        }

        synchronized (this) {
            // 是否是第一次设置，只设置一次
            if (!providerApplication.equals(application)) {
                if (!StringUtils.isEmpty(application)) {
                    ruleRepository.removeListener(application + RULE_SUFFIX, this);
                }
                // tag 是针对 provider 的，所以这里要监听 provider 的相关配置文件（config center）*.tag-router
                String key = providerApplication + RULE_SUFFIX;
                ruleRepository.addListener(key, this);
                application = providerApplication;
                // 初始拉取 provider 端的 tag 路由
                String rawRule = ruleRepository.getRule(key, DynamicConfiguration.DEFAULT_GROUP);
                if (StringUtils.isNotEmpty(rawRule)) {
                    this.process(new ConfigChangedEvent(key, DynamicConfiguration.DEFAULT_GROUP, rawRule));
                }
            }
        }
    }

}
