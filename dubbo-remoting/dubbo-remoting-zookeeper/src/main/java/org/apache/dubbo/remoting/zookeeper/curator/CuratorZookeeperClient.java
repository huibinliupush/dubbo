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
package org.apache.dubbo.remoting.zookeeper.curator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.EventType;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.support.AbstractZookeeperClient;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

public class CuratorZookeeperClient extends AbstractZookeeperClient<CuratorZookeeperClient.CuratorWatcherImpl, CuratorZookeeperClient.CuratorWatcherImpl> {

    protected static final Logger logger = LoggerFactory.getLogger(CuratorZookeeperClient.class);
    private static final String ZK_SESSION_EXPIRE_KEY = "zk.session.expire";

    static final Charset CHARSET = Charset.forName("UTF-8");
    //curator框架实现的zk客户端
    private final CuratorFramework client;
    //path节点数据监听器缓存
    private Map<String, TreeCache> treeCacheMap = new ConcurrentHashMap<>();

    /**
     * 应用级服务发现创建 CuratorFramework 略过粗糙，demo 级别
     * org.apache.dubbo.registry.zookeeper.util.CuratorFrameworkUtils#buildCuratorFramework(org.apache.dubbo.common.URL)
     * 应该以这里为准
     * */
    public CuratorZookeeperClient(URL url) {
        super(url);
        try {
            //获取连接超时时间 默认5s <dubbo:registry timeout=" ">
            int timeout = url.getParameter(TIMEOUT_KEY, DEFAULT_CONNECTION_TIMEOUT_MS);
            //获取session 过期时间 默认60s <dubbo:registry session=" ">
            /**
             * 设置客户端会话的超时时间（sessionTimeout），
             * 当服务器压力太大、网络故障或是客户端主动断开连接等原因导致连接断开时，
             * 只要客户端在 sessionTimeout 规定的时间内能够重新连接到 ZooKeeper 集群中任意一个实例，
             * 那么之前创建的会话仍然有效。ZooKeeper 通过 sessionID 唯一标识 Session，
             * 所以在 ZooKeeper 集群中，sessionID 需要保证全局唯一。 由于 ZooKeeper 会将 Session 信息存放到硬盘中，
             * 即使节点重启，之前未过期的 Session 仍然会存在。
             *
             * */
            int sessionExpireMs = url.getParameter(ZK_SESSION_EXPIRE_KEY, DEFAULT_SESSION_TIMEOUT_MS);
            //创建curator框架的zk客户端
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString(url.getBackupAddress())//指定所有zk节点地址: urlAddress + backup
                    .retryPolicy(new RetryNTimes(1, 1000))
                    .connectionTimeoutMs(timeout)
                    .sessionTimeoutMs(sessionExpireMs);
            //username:password
            String authority = url.getAuthority();
            if (authority != null && authority.length() > 0) {
                builder = builder.authorization("digest", authority.getBytes());
            }
            client = builder.build();
            //添加具体的客户端框架curator实现中的 连接状态监听器对 连接状态进行监听
            // 应用级服务发现 client 没有配置 CuratorConnectionStateListener，因为使用的 ServiceDiscovery 客户端（内部已经处理）
            client.getConnectionStateListenable().addListener(new CuratorConnectionStateListener(url));
            client.start();
            //同步启动
            boolean connected = client.blockUntilConnected(timeout, TimeUnit.MILLISECONDS);
            if (!connected) {
                throw new IllegalStateException("zookeeper not connected");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void createPersistent(String path) {
        try {
            client.create().forPath(path);
        } catch (NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists.", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void createEphemeral(String path) {
        try {
            client.create().withMode(CreateMode.EPHEMERAL).forPath(path);
        } catch (NodeExistsException e) {
            //这里主要是用来处理session过期后 重新注册相关数据
            //因为在session过期后。 zk服务端可能还没来得及删除这个临时节点，所以这里重新删除，并创建即可
            logger.warn("ZNode " + path + " already exists, since we will only try to recreate a node on a session expiration" +
                    ", this duplication might be caused by a delete delay from the zk server, which means the old expired session" +
                    " may still holds this ZNode and the server just hasn't got time to do the deletion. In this case, " +
                    "we can just try to delete and create again.", e);
            deletePath(path);
            createEphemeral(path);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void createPersistent(String path, String data) {
        byte[] dataBytes = data.getBytes(CHARSET);
        try {
            client.create().forPath(path, dataBytes);
        } catch (NodeExistsException e) {
            try {
                client.setData().forPath(path, dataBytes);
            } catch (Exception e1) {
                throw new IllegalStateException(e.getMessage(), e1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void createEphemeral(String path, String data) {
        byte[] dataBytes = data.getBytes(CHARSET);
        try {
            client.create().withMode(CreateMode.EPHEMERAL).forPath(path, dataBytes);
        } catch (NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists, since we will only try to recreate a node on a session expiration" +
                    ", this duplication might be caused by a delete delay from the zk server, which means the old expired session" +
                    " may still holds this ZNode and the server just hasn't got time to do the deletion. In this case, " +
                    "we can just try to delete and create again.", e);
            deletePath(path);
            createEphemeral(path, data);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected void deletePath(String path) {
        try {
            //级联删除path下的子节点
            client.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (NoNodeException e) {
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public List<String> getChildren(String path) {
        try {
            return client.getChildren().forPath(path);
        } catch (NoNodeException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public boolean checkExists(String path) {
        try {
            if (client.checkExists().forPath(path) != null) {
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public boolean isConnected() {
        return client.getZookeeperClient().isConnected();
    }

    @Override
    public String doGetContent(String path) {
        try {
            byte[] dataBytes = client.getData().forPath(path);
            return (dataBytes == null || dataBytes.length == 0) ? null : new String(dataBytes, CHARSET);
        } catch (NoNodeException e) {
            // ignore NoNode Exception.
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public void doClose() {
        client.close();
    }

    @Override
    public CuratorZookeeperClient.CuratorWatcherImpl createTargetChildListener(String path, ChildListener listener) {
        return new CuratorZookeeperClient.CuratorWatcherImpl(client, listener, path);
    }

    @Override
    public List<String> addTargetChildListener(String path, CuratorWatcherImpl listener) {
        try {
            // 注意这个CuratorWatcher 是一次性的，触发后就没了 需要重新注册
            // 因为dubbo在每次变更通知时 都是全量通知，需要重新全量拉取，在重新拉取的过程中在对CuratorWatcher进行注册
            // see : org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient.CuratorWatcherImpl.process

            // 监听哪个 path 在 CuratorWatcherImpl 中已经封装好了 @see org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient.createTargetChildListener
            return client.getChildren().usingWatcher(listener).forPath(path);
        } catch (NoNodeException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    protected CuratorZookeeperClient.CuratorWatcherImpl createTargetDataListener(String path, DataListener listener) {
        return new CuratorWatcherImpl(client, listener);
    }

    @Override
    protected void addTargetDataListener(String path, CuratorZookeeperClient.CuratorWatcherImpl treeCacheListener) {
        this.addTargetDataListener(path, treeCacheListener, null);
    }

    @Override
    protected void addTargetDataListener(String path, CuratorZookeeperClient.CuratorWatcherImpl treeCacheListener, Executor executor) {
        try {
            //创建path节点的监听器实现对节点数据的监听（treeCache为curator客户端框架本地对zk服务端数据的缓存）
            // treeCache相当于zk服务端数据  在 本地的 缓存视图
            TreeCache treeCache = TreeCache.newBuilder(client, path).setCacheData(false).build();
            //path节点数据监听器缓存
            treeCacheMap.putIfAbsent(path, treeCache);

            if (executor == null) {
                //向treeCache添加监听器
                treeCache.getListenable().addListener(treeCacheListener);
            } else {
                treeCache.getListenable().addListener(treeCacheListener, executor);
            }

            treeCache.start();
        } catch (Exception e) {
            throw new IllegalStateException("Add treeCache listener for path:" + path, e);
        }
    }

    @Override
    protected void removeTargetDataListener(String path, CuratorZookeeperClient.CuratorWatcherImpl treeCacheListener) {
        TreeCache treeCache = treeCacheMap.get(path);
        if (treeCache != null) {
            treeCache.getListenable().removeListener(treeCacheListener);
        }
        treeCacheListener.dataListener = null;
    }

    @Override
    public void removeTargetChildListener(String path, CuratorWatcherImpl listener) {
        listener.unwatch();
    }

    static class CuratorWatcherImpl implements CuratorWatcher, TreeCacheListener {

        private CuratorFramework client;
        //包装dubbo内部子节点监听模型
        private volatile ChildListener childListener;
        //包装dubbo内部节点数据监听模型
        // org.apache.dubbo.configcenter.support.zookeeper.ZookeeperDynamicConfiguration.cacheListener
        private volatile DataListener dataListener;
        // 监听节点的Path
        private String path;

        public CuratorWatcherImpl(CuratorFramework client, ChildListener listener, String path) {
            this.client = client;
            this.childListener = listener;
            this.path = path;
        }

        public CuratorWatcherImpl(CuratorFramework client, DataListener dataListener) {
            // org.apache.dubbo.configcenter.support.zookeeper.ZookeeperDynamicConfiguration.cacheListener
            this.dataListener = dataListener;
        }

        protected CuratorWatcherImpl() {
        }

        public void unwatch() {
            this.childListener = null;
        }

        @Override
        public void process(WatchedEvent event) throws Exception {
            // if client connect or disconnect to server, zookeeper will queue
            // watched event(Watcher.Event.EventType.None, .., path = null).
            //CuratorWatcher回调  用来响应 子节点变化
            if (event.getType() == Watcher.Event.EventType.None) {
                return;
            }

            if (childListener != null) {
                //全量拉取，并且重新在path上注册该CuratorWatcher
                childListener.childChanged(path, client.getChildren().usingWatcher(this).forPath(path));
            }
        }

        /**
         * 由 treeCache 进行通知
         * org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient#addTargetDataListener(java.lang.String, org.apache.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient.CuratorWatcherImpl, java.util.concurrent.Executor)
         * */
        @Override
        public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
            //treeCache回调 TreeCacheListener  用来响应节点变化
            if (dataListener != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("listen the zookeeper changed. The changed data:" + event.getData());
                }
                TreeCacheEvent.Type type = event.getType();
                EventType eventType = null;
                String content = null;
                String path = null;
                switch (type) {
                    case NODE_ADDED:
                        /**
                         *
                         * 含义：一个新的节点被添加到缓存树中。
                         *
                         * 触发场景：
                         *
                         * 在 TreeCache 启动后，首次发现一个符合监听路径范围的新节点（包括根节点）。
                         *
                         * 在 TreeCache 运行期间，监听到 ZooKeeper 中创建了一个新的子节点（在监听路径范围内）。
                         *
                         * 可用数据：getData() 可以获取到新节点的数据（如果节点有数据）。getInitialData() 对于此事件类型通常为 null。getPath() 返回新节点的完整路径。
                         * */
                        eventType = EventType.NodeCreated;
                        path = event.getData().getPath();
                        content = event.getData().getData() == null ? "" : new String(event.getData().getData(), CHARSET);
                        break;
                    case NODE_UPDATED:
                        /**
                         *
                         * 含义：缓存树中一个已存在节点的数据内容被更新。
                         *
                         * 触发场景：监听到 ZooKeeper 中某个节点的 setData 操作（在监听路径范围内）。
                         *
                         * 可用数据：getData() 返回节点更新后的数据。getInitialData() 返回节点更新前的数据（非常关键，用于比较变化）。getPath() 返回被更新节点的完整路径。
                         * */
                        eventType = EventType.NodeDataChanged;
                        path = event.getData().getPath();
                        content = event.getData().getData() == null ? "" : new String(event.getData().getData(), CHARSET);
                        break;
                    case NODE_REMOVED:
                        /**
                         * 含义：一个节点从缓存树中被移除。
                         *
                         * 触发场景：监听到 ZooKeeper 中某个节点被删除（在监听路径范围内）。
                         *
                         * 可用数据：getData() 通常为 null（因为节点已不存在）。getInitialData() 返回节点被删除前的数据（即最后缓存的数据）。getPath() 返回被删除节点的完整路径。
                         * */
                        path = event.getData().getPath();
                        eventType = EventType.NodeDeleted;
                        break;
                    case INITIALIZED:
                        // TreeCache connecnt 成功
                        /**
                         *
                         * 含义：TreeCache 实例已完成初始的 ZooKeeper 树结构同步，其内部缓存树现在被认为是完整且最新的。
                         *
                         * 触发场景：仅在 TreeCache 启动（调用 start()）后，当它成功连接到 ZooKeeper 并首次完整拉取并构建了指定路径下的整个子树缓存时触发一次。这是最重要的初始化完成事件。
                         *
                         * 可用数据：getData() 和 getInitialData() 通常为 null。getPath() 通常是根路径（TreeCache 监听的路径）。
                         *
                         * 重要性：在收到此事件之前，缓存可能不完整或为空。通常在这个事件之后，应用才会认为缓存可用并开始处理其他业务逻辑或查询缓存。
                         * */
                        eventType = EventType.INITIALIZED;
                        break;
                    case CONNECTION_LOST:
                        //连接session到期，zk 会自动重新创建回话
                        /**
                         * 含义：与 CONNECTION_SUSPENDED 类似，表示连接永久丢失（通常意味着会话过期）。TreeCache 实例将关闭且不再可用。
                         *
                         * 触发场景：Curator 的连接状态监听器报告连接状态变为 LOST（会话过期）。
                         *
                         * 可用数据：getData(), getInitialData() 通常为 null。getPath() 通常是根路径或 null。
                         *
                         * 关键区别：收到此事件后，TreeCache 实例会停止工作并关闭。应用必须创建新的 TreeCache 实例并重新启动它来恢复功能。
                         * */
                        eventType = EventType.CONNECTION_LOST;
                        break;
                    case CONNECTION_RECONNECTED:
                        //连接丢失后，重连成功， session 未过期，重连成功之后自动同步 zookeeper 数据
                        /**
                         *
                         * 含义：TreeCache 在经历连接中断后，已成功重新连接到 ZooKeeper 集群。
                         *
                         * 触发场景：Curator 的连接状态监听器报告连接状态从 LOST/SUSPENDED 变回 RECONNECTED。
                         *
                         * 可用数据：getData(), getInitialData() 通常为 null。getPath() 通常是根路径或 null。
                         *
                         * 后续动作：TreeCache 会自动执行全量同步（类似于初始同步），以确保缓存与 ZooKeeper 服务器状态一致。
                         * 这意味着在同步期间或之后，你可能会收到一系列的 NODE_ADDED, NODE_UPDATED, NODE_REMOVED 事件来反映连接中断期间发生的所有变更。
                         * 最终会再次触发一个 INITIALIZED 事件表示同步完成。
                         * */
                        eventType = EventType.CONNECTION_RECONNECTED;
                        break;
                    case CONNECTION_SUSPENDED:
                        // connection timeout 连接空闲自动重连
                        /**
                         * 含义：TreeCache 检测到与 ZooKeeper 集群的连接已中断。
                         *
                         * 触发场景：Curator 的连接状态监听器报告连接状态变为 LOST 或 SUSPENDED。
                         *
                         * 可用数据：getData(), getInitialData() 通常为 null。getPath() 通常是根路径或 null。
                         *
                         * 含义：此时缓存数据可能已过时（因为无法接收实时更新）。应用应进入“连接中断”处理模式（如显示警告、暂停写操作等）。TreeCache 会尝试自动重连。
                         * */
                        eventType = EventType.CONNECTION_SUSPENDED;
                        break;

                }
                // org.apache.dubbo.configcenter.support.zookeeper.ZookeeperDynamicConfiguration.cacheListener
                dataListener.dataChanged(path, content, eventType);
            }
        }
    }

    private class CuratorConnectionStateListener implements ConnectionStateListener {
        private final long UNKNOWN_SESSION_ID = -1L;
        // 缓存最近一次sessionId,用于重连时判断session是否失效
        private long lastSessionId;
        // registryURL
        private URL url;

        public CuratorConnectionStateListener(URL url) {
            this.url = url;
        }

        @Override
        public void stateChanged(CuratorFramework client, ConnectionState state) {
            int timeout = url.getParameter(TIMEOUT_KEY, DEFAULT_CONNECTION_TIMEOUT_MS);
            int sessionExpireMs = url.getParameter(ZK_SESSION_EXPIRE_KEY, DEFAULT_SESSION_TIMEOUT_MS);

            long sessionId = UNKNOWN_SESSION_ID;
            try {
                sessionId = client.getZookeeperClient().getZooKeeper().getSessionId();
            } catch (Exception e) {
                logger.warn("Curator client state changed, but failed to get the related zk session instance.");
            }

            if (state == ConnectionState.LOST) {
                //session 过期，zk 会重新创建 session, 并触发 RECONNECTED（NEW_SESSION_CREATED）
                logger.warn("Curator zookeeper session " + Long.toHexString(lastSessionId) + " expired.");
                // 通知dubbo内部 连接状态监听器，实例内部类(private class)可访问其所属的类 this 实例指针
                CuratorZookeeperClient.this.stateChanged(StateListener.SESSION_LOST);
            } else if (state == ConnectionState.SUSPENDED) {
                //连接丢失 connection timeout （连接空闲自动重连），触发 RECONNECTED
                logger.warn("Curator zookeeper connection of session " + Long.toHexString(sessionId) + " timed out. " +
                        "connection timeout value is " + timeout + ", session expire timeout value is " + sessionExpireMs);
                CuratorZookeeperClient.this.stateChanged(StateListener.SUSPENDED);
            } else if (state == ConnectionState.CONNECTED) {
                //ZK客户端第一次连接成功服务端时触发
                // 缓存最近一次sessionId,用于重连时判断session是否失效
                lastSessionId = sessionId;
                logger.info("Curator zookeeper client instance initiated successfully, session id is " + Long.toHexString(sessionId));
                CuratorZookeeperClient.this.stateChanged(StateListener.CONNECTED);
            } else if (state == ConnectionState.RECONNECTED) {
                if (lastSessionId == sessionId && sessionId != UNKNOWN_SESSION_ID) {
                    //连接短暂丢失，在session过期之前，客户端又重连成功。这时和session相关的watcher以及临时znode没有被删除
                    logger.warn("Curator zookeeper connection recovered from connection lose, " +
                            "reuse the old session " + Long.toHexString(sessionId));
                    CuratorZookeeperClient.this.stateChanged(StateListener.RECONNECTED);
                } else {
                    //连接丢失的比较久，客户端重连成功，但是session已经过期，这时和session相关的watcher以及临时znode已经被删除。
                    logger.warn("New session created after old session lost, " +
                            "old session " + Long.toHexString(lastSessionId) + ", new session " + Long.toHexString(sessionId));
                    lastSessionId = sessionId;
                    CuratorZookeeperClient.this.stateChanged(StateListener.NEW_SESSION_CREATED);
                }
            }
        }

    }

    /**
     * just for unit test
     *
     * @return
     */
    CuratorFramework getClient() {
        return client;
    }
}
