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
package com.github.benmanes.caffeine.jcache.event;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.Iterator;

import javax.cache.Cache;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryRemovedListener;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalNotification;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheEvictionListenerTest {
  JCacheEvictionListener<Integer, Integer> listener;
  JCacheStatisticsMXBean statistics;

  @Mock Cache<Integer, Integer> cache;
  @Mock EvictionListener entryListener;

  @BeforeMethod
  public void before() {
    MockitoAnnotations.initMocks(this);
    statistics = new JCacheStatisticsMXBean();
    EventDispatcher<Integer, Integer> dispatcher =
        new EventDispatcher<>(MoreExecutors.directExecutor());
    listener = new JCacheEvictionListener<>(dispatcher, statistics);
    listener.setCache(cache);
    statistics.enable(true);

    dispatcher.register(new MutableCacheEntryListenerConfiguration<Integer, Integer>(
        () -> entryListener, null, false, false));
  }

  @DataProvider
  public Iterator<Object[]> notifications() {
    return Arrays.stream(RemovalCause.values())
        .map(cause -> new RemovalNotification<>(1, new Expirable<>(2, 3), cause))
        .map(notification -> new Object[] { notification })
        .iterator();
  }

  @Test(dataProvider = "notifications")
  public void publishIfEvicted(RemovalNotification<Integer, Expirable<Integer>> notification) {
    listener.delete(notification.getKey(), notification.getValue(), notification.getCause());

    if (notification.wasEvicted()) {
      if (notification.getCause() == RemovalCause.EXPIRED) {
        verify(entryListener).onExpired(any());
      } else {
        verify(entryListener).onRemoved(any());
      }
      assertThat(statistics.getCacheEvictions(), is(1L));
    } else {
      verify(entryListener, never()).onRemoved(any());
      assertThat(statistics.getCacheEvictions(), is(0L));
    }
  }

  interface EvictionListener extends CacheEntryRemovedListener<Integer, Integer>,
      CacheEntryExpiredListener<Integer, Integer> {}
}
