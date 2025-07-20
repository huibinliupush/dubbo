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
package org.apache.dubbo.rpc.protocol.tri.rest.mapping;

import org.apache.dubbo.common.utils.Pair;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathExpression;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathSegment;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathSegment.Type;
import org.apache.dubbo.rpc.protocol.tri.rest.util.KeyString;
import org.apache.dubbo.rpc.protocol.tri.rest.util.PathUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * A high-performance Radix Tree for efficient path matching.
 *
 * @param <T> Type of values associated with the paths.
 */
public final class RadixTree<T> {
    // Match 封装 PathExpression 和 Registration
    private final Map<KeyString, List<Match<T>>> directPathMap = new HashMap<>();
    // 叶子节点中存放 PathExpression 和 Registration（ Pair 封装）
    private final Node<T> root = new Node<>();
    private final char separator;
    private final boolean caseSensitive;

    public RadixTree(boolean caseSensitive, char separator) {
        this.caseSensitive = caseSensitive;
        this.separator = separator;
    }

    public RadixTree(boolean caseSensitive) {
        this(caseSensitive, '/');
    }

    public RadixTree(char separator) {
        this(true, separator);
    }

    public RadixTree() {
        this(true, '/');
    }

    public T addPath(PathExpression path, T value) {
        if (path.isDirect()) {
            // 底层的 hashCode 以及 equals 方法使用 netty 的 PlatformDependent 生成
            KeyString key = new KeyString(path.getPath(), caseSensitive);
            // direct path 直接注册到 directPathMap 中
            List<Match<T>> matches = directPathMap.computeIfAbsent(key, k -> new ArrayList<>());
            for (int i = 0, size = matches.size(); i < size; i++) {
                Match<T> match = matches.get(i);
                if (match.getValue().equals(value)) {
                    return match.getValue();
                }
            }
            // 存放 PathExpression 和 Registration（ Match 封装）
            matches.add(new Match<>(path, value));
            // 之前没有注册过则返回 null , 如果直接注册过则返回 value(Registration)
            return null;
        }
        // 非 direct path 的注册逻辑（包含路径变量或者正则变量的 path）
        Node<T> current = root;
        PathSegment[] segments = path.getSegments();
        for (int i = 0, len = segments.length; i < len; i++) {
            // LITERAL 类型的 PathSegment 直接注册到 current.children 中
            // VARIABLE 以及 PATTERN 等其他类型的 PathSegment 注册到 current.fuzzyChildren
            Node<T> child = getChild(current, segments[i]);
            if (i == len - 1) {
                // 最后在叶子节点中存放 PathExpression 和 Registration（ Pair 封装）
                List<Pair<PathExpression, T>> values = child.values;
                for (int j = 0, size = values.size(); j < size; j++) {
                    if (values.get(j).getLeft().equals(path)) {
                        return values.get(j).getRight();
                    }
                }
                values.add(Pair.of(path, value));
            }
            current = child;
        }
        return null;
    }

    public T addPath(String path, T value) {
        if (path == null) {
            return value;
        }
        if (separator == '/') {
            path = PathUtils.normalize(path);
        } else {
            path = path.replace(separator, '/');
            if (path.isEmpty() || path.charAt(0) != '/') {
                path = '/' + path;
            }
        }
        return addPath(PathExpression.parse(path), value);
    }

    public void addPath(T value, String... paths) {
        for (String path : paths) {
            addPath(path, value);
        }
    }

    private Node<T> getChild(Node<T> current, PathSegment segment) {
        Node<T> child;
        if (segment.getType() == Type.LITERAL) {
            // LITERAL 类型的 PathSegment 直接注册到 current.children 中
            // KeyString 为 PathSegment 的 value
            // node 为路径在 RadixTree 中的节点
            // /demo/get/muchParam
            // root node -> demo node -> get node -> mushParam node（ children 一条线）
            Map<KeyString, Node<T>> children = current.children;
            KeyString key = new KeyString(segment.getValue(), caseSensitive);
            child = children.get(key);
            if (child == null) {
                child = new Node<>();
                children.put(key, child);
            }
        } else {
            // VARIABLE 以及 PATTERN 等其他类型的 PathSegment 注册到 current.fuzzyChildren
            // /demo/get/head/{id}
            // head node 的 fuzzyChildren 中存放 {id} node
            // 注意 key 为 PathSegment
            Map<PathSegment, Node<T>> children = current.fuzzyChildren;
            child = children.get(segment);
            if (child == null) {
                child = new Node<>();
                children.put(segment, child);
            }
        }
        return child;
    }

    public void remove(Predicate<T> tester) {
        directPathMap.entrySet().removeIf(entry -> {
            List<Match<T>> values = entry.getValue();
            values.removeIf(match -> tester.test(match.getValue()));
            return values.isEmpty();
        });
        removeRecursive(root, tester);
    }

    private void removeRecursive(Node<T> current, Predicate<T> tester) {
        current.values.removeIf(pair -> tester.test(pair.getValue()));

        List<Map<?, Node<T>>> list = new ArrayList<>();
        list.add(current.children);
        list.add(current.fuzzyChildren);
        for (Map<?, Node<T>> children : list) {
            Iterator<? extends Entry<?, Node<T>>> cit = children.entrySet().iterator();
            while (cit.hasNext()) {
                Node<T> node = cit.next().getValue();
                removeRecursive(node, tester);
                if (node.isEmpty()) {
                    cit.remove();
                }
            }
        }
    }

    public void walk(BiConsumer<PathExpression, T> consumer) {
        for (List<Match<T>> matches : directPathMap.values()) {
            for (Match<T> match : matches) {
                consumer.accept(match.getExpression(), match.getValue());
            }
        }
        walkRecursive(root, consumer);
    }

    private void walkRecursive(Node<T> root, BiConsumer<PathExpression, T> consumer) {
        for (Pair<PathExpression, T> pair : root.values) {
            consumer.accept(pair.getLeft(), pair.getRight());
        }

        for (Node<T> node : root.children.values()) {
            walkRecursive(node, consumer);
        }

        for (Node<T> node : root.fuzzyChildren.values()) {
            walkRecursive(node, consumer);
        }
    }

    /**
     * Ensure that the path is normalized using {@link PathUtils#normalize(String)} before matching.
     */
    public void match(KeyString path, List<Match<T>> matches) {
        // 对于 directPath 来说，比如 /demo/post/list
        // 可以直接在 directPathMap 中找到映射关系 Match
        List<Match<T>> directMatches = directPathMap.get(path);
        if (directMatches != null) {
            for (int i = 0, size = directMatches.size(); i < size; i++) {
                matches.add(directMatches.get(i));
            }
        }
        // 如果 root 是叶子节点，那么就直接 return , 不会到 radixTree 中继续查找
        if (root.isLeaf()) {
            return;
        }
        /**
         * 对于 /demo/post/list 路径来说既然已经在 directPathMap 中找到了映射了，那为什么还要去 radixTree 中查找
         * matchRecursive 主要处理的是带有路径变量或者正则变量的 path，比如 /demo/post/{list}
         * 所以需要到 radixTree 中按照 demo -> post 逐级查找，如果 post 的 fuzzyChildren 是空的
         * 那么就说明，工程中没有 /demo/post/{list} 的路径映射，直接返回
         *
         * 注意只有 directpath 才会直接注册到 directPathMap 中
         * 对于带有路径变量或者正则变量的 path ，比如 /demo/get/head/{id} 才会注册到 radixTree 中
         * radixTree 中的结果如下：root -> demo(LITERA PathSegment) -> get(LITERA PathSegment) -> head(LITERA PathSegment) -> {id}(VARIABLE PathSegment)
         * 而 LITERA PathSegment 全部在上一级 node 中的 child 中存储，比如 ： root.children -> demo , demo.children -> get , get.children -> head,
         * VARIABLE PathSegment 则是在上一级 node 中的 fuzzyChildren 中存储，比如：head.fuzzyChildren -> {id}
         * */
        matchRecursive(root, path, 1, new HashMap<>(), matches);
    }

    public void match(String path, List<Match<T>> matches) {
        match(new KeyString(path, caseSensitive), matches);
    }

    public List<Match<T>> match(KeyString path) {
        List<Match<T>> matches = directPathMap.get(path);
        if (matches == null) {
            if (root.isLeaf()) {
                return Collections.emptyList();
            }
            matches = new ArrayList<>();
        } else {
            if (root.isLeaf()) {
                return Collections.unmodifiableList(matches);
            }
            matches = new ArrayList<>(matches);
        }

        matchRecursive(root, path, 1, new HashMap<>(), matches);
        return matches;
    }

    public List<Match<T>> match(String path) {
        return match(new KeyString(path, caseSensitive));
    }

    public List<Match<T>> matchRelaxed(String path) {
        KeyString keyPath = new KeyString(path, caseSensitive);
        List<Match<T>> matches = new ArrayList<>();
        match(keyPath, matches);
        if (!matches.isEmpty()) {
            return matches;
        }

        int end = path.length();
        if (end > 1 && path.charAt(end - 1) == '/') {
            match(keyPath.subSequence(0, --end), matches);
            if (!matches.isEmpty()) {
                return matches;
            }
        }

        for (int i = end - 1; i >= 0; i--) {
            char ch = path.charAt(i);
            if (ch == '/') {
                break;
            }
            if (ch == '.') {
                match(keyPath.subSequence(0, i), matches);
                if (!matches.isEmpty()) {
                    return matches;
                }
            }
        }

        return matches;
    }

    /**
     * 示例：/demo/get/muchVariable/{id}/{name} -- /demo/get/muchVariable/345/muchvalue
     *
     * 关于 /demo/get/muchVariable/{id}/{name} 中对于 PathSegment 的解析，see:
     * org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathParser#parseSegments(java.lang.String)
     *
     * 路径变量 variableMap 最终会添加进 Match 中， see:
     * org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree#addMatch(org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.Node, java.util.Map, java.util.List)
     * */
    private void matchRecursive(
            Node<T> current, KeyString path, int start, Map<String, String> variableMap, List<Match<T>> matches) {
        int end = -2;
        // 直到遇到 muchVariable node 的 children 为空
        // id node 的 children 为空
        if (!current.children.isEmpty()) {
            // /demo/post/list 使用 / 将 path 逐级切分，demo , post 递归查找他的 child
            end = path.indexOf(separator, start);
            // 第一次先找 demo 的 child （LITERAL 类型的 PathSegment）

            // 依次获取 demo ,get, muchVariable node （LITERAL 类型的 PathSegment）
            // 直到遇到 muchVariable node 的 children 为空
            Node<T> child = current.children.get(path.subSequence(start, end));
            if (child != null) {
                if (end == -1) {
                    addMatch(child, variableMap, matches);
                } else {
                    // 逐级递归查找当前 child 的下一级
                    matchRecursive(child, path, end + 1, variableMap, matches);
                }
            }
        }
        // VARIABLE 以及 PATTERN 等其他类型的 PathSegment 注册到 current.fuzzyChildren
        // /demo/get/head/{id}
        // see : org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.getChild

        // muchVariable node 的 children 为空，但是 fuzzyChildren 并不为空，里面包含了一个元素
        // key : PathSegment{type=VARIABLE, value=id, variables=[id]}, value: node(id)

        // id node 的 children 为空,同样 fuzzyChildren 里面也包含一个元素
        // key : {type=VARIABLE, value=name, variables=[name]} , value: node(name)
        if (current.fuzzyChildren.isEmpty()) {
            return;
        }
        // 当前 node 的 children 为空， end = -2
        if (end == -2) {
            // [start , end] 之间则为路径变量的值
            // 当前 current 为 name node 时， end = -1 已经到了 path 末尾
            end = path.indexOf(separator, start);
        }
        Map<String, String> workVariableMap = new LinkedHashMap<>();
        for (Map.Entry<PathSegment, Node<T>> entry : current.fuzzyChildren.entrySet()) {
            // {type=VARIABLE, value=id, variables=[id]}
            // {type=VARIABLE, value=name, variables=[name]}
            PathSegment segment = entry.getKey();
            // 对于 VARIABLE 来说，这里会按照路径变量名，path 中提取的对应变量值，加入到 workVariableMap 中
            // key : id , value: 123
            // key : name , value: muchvalue
            if (segment.match(path, start, end, workVariableMap)) {
                // 将 variableMap 中的内容填充到 workVariableMap 中，此时 workVariableMap 包含连个 key value
                // key : id , value: 123
                // key : name , value: muchvalue
                workVariableMap.putAll(variableMap);
                // 获取 id node
                // 获取 name node
                Node<T> child = entry.getValue();
                if (segment.isTailMatching()) {
                    addMatch(child, workVariableMap, matches);
                } else {
                    // 当前 current 为 name node 时， end = -1 已经到了 path 末尾
                    if (end == -1) {
                        // 此时 child 为叶子节点，注册信息全部保存在叶子结点中（node.values）
                        addMatch(child, workVariableMap, matches);
                    } else {
                        matchRecursive(child, path, end + 1, workVariableMap, matches);
                    }
                }
                if (!workVariableMap.isEmpty()) {
                    workVariableMap = new LinkedHashMap<>();
                }
            }
        }
    }

    private static <T> void addMatch(Node<T> node, Map<String, String> variableMap, List<Match<T>> matches) {
        // 对于 fuzzyChildren 的叶子节点来说值为 Pair(PathExpression, Registration)
        // 对于 Children 的叶子节点来说值为 Match
        List<Pair<PathExpression, T>> values = node.values;
        if (values.isEmpty()) {
            if (node.fuzzyChildren.isEmpty()) {
                return;
            }
            for (Entry<PathSegment, Node<T>> entry : node.fuzzyChildren.entrySet()) {
                if (entry.getKey().getType() == Type.WILDCARD_TAIL) {
                    addMatch(entry.getValue(), variableMap, matches);
                }
            }
            return;
        }
        // key : id , value: 123
        // key : name , value: muchvalue
        variableMap = variableMap.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(variableMap);
        for (int i = 0, size = values.size(); i < size; i++) {
            Pair<PathExpression, T> pair = values.get(i);
            matches.add(new Match<>(pair.getLeft(), pair.getRight(), variableMap));
        }
    }

    public void clear() {
        directPathMap.clear();
        root.clear();
    }

    public boolean isEmpty() {
        return directPathMap.isEmpty() && root.isEmpty();
    }

    public static final class Match<T> implements Comparable<Match<T>> {
        // /demo/get/muchVariable/{id}/{name}
        private final PathExpression expression;
        // Registration
        private final T value;
        // 路径变量中的值
        // key : id , value: 123
        // key : name , value: muchvalue
        private final Map<String, String> variableMap;

        Match(PathExpression expression, T value, Map<String, String> variableMap) {
            this.expression = expression;
            this.value = value;
            this.variableMap = variableMap;
        }

        private Match(PathExpression expression, T value) {
            this.expression = expression;
            this.value = value;
            variableMap = Collections.emptyMap();
        }

        public PathExpression getExpression() {
            return expression;
        }

        public T getValue() {
            return value;
        }

        public Map<String, String> getVariableMap() {
            return variableMap;
        }

        @Override
        public int compareTo(Match<T> other) {
            int comparison = expression.compareTo(other.getExpression());
            return comparison == 0 ? variableMap.size() - other.variableMap.size() : comparison;
        }
    }

    private static final class Node<T> {

        private final Map<KeyString, Node<T>> children = new HashMap<>();
        private final Map<PathSegment, Node<T>> fuzzyChildren = new HashMap<>();
        private final List<Pair<PathExpression, T>> values = new ArrayList<>();

        private boolean isLeaf() {
            return children.isEmpty() && fuzzyChildren.isEmpty();
        }

        private boolean isEmpty() {
            return isLeaf() && values.isEmpty();
        }

        private void clear() {
            children.clear();
            fuzzyChildren.clear();
            values.clear();
        }
    }
}
