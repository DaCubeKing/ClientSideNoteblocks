package me.dacubeking.clientsidenoteblocks.expiringmap;/*
 * Copyright (c) 2019 Pierantonio Cangianiello
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import me.dacubeking.clientsidenoteblocks.client.ClientSideNoteblocksClient;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * A HashMap which entries expires after the specified life time. The life-time can be defined on a per-key basis, or using a
 * default one, that is passed to the constructor.
 *
 * @param <K> the Key type
 * @param <V> the Value type
 * @author Pierantonio Cangianiello
 */
public class SelfExpiringHashMap<K, V> implements SelfExpiringMap<K, V> {

    private final Map<K, V> internalMap;

    private final Map<K, ExpiringKey<K>> expiringKeys;

    /**
     * Holds the map keys using the given life time for expiration.
     */
    private final DelayQueue<ExpiringKey<?>> delayQueue = new DelayQueue<>();

    /**
     * The default max life time in milliseconds.
     */
    private final long maxLifeTimeMillis;

    public SelfExpiringHashMap() {
        internalMap = new ConcurrentHashMap<K, V>();
        expiringKeys = new WeakHashMap<K, ExpiringKey<K>>();
        this.maxLifeTimeMillis = Long.MAX_VALUE;
    }

    public SelfExpiringHashMap(long defaultMaxLifeTimeMillis) {
        internalMap = new ConcurrentHashMap<K, V>();
        expiringKeys = new WeakHashMap<K, ExpiringKey<K>>();
        this.maxLifeTimeMillis = defaultMaxLifeTimeMillis;
    }

    public SelfExpiringHashMap(long defaultMaxLifeTimeMillis, int initialCapacity) {
        internalMap = new ConcurrentHashMap<K, V>(initialCapacity);
        expiringKeys = new WeakHashMap<K, ExpiringKey<K>>(initialCapacity);
        this.maxLifeTimeMillis = defaultMaxLifeTimeMillis;
    }

    public SelfExpiringHashMap(long defaultMaxLifeTimeMillis, int initialCapacity, float loadFactor) {
        internalMap = new ConcurrentHashMap<K, V>(initialCapacity, loadFactor);
        expiringKeys = new WeakHashMap<K, ExpiringKey<K>>(initialCapacity, loadFactor);
        this.maxLifeTimeMillis = defaultMaxLifeTimeMillis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        cleanup();
        return internalMap.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        cleanup();
        return internalMap.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsKey(Object key) {
        cleanup();
        return internalMap.containsKey(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsValue(Object value) {
        cleanup();
        return internalMap.containsValue(value);
    }

    @Override
    public V get(Object key) {
        cleanup();
        renewKey((K) key);
        return internalMap.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V put(K key, V value) {
        return this.put(key, value, maxLifeTimeMillis);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V put(K key, V value, long lifeTimeMillis) {
        cleanup();
        ExpiringKey delayedKey = new ExpiringKey(key, lifeTimeMillis);
        ExpiringKey oldKey = expiringKeys.put(key, delayedKey);
        if (oldKey != null) {
            expireKey(oldKey);
            expiringKeys.put(key, delayedKey);
        }
        delayQueue.offer(delayedKey);
        return internalMap.put(key, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V remove(Object key) {
        V removedValue = internalMap.remove(key);
        expireKey(expiringKeys.remove(key));
        return removedValue;
    }

    /**
     * Not supported.
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean renewKey(K key) {
        ExpiringKey<K> delayedKey = expiringKeys.get(key);
        if (delayedKey != null) {
            delayedKey.renew();
            return true;
        }
        return false;
    }

    private void expireKey(ExpiringKey<K> delayedKey) {
        if (delayedKey != null) {
            delayedKey.expire();
            cleanup();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        delayQueue.clear();
        expiringKeys.clear();
        internalMap.clear();
    }

    /**
     * Not supported.
     */
    @Override
    public Set<K> keySet() {
        //cleanup();
        return internalMap.keySet();
    }

    /**
     * Not supported.
     */
    @Override
    public Collection<V> values() {
        throw new UnsupportedOperationException();
    }

    /**
     * Not supported.
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException();
    }

    private void cleanup() {
        var delayedKey = delayQueue.poll();
        while (delayedKey != null) {
            var value = internalMap.get(delayedKey.getKey());
            if (ClientSideNoteblocksClient.isDebug() && value instanceof Integer integer) {
                System.out.println("Removing expired key, server sound not played. Unplayed Sounds: " + integer);

            }
            internalMap.remove(delayedKey.getKey());
            expiringKeys.remove(delayedKey.getKey());
            delayedKey = delayQueue.poll();
        }
    }

    private class ExpiringKey<K> implements Delayed {

        private long startTime = System.currentTimeMillis();
        private final long maxLifeTimeMillis;
        private final K key;

        public ExpiringKey(K key, long maxLifeTimeMillis) {
            this.maxLifeTimeMillis = maxLifeTimeMillis;
            this.key = key;
        }

        public K getKey() {
            return key;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final ExpiringKey<K> other = (ExpiringKey<K>) obj;
            return Objects.equals(this.key, other.key);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + (this.key != null ? this.key.hashCode() : 0);
            return hash;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(getDelayMillis(), TimeUnit.MILLISECONDS);
        }

        private long getDelayMillis() {
            return (startTime + maxLifeTimeMillis) - System.currentTimeMillis();
        }

        public void renew() {
            startTime = System.currentTimeMillis();
        }

        public void expire() {
            startTime = Long.MIN_VALUE;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int compareTo(@NotNull Delayed that) {
            return Long.compare(this.getDelayMillis(), ((ExpiringKey) that).getDelayMillis());
        }

    }
}