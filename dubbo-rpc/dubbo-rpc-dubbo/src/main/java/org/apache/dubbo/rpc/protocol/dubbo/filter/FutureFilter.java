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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.AsyncMethodInfo;
import org.apache.dubbo.rpc.model.ConsumerModel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ASYNC_METHOD_INFO;

/**
 * EventFilter 事件回调
 *
 * https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/framework/more/events-notify/
 *
 * DubboBeanDefinitionParser 中解析事件回调相关信息，封装到 MethodConfig
 * see : org.apache.dubbo.config.spring.schema.DubboBeanDefinitionParser.parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class<?>, boolean)
 *
 * ReferenceConfig init 过程中将 MethodConfig 中封装的事件回调相关信息封装到 AsyncMethodInfo
 * see : org.apache.dubbo.config.AbstractConfig#convertMethodConfig2AsyncInfo(org.apache.dubbo.config.MethodConfig)
 */
@Activate(group = CommonConstants.CONSUMER)
public class FutureFilter implements Filter, Filter.Listener {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        // oninvoke 回调
        fireInvokeCallback(invoker, invocation);
        // need to configure if there's return value before the invocation in order to help invoker to judge if it's
        // necessary to return future.
        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        if (result.hasException()) {
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        fireThrowCallback(invoker, invocation, t);
    }

    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        // see : org.apache.dubbo.config.AbstractConfig#convertMethodConfig2AsyncInfo(org.apache.dubbo.config.MethodConfig)
        final AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }
        final Method onInvokeMethod = asyncMethodInfo.getOninvokeMethod();
        // onInvokeMethod 所属的实例
        final Object onInvokeInst = asyncMethodInfo.getOninvokeInstance();

        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a oninvoke callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }

        Object[] params = invocation.getArguments();
        try {
            // onInvoke 事件方法参数：对应 method 的请求参数
            onInvokeMethod.invoke(onInvokeInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {
        final AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }

        final Method onReturnMethod = asyncMethodInfo.getOnreturnMethod();
        final Object onReturnInst = asyncMethodInfo.getOnreturnInstance();

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        Object[] args = invocation.getArguments();
        Object[] params;
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();
        // onReturn 事件回调方法参数：对应 method 的返回值，请求参数
        if (rParaTypes.length > 1) {
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;
            } else {
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }
        } else {
            params = new Object[]{result};
        }
        try {
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {
        final AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }

        final Method onthrowMethod = asyncMethodInfo.getOnthrowMethod();
        final Object onthrowInst = asyncMethodInfo.getOnthrowInstance();

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {
                Object[] args = invocation.getArguments();
                Object[] params;
                // onThrow 事件回调方法参数：exception，对应 method 的请求参数
                if (rParaTypes.length > 1) {
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                    } else {
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                } else {
                    params = new Object[]{exception};
                }
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }

    private AsyncMethodInfo getAsyncMethodInfo(Invoker<?> invoker, Invocation invocation) {
        AsyncMethodInfo asyncMethodInfo = (AsyncMethodInfo) invocation.get(ASYNC_METHOD_INFO);
        if (asyncMethodInfo != null) {
            return asyncMethodInfo;
        }

        ConsumerModel consumerModel = ApplicationModel.getConsumerModel(invoker.getUrl().getServiceKey());
        if (consumerModel == null) {
            return null;
        }

        String methodName = invocation.getMethodName();
        if (methodName.equals($INVOKE)) {
            methodName = (String) invocation.getArguments()[0];
        }
        // see : org.apache.dubbo.rpc.model.ConsumerModel.init
        // 在 referenceConfig#init 中创建完代理会初始化 consumerModel
        return consumerModel.getAsyncInfo(methodName);
    }

}
