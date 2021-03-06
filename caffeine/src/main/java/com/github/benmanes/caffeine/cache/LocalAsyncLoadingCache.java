/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * This class provides a skeletal implementation of the {@link AsyncLoadingCache} interface to
 * minimize the effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class LocalAsyncLoadingCache<C extends LocalCache<K, CompletableFuture<V>>, K, V>
    implements AsyncLoadingCache<K, V> {
  static final Logger logger = Logger.getLogger(LocalAsyncLoadingCache.class.getName());

  final C cache;
  final boolean canBulkLoad;
  final CacheLoader<K, V> loader;
  LoadingCacheView localCacheView;

  @SuppressWarnings("unchecked")
  LocalAsyncLoadingCache(C cache, CacheLoader<? super K, V> loader) {
    this.loader = (CacheLoader<K, V>) loader;
    this.canBulkLoad = canBulkLoad(loader);
    this.cache = cache;
  }

  /** Returns the policy supported by this implementation and its configuration. */
  protected abstract Policy<K, V> policy();

  /** Returns whether the supplied cache loader has bulk load functionality. */
  private static boolean canBulkLoad(CacheLoader<?, ?> loader) {
    try {
      Method loadAll = loader.getClass().getMethod(
          "loadAll", Iterable.class);
      Method asyncLoadAll = loader.getClass().getMethod(
          "asyncLoadAll", Iterable.class, Executor.class);
      return !loadAll.isDefault() || !asyncLoadAll.isDefault();
    } catch (NoSuchMethodException | SecurityException e) {
      logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
      return false;
    }
  }

  @Override
  public CompletableFuture<V> getIfPresent(@Nonnull Object key) {
    return cache.getIfPresent(key, true);
  }

  @Override
  public CompletableFuture<V> get(@Nonnull K key,
      @Nonnull Function<? super K, ? extends V> mappingFunction) {
    requireNonNull(mappingFunction);
    return get(key, (k1, executor) -> CompletableFuture.<V>supplyAsync(
        () -> mappingFunction.apply(key), executor));
  }

  @Override
  public CompletableFuture<V> get(K key,
      BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction) {
    long startTime = cache.statsTicker().read();
    @SuppressWarnings({"unchecked", "rawtypes"})
    CompletableFuture<V>[] result = new CompletableFuture[1];
    CompletableFuture<V> future = cache.computeIfAbsent(key, k -> {
      result[0] = mappingFunction.apply(key, cache.executor());
      if (result[0] == null) {
        cache.statsCounter().recordLoadFailure(cache.statsTicker().read() - startTime);
      }
      return result[0];
    }, true);
    if (result[0] != null) {
      AtomicBoolean completed = new AtomicBoolean();
      result[0].whenComplete((value, error) -> {
        if (!completed.compareAndSet(false, true)) {
          // Ignore multiple invocations due to ForkJoinPool retrying on delays
          return;
        }
        long loadTime = cache.statsTicker().read() - startTime;
        if (value == null) {
          cache.statsCounter().recordLoadFailure(loadTime);
          cache.remove(key, result[0]);
        } else {
          // update the weight and expiration timestamps
          cache.replace(key, result[0], result[0]);
          cache.statsCounter().recordLoadSuccess(loadTime);
        }
      });
    }
    return future;
  }

  @Override
  public CompletableFuture<V> get(K key) {
    return get(key, loader::asyncLoad);
  }

  @Override
  public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
    if (canBulkLoad) {
      return getAllBulk(keys);
    }

    Map<K, CompletableFuture<V>> result = new HashMap<>();
    for (K key : keys) {
      result.put(key, get(key));
    }
    return composeResult(result);
  }

  /** Computes all of the missing entries in a single {@link CacheLoader#asyncLoadAll} call. */
  private CompletableFuture<Map<K, V>> getAllBulk(Iterable<? extends K> keys) {
    Map<K, CompletableFuture<V>> futures = new HashMap<>();
    Map<K, CompletableFuture<V>> proxies = new HashMap<>();
    for (K key : keys) {
      CompletableFuture<V> future = cache.getIfPresent(key, false);
      if (future == null) {
        CompletableFuture<V> proxy = new CompletableFuture<>();
        future = cache.putIfAbsent(key, proxy);
        if (future == null) {
          future = proxy;
          proxies.put(key, proxy);
        }
      }
      futures.put(key, future);
    }
    cache.statsCounter().recordMisses(proxies.size());
    cache.statsCounter().recordHits(futures.size() - proxies.size());
    if (proxies.isEmpty()) {
      return composeResult(futures);
    }

    loader.asyncLoadAll(proxies.keySet(), cache.executor())
        .whenComplete(new AsyncBulkCompleter(proxies));
    return composeResult(futures);
  }

  /**
   * Returns a future that waits for all of the dependent futures to complete and returns the
   * combined mapping if successful. If any future fails then it is automatically removed from
   * the cache if still present.
   */
  private CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
    if (futures.isEmpty()) {
      return CompletableFuture.completedFuture(Collections.emptyMap());
    }
    @SuppressWarnings("rawtypes")
    CompletableFuture<?>[] array = futures.values().toArray(
        new CompletableFuture[futures.size()]);
    return CompletableFuture.allOf(array).thenApply(ignored -> {
      Map<K, V> result = new HashMap<>(futures.size());
      for (Entry<K, CompletableFuture<V>> entry : futures.entrySet()) {
        V value = entry.getValue().getNow(null);
        if (value != null) {
          result.put(entry.getKey(), value);
        }
      }
      return Collections.unmodifiableMap(result);
    });
  }

  @Override
  public void put(K key, CompletableFuture<V> valueFuture) {
    if (valueFuture.isCompletedExceptionally()
        || (valueFuture.isDone() && (valueFuture.join() == null))) {
      cache.statsCounter().recordLoadFailure(0L);
      cache.remove(key);
      return;
    }
    AtomicBoolean completed = new AtomicBoolean();
    long startTime = cache.statsTicker().read();
    cache.put(key, valueFuture);
    valueFuture.whenComplete((value, error) -> {
      if (!completed.compareAndSet(false, true)) {
        // Ignore multiple invocations due to ForkJoinPool retrying on delays
        return;
      }
      long loadTime = cache.statsTicker().read() - startTime;
      if (value == null) {
        cache.remove(key, valueFuture);
        cache.statsCounter().recordLoadFailure(loadTime);
      } else {
        // update the weight and expiration timestamps
        cache.replace(key, valueFuture, valueFuture);
        cache.statsCounter().recordLoadSuccess(loadTime);
      }
    });
  }

  @Override
  public LoadingCache<K, V> synchronous() {
    return (localCacheView == null) ? (localCacheView = new LoadingCacheView()) : localCacheView;
  }

  /** A function executed asynchronously after a bulk load completes. */
  private final class AsyncBulkCompleter implements BiConsumer<Map<K, V>, Throwable> {
    private final Map<K, CompletableFuture<V>> proxies;
    private final long startTime;

    AsyncBulkCompleter(Map<K, CompletableFuture<V>> proxies) {
      this.startTime = cache.statsTicker().read();
      this.proxies = proxies;
    }

    @Override
    public void accept(Map<K, V> result, Throwable error) {
      long loadTime = cache.statsTicker().read() - startTime;

      if (result == null) {
        if (error == null) {
          error = new CompletionException("null map", null);
        }
        for (Entry<K, CompletableFuture<V>> entry : proxies.entrySet()) {
          cache.remove(entry.getKey(), entry.getValue());
          entry.getValue().obtrudeException(error);
        }
        cache.statsCounter().recordLoadFailure(loadTime);
      } else {
        fillProxies(result);
        addNewEntries(result);
        cache.statsCounter().recordLoadSuccess(result.size());
      }
    }

    /** Populates the proxies with the computed result. */
    private void fillProxies(Map<K, V> result) {
      for (Entry<K, CompletableFuture<V>> proxy : proxies.entrySet()) {
        V value = result.get(proxy.getKey());
        proxy.getValue().obtrudeValue(value);
        if (value == null) {
          cache.remove(proxy.getKey(), proxy.getValue());
        } else {
          // update the weight and expiration timestamps
          cache.replace(proxy.getKey(), proxy.getValue(), proxy.getValue());
        }
      }
    }

    /** Adds to the cache any extra entries computed that were not requested. */
    private void addNewEntries(Map<K, V> result) {
      if (proxies.size() == result.size()) {
        return;
      }
      for (Entry<K, V> entry : result.entrySet()) {
        if (!proxies.containsKey(entry.getKey())) {
          cache.put(entry.getKey(), CompletableFuture.completedFuture(entry.getValue()));
        }
      }
    }
  }

  /* ---------------- Synchronous views -------------- */

  final class LoadingCacheView implements LoadingCache<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    transient AsMapView<K, V> asMapView;

    /** A test-only method for validation. */
    LocalAsyncLoadingCache<C, K, V> getOuter() {
      return LocalAsyncLoadingCache.this;
    }

    @Override
    public V getIfPresent(Object key) {
      CompletableFuture<V> future = cache.getIfPresent(key, true);
      return Async.getIfReady(future);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      int hits = 0;
      int misses = 0;
      Map<K, V> result = new LinkedHashMap<>();
      for (Object key : keys) {
        CompletableFuture<V> future = cache.get(key);
        V value = Async.getIfReady(future);
        if (value == null) {
          misses++;
        } else {
          hits++;
          @SuppressWarnings("unchecked")
          K castKey = (K) key;
          result.put(castKey, value);
        }
      }
      cache.statsCounter().recordHits(hits);
      cache.statsCounter().recordMisses(misses);
      return Collections.unmodifiableMap(result);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      CompletableFuture<V> future = LocalAsyncLoadingCache.this.get(key, (k, executor) ->
          CompletableFuture.supplyAsync(() -> mappingFunction.apply(key), executor));
      try {
        return future.get();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        } else if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw new CompletionException(e.getCause());
      } catch (InterruptedException e) {
        throw new CompletionException(e);
      }
    }

    @Override
    public V get(K key) {
      try {
        return LocalAsyncLoadingCache.this.get(key).get();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        } else if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw new CompletionException(e.getCause());
      } catch (InterruptedException e) {
        throw new CompletionException(e);
      }
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      try {
        return LocalAsyncLoadingCache.this.getAll(keys).get();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        } else if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw new CompletionException(e.getCause());
      } catch (InterruptedException e) {
        throw new CompletionException(e);
      }
    }

    @Override
    public void put(K key, V value) {
      requireNonNull(value);
      cache.put(key, CompletableFuture.completedFuture(value));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
        put(entry.getKey(), entry.getValue());
      }
    }

    @Override
    public void invalidate(Object key) {
      cache.remove(key);
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      cache.invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
      cache.clear();
    }

    @Override
    public long estimatedSize() {
      return cache.size();
    }

    @Override
    public CacheStats stats() {
      return cache.statsCounter().snapshot();
    }

    @Override
    public void cleanUp() {
      cache.cleanUp();
    }

    @Override
    public void refresh(K key) {
      requireNonNull(key);

      BiFunction<K, CompletableFuture<V>, CompletableFuture<V>> refreshFunction =
          (k, oldValueFuture) -> {
            V oldValue = null;
            try {
              oldValue = (oldValueFuture == null) ? null : oldValueFuture.get();
            } catch (InterruptedException e) {
              throw new CompletionException(e);
            } catch (ExecutionException e) {}
            V newValue = (oldValue == null) ? loader.load(key) : loader.reload(key, oldValue);
            return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
          };
      cache.executor().execute(() -> {
        try {
          cache.compute(key, refreshFunction, false, false);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during refresh", t);
        }
      });
    }

    @Override
    public Policy<K, V> policy() {
      return getOuter().policy();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      if (asMapView == null) {
        asMapView = new AsMapView<K, V>(cache);
      }
      return asMapView;
    }
  }

  static final class AsMapView<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    final LocalCache<K, CompletableFuture<V>> delegate;

    Collection<V> values;
    Set<Entry<K, V>> entries;

    AsMapView(LocalCache<K, CompletableFuture<V>> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public boolean containsKey(Object key) {
      return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      requireNonNull(value);

      for (CompletableFuture<V> valueFuture : delegate.values()) {
        if (value.equals(Async.getIfReady(valueFuture))) {
          return true;
        }
      }
      return false;
    }

    @Override
    public V get(Object key) {
      return Async.getIfReady(delegate.get(key));
    }

    @Override
    public V putIfAbsent(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> valueFuture =
          delegate.putIfAbsent(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(valueFuture);
    }

    @Override
    public V put(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.put(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public V remove(Object key) {
      CompletableFuture<V> oldValueFuture = delegate.remove(key);
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public V replace(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.replace(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      requireNonNull(oldValue);
      requireNonNull(newValue);
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      return oldValue.equals(Async.getIfReady(oldValueFuture))
          ? delegate.replace(key, oldValueFuture, CompletableFuture.completedFuture(newValue))
          : false;
    }

    @Override
    public boolean remove(Object key, Object value) {
      requireNonNull(key);
      if (value == null) {
        return false;
      }
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      return value.equals(Async.getIfReady(oldValueFuture))
          ? delegate.remove(key, oldValueFuture)
          : false;
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfAbsent(key, k -> {
        V newValue = mappingFunction.apply(key);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Async.getWhenSuccessful(valueFuture);
    }

    @Override
    public V computeIfPresent(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfPresent(key, (k, oldValueFuture) -> {
        V oldValue = Async.getWhenSuccessful(oldValueFuture);
        if (oldValue == null) {
          return null;
        }
        V newValue = remappingFunction.apply(key, oldValue);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Async.getWhenSuccessful(valueFuture);
    }

    @Override
    public V compute(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      long startTime = delegate.statsTicker().read();
      CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
        V oldValue = Async.getWhenSuccessful(oldValueFuture);
        V newValue = remappingFunction.apply(key, oldValue);
        long loadTime = delegate.statsTicker().read() - startTime;
        if (newValue == null) {
          delegate.statsCounter().recordLoadFailure(loadTime);
          return null;
        }
        delegate.statsCounter().recordLoadSuccess(loadTime);
        return CompletableFuture.completedFuture(newValue);
      }, false, true);
      return Async.getWhenSuccessful(valueFuture);
    }

    @Override
    public V merge(K key, V value,
        BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      requireNonNull(value);
      requireNonNull(remappingFunction);
      CompletableFuture<V> mergedValueFuture = delegate.merge(
          key, CompletableFuture.completedFuture(value), (oldValueFuture, valueFuture) -> {
        V oldValue = Async.getWhenSuccessful(oldValueFuture);
        if (oldValue == null) {
          return valueFuture;
        }
        V newValue = remappingFunction.apply(oldValue, value);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Async.getWhenSuccessful(mergedValueFuture);
    }

    @Override
    public Set<K> keySet() {
      return delegate.keySet();
    }

    @Override
    public Collection<V> values() {
      return (values == null) ? (values = new Values()) : values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return (entries == null) ? (entries = new EntrySet()) : entries;
    }

    private final class Values extends AbstractCollection<V> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public boolean contains(Object o) {
        return AsMapView.this.containsValue(o);
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public Iterator<V> iterator() {
        return new Iterator<V>() {
          Iterator<Entry<K, V>> iterator = entrySet().iterator();

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public V next() {
            return iterator.next().getValue();
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }

    private final class EntrySet extends AbstractSet<Entry<K, V>> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public boolean contains(Object o) {
        if (!(o instanceof Entry<?, ?>)) {
          return false;
        }
        Entry<?, ?> entry = (Entry<?, ?>) o;
        V value = AsMapView.this.get(entry.getKey());
        return (value != null) && value.equals(entry.getValue());
      }

      @Override
      public boolean remove(Object obj) {
        if (!(obj instanceof Entry<?, ?>)) {
          return false;
        }
        Entry<?, ?> entry = (Entry<?, ?>) obj;
        return AsMapView.this.remove(entry.getKey(), entry.getValue());
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
        return new Iterator<Entry<K, V>>() {
          Iterator<Entry<K, CompletableFuture<V>>> iterator = delegate.entrySet().iterator();
          Entry<K, V> cursor;
          K removalKey;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              Entry<K, CompletableFuture<V>> entry = iterator.next();
              V value = Async.getIfReady(entry.getValue());
              if (value != null) {
                cursor = new WriteThroughEntry<>(AsMapView.this, entry.getKey(), value);
              }
            }
            return (cursor != null);
          }

          @Override
          public Entry<K, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            removalKey = cursor.getKey();
            Entry<K, V> entry = cursor;
            cursor = null;
            return entry;
          }

          @Override
          public void remove() {
            Caffeine.requireState(removalKey != null);
            delegate.remove(removalKey);
            removalKey = null;
          }
        };
      }
    }
  }
}
