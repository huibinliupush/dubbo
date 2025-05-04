/*
 *
 *   Licensed to the Apache Software Foundation (ASF) under one or more
 *   contributor license agreements.  See the NOTICE file distributed with
 *   this work for additional information regarding copyright ownership.
 *   The ASF licenses this file to You under the Apache License, Version 2.0
 *   (the "License"); you may not use this file except in compliance with
 *   the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package org.apache.dubbo.demo.provider;


import org.apache.dubbo.config.annotation.Argument;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.config.annotation.Method;
import org.apache.dubbo.demo.CallbackListener;
import org.apache.dubbo.demo.CallbackService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@DubboService(onconnect = "onconnect",methods = {@Method(name ="addListener",
        arguments = {@Argument(index = 1 , callback = true) }) })
public class CallbackServiceImpl implements CallbackService {

    private final Map<String, CallbackListener> listeners = new ConcurrentHashMap<String, CallbackListener>();

    public CallbackServiceImpl() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    for (Map.Entry<String, CallbackListener> entry : listeners.entrySet()) {
                        try {
                            entry.getValue().changed(getChanged(entry.getKey()));
                        } catch (Throwable t1) {
                            listeners.remove(entry.getKey());
                        }
                    }
                    Thread.sleep(5000); // timely trigger change event
                } catch (Throwable t1) {
                    t1.printStackTrace();
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void addListener(String key, CallbackListener listener) {
        listeners.put(key, listener);
        listener.changed(getChanged(key)); // send notification for change
    }

    private String getChanged(String key) {
        return "Changed: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    public void onconnect() {
        // 在 provider 端，需要再 service 实现中实现 onconnect() 方法（接口不必申明），配置 onconnect = ""
        // 这样当客户端连接到 provider 端的时候就会触发这里的 onconnect() 事件 handler
        // 但需要注意的是 callbackService 与 demoService 底层共用一个连接（在客户端）
        // 所以只会触发 callbackService 或者 demoService 中的任意一个 onconnect() 方法

        // 对于 reference 来说，如果想要触发 onconnect 事件，则需要编写 stub 类，然后在 stub 类中实现 onconnect()
        // 然后在 reference 的 onconnect = "" 配置中指明 onconnect() 方法
        // 随后会在 org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper.getProxy(org.apache.dubbo.rpc.Invoker<T>, boolean)
        // 将 stub service 本地暴露出来
        // 客户端端连接到服务端的时候，stub service 的 onconnect 方法就会被调用
        System.out.print("onconnect");
    }

    public void ondisconnect() {
        System.out.print("disconnect");
    }

}
