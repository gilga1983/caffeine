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
package com.github.benmanes.caffeine.jcache.configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopyStrategy;
import com.google.common.collect.Iterables;
import com.typesafe.config.ConfigFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TypesafeConfigurationTest {

  @Test
  public void configuration() {
    Optional<CaffeineConfiguration<Integer, Integer>> config =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache");
    assertThat(config.get(), is(equalTo(TypesafeConfigurator.from(
        ConfigFactory.load(), "test-cache").get())));
    assertThat(config.get(), is(not(equalTo(TypesafeConfigurator.from(
        ConfigFactory.load(), "test-cache-2").get()))));
    checkConfig(config.get());
  }

  @Test
  public void cache() {
    Cache<Integer, Integer> cache = Caching.getCachingProvider()
        .getCacheManager().getCache("test-cache");
    assertThat(cache, is(not(nullValue())));
  }

  static void checkConfig(CaffeineConfiguration<?, ?> config) {
    checkStoreByValue(config);
    checkListener(config);

    assertThat(config.getCacheLoaderFactory().create(),
        instanceOf(TestCacheLoader.class));
    assertThat(config.getCacheWriter(), instanceOf(TestCacheWriter.class));
    assertThat(config.isStatisticsEnabled(), is(true));
    assertThat(config.isManagementEnabled(), is(true));

    checkSize(config);
    checkLazyExpiration(config);
    checkEagerExpiration(config);
  }

  static void checkStoreByValue(CaffeineConfiguration<?, ?> config) {
    assertThat(config.isStoreByValue(), is(true));
    assertThat(config.getCopyStrategyFactory().create(),
        instanceOf(JavaSerializationCopyStrategy.class));
  }

  static void checkListener(CaffeineConfiguration<?, ?> config) {
    CacheEntryListenerConfiguration<?, ?> listener = Iterables.getOnlyElement(
        config.getCacheEntryListenerConfigurations());
    assertThat(listener.getCacheEntryListenerFactory().create(),
        instanceOf(TestCacheEntryListener.class));
    assertThat(listener.getCacheEntryEventFilterFactory().create(),
        instanceOf(TestCacheEntryEventFilter.class));
    assertThat(listener.isSynchronous(), is(true));
    assertThat(listener.isOldValueRequired(), is(true));
  }

  static void checkLazyExpiration(CaffeineConfiguration<?, ?> config) {
    ExpiryPolicy expiry = config.getExpiryPolicyFactory().create();
    assertThat(expiry.getExpiryForCreation(), is(Duration.ONE_MINUTE));
    assertThat(expiry.getExpiryForUpdate(), is(Duration.FIVE_MINUTES));
    assertThat(expiry.getExpiryForAccess(), is(Duration.TEN_MINUTES));
  }

  static void checkEagerExpiration(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getExpireAfterWrite().getAsLong(), is(TimeUnit.MINUTES.toNanos(1)));
    assertThat(config.getExpireAfterAccess().getAsLong(), is(TimeUnit.MINUTES.toNanos(5)));
  }

  static void checkSize(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getMaximumSize().isPresent(), is(false));
    assertThat(config.getMaximumWeight().getAsLong(), is(1_000L));
    assertThat(config.getWeigherFactory().create(), instanceOf(TestWeigher.class));
  }
}
