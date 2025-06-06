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
package org.apache.dubbo.rpc.cluster.router;

/**
 * TODO Extract more code here if necessary
 */
public abstract class AbstractRouterRule {
    private String rawRule;
    // 表示是否在每次调用时执行该路由规则。如果设置为 false，
    // 则会在 Provider 列表变更时预先执行并缓存结果，调用时直接从缓存中获取路由结果。
    private boolean runtime = true;

    // Force 以 invocation ， consuemrUrl 中的 dubbo.force.tag 优先
    // 其次在按照 tagRouterRule 中的 force
    // 如果该路由规则过滤出一个空的 invoker 集合，没有任何 invokers 可以调用
    // true 表示强制按照路由规则执行，返回一个空的 invoker 集合
    // false 则返回一个不带任何 tag 的 invoker 集合
    private boolean force = false;
    private boolean valid = true;
    private boolean enabled = true;
    private int priority;
    // 表示该路由规则是否为持久数据，当注册方退出时，路由规则是否依然存在。
    // 类比注册的 URL , 如果是 false 表示静态数据，注册方退出不会删除
    private boolean dynamic = false;

    private String scope;
    private String key;

    public String getRawRule() {
        return rawRule;
    }

    public void setRawRule(String rawRule) {
        this.rawRule = rawRule;
    }

    public boolean isRuntime() {
        return runtime;
    }

    public void setRuntime(boolean runtime) {
        this.runtime = runtime;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
