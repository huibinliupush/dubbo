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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    /**
     *
     * proxy 在运行过程中没有任何反射开销，所有需要反射的地方均在生成 proxy 的过程中获取到了
     * 运行过程中直接拿反射的结果，比如 Method, 在 InvokerInvocationHandler 直接通过
     * Method 获取方法名称，参数类型，没有任何反射开销
     * */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        // 实现 interfaces 中的所有方法，方法体是将方法转到调用 org.apache.dubbo.rpc.proxy.InvokerInvocationHandler.invoke
        // 实现 newInstance 方法，向 Proxy 实例中写入 InvokerInvocationHandler 实例
        // 注意： Proxy 调用 InvokerInvocationHandler.invoke 方法的时候并不是通过反射获取方法名，方法参数类型，以及参数值
        // 而是在动态生成 Proxy 的时候，在方法体中就已经写死了，运行时不需要通过反射获取

        // invokerInvocationHandler.invoke(methodname , paramType , param) 静态在 Proxy 对应方法体中写死
        // 这样在运行时就不需要通过反射获取方法名以及参数类型信息系了
        return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        //获取服务实现类ref的动态代理，封装在wrapper中
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                //通过javassist字节码动态生成wrapper子类，直接调用服务目标方法，省去反射开销
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
