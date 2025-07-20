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
package org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition;

import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.protocol.tri.rest.Messages;
import org.apache.dubbo.rpc.protocol.tri.rest.PathParserException;
import org.apache.dubbo.rpc.protocol.tri.rest.RestConstants;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathSegment.Type;
import org.apache.dubbo.rpc.protocol.tri.rest.util.PathUtils;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * See
 * <p>
 * <a href="https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html#mvc-ann-requestmapping-uri-templates">Spring uri templates</a>
 * <br/>
 * <a href="https://docs.jboss.org/resteasy/docs/6.2.7.Final/userguide/html/ch04.html">Path and regular expression mappings</a>
 * </p>
 */
final class PathParser {

    private static final PathSegment SLASH = new PathSegment(Type.SLASH, RestConstants.SLASH);

    private final List<PathSegment> segments = new LinkedList<>();
    private final StringBuilder buf = new StringBuilder();

    /**
     * Ensure that the path is normalized using {@link PathUtils#normalize(String)} before parsing.
     */
    static PathSegment[] parse(String path) {
        if (path == null || path.isEmpty() || RestConstants.SLASH.equals(path)) {
            return new PathSegment[] {PathSegment.literal(RestConstants.SLASH)};
        }
        // 直接路径，不带 PathVariable 以及正则表达式的路径
        if (PathUtils.isDirectPath(path)) {
            // DirectPath 一般都是一个 PathSegment (LITERAL)
            return new PathSegment[] {PathSegment.literal(path)};
        }
        // 带有 PathVariable 或者正则表达式的 path 就会对应多个 PathSegment
        // 字符的 PathSegment 类型为 LITERAL
        // PathVariable 的 PathSegment 类型为 VARIABLE
        // PATTERN 的 PathSegment 类型为 PATTERN
        List<PathSegment> segments = new PathParser().doParse(path);
        return segments.toArray(new PathSegment[0]);
    }

    private List<PathSegment> doParse(String path) {
        parseSegments(path);
        // 对于 PATTERN segment 来说，这里会将多个 PATTERN segment 转化为一个 PATTERN segment（按照 path 变量来）
        // {type=PATTERN, value=(?<name>[a-z-]+)-(?<version>\d\.\d\.\d)(?<ext>\.[a-z]+), variables=[name, version, ext]}
        // 去掉 SLASH segment
        transformSegments(segments, path);
        for (PathSegment segment : segments) {
            try {
                segment.initPattern();
            } catch (Exception e) {
                throw new PathParserException(Messages.REGEX_PATTERN_INVALID, segment.getValue(), path, e);
            }
        }
        return segments;
    }

    /**
     *
     * /demo/get/head/{id}
     *
     * segment 创建完成之后会跟着一个 SLASH segment
     * 最后一个 segment 之后没有 SLASH segment
     *
     * /demo/get/reg/{name:[a-z-]+}-{version:\d\.\d\.\d}{ext:\.[a-z]+}
     *
     *
     *
     * */
    private void parseSegments(String path) {
        int state = State.INITIAL;
        boolean regexBraceStart = false;
        boolean regexMulti = false;
        // 用于暂时存放 REGEX 变量名，如 name ， version ， ext
        String variableName = null;
        int len = path.length();
        for (int i = 0; i < len; i++) {
            char c = path.charAt(i);
            switch (c) {
                case '/':
                    switch (state) {
                        case State.INITIAL:
                        case State.SEGMENT_END:
                            // 初始状态会遇到 / 直接跳过
                            continue;
                        case State.LITERAL_START:
                            if (buf.length() > 0) {
                                // /demo 正常字符匹配玩之后，此时 buf 中存放的是 demo
                                // 当遇到下一个 / 的时候，需要把 demo 添加到 LITERAL Segment
                                // 创建完一个 segment 之后跳转到 default 分支
                                // 然后创建 SLASH segment ，转化 State.SEGMENT_END
                                appendSegment(Type.LITERAL);
                            }
                            // VARIABLE segment 以及 PATTERN segment 创建完成之后，状态会设置为 LITERAL_START ，但此时 buf 是空的
                            // 继续下一个字符匹配
                            break;
                        case State.WILDCARD_START:
                            appendSegment(Type.WILDCARD);
                            break;
                        case State.REGEX_VARIABLE_START:
                            if (path.charAt(i - 1) != '^' || path.charAt(i - 2) != '[') {
                                regexMulti = true;
                            }
                            buf.append(c);
                            continue;
                        case State.VARIABLE_START:
                        case State.WILDCARD_VARIABLE_START:
                            throw new PathParserException(Messages.MISSING_CLOSE_CAPTURE, path, i);
                        default:
                    }
                    // 每创建完一个 segment , 就在后面创建一个 SLASH segment，然后设置 State.SEGMENT_END
                    // 接着匹配下一个字符
                    segments.add(SLASH);
                    state = State.SEGMENT_END;
                    continue;
                case '?':
                    switch (state) {
                        case State.INITIAL:
                        case State.LITERAL_START:
                        case State.SEGMENT_END:
                            state = State.WILDCARD_START;
                            break;
                        default:
                    }
                    break;
                case '*':
                    switch (state) {
                        case State.INITIAL:
                        case State.LITERAL_START:
                        case State.SEGMENT_END:
                            state = State.WILDCARD_START;
                            break;
                        case State.VARIABLE_START:
                            if (path.charAt(i - 1) == '{') {
                                state = State.WILDCARD_VARIABLE_START;
                                continue;
                            }
                            break;
                        default:
                    }
                    break;
                case '.':
                    if (state == State.REGEX_VARIABLE_START) {
                        if (path.charAt(i - 1) != '\\') {
                            regexMulti = true;
                        }
                    }
                    break;
                case 'S':
                case 'W':
                    if (state == State.REGEX_VARIABLE_START) {
                        if (path.charAt(i - 1) == '\\') {
                            regexMulti = true;
                        }
                    }
                    break;
                case ':': // // {name:[a-z-]+}-{version:\d\.\d\.\d}{ext:\.[a-z]+}
                    // 此时 buf 中是 name , State.VARIABLE_START
                    if (state == State.VARIABLE_START) {
                        // 遇到 : 字符状态变为 REGEX_VARIABLE_START
                        state = State.REGEX_VARIABLE_START;
                        // buf 中存放的事 REGEX 变量名 name , versersion , ext
                        variableName = buf.toString();
                        buf.setLength(0);
                        // 往后继续提取正则表达式
                        continue;
                    }
                    break;
                case '{':
                    // /demo/get/head/{id}
                    // 当遇到 { 的时候，开始创建 VARIABLE segment

                    // {name:[a-z-]+}-{version:\d\.\d\.\d}{ext:\.[a-z]+}
                    // 遇到 { 也有可能是 PATTERN segment, 但还是会按照 VARIABLE segment 来提取 name ， version ， ext
                    // 直到遇到 : 字符 标志开始提取 PATTERN segment
                    switch (state) {
                        case State.INITIAL:
                        case State.SEGMENT_END:
                            // 开始提取 path variable
                            // 遇到 { 也有可能是 PATTERN segment, 但还是会按照 VARIABLE segment 来提取 name ， version ， ext
                            // 直到遇到 : 字符 标志开始提取 PATTERN segment
                            state = State.VARIABLE_START;
                            continue; // 继续，开始匹配 id 作为 path variable 名称
                        case State.LITERAL_START:
                            if (buf.length() > 0) {
                                // 上述 PATTERN path 中的 - 会被创建成一个 LITERAL segment
                                appendSegment(Type.LITERAL);
                            }
                            // 重新开始提取下一个 PATTERN 变量名，直到遇到 : 字符在进行提取正则表达式
                            state = State.VARIABLE_START;
                            continue;
                        case State.VARIABLE_START:
                        case State.WILDCARD_VARIABLE_START:
                            throw new PathParserException(Messages.ILLEGAL_NESTED_CAPTURE, path, i);
                        case State.REGEX_VARIABLE_START:
                            if (path.charAt(i - 1) != '\\') {
                                regexBraceStart = true;
                            }
                            break;
                        default:
                    }
                    break;
                case '}': // /demo/get/head/{id}
                    switch (state) {
                        case State.INITIAL:
                        case State.LITERAL_START:
                        case State.SEGMENT_END:
                            throw new PathParserException(Messages.MISSING_OPEN_CAPTURE, path);
                        case State.VARIABLE_START:
                            // 当遇到 } 时 ，表示 path 变量的结束位置，此时 buf 中存放的就是 path 变量名称 (id)
                            // 为 path 变量创建 VARIABLE segment
                            appendSegment(Type.VARIABLE, buf.toString());
                            // 状态变为 LITERAL_START
                            state = State.LITERAL_START;
                            continue;
                        case State.REGEX_VARIABLE_START:
                            // {name:[a-z-]+}-{version:\d\.\d\.\d}{ext:\.[a-z]+}
                            // 遇到 } 字符，此时 name 后面的正则表达式（[a-z-]+）已经提取到 buf 中
                            if (regexBraceStart) {
                                regexBraceStart = false;
                            } else {
                                if (buf.length() == 0) {
                                    throw new PathParserException(Messages.MISSING_REGEX_CONSTRAINT, path, i);
                                }
                                appendSegment(regexMulti ? Type.PATTERN_MULTI : Type.PATTERN, variableName);
                                regexMulti = false;
                                // 状态变为 LITERAL_START
                                state = State.LITERAL_START;
                                continue;
                            }
                            break;
                        case State.WILDCARD_VARIABLE_START:
                            appendSegment(Type.WILDCARD_TAIL, buf.toString());
                            state = State.END;
                            continue;
                        default:
                    }
                    break;
                default:
                    // 正常字符会来到这里
                    if (state == State.INITIAL || state == State.SEGMENT_END) {
                        // INITIAL 状态下，遇到第一个正常字符，会设置 State.LITERAL_STAR
                        // SEGMENT_END 状态下，表示刚刚创建完一个 segment (/demo)
                        // 遇到新的字符（get）, 准备为 get 创建 segment
                        state = State.LITERAL_START;
                    }
                    break;
            }
            if (state == State.END) {
                throw new PathParserException(Messages.NO_MORE_DATA_ALLOWED, path, i);
            }
            // 将字符放入 buf 中用于提取 pathSegment
            // 如果当前是 State.VARIABLE_START ， 那么就将 path 变量名称加入到 buf 中
            // 当遇到下一个 } 时，将 buf 中存放的 path 变量名称放入到

            // /demo/get/reg/{name:[a-z-]+}-{version:\d\.\d\.\d}{ext:\.[a-z]+}
            // 正则表达式，比如 name 的 [a-z-]+ 也会被认为是正常字符
            // buf 可以用于暂时收集正则表达式， 直到遇到 } 字符
            buf.append(c);
        }

        if (buf.length() > 0) {
            switch (state) {
                case State.LITERAL_START:
                    appendSegment(Type.LITERAL);
                    break;
                case State.WILDCARD_START:
                    appendSegment(Type.WILDCARD);
                    break;
                case State.VARIABLE_START:
                case State.REGEX_VARIABLE_START:
                case State.WILDCARD_VARIABLE_START:
                    throw new PathParserException(Messages.MISSING_CLOSE_CAPTURE, path, len - 1);
                default:
            }
        }
    }

    private void appendSegment(Type type) {
        segments.add(new PathSegment(type, buf.toString()));
        buf.setLength(0);
    }

    private void appendSegment(Type type, String name) {
        segments.add(new PathSegment(type, buf.toString().trim(), name.trim()));
        buf.setLength(0);
    }

    private static void transformSegments(List<PathSegment> segments, String path) {
        ListIterator<PathSegment> iterator = segments.listIterator();
        PathSegment curr, prev = null;
        while (iterator.hasNext()) {
            curr = iterator.next();
            String value = curr.getValue();
            Type type = curr.getType();
            switch (type) {
                case SLASH:
                    if (prev != null) {
                        switch (prev.getType()) {
                            case LITERAL:
                            case VARIABLE:
                            case PATTERN:
                                prev = curr;
                                break;
                            case PATTERN_MULTI:
                                if (!".*".equals(prev.getValue())) {
                                    prev.setValue(prev.getValue() + '/');
                                }
                                break;
                            default:
                        }
                    }
                    iterator.remove();
                    continue;
                case WILDCARD:
                    if ("*".equals(value)) {
                        type = Type.VARIABLE;
                        value = StringUtils.EMPTY_STRING;
                    } else if ("**".equals(value)) {
                        if (!iterator.hasNext()) {
                            type = Type.WILDCARD_TAIL;
                            value = StringUtils.EMPTY_STRING;
                        } else {
                            type = Type.PATTERN_MULTI;
                            value = ".*";
                        }
                    } else {
                        type = Type.PATTERN;
                        value = toRegex(value);
                    }
                    curr.setType(type);
                    curr.setValue(value);
                    break;
                case WILDCARD_TAIL:
                    break;
                case PATTERN:
                case PATTERN_MULTI:
                    curr.setValue("(?<" + curr.getVariable() + '>' + value + ')');
                    break;
                default:
            }
            if (prev == null) {
                prev = curr;
                continue;
            }
            String pValue = prev.getValue();
            switch (prev.getType()) {
                case LITERAL:
                    switch (type) {
                        case VARIABLE:
                            prev.setType(Type.PATTERN);
                            prev.setValue(quoteRegex(pValue) + "(?<" + curr.getVariable() + ">[^/]+)");
                            prev.setVariables(curr.getVariables());
                            iterator.remove();
                            continue;
                        case PATTERN:
                        case PATTERN_MULTI:
                            prev.setType(type);
                            prev.setValue(quoteRegex(pValue) + "(?<" + curr.getVariable() + '>' + value + ')');
                            prev.setVariables(curr.getVariables());
                            iterator.remove();
                            continue;
                        default:
                    }
                    break;
                case VARIABLE:
                    switch (type) {
                        case LITERAL:
                            prev.setType(Type.PATTERN);
                            prev.setValue("(?<" + prev.getVariable() + ">[^/]+)" + quoteRegex(value));
                            iterator.remove();
                            continue;
                        case VARIABLE:
                            throw new PathParserException(Messages.ILLEGAL_DOUBLE_CAPTURE, path);
                        case PATTERN:
                        case PATTERN_MULTI:
                            String var = curr.getVariable();
                            prev.addVariable(var);
                            prev.setType(type);
                            prev.setValue("(?<" + prev.getVariable() + ">[^/]+)(?<" + var + '>' + value + ')');
                            iterator.remove();
                            continue;
                        default:
                    }
                    break;
                case PATTERN:
                case PATTERN_MULTI:
                    switch (type) {
                        case LITERAL:
                            prev.setValue(pValue + quoteRegex(value));
                            iterator.remove();
                            continue;
                        case WILDCARD_TAIL:
                            if (curr.getVariables() == null) {
                                prev.setValue(pValue + ".*");
                            } else {
                                prev.addVariable(curr.getVariable());
                                prev.setValue(pValue + "(?<" + curr.getVariable() + ">.*)");
                            }
                            prev.setType(Type.PATTERN_MULTI);
                            iterator.remove();
                            continue;
                        case VARIABLE:
                            if (value.isEmpty()) {
                                prev.setValue(pValue + "[^/]+");
                                iterator.remove();
                                continue;
                            }
                            prev.addVariable(curr.getVariable());
                            prev.setValue(pValue + "(?<" + curr.getVariable() + ">[^/]+)");
                            iterator.remove();
                            continue;
                        case PATTERN_MULTI:
                            prev.setType(Type.PATTERN_MULTI);
                        case PATTERN:
                            if (curr.getVariables() == null) {
                                prev.setValue(pValue + value);
                            } else {
                                prev.addVariable(curr.getVariable());
                                prev.setValue(pValue + "(?<" + curr.getVariable() + '>' + value + ')');
                            }
                            iterator.remove();
                            continue;
                        default:
                    }
                    break;
                default:
            }
            prev = curr;
        }
    }

    private static String quoteRegex(String regex) {
        for (int i = 0, len = regex.length(); i < len; i++) {
            switch (regex.charAt(i)) {
                case '(':
                case ')':
                case '[':
                case ']':
                case '$':
                case '^':
                case '.':
                case '{':
                case '}':
                case '|':
                case '\\':
                    return "\\Q" + regex + "\\E";
                default:
            }
        }
        return regex;
    }

    private static String toRegex(String wildcard) {
        int len = wildcard.length();
        StringBuilder sb = new StringBuilder(len + 8);
        for (int i = 0; i < len; i++) {
            char c = wildcard.charAt(i);
            switch (c) {
                case '*':
                    if (i > 0) {
                        char prev = wildcard.charAt(i - 1);
                        if (prev == '*') {
                            continue;
                        }
                        if (prev == '?') {
                            sb.append("*");
                            continue;
                        }
                    }
                    sb.append("[^/]*");
                    break;
                case '?':
                    if (i > 0 && wildcard.charAt(i - 1) == '*') {
                        continue;
                    }
                    sb.append("[^/]");
                    break;
                case '(':
                case ')':
                case '$':
                case '.':
                case '{':
                case '}':
                case '|':
                case '\\':
                    sb.append('\\');
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private interface State {

        int INITIAL = 0;
        int LITERAL_START = 1;
        int WILDCARD_START = 2;
        int VARIABLE_START = 3;
        int REGEX_VARIABLE_START = 4;
        int WILDCARD_VARIABLE_START = 5;
        int SEGMENT_END = 6;
        int END = 7;
    }
}
