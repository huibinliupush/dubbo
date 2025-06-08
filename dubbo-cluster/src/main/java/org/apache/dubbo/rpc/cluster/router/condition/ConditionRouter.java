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
package org.apache.dubbo.rpc.cluster.router.condition;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHOD_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.ADDRESS_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.FORCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RUNTIME_KEY;

/**
 * ConditionRouter
 *
 */
public class ConditionRouter extends AbstractRouter {
    public static final String NAME = "condition";

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    protected static final Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    // 匹配参数 key -> 匹配条件 MatchPair
    // MatchPair 中的 matches 集合存放 key = 后面的字符
    // MatchPair 中的 mismatches 集合存放 key != 后面的字符

    // when 条件中的参数 key 从 consuemrUrl 或者 invovation 中获取，然后与 MatchPair 中的值进行匹配
    protected Map<String, MatchPair> whenCondition;
    // then 条件中的参数 key 从 providerUrl 中获取，然后与 MatchPair 中的值进行匹配
    // MatchPair 集合中的值如果遇到 $ , 则从 consumerURL 中提取
    // '=> region = $region'
    protected Map<String, MatchPair> thenCondition;

    private boolean enabled;

    public ConditionRouter(String rule, boolean force, boolean enabled) {
        this.force = force;
        this.enabled = enabled;
        this.init(rule);
    }

    public ConditionRouter(URL url) {
        // RouteUrl
        this.url = url;
        this.priority = url.getParameter(PRIORITY_KEY, 0);
        this.force = url.getParameter(FORCE_KEY, false);
        this.enabled = url.getParameter(ENABLED_KEY, true);
        init(url.getParameterAndDecoded(RULE_KEY));
    }

    public void init(String rule) {
        try {
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            // 如果条件中不存在 => ， 那么默认是 thenRule
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            // 如果匹配条件为空，表示对所有请求生效，如：=> status != staging
            //如果过滤条件为空，表示禁止来自相应请求的访问，如：application = product =>
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
    // rule :
    // method = getDetail & arguments[0] = dubbo
    // host = 2.2.2.2,1.1.1.1,3.3.3.3 => host = 1.2.3.4
    // host = 2.2.2.2,1.1.1.1,3.3.3.3 & host !=1.1.1.1 => host = 1.2.3.4
    // methods=getFoo & host=1.1.1.1 => host = 1.2.3.4
    // see : org.apache.dubbo.rpc.cluster.router.condition.ConditionRouterTest.testParseRule
    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        // 条件中的匹配参数 key 对应的 MatchPair （匹配的参数 value）
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;

        /**
         * ([&!=,]*)\s*([^&!=,\s]+) 分为两个匹配组：
         * 第一个匹配组：([&!=,]*) ，匹配条件中的 & != = , 零个或者多个，用来匹配条件中的指定字符
         * 中间匹配零个或者多个空白字符
         * 第一个匹配组：([^&!=,\s]+) ，匹配一个或者多个所有不是 & != = , 空白字符的字符（遇到这些字符则停止匹配），用来匹配指定字符前面的参数 key , 以及指定字符后面的参数 value
         * method = getDetail & arguments[0] = dubbo
         *
         * 第一次 find :
         *
         * 1. 第一个匹配组匹配不到任何字符，第二个匹配组匹配到 method
         * null = separator = matcher.group(1)
         * method = content = matcher.group(2); 参数 key
         *
         * condition : method -> MatchPair
         *
         * 第二次 find :
         * = getDetail & arguments[0] = dubbo
         *
         * 1. 第一个匹配组匹配到 = ，第二个匹配组匹配到 getDetail
         * "=" = separator = matcher.group(1)
         * getDetail = content = matcher.group(2)
         * pair.matches -> (getDetail)
         *
         * 第三次 find :
         * & arguments[0] = dubbo
         *
         * 1. 第一个匹配组匹配到 & ，第二个匹配组匹配到 arguments[0]
         * "&" = separator = matcher.group(1)
         * arguments[0] = content = matcher.group(2)
         *
         * condition : method -> MatchPair
     *                 arguments[0] -> MatchPair (局部变量 pair 切换到这里)
         *
         * 第四次 find :
         * = dubbo
         *
         * 1. 第一个匹配组匹配到 = ，第二个匹配组匹配到 dubbo
         * "=" = separator = matcher.group(1)
         * dubbo = content = matcher.group(2)
         *
         * pair.matches -> (dubbo)
         *
         * */
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        while (matcher.find()) { // Try to match one by one
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // Start part of the condition expression.
            if (StringUtils.isEmpty(separator)) {
                // 遇到新的 key(content) 为该 key 创建 MatchPair
                pair = new MatchPair();
                // method -> MatchPair
                condition.put(content, pair);
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {
                // 又遇到一个 key(content)
                if (condition.get(content) == null) {
                    // 该 key 是一个新 key, 为新 key 创建 MatchPair
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    // 该 key 是一个旧 key，缓存旧 key 对应的 MatchPair
                    // 因为后面紧接着就要解析到新的值，放入 MatchPair 中
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            else if ("=".equals(separator)) {
                // 遇到值，就 value 放入 key 对应的 MatchPair 中（之前已经被缓存在变量 pair 中）
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.matches;
                values.add(content);
            }
            // The Value in the KV part.
            else if ("!=".equals(separator)) {
                // 遇到值，就 value 放入 key 对应的 MatchPair 中（之前已经被缓存在变量 pair 中）
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            else if (",".equals(separator)) { // Should be separated by ','
                // 遇到多个值，就 value 放入 key 对应的 MatchPair 中（之前已经被缓存在变量 pair 中）
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (!enabled) {
            return invokers;
        }

        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }
        try {
            // 针对 consumerUrl 的过滤
            // when 条件不支持 $ 引用（ then 条件遇到 $ 则从 consumerurl 或者 invocation 中获取对应的参数值）
            // '=> region = $region'

            // when 条件中的参数值全部从 consumeURL ,Invocation 中获取
            // 首先检查 参数值 是否和 mismatches 集合中的值不匹配
            // 在检查是否和 matches 集合中的值匹配
            if (!matchWhen(url, invocation)) {
                // when 条件匹配失败表示 consumer 不适用路由规则，返回全部 provider
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            for (Invoker<T> invoker : invokers) {
                // 针对 providerUrl 的过滤
                // then 条件遇到 $ 则从 consumerurl 或者 invocation 中获取对应的参数值
                // '=> region = $region'
                // see : UrlUtils.isMatchGlobPattern(java.lang.String, java.lang.String, org.apache.dubbo.common.URL)
                if (matchThen(invoker.getUrl(), url)) {
                    // 将符合 then 条件的 invoker 添加到返回结果中
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers;
    }

    @Override
    public boolean isRuntime() {
        // We always return true for previously defined Router, that is, old Router doesn't support cache anymore.
//        return true;
        return this.url.getParameter(RUNTIME_KEY, false);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    // url 为 consumerUrl
    boolean matchWhen(URL url, Invocation invocation) {
        return CollectionUtils.isEmptyMap(whenCondition) || matchCondition(whenCondition, url, null, invocation);
    }
    // url 为 providerUrl
    // param 为 consumerUrl
    private boolean matchThen(URL url, URL param) {
        return CollectionUtils.isNotEmptyMap(thenCondition) && matchCondition(thenCondition, url, param, null);
    }

    // matchWhen : url 为 consumerUrl


    // matchThen ： url 为 providerUrl ， param 为 consumerUrl
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        boolean result = false;
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            // 提取 key 在 url 或者 invocation 中对应的值
            String sampleValue;
            //get real invoked method name from invocation
            if (invocation != null && (METHOD_KEY.equals(key) || METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else if (ADDRESS_KEY.equals(key)) {
                sampleValue = url.getAddress();
            } else if (HOST_KEY.equals(key)) {
                sampleValue = url.getHost();
            } else {
                // url 参数
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(key);
                }
            }
            if (sampleValue != null) {
                // 首先检查 sampleValue 是否和 mismatches 集合中的值不匹配
                // 在检查是否和 matches 集合中的值匹配
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    result = true;
                }
            } else {
                //not pass the condition
                // 条件路由中指定的参考key 在 url 或者 invocation 中不存在
                // 如果 matches 集合为空，则匹配
                // matches 集合不为空，则不匹配
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }

    protected static final class MatchPair {
        // 存放 = 后面的字符
        final Set<String> matches = new HashSet<String>();
        // 存放 != 后面的字符
        final Set<String> mismatches = new HashSet<String>();

        private boolean isMatch(String value, URL param) {
            //
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }

            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                return true;
            }

            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }
}
