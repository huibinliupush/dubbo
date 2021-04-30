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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
/**
 * dubbo的扩展性非常强，每个功能点都会有很多的扩展，在zk客户端的实现中，dubbo也提供了灵活的扩展
 * 由AbstractZookeeperClient实现zk客户端的抽像功能，子类用来集成具体的客户端实现，所以AbstractZookeeperClient
 * 需要定义泛型TargetDataListener，TargetChildListener，因为不同的zk客户端实现可能依赖不同的zk客户端组件如curator，zkClient，
 * 不同的客户端组件对监听器的实现会有所不同,而dubbo对外暴露的监听器都是统一的，所以需要将dubbo框架内定义统一监听模型转换为具体的
 * zk客户端监听实现。curator中监听的实现类为CuratorWatcher
 * */
public abstract class AbstractZookeeperClient<TargetDataListener, TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    protected int DEFAULT_CONNECTION_TIMEOUT_MS = 5 * 1000;
    protected int DEFAULT_SESSION_TIMEOUT_MS = 60 * 1000;
    // regsitryUrl
    private final URL url;
    // 连接状态监听器集合
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();
    /**
     * dubbo框架内的监听模型 到 zk客户端监听模型实现的 转换
     */
    // path节点对应的子节点监听器集合  key:监听节点path  value: [key：dubbo内部子节点监听模型  value: 具体zk客户端子节点监听实现模型]
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();
    // path节点数据监听器集合  key:监听节点path  value: [key：dubbo内部节点数据监听模型  value: 具体zk客户端节点数据监听实现模型]
    private final ConcurrentMap<String, ConcurrentMap<DataListener, TargetDataListener>> listeners = new ConcurrentHashMap<String, ConcurrentMap<DataListener, TargetDataListener>>();

    private volatile boolean closed = false;
    //zk中创建的持久节点缓存
    private final Set<String>  persistentExistNodePath = new ConcurrentHashSet<>();

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void delete(String path){
        //never mind if ephemeral
        // 删除持久节点的缓存
        persistentExistNodePath.remove(path);
        deletePath(path);
    }


    @Override
    public void create(String path, boolean ephemeral) {
        if (!ephemeral) {
            //创建持久节点时 要节点缓存中是否存在，减少和zk服务端的交互
            if(persistentExistNodePath.contains(path)){
                return;
            }
            //zk中已经存在该持久节点，则放入缓存，直接返回。
            if (checkExists(path)) {
                persistentExistNodePath.add(path);
                return;
            }
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            //创建持久节点路径比如 root service category
            create(path.substring(0, i), false);
        }
        //dubbo中，临时节点肯定是最后一层，也就是url层才是临时节点，上层节点比如root service category层均是持久节点
        if (ephemeral) {
            //临时节点路径为：/root/service/category/url
            createEphemeral(path);
        } else {
            // 创建持久节点路径
            createPersistent(path);
            persistentExistNodePath.add(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        //增加连接状态监听器
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        //添加对path一级子节点监听
        // dubbo内部监听模型childListener 到 zkClient具体的监听实现转换
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        // 创建zkClient具体的监听器CuratorWatcherImpl
        TargetChildListener targetListener = listeners.computeIfAbsent(listener, k -> createTargetChildListener(path, k));
        // 在具体的zk客户端实现上添加 对应的监听器
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener, null);
    }

    @Override
    public void addDataListener(String path, DataListener listener, Executor executor) {
        // 添加path节点数据的监听
        // 创建dubbo内部数据监听模型 到 具体zk客户端 实现的数据监听器的映射
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        // 创建具体zk客户端实现的数据监听器TreeCache
        TargetDataListener targetListener = dataListenerMap.computeIfAbsent(listener, k -> createTargetDataListener(path, k));
        // 在具体zk客户端实现上 添加 相应的数据监听器
        addTargetDataListener(path, targetListener, executor);
    }

    @Override
    public void removeDataListener(String path, DataListener listener ){
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetListener = dataListenerMap.remove(listener);
            if(targetListener != null){
                //从zkClient上移除对应的数据监听器
                removeTargetDataListener(path, targetListener);
            }
        }
    }

    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                // 从zkClient客户端上移除对应的子节点监听器
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    protected void stateChanged(int state) {
        //在zk客户端具体实现中包裹 dubbo内部 连接状态监听器
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void create(String path, String content, boolean ephemeral) {
        //创建的时候 关注对  持久路径节点的创建
        if (checkExists(path)) {
            delete(path);
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path, content);
        } else {
            createPersistent(path, content);
        }
    }

    @Override
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    protected abstract boolean checkExists(String path);

    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

    protected abstract String doGetContent(String path);

    /**
     * we invoke the zookeeper client to delete the node
     * @param path the node path
     */
    protected abstract void deletePath(String path);

}
