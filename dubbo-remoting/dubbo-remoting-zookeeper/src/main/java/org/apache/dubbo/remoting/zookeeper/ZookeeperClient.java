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
package org.apache.dubbo.remoting.zookeeper;

import org.apache.dubbo.common.URL;

import java.util.List;
import java.util.concurrent.Executor;

public interface ZookeeperClient {
    //创建节点
    void create(String path, boolean ephemeral);
    //删除节点
    void delete(String path);
    //获取节点的一级子节点
    List<String> getChildren(String path);
    //监听path节点下的一级子节点变动
    List<String> addChildListener(String path, ChildListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     */
    // 监听path节点对应的数据变动
    void addDataListener(String path, DataListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     * @param executor another thread
     */
    void addDataListener(String path, DataListener listener, Executor executor);
    //删除path节点数据的监听
    void removeDataListener(String path, DataListener listener);
    //删除Path节点下一级子节点的变动监听
    void removeChildListener(String path, ChildListener listener);
    // 对连接状态的监听
    void addStateListener(StateListener listener);
    // 删除对连接状态的监听
    void removeStateListener(StateListener listener);
    // 判断连接是否有效
    boolean isConnected();

    void close();

    URL getUrl();

    void create(String path, String content, boolean ephemeral);
    // 获取path节点中的数据
    String getContent(String path);

}
