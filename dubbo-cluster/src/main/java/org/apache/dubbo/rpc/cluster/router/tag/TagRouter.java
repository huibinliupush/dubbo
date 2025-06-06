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
 * TagRouter, "providerApplication.tag-router"
 *
 * 标签路由是一套严格隔离的流量体系，对于同一个应用而言，一旦打了标签则这部分地址子集就被隔离出来，
 * 只有带有对应标签的请求流量可以访问这个地址子集，这部分地址不再接收没有标签或者具有不同标签的流量。
 *
 * 标签路由的作用域是提供者应用，消费者应用无需配置标签路由规则,标签主要是指对 Provider 端应用实例的分组
 *
 * 一个提供者应用内的所有服务只能有一条分组规则，不会有服务 A 使用一条路由规则、服务 B 使用另一条路由规则的情况出现。
 *
 * 因为路由的逻辑是在 consuemr 端，所以为 provider 打 tag 也是在 consumer 端实现
 *
 * 可以为不同的 provider 实例打不同的 tag ,  一个 provider 实例可以被打上不同的 tags
 *
 * 只要一个 provider 被 tag 了。那么正常的流量就不能访问他了，只能特定的 tag 流量才可以访问
 * 但是带有 tag 的流量既可以流向对应 tag 的 provider , 也可以降级到无 tag 的 provider (dubbo.force.tag=false 默认)
 *
 * https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/traffic-management/
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
    private String application; // providerApplicationName

    // 由类中的 notify 方法触发动态路由规则的订阅
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

    /**
     *  给 provider 端打 tag 的两种方式：
     *  1：<dubbo:provider tag="gray"/> or <dubbo:service tag="gray"/>，一个提供者应用内的所有服务只能有一条分组规则，
     *  不会有服务 A 使用一条路由规则、服务 B 使用另一条路由规则的情况出现。所以一般都用 <dubbo:provider tag="gray"/>
     *
     *  2. 在 provider 实例所在的机器上设置系统变量 or 环境变量 DUBBO_LABELS = "region=hangzhou; env=gray" 自动给实例打标
     *  这样 providerURl 中就有了两个参数：region=hangzhou&env=gray
     *
     *  具体参考 org.apache.dubbo.rpc.cluster.router.tag.model.Tag 中的注释
     *
     *  给 consumer 请求流量打 tag 的两种方式：
     *
     *  1： <dubbo:consumer tag="gray"/> or <dubbo:reference tag="gray"/> 这样一来 consumerUrl 中自动带有 tag
     *  2:  RpcContext.getContext().setAttachment(Constants.TAG_KEY, "gray") 这种优先级最高，但每次 rpc 调用完需要重新设置
     *
     *  tagRouter 会优先从 RpcContext 中获取 dubbo.tag ，没有的话再从 consumerUrl 中获取 dubbo.tag
     *
     *  以上给 provider 实例打好的 tag 只是表示，在 providerUrl 中已经有了 tag 的元数据，此时 consumer 并不能识别
     *  要想让 consumer 识别，就需要在动态 tag 路由规则中定义出来。定义有两种方式：
     *
     *  1:
     *  tags:
     *   - name: tag1
     *     addresses: [ip1, ip2]
     *
     *  根据 provider 实例地址为 provider 实例打上 tag , 在 tagRouter 中会根据实例地址过滤出对应 tag 的 invokers
     *
     *  2: Dubbo3
     *  tags:
     *    - name: gray
     *       match:
     *         - key: env
     *           value:
     *             exact: gray
     *
     *
     *  tagRouter 会根据 invoker 中的 providerUrl ，过滤出包含参数 env=gray 的 invokers
     *
     *  优化点：
     *  consumer 给请求流量打 tag 的方式有很多不足，如果采用 <dubbo:consumer tag="gray"/> 的方式就很死板
     *  由该 consumer 请求的流量都会带上固定的 tag , 当然我们可以根据 consumer 的动态配置文件，动态修改 tag
     *
     *  RpcContext.getContext().setAttachment(Constants.TAG_KEY, "gray") 的方式对业务代码的侵入性非常大
     *  每当发起一次 RPC , 就需要重新设置 RpcContext ， 业务代码中就会充斥大量的设置 RpcContext 代码
     *
     *  比如我们可以根据请求参数，请求参数中包含 tag 属性，然后在 reference proxy 中，如果发现请求参数中带有 tag 属性
     *  org.apache.dubbo.rpc.proxy.InvokerInvocationHandler#invoke(java.lang.Object, java.lang.reflect.Method, java.lang.Object[])
     *  自动调用 RpcInvocation.setAttachment(TAG_KEY,tag)
     *
     *  具体的实现方式可参考： org.apache.dubbo.common.extension.AdaptiveClassCodeGenerator#generateMethodContent(java.lang.reflect.Method)
     *  核心思想就是查看 method 所有的参数，是否包含 getTag 方法，如果有的话就自动设置 RpcInvocation.setAttachment(TAG_KEY,tag)
     *  如果没有则不设置 （这种方式虽然方便，但性能较差，感觉还是 RPC 手动设置一下好一点）
     *
     *  这样我们就可以根据业务的请求参数，自动打上 tag , 比如，哪些用户需要走 gray ? 前端设置好 tag -> 后端的实体类中也有 tag
     *  直接调用 RPC 请求，在 proxy 中就可以根据实体类中的 tag 为流量打标了
     *
     *
     *  依据这里的 tagRouter 我们就可以实现蓝绿发布、灰度发布
     *
     *  灰度发布：针对那些用户灰度 ？ 针对那些地区灰度 ？ 业务代码进行判断，然后符合灰度条件的调用 RpcContext.getContext().setAttachment(Constants.TAG_KEY, "gray")
     *  也可以按照不同 tag 实现按照权重调配流量，但这个需要在业务中做，选中一个 tag ,然后后续调用 RpcContext.getContext().setAttachment(Constants.TAG_KEY, "gray")
     *
     *  蓝绿发布：<dubbo:provider tag="blue or green"/> ， <dubbo:consumer tag="blue or green"/>
     *  为所有 provider 实例，consumer 实例打上蓝，绿  tag , 流量慢慢向蓝组或者绿组倾斜
     *
     *  标签路由规则是一个非此即彼的流量隔离方案，也就是匹配标签的请求会 100% 转发到有相同标签的实例，
     *  没有匹配标签的请求会 100% 转发到其余未匹配的实例。如果您需要按比例的流量调度方案，请参考示例 基于权重的按比例流量路由。
     *  https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/traffic-management/weight/
     *
     *  基于权重的话，就不能在 consumer 端的 tag 路由规则中为 provider 实例打 tag 了，因为 tag 路由是非此即彼的
     *  只能在 provider 端通过 DUBBO_LABELS 为实例打 tag 这里需要依靠 service 的动态配置文件，如下所示，调整不同分组 provider 实例的权重
     *
     * configVersion: v3.0
     * scope: service
     * key: org.apache.dubbo.samples.OrderService
     * configs:
     *   - side: provider
     *     match:
     *       param:
     *         - key: orderVersion
     *           value:
     *             exact: v2
     *     parameters:
     *       weight: 25
     *
     * 所有的 OrderService provider 实例分为两组，v1 组所有实例的权重为 100 ， v2 组所有实例的权重为 25
     * 这样就可以实现 v1 组与 v2 组之间流量的比例为 4:1
     *
     * 动态配置文件生成 override://ip:port/interface? weight=25&MATCH_CONDITION=orderVersion,v2
     * providerUrl 主要匹配到 match 条件，也就是说 url 中含有 orderVersion=v2 的参数，就会被 override
     *
     * OrderService=v2 可以通过 系统变量 or 环境变量 DUBBO_LABELS 进行设置
     *
     * 获取 DUBBO_LABELS ，并填充到 url 中 ： org.apache.dubbo.config.ApplicationConfig#refresh()
     * org.apache.dubbo.common.infra.support.EnvironmentAdapter#getExtraAttributes(java.util.Map)
     *
     *
     *  如何实现蓝绿发布？ https://time.geekbang.org/column/article/537518 ， 三个维度：单个应用，链路，环境
     *
     *  针对单个服务的蓝绿发布，比如单个风控服务实现蓝绿，其他服务不变
     *  online -> risk蓝 -> data
     *  online -> risk绿 -> data
     *  现在要求 online 调用 risk 服务的时候按照动态权重调用蓝绿服务
     *  1. 首先通过 DUBBO_LABELS 为不同的 risk 服务实例打标，riskversion = bule , riskversion = green
     *  2. 在 risk 动态配置文件中为 bule ， green 实例分组设置权重 weight
     *  3. online 端不为 risk 设置 tag 路由，risk 的 bule ， green 两组实例在 online 端都可以获取
     *  4. online 在调用的时候经过路由返回全量 risk 实例（蓝绿），在负载均衡阶段，通过蓝绿权重调用对应实例
     *
     *  针对整个调用链路的蓝绿发布
     *  online -> risk蓝 -> data蓝
     *  online -> risk绿 -> data绿
     *  首先我们要保证的是 online 对于 risk 服务的调用要按照权重在蓝绿两组 risk 实例之间调配，要保证这个，还是重复上述 1，2，3，4 步骤
     *  其次我们要保证流量一经染色，也就是说一旦进入蓝绿环境，整个链路必须统一，也就是说 risk蓝 只能调用链路中的 data蓝 后面的链路也都应该是蓝色的
     *  这就是标签路由的场景（非此即彼）。
     *  1. 保证 risk 服务发出的流量染色 ， -Dubbo.consumer.tag=BLUE or -Dubbo.consumer.tag=GREEN
     *  2. 为后面链路的所有服务打上 tag , 同时也需要保证后面服务发出的流量被染色
     *  -Dubbo.provider.tag=BLUE or -Dubbo.provider.tag=GREEN，-Dubbo.consumer.tag=BLUE or -Dubbo.consumer.tag=GREEN
     *
     *  但这样会有一个问题，就是 online 第一次调用 risk 是蓝色，下面继续调用 risk 可能就是绿色了（同一个请求处理可能会调用多次 risk）
     *  或者是 online 调用 risk 是蓝色，接着调用 data 就是绿色了 ，同一次请求只能在一种环境下进行，所以我们还需要环境隔离的方案
     *
     *  针对所有应用的全局蓝绿（整个环境隔离）
     *
     *  首先需要为环境中的所有应用实例打上 tag -Dubbo.provider.tag= , -Dubbo.consumer.tag
     *  -Dubbo.provider.tag= 的目的是给 provider 实例打 tag, -Dubbo.consumer.tag 的目的是从该应用发出的流量全部自动带上 tag 实现环境内部流量的隔离
     *  这一步做完，凡是在 tag 环境内的所有应用就全部被隔离起来了，环境内部的流量全部自动带上 tag —— online蓝 -> risk蓝 -> data蓝
     *
     *  其次就是需要考虑在隔离环境之外，如果将请求发送到对应环境中，比如网关需要为流量手动染色（根据一定的条件算法），然后将请求转发到对应环境的 online 服务中
     *  网关划分出两组 online 实例 : 蓝色[ip1,ip2,ip3] , 绿色[ip4,ip5,ip6]
     *  流量进来，网关需要按照一定的算法，比如根据权重选取一个颜色 —— 蓝色，然后从 [ip1,ip2,ip3] 选取一个 online 实例进行请求，后续过程就全部在蓝色环境中
     *
     *  但如果隔离环境不是从 online 开始，而是从 risk 开始 —— online -> risk蓝 -> data蓝，那么就需要在 online 应用中为流量染色
     *  总之复杂染色的逻辑需要再隔离环境的前置服务中进行，online 服务通过染色算法选取一个颜色 ——绿色，往后在 online 实例中的所有请求都需要带上绿色，逻辑如下：
     *  1. online 为本次流量染色
     *  2. online 请求 risk 需要带上 tag,online 请求 data 也需要带上 tag,总之 oneline 请求一切 RPC 服务都需要带上 tag
     *  3. online 内部向后续服务发出的一切请求都需要带上这个固定下来的 tag
     *
     *  但这样一来，在 online 服务中就会出现大量的 RpcContext.getContext().setAttachment(Constants.TAG_KEY, "gray")
     *
     *  我们可以仿照 RpcContext 设计一个 TagContext , 里面用 ThreadLocal 存放 tag ,流量一进入 online , 选取 tag 后就设置到 TagContext 中
     *  然后在 online 中添加一个 ClusterFilter, 在 ClusterFilter 中根据 TagContext 中的 tag 设置到 RpcContext.getContext().setAttachment(Constants.TAG_KEY, "gray") 中
     *  实现用户无感知，但注意异步调用的时候会丢失 TagContext，可参考 RpcContext 中的设计（startAsync），或者使用 TransmittableThreadLocal
     *
     *  或者在 RpcContext 中在添加一个全局 tag 标识（ThreadLocal）不需要额外设计 TagContext
     *
     *  可以非常灵活的实现流量隔离能力。可以单独为集群中的某一个或多个应用划分隔离环境，也可以为整个微服务集群划分隔离环境；
     *  可以在部署态静态的标记隔离环境，也可以在运行态通过规则动态的隔离出一部分机器环境。
     *
     *  https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/traffic-management/isolation/
     *
     *  标签路由是一套严格隔离的流量体系，对于同一个应用而言，一旦打了标签则这部分地址子集就被隔离出来，只有带有对应标签的请求流量可以访问这个地址子集，
     *  这部分地址不再接收没有标签或者具有不同标签的流量。举个例子，如果我们将一个应用进行打标，打标后划分为 tag-a、tag-b、无 tag 三个地址子集，
     *  则访问这个应用的流量，要么路由到 tag-a (当请求上下文 dubbo.tag=tag-a)，要么路由到 tag-b (dubbo.tag=tag-b)，
     *  或者路由到无 tag 的地址子集 (dubbo.tag 未设置)，不会出现混调的情况。
     *
     * */
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
            // 但是带有 tag 的流量既可以流向对应 tag 的 provider , 也可以降级到无 tag 的 provider (dubbo.force.tag=false 默认)
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
            // Force 以 invocation ， consuemrUrl 中的 dubbo.force.tag 优先
            // 其次在按照 tagRouterRule 中的 force
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
        // 优先提取 consumer 端 invocation 中设置的 tag ,如果没有则从 consumerUrl 中提取
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            // 过滤出与 consumer 指定的 tag 相同的 provider
            // invoker 中的 providerUrl 会被 consumerUrl 以及配置中心的动态配置覆盖
            // see : org.apache.dubbo.registry.integration.RegistryDirectory.mergeUrl
            result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            // 从 invocation 或者 url 中获取是否强制执行 tag —— dubbo.force.tag 默认 false
            // force = false ， 如果没有相关 tag 的 invoker , 那么就可以降级到不带 tag 的 invoker 中
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
        // 但是带有 tag 的流量既可以流向对应 tag 的 provider , 也可以降级到无 tag 的 provider (dubbo.force.tag=false 默认)
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



/**
 *     RegistryDirectory 初始拉取所有 invokers , 以及后续 invokers 发生变化都会通过这里
 *     consumer 第一次向注册中心发起订阅的时候，会全量拉取 providers，转换为 invokers 会调用到这里
 *     当注册中心中的 providers 发生变化的时候，consumer 会重新拉取 provider 重新生成 invokers , 也会调用到这里
 *     RouterChanin 中的 invokers 也是会动态更新的
 *
 *     触发 router chain 中的 routers 向配置中心订阅动态路由规则(也就是这里的 notify 方法)
 *
 *     see : org.apache.dubbo.rpc.cluster.RouterChain.setInvokers
 *
 *     为什么不是创建 Router 的时候就订阅，干嘛非要等到拉取到 invoker 的时候才订阅呢 ？
 *
 *     因为我们需要用到 providerApplicationName , 这个只有获取到 providerUrl 才能知道
 *
 *     标签规则是针对 provider 的，provider 打上标签之后，只有特定的标签流量才能经过 provider
 * */
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
                // 监听 /dubbo/config/dubbo/providerApplication.tag-router 动态路由配置文件
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
