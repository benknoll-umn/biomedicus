/*
 * Copyright (c) 2017 Regents of the University of Minnesota.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.umn.biomedicus.common.labels;

import edu.umn.biomedicus.common.types.text.Document;
import edu.umn.biomedicus.common.types.text.Span;

import javax.annotation.Nullable;
import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.PatternSyntaxException;

/**
 *
 */
public class Searcher {
    static final Node ACCEPT = new Node() {
        @Nullable
        @Override
        Class<?> firstType() {
            return null;
        }
    };

    static final int LOOP_LIMIT = 10_000;

    private final Node root;
    private final int numberGroups;
    private final int numberLocals;
    private final Map<String, Integer> groupNames;

    Searcher(Node root,
             int numberGroups,
             int numberLocals,
             Map<String, Integer> groupNames) {
        this.root = root;
        this.numberGroups = numberGroups;
        this.numberLocals = numberLocals;
        this.groupNames = groupNames;
    }

    public static Searcher parse(LabelAliases labelAliases, String pattern) {
        return new Parser(labelAliases, pattern).compile();
    }

    public Search search(Document document) {
        return new DefaultSearch(document, document.getDocumentSpan());
    }

    public Search search(Document document, Span span) {
        return new DefaultSearch(document, span);
    }

    /**
     * Represents a return value of an atomic chain with a head and a tail. The
     * begin value after the tail is the proper matching begin value for the
     * entire chain.
     */
    static class Chain {
        final Node head;
        final Node tail;

        Chain(Node single) {
            head = tail = single;
        }

        Chain(Node head, Node tail) {
            this.head = head;
            this.tail = tail;
        }
    }

    static class Save {
        final int begin;
        final int end;
        final int limit;

        Save(DefaultSearch search) {
            begin = search.lastBegin;
            end = search.lastEnd;
            limit = search.limit;
        }

        void apply(DefaultSearch search) {
            search.lastBegin = begin;
            search.lastEnd = end;
            search.limit = limit;
        }

        void pin(DefaultSearch search) {
            search.limit = search.lastEnd;
            search.lastBegin = search.lastEnd = begin;
        }
    }

    private static class Parser {
        private final LabelAliases labelAliases;
        private final String pattern;
        private final char[] arr;
        private final Map<String, Integer> groupNames = new HashMap<>();
        private final Map<Integer, Class<?>> groupTypes = new HashMap<>();
        private int index = 0;
        private int groupIndex = 0;
        private int localsCount = 0;

        private Parser(LabelAliases labelAliases,
                       String pattern) {
            this.labelAliases = labelAliases;
            this.pattern = pattern;
            char[] charr = pattern.toCharArray();
            char[] tmp = new char[charr.length + 2];
            tmp[tmp.length - 1] = 0;
            tmp[tmp.length - 2] = 0;
            System.arraycopy(charr, 0, tmp, 0, charr.length);
            arr = tmp;
        }

        private Searcher compile() {
            return new Searcher(alts(ACCEPT), groupIndex,
                    localsCount, groupNames);
        }

        private Node alts(Node end) {
            Chain concat = concat(end);
            peekPastWhiteSpace();
            if (!accept('|')) {
                return concat.head;
            }
            Node join = new Noop();
            Branch branch = new Branch();
            if (concat.head == end) {
                branch.add(join);
            } else {
                branch.add(concat.head);
                concat.tail.next = join;
            }
            do {
                Chain next = concat(join);
                branch.add(next.head);
                peekPastWhiteSpace();
            } while (accept('|'));
            return branch;
        }

        private Chain concat(Node end) {
            Node head = null;
            Node tail = null;
            Node node;
            LOOP:
            while (true) {
                int ch = peekPastWhiteSpace();
                switch (ch) {
                    case '(':
                    case '[':
                        Chain chain = ch == '(' ? group() : pinning();
                        node = chain.head;
                        if (node == null) {
                            continue;
                        }
                        if (head == null) {
                            head = node;
                        } else {
                            tail.next = node;
                        }
                        tail = chain.tail;

                        Chain repetition = groupRepetition(head, tail);

                        head = repetition.head;
                        tail = repetition.tail;

                        continue;
                    case '|':
                    case ')':
                    case ']':
                    case '&':
                    case 0:
                        break LOOP;
                    default:
                        node = type(false);
                }

                node = atomicRepetition(node);

                if (head == null) {
                    head = tail = node;
                } else {
                    tail.next = node;
                    tail = node;
                }
            }
            if (head == null) {
                return new Chain(end, end);
            }
            if (head != tail) {
                Node tmp = head.next;
                int local = localsCount++;
                head.next = new SaveBegin(local);
                head.next.next = tmp;

                LoadBegin loadBegin = new LoadBegin(local);
                tail = tail.next = loadBegin;
            }

            return new Chain(head, tail);
        }

        private Chain groupRepetition(Node head, Node tail) {
            int ch = peek();
            switch (ch) {
                case '?':
                    ch = next();
                    if (ch == '+') {
                        next();
                        head = new PossessiveOptional(head);
                        return new Chain(head, head);
                    } else {
                        tail.next = new Noop();
                        tail = tail.next;
                        Branch branch = new Branch();
                        if (ch == '?') {
                            next();
                            branch.add(tail);
                            branch.add(head);
                        } else {
                            branch.add(head);
                            branch.add(tail);
                        }
                        head = branch;
                        return new Chain(head, tail);
                    }
                case '*':
                    ch = next();
                    RecursiveLoop recursiveLoop;
                    if (ch == '+') {
                        next();
                        recursiveLoop = new PossessiveLoop(head, localsCount++,
                                localsCount++, 0, LOOP_LIMIT);
                    } else if (ch == '?') {
                        next();
                        recursiveLoop = new LazyLoop(head, localsCount++,
                                localsCount++, 0, LOOP_LIMIT);
                    } else {
                        next();
                        recursiveLoop = new GreedyLoop(head, localsCount++,
                                localsCount++, 0, LOOP_LIMIT);
                    }
                    return new Chain(new LoopHead(recursiveLoop,
                            recursiveLoop.beginLocal), recursiveLoop);
                case '+':
                    ch = next();
                    if (ch == '+') {
                        next();
                        recursiveLoop = new PossessiveLoop(head, localsCount++,
                                localsCount++, 1, LOOP_LIMIT);
                    } else if (ch == '?') {
                        next();
                        recursiveLoop = new LazyLoop(head, localsCount++,
                                localsCount++, 1, LOOP_LIMIT);
                    } else {
                        next();
                        recursiveLoop = new GreedyLoop(head, localsCount++,
                                localsCount++, 1, LOOP_LIMIT);
                    }
                    return new Chain(new LoopHead(recursiveLoop,
                            recursiveLoop.beginLocal), recursiveLoop);

                case '{':
                    Span span = parseCurlyRange();
                    int min = span.getBegin();
                    int max = span.getEnd();

                    ch = peek();
                    if (ch == '+') {
                        next();
                        recursiveLoop = new PossessiveLoop(head, localsCount++,
                                localsCount++, min, max);
                    } else if (ch == '?') {
                        next();
                        recursiveLoop = new LazyLoop(head, localsCount++,
                                localsCount++, min, max);
                    } else {
                        next();
                        recursiveLoop = new GreedyLoop(head, localsCount++,
                                localsCount++, min, max);
                    }
                    return new Chain(new LoopHead(recursiveLoop,
                            recursiveLoop.beginLocal), recursiveLoop);

                default:
                    return new Chain(head, tail);
            }
        }

        private Node atomicRepetition(Node node) {
            int ch = peek();
            switch (ch) {
                case '?':
                    ch = next();
                    if (ch == '?') {
                        next();
                        node = new LazyOptional(node);
                    } else if (ch == '+') {
                        next();
                        node = new PossessiveOptional(node);
                    } else {
                        node = new GreedyOptional(node);
                    }
                    break;
                case '*':
                    ch = next();
                    RecursiveLoop loop;
                    if (ch == '+') {
                        next();
                        loop = new PossessiveLoop(node, localsCount++,
                                localsCount++, 0, LOOP_LIMIT);
                    } else if (ch == '?') {
                        next();
                        loop = new LazyLoop(node, localsCount++,
                                localsCount++, 0, LOOP_LIMIT);
                    } else {
                        next();
                        loop = new GreedyLoop(node, localsCount++,
                                localsCount++, 0, LOOP_LIMIT);
                    }
                    return new LoopHead(loop, loop.beginLocal);
                case '+':
                    ch = next();
                    if (ch == '+') {
                        next();
                        loop = new PossessiveLoop(node, localsCount++,
                                localsCount++, 1, LOOP_LIMIT);
                    } else if (ch == '?') {
                        next();
                        loop = new LazyLoop(node, localsCount++,
                                localsCount++, 1, LOOP_LIMIT);
                    } else {
                        next();
                        loop = new GreedyLoop(node, localsCount++,
                                localsCount++, 1, LOOP_LIMIT);
                    }
                    return new LoopHead(loop, loop.beginLocal);

                case '{':
                    Span span = parseCurlyRange();
                    int min = span.getBegin();
                    int max = span.getEnd();

                    ch = peek();
                    if (ch == '+') {
                        next();
                        loop = new PossessiveLoop(node, localsCount++,
                                localsCount++, min, max);
                    } else if (ch == '?') {
                        next();
                        loop = new LazyLoop(node, localsCount++,
                                localsCount++, min, max);
                    } else {
                        next();
                        loop = new GreedyLoop(node, localsCount++,
                                localsCount++, min, max);
                    }
                    return new LoopHead(loop, loop.beginLocal);
            }
            return node;
        }

        private Span parseCurlyRange() {
            int ch;
            ch = peekNext();
            if (!Character.isDigit(ch)) {
                throw error(
                        "Curly brackets should be in format {min[,max]}");
            }
            skip();
            int min = 0;
            do {
                min = min * 10 + (ch - '0');
            } while (ch <= '9' && ch >= '0');
            int max = min;
            if (ch == ',') {
                ch = read();
                max = LOOP_LIMIT;
                if (ch != '}') {
                    max = 0;
                    while (ch <= '9' && ch >= '0') {
                        max = max * 10 + (ch - '0');
                        ch = read();
                    }
                }
            }
            if (ch != '}') {
                throw error(
                        "Unclosed curly bracket repetition");
            }
            if (max < min || min < 0 || max < 0) {
                throw error(
                        "Curly bracket repetition illegal range");
            }
            return new Span(min, max);
        }

        private Chain group() {
            Node head;
            Node tail;

            int ch = next();
            if (ch == '?') {
                ch = skip();
                switch (ch) {
                    case '=':
                    case '!':
                        tail = new GroupTail(groupIndex);
                        head = alts(tail);
                        groupIndex = groupIndex + 2;
                        if (ch == '=') {
                            head = tail = new PositiveLookahead(head);
                        } else {
                            head = tail = new NegativeLookahead(head);
                        }
                        break;
                    case '>':
                        tail = new GroupTail(groupIndex);
                        head = alts(tail);
                        groupIndex = groupIndex + 2;
                        head = tail = new Independent(head);
                        break;
                    case '<':
                        String name = readGroupName();
                        if (groupNames.containsKey(name)) {
                            throw error("Duplicate capturing group name: \""
                                    + name + "\"");
                        }
                        tail = new GroupTail(groupIndex);
                        head = alts(tail);
                        groupNames.put(name, groupIndex);
                        groupIndex = groupIndex + 2;
                        break;
                    default:
                        unread();
                        tail = new GroupTail(groupIndex);
                        head = alts(tail);
                        tail.next = new GroupTail(groupIndex);
                        groupIndex = groupIndex + 2;
                        break;
                }
            } else {
                tail = new GroupTail(groupIndex);
                head = alts(tail);
                tail.next = new GroupTail(groupIndex);
                groupIndex = groupIndex + 2;
            }
            peekPastWhiteSpace();
            expect(')', "Unclosed group");

            return new Chain(head, tail);
        }

        private String readGroupName() {
            StringBuilder stringBuilder = new StringBuilder();
            int ch;
            while ((ch = read()) != '>') {
                if (!Character.isAlphabetic(ch) && !Character.isDigit(ch)) {
                    throw error(
                            "Non alphanumeric character in capturing group name");
                }
                stringBuilder.append((char) ch);
            }

            if (stringBuilder.length() == 0) {
                throw error("0-length named capturing gorup");
            }

            return stringBuilder.toString();
        }

        private Chain pinning() {
            int ch = readPastWhitespace();
            boolean seek = false;
            if (ch == '?') {
                seek = true;
                read();
            }

            Node pinned = type(seek);

            if (accept(']')) {
                return new Chain(pinned);
            }

            InnerConditions innerConditions = new InnerConditions(
                    localsCount++);

            do {
                Node inner = alts(ACCEPT);
                innerConditions.addCondition(inner);
                peekPastWhiteSpace();
            } while (accept('&'));

            pinned.next = innerConditions;
            return new Chain(pinned, innerConditions);
        }

        private Node type(boolean seek) {
            TypeMatch node;
            int ch = read();
            if (!Character.isAlphabetic(ch) && !Character.isDigit(ch))
                throw error("Illegal identifier");

            String first = readAlphanumeric(ch);
            String variable = null;
            String type;
            ch = peek();
            if (ch == ':') {
                next();
                variable = first;
                type = readAlphanumeric(ch);
            } else {
                type = first;
            }
            Class<?> aClass = labelAliases.getLabelable(type);
            if (aClass == null) {
                try {
                    aClass = Class.forName(type);
                } catch (ClassNotFoundException e) {
                    throw error("Couldn't find a type with alias or name "
                            + type);
                }
            }
            int group = -1;
            if (variable != null) {
                group = groupIndex++;
                groupTypes.put(group, aClass);
            }
            node = new TypeMatch(aClass, seek, group);
            ch = peek();
            if (ch == '{') {
                next();
                do {
                    parseProperty(node);
                    if (peek() == ',') {
                        read();
                        parseProperty(node);
                    } else {
                        break;
                    }
                } while (true);
            }
            return node;
        }

        String readAlphanumeric(int ch) {
            StringBuilder groupName = new StringBuilder();
            groupName.append((char) ch);
            while (Character.isAlphabetic(ch = peek())
                    || Character.isDigit(ch)) {
                groupName.append((char) ch);
                read();
            }
            return groupName.toString();
        }

        String parseTypeName(int ch) {
            StringBuilder typeName = new StringBuilder();
            typeName.append((char) ch);
            read();
            while (Character.isAlphabetic(ch = peek())
                    || Character.isDigit(ch)) {
                typeName.append((char) ch);
            }

            return typeName.toString();
        }

        String parsePropertyStringValue() {
            StringBuilder vb = new StringBuilder();
            int ch;
            boolean escaped = false;
            while ((ch = read()) != '"' || escaped) {
                if (escaped) {
                    escaped = false;
                    vb.append((char) ch);
                } else if (ch == '\\') {
                    escaped = true;
                } else {
                    vb.append((char) ch);
                }
            }
            return vb.toString();
        }

        Object parseNumber(int ch) {
            StringBuilder nb = new StringBuilder();
            nb.append(ch);
            boolean isDouble = false;
            while (Character.isDigit(ch = peek()) || (!isDouble && ch == '.')) {
                if (ch == '.') {
                    isDouble = true;
                }
                nb.append(ch);
                read();
            }

            String digitString = nb.toString();
            return isDouble ? Double.parseDouble(digitString)
                    : Long.parseLong(digitString);
        }

        String parseBackreferenceGroupName() {
            StringBuilder bsb = new StringBuilder();
            read();
            int ch;
            while (Character.isAlphabetic(ch = peek())
                    || Character.isDigit(ch)) {
                bsb.append(ch);
                read();
            }
            return bsb.toString();
        }

        void parseProperty(TypeMatch typeMatch) {
            StringBuilder pnsb = new StringBuilder();
            int ch;
            while (Character.isAlphabetic(ch = read()) || Character.isDigit(ch))
                pnsb.append(ch);
            if (ch != '=') throw error("Invalid property value format");
            String propertyName = pnsb.toString();
            ch = read();
            if (ch == '"') {
                typeMatch.addPropertyMatch(propertyName,
                        parsePropertyStringValue());
            } else if (Character.isDigit(ch)) {
                typeMatch.addPropertyMatch(propertyName, parseNumber(ch));
            } else if (Character.isAlphabetic(ch)) {
                Object value;
                if (ch == 't' || ch == 'T' || ch == 'y' || ch == 'Y') {
                    value = true;
                } else if (ch == 'f' || ch == 'F' || ch == 'n' || ch == 'N') {
                    value = false;
                } else {
                    throw error("Invalid property value");
                }
                while (Character.isAlphabetic(peek()))
                    read();
                typeMatch.addPropertyMatch(propertyName, value);
            } else if (ch == '$') {
                String backReferenceGroup = parseBackreferenceGroupName();
                ch = peek();
                if (ch == '.') {
                    Integer brGroup = groupNames.get(backReferenceGroup);
                    Class<?> type = groupTypes.get(brGroup);
                    String backPropertyName = parseTypeName(read());
                    try {
                        Method method = type.getMethod(backPropertyName);
                        typeMatch.addPropertyValueBackReference(propertyName,
                                backReferenceGroup, method);
                    } catch (NoSuchMethodException e) {
                        throw error(e.getLocalizedMessage());
                    }
                } else {
                    typeMatch.addSpanBackReference(propertyName,
                            backReferenceGroup);
                }
            } else {
                throw error("Illegal property value");
            }
        }

        PatternSyntaxException error(String desc) {
            return new PatternSyntaxException(desc, pattern, index);
        }

        boolean atEnd() {
            return index == arr.length;
        }

        int read() {
            return arr[index++];
        }

        int readPastWhitespace() {
            int ch;
            do {
                ch = read();
            } while (Character.isWhitespace(ch));
            return ch;
        }

        int peekPastWhiteSpace() {
            int ch;
            while (Character.isWhitespace(ch = peek())) {
                read();
            }
            return ch;
        }

        int peek() {
            return arr[index];
        }

        int peekNext() {
            return arr[index + 1];
        }

        int skip() {
            int ch = arr[index + 1];
            index += 2;
            return ch;
        }

        void unread() {
            index--;
        }

        int next() {
            return arr[++index];
        }

        boolean accept(int ch) {
            if (arr[index] == ch) {
                index = index + 1;
                return true;
            }
            return false;
        }

        boolean expect(int ch, String msg) {
            if (arr[index] != ch) {
                throw error(msg);
            }
            index = index + 1;
            return true;
        }

        boolean consumePastWhiteSpace(int ch) {
            int peek = peekPastWhiteSpace();
            if (peek == ch) {
                read();
                return true;
            } else {
                return false;
            }
        }
    }

    static class Node {
        Node next;

        Node() {
            next = ACCEPT;
        }

        boolean search(DefaultSearch search) {
            return true;
        }

        @Nullable
        Class<?> firstType() {
            return next.firstType();
        }
    }

    static class SaveBegin extends Node {
        final int local;

        SaveBegin(int local) {
            this.local = local;
        }

        @Override
        boolean search(DefaultSearch search) {
            search.locals[local] = search.lastBegin;
            return next.search(search);
        }
    }

    static class LoadBegin extends Node {
        final int local;

        LoadBegin(int local) {
            this.local = local;
        }

        @Override
        public boolean search(DefaultSearch search) {
            search.lastBegin = search.locals[local];
            return next.search(search);
        }
    }

    static class GroupTail extends Node {
        final int groupIndex;

        GroupTail(int groupIndex) {
            this.groupIndex = groupIndex;
        }

        @Override
        boolean search(DefaultSearch search) {
            search.groups[groupIndex] = search.lastBegin;
            search.groups[groupIndex + 1] = search.lastEnd;
            return next.search(search);
        }
    }

    static class Branch extends Node {
        Node[] paths = new Node[2];
        int size = 0;

        void add(Node node) {
            if (size >= paths.length) {
                Node[] tmp = new Node[paths.length * 2];
                System.arraycopy(paths, 0, tmp, 0, paths.length);
                paths = tmp;
            }
            paths[size++] = node;
        }

        @Override
        public boolean search(DefaultSearch search) {
            int begin = search.lastBegin;
            int end = search.lastEnd;
            for (int i = 0; i < size; i++) {
                search.lastBegin = begin;
                search.lastEnd = end;
                if (paths[i].search(search)) {
                    return true;
                }
            }
            return false;
        }
    }

    static class Noop extends Node {
        @Override
        public boolean search(DefaultSearch search) {
            return next.search(search);
        }
    }

    static class PositiveLookahead extends Node {
        Node condition;

        PositiveLookahead(Node condition) {
            this.condition = condition;
        }

        @Override
        boolean search(DefaultSearch search) {
            int begin = search.lastBegin;
            int end = search.lastEnd;
            if (!condition.search(search)) {
                return false;
            }
            search.lastBegin = begin;
            search.lastEnd = end;
            return next.search(search);
        }
    }

    static class NegativeLookahead extends Node {
        Node condition;

        NegativeLookahead(Node condition) {
            this.condition = condition;
        }

        @Override
        boolean search(DefaultSearch search) {
            int begin = search.lastBegin;
            int end = search.lastEnd;

            if (condition.search(search)) {
                return false;
            }
            search.lastBegin = begin;
            search.lastEnd = end;
            return next.search(search);
        }
    }

    static class Independent extends Node {
        final Node node;

        Independent(Node node) {
            this.node = node;
        }

        @Override
        boolean search(DefaultSearch search) {
            return node.search(search) && next.search(search);
        }
    }

    static class GreedyOptional extends Node {
        final Node node;

        GreedyOptional(Node node) {
            this.node = node;
        }

        @Override
        boolean search(DefaultSearch search) {
            Save save = new Save(search);
            if (node.search(search) && next.search(search)) {
                return true;
            }
            save.apply(search);

            return next.search(search);
        }
    }

    static class LazyOptional extends Node {
        final Node node;

        LazyOptional(Node node) {
            this.node = node;
        }

        @Override
        boolean search(DefaultSearch search) {
            Save save = new Save(search);
            if (next.search(search)) {
                return true;
            }
            save.apply(search);

            return node.search(search) && next.search(search);
        }
    }

    static class PossessiveOptional extends Node {
        final Node node;

        PossessiveOptional(Node node) {
            this.node = node;
        }

        @Override
        boolean search(DefaultSearch search) {
            Save save = new Save(search);
            if (!node.search(search)) {
                save.apply(search);
            }
            return next.search(search);
        }
    }

    static abstract class RecursiveLoop extends Node {
        final Node body;
        final int countLocal;
        final int beginLocal;
        final int min, max;

        RecursiveLoop(Node body,
                      int countLocal,
                      int beginLocal,
                      int min,
                      int max) {
            this.body = body;
            this.countLocal = countLocal;
            this.beginLocal = beginLocal;
            this.min = min;
            this.max = max;
        }

        abstract boolean enterLoop(DefaultSearch search);

        @Nullable
        @Override
        Class<?> firstType() {
            return body.firstType();
        }
    }

    static class GreedyLoop extends RecursiveLoop {

        GreedyLoop(Node body,
                   int countLocal,
                   int beginLocal,
                   int min,
                   int max) {
            super(body, countLocal, beginLocal, min, max);
        }

        @Override
        boolean enterLoop(DefaultSearch search) {
            int save = search.locals[countLocal];
            boolean found;
            if (0 < min) {
                search.locals[countLocal] = 1;
                found = body.search(search);
            } else if (0 < max) {
                search.locals[countLocal] = 1;
                found = body.search(search);
                if (!found) {
                    found = next.search(search);
                }
            } else {
                found = next.search(search);
            }

            search.locals[countLocal] = save;
            return found;
        }

        @Override
        boolean search(DefaultSearch search) {
            if (search.lastEnd == search.locals[beginLocal]) {
                // our loop is not actually going anywhere but  theoretically it
                // would match an infinite number of times and then back off
                // to something under the max
                return next.search(search);
            }
            int count = search.locals[countLocal];

            if (count < min) {
                // increment and loop
                search.locals[countLocal] = count + 1;
                boolean bodyFound = body.search(search);
                if (!bodyFound) {
                    // loop failed, de-increment
                    search.locals[countLocal] = count;
                }
                return bodyFound;
            }

            if (count < max) {
                search.locals[countLocal] = count + 1;
                boolean bodyFound = body.search(search);
                if (!bodyFound) {
                    search.locals[countLocal] = count;
                } else {
                    return true;
                }
            }
            return next.search(search);
        }
    }

    static class LazyLoop extends RecursiveLoop {
        LazyLoop(Node body, int countLocal, int beginLocal, int min, int max) {
            super(body, countLocal, beginLocal, min, max);
        }

        @Override
        boolean enterLoop(DefaultSearch search) {
            int save = search.locals[countLocal];
            boolean found = false;
            if (0 < min) {
                search.locals[countLocal] = 1;
                found = body.search(search);
            } else if (next.search(search)) {
                found = true;
            } else if (0 < max) {
                search.locals[countLocal] = 1;
                found = body.search(search);
            }

            search.locals[countLocal] = save;
            return found;
        }

        @Override
        boolean search(DefaultSearch search) {
            if (search.lastEnd == search.locals[beginLocal]) {
                // our loop is not actually going anywhere but theoretically it
                // would match the minimum number of times then find the next.
                return next.search(search);
            }

            int count = search.locals[countLocal];
            if (count < min) {
                search.locals[countLocal] = count + 1;
                boolean found = body.search(search);
                if (!found) {
                    search.locals[countLocal] = count;
                }
                return found;
            }
            if (next.search(search)) {
                return true;
            }
            if (count < max) {
                search.locals[countLocal] = count + 1;
                boolean bodyFound = body.search(search);
                if (!bodyFound) {
                    search.locals[countLocal] = count;
                }
                return bodyFound;
            }

            return false;
        }
    }

    static class PossessiveLoop extends RecursiveLoop {
        PossessiveLoop(Node body,
                       int countLocal,
                       int beginLocal,
                       int min,
                       int max) {
            super(body, countLocal, beginLocal, min, max);
        }

        @Override
        boolean enterLoop(DefaultSearch search) {
            int save = search.locals[countLocal];
            boolean found;
            if (0 < min) {
                search.locals[countLocal] = 1;
                found = body.search(search);
            } else if (0 < max) {
                search.locals[countLocal] = 1;
                found = body.search(search);
                if (!found) {
                    found = next.search(search);
                }
            } else {
                search.locals[countLocal] = 1;
                found = body.search(search);
                found = !found && next.search(search);
            }

            search.locals[countLocal] = save;
            return found;
        }

        @Override
        boolean search(DefaultSearch search) {
            if (search.lastEnd == search.locals[beginLocal]) {
                return next.search(search);
            }
            int count = search.locals[countLocal];

            if (count < min) {
                // increment and loop
                search.locals[countLocal] = count + 1;
                boolean bodyFound = body.search(search);
                if (!bodyFound) {
                    // loop failed, de-increment
                    search.locals[countLocal] = count;
                }
                return bodyFound;
            } else if (count < max) {
                search.locals[countLocal] = count + 1;
                boolean bodyFound = body.search(search);
                if (!bodyFound) {
                    search.locals[countLocal] = count;
                    return next.search(search);
                } else {
                    return true;
                }
            } else {
                search.locals[countLocal] = count + 1;
                boolean bodyFound = body.search(search);
                if (bodyFound) {
                    // possessive over maximum
                    return false;
                } else {
                    search.locals[countLocal] = count;
                    return next.search(search);
                }
            }
        }
    }

    static class LoopHead extends Node {
        private final RecursiveLoop recursiveLoop;
        private final int beginLocal;

        LoopHead(RecursiveLoop recursiveLoop, int beginLocal) {
            this.recursiveLoop = recursiveLoop;
            this.beginLocal = beginLocal;
        }

        @Override
        boolean search(DefaultSearch search) {
            search.locals[beginLocal] = search.lastEnd;
            return recursiveLoop.enterLoop(search);
        }

        @Nullable
        @Override
        Class<?> firstType() {
            return recursiveLoop.firstType();
        }
    }

    static class InnerConditions extends Node {
        final int localAddr;
        Node[] conditions = new Node[2];
        int size = 0;

        InnerConditions(int localAddr) {
            this.localAddr = localAddr;
        }

        @Override
        public boolean search(DefaultSearch search) {
            Save save = new Save(search);

            for (int i = 0; i < size; i++) {
                save.pin(search);
                if (!conditions[i].search(search)) {
                    return false;
                }
            }

            save.apply(search);

            return next.search(search);
        }

        void addCondition(Node node) {
            if (conditions.length == size) {
                Node[] tmp = new Node[conditions.length * 2];
                System.arraycopy(conditions, 0, tmp, 0, size);
                conditions = tmp;
            }
            conditions[size++] = node;
        }
    }

    static class TypeMatch extends Node {
        final Class<?> labelType;
        final List<PropertyMatch> requiredProperties = new ArrayList<>();
        final boolean seek;
        final int group;

        TypeMatch(Class labelType, boolean seek, int group) {
            this.labelType = labelType;
            this.seek = seek;
            this.group = group;
        }

        @Override
        public boolean search(DefaultSearch search) {
            LabelIndex<?> labelIndex = search.document.getLabelIndex(labelType)
                    .insideSpan(new Span(search.lastEnd, search.limit));
            if (!seek) {
                Optional<? extends Label<?>> labelOp = labelIndex.first();
                if (!labelOp.isPresent()) return false;
                Label<?> label = labelOp.get();
                if (!propertiesMatch(search, label)) return false;
                search.lastBegin = label.getBegin();
                search.lastEnd = label.getEnd();
                if (group != -1) {
                    search.groups[group * 2] = label.getBegin();
                    search.groups[group * 2 + 1] = label.getEnd();
                }
                if (next.search(search)) {
                    search.lastBegin = label.getBegin();
                    return true;
                }
            }
            for (Label<?> label : labelIndex) {
                if (!propertiesMatch(search, label)) continue;
                Span span = label.toSpan();
                search.lastBegin = span.getBegin();
                search.lastEnd = span.getEnd();
                if (group != -1) {
                    search.groups[group * 2] = label.getBegin();
                    search.groups[group * 2 + 1] = label.getEnd();
                }
                if (next.search(search)) {
                    search.lastBegin = span.getBegin();
                    return true;
                }
            }
            return false;
        }

        @Nullable
        @Override
        Class<?> firstType() {
            return labelType;
        }

        boolean propertiesMatch(Search search, Label label) {
            for (PropertyMatch requiredProperty : requiredProperties) {
                if (!requiredProperty.doesMatch(search, label)) return false;
            }
            return true;
        }

        void addPropertyMatch(String name, Object value) {
            requiredProperties.add(new ValuedPropertyMatch(name, value));
        }

        void addPropertyValueBackReference(String name,
                                           String group,
                                           Method backrefMethod) {
            requiredProperties.add(new PropertyValueBackReference(name, group,
                    backrefMethod));
        }

        void addSpanBackReference(String name, String group) {
            requiredProperties.add(new SpanBackReference(name, group));
        }

        abstract class PropertyMatch {
            final String name;
            final Method readMethod;

            PropertyMatch(String name) {
                this.name = name;
                try {
                    readMethod = new PropertyDescriptor(name, labelType)
                            .getReadMethod();
                } catch (IntrospectionException e) {
                    throw new IllegalStateException(e);
                }
            }

            abstract boolean doesMatch(Search search, Label<?> label);
        }


        class ValuedPropertyMatch extends PropertyMatch {
            final Object value;

            ValuedPropertyMatch(String name, Object value) {
                super(name);
                this.value = value;
            }

            @Override
            boolean doesMatch(Search search, Label<?> label) {
                try {
                    return value.equals(readMethod.invoke(label.getValue()));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("");
                }
            }
        }

        class PropertyValueBackReference extends PropertyMatch {
            private final String group;
            private final Method backrefMethod;

            PropertyValueBackReference(String name,
                                       String group,
                                       Method backrefMethod) {
                super(name);
                this.group = group;
                this.backrefMethod = backrefMethod;
            }

            @Override
            boolean doesMatch(Search search, Label<?> label) {
                Label<?> groupLabel = search.getLabel(group);
                try {
                    Object value = backrefMethod.invoke(groupLabel.getValue());
                    return value.equals(readMethod.invoke(label.getValue()));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("");
                }
            }
        }

        class SpanBackReference extends PropertyMatch {
            private final String group;

            SpanBackReference(String name, String group) {
                super(name);
                this.group = group;
            }

            @Override
            boolean doesMatch(Search search, Label<?> label) {
                Span span = search.getSpan(group);
                try {
                    return span.equals(readMethod.invoke(label.getValue()));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("");
                }
            }
        }
    }

    /**
     *
     */
    class DefaultSearch implements Search {
        final Document document;
        final Label[] labels;
        final int[] groups;
        final int[] locals;
        boolean found;

        int lastBegin;
        int lastEnd;

        int limit;

        DefaultSearch(Document document, Span span) {
            this.document = document;
            labels = new Label[numberGroups];
            groups = new int[numberGroups];
            locals = new int[numberLocals];
            lastEnd = lastBegin = span.getBegin();
            limit = span.getEnd();
            found = root.search(this);
        }

        @Override
        public Label<?> getLabel(String name) {
            return labels[groupNames.get(name)];
        }

        @Override
        public Span getSpan(String name) {
            Integer integer = groupNames.get(name);
            if (integer == null) {
                throw new IllegalArgumentException("Name not found");
            }
            return new Span(groups[integer * 2], groups[integer * 2 + 1]);
        }

        @Override
        public boolean foundMatch() {
            return found;
        }

        @Override
        public boolean findNext() {
            if (!found) {
                throw new IllegalStateException();
            }

            Arrays.fill(groups, -1);
            Arrays.fill(labels, null);

            return found = root.search(this);
        }

        @Override
        public Span getSpan() {
            return new Span(lastBegin, lastEnd);
        }
    }
}
