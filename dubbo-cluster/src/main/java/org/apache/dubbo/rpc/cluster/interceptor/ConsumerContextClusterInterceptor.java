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
package org.apache.dubbo.rpc.cluster.interceptor;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;

// 加载于普通 ClusterInvoker 前面
// ConsumerContext 在每次发起远程调用之后都会被清除
// 远程调用相应之后，会填充 ServerContext
@Activate
public class ConsumerContextClusterInterceptor implements ClusterInterceptor, ClusterInterceptor.Listener {

    @Override
    public void before(AbstractClusterInvoker<?> invoker, Invocation invocation) {
        RpcContext context = RpcContext.getContext();
        context.setInvocation(invocation).setLocalAddress(NetUtils.getLocalHost(), 0);
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        // 远程调用之前清除 ServerContext
        RpcContext.removeServerContext();
    }

    @Override
    public void after(AbstractClusterInvoker<?> clusterInvoker, Invocation invocation) {
        // 远程调用之后，清除 ConsumerContext
        RpcContext.removeContext(true);
    }

    @Override
    public void onMessage(Result appResponse, AbstractClusterInvoker<?> invoker, Invocation invocation) {
        // 响应回来之后设置远端 serverContext
        RpcContext.getServerContext().setObjectAttachments(appResponse.getObjectAttachments());
    }

    @Override
    public void onError(Throwable t, AbstractClusterInvoker<?> invoker, Invocation invocation) {

    }
}
