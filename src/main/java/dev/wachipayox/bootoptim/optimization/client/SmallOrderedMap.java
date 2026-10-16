package dev.wachipayox.bootoptim.optimization.client;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Mutable insertion-ordered map for tiny private parser containers (at most a handful of keys, so
 * lookup is a linear scan). It reproduces {@link java.util.LinkedHashMap}'s observable behaviour
 * for the operations the parser and its callers use: iteration order, null keys and values, live
 * views, fail-fast iterators and mutation from callbacks. Entries keep a stable identity, so
 * iterating does not allocate a wrapper per element.
 *
 * <p>Not thread-safe, exactly like the map it replaces.</p>
 */
public final class SmallOrderedMap<K, V> extends AbstractMap<K, V> {
    private Node<K, V>[] entries;
    private int count;
    private int modCount;
    private Set<Entry<K, V>> view;

    @SuppressWarnings("unchecked")
    public SmallOrderedMap(Map<? extends K, ? extends V> source) {
        entries = (Node<K, V>[]) new Node[source.size()];
        putAll(source);
    }

    private static final class Node<K, V> implements Entry<K, V> {
        final K key;
        V value;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override public K getKey() { return key; }

        @Override public V getValue() { return value; }

        @Override public V setValue(V next) {
            V old = value;
            value = next;
            return old;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Entry<?, ?> e && Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        @Override public int hashCode() { return Objects.hashCode(key) ^ Objects.hashCode(value); }

        @Override public String toString() { return key + "=" + value; }
    }

    private int find(Object key) {
        for (int i = 0; i < count; i++) {
            if (Objects.equals(key, entries[i].key)) {
                return i;
            }
        }
        return -1;
    }

    @Override public int size() { return count; }

    @Override public boolean containsKey(Object key) { return find(key) >= 0; }

    @Override public V get(Object key) {
        int i = find(key);
        return i < 0 ? null : entries[i].value;
    }

    @Override public boolean containsValue(Object value) {
        for (int i = 0; i < count; i++) {
            if (Objects.equals(value, entries[i].value)) {
                return true;
            }
        }
        return false;
    }

    @Override public V put(K key, V value) {
        int i = find(key);
        if (i >= 0) {
            return entries[i].setValue(value);
        }
        if (count == entries.length) {
            entries = Arrays.copyOf(entries, Math.max(count + 1, entries.length + entries.length / 2 + 1));
        }
        entries[count++] = new Node<>(key, value);
        modCount++;
        return null;
    }

    private V removeAt(int i) {
        V old = entries[i].value;
        int moved = count - i - 1;
        if (moved > 0) {
            System.arraycopy(entries, i + 1, entries, i, moved);
        }
        entries[--count] = null;
        modCount++;
        return old;
    }

    @Override public V remove(Object key) {
        int i = find(key);
        return i < 0 ? null : removeAt(i);
    }

    @Override public void clear() {
        Arrays.fill(entries, 0, count, null);
        count = 0;
        modCount++;
    }

    @Override public Set<Entry<K, V>> entrySet() {
        if (view == null) {
            view = new AbstractSet<>() {
                @Override public int size() { return count; }

                @Override public void clear() { SmallOrderedMap.this.clear(); }

                @Override public boolean contains(Object value) {
                    if (!(value instanceof Entry<?, ?> e)) {
                        return false;
                    }
                    int i = find(e.getKey());
                    return i >= 0 && Objects.equals(entries[i].value, e.getValue());
                }

                @Override public boolean remove(Object value) {
                    if (!(value instanceof Entry<?, ?> e)) {
                        return false;
                    }
                    int i = find(e.getKey());
                    if (i < 0 || !Objects.equals(entries[i].value, e.getValue())) {
                        return false;
                    }
                    removeAt(i);
                    return true;
                }

                @Override public Iterator<Entry<K, V>> iterator() {
                    return new Iterator<>() {
                        int cursor;
                        int last = -1;
                        int expected = modCount;

                        @Override public boolean hasNext() { return cursor < count; }

                        @Override public Entry<K, V> next() {
                            if (expected != modCount) {
                                throw new ConcurrentModificationException();
                            }
                            if (!hasNext()) {
                                throw new NoSuchElementException();
                            }
                            last = cursor;
                            return entries[cursor++];
                        }

                        @Override public void remove() {
                            if (expected != modCount) {
                                throw new ConcurrentModificationException();
                            }
                            if (last < 0) {
                                throw new IllegalStateException();
                            }
                            removeAt(last);
                            cursor = last;
                            last = -1;
                            expected = modCount;
                        }
                    };
                }
            };
        }
        return view;
    }

    @Override public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = modCount;
        for (int i = 0; i < count; i++) {
            Node<K, V> e = entries[i];
            action.accept(e.key, e.value);
            if (expected != modCount) {
                throw new ConcurrentModificationException();
            }
        }
    }

    @Override public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = modCount;
        for (int i = 0; i < count; i++) {
            Node<K, V> e = entries[i];
            e.value = function.apply(e.key, e.value);
            if (expected != modCount) {
                throw new ConcurrentModificationException();
            }
        }
    }

    @Override public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        Objects.requireNonNull(function);
        V old = get(key);
        if (old != null) {
            return old;
        }
        int expected = modCount;
        V next = function.apply(key);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (next != null) {
            put(key, next);
        }
        return next;
    }

    @Override public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        V old = get(key);
        if (old == null) {
            return null;
        }
        int expected = modCount;
        V next = function.apply(key, old);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (next == null) {
            remove(key);
        } else {
            put(key, next);
        }
        return next;
    }

    @Override public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        V old = get(key);
        int expected = modCount;
        V next = function.apply(key, old);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (next == null) {
            remove(key);
        } else {
            put(key, next);
        }
        return next;
    }

    @Override public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(function);
        V old = get(key);
        int expected = modCount;
        V next = old == null ? value : function.apply(old, value);
        if (expected != modCount) {
            throw new ConcurrentModificationException();
        }
        if (next == null) {
            remove(key);
        } else {
            put(key, next);
        }
        return next;
    }
}
