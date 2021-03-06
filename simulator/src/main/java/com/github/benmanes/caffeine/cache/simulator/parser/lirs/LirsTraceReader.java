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
package com.github.benmanes.caffeine.cache.simulator.parser.lirs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;

/**
 * A reader for the trace files provided by the authors of the LIRS algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LirsTraceReader extends TextTraceReader<Long> {

  public LirsTraceReader(Path filePath) {
    super(filePath);
  }

  @Override
  public Stream<Long> events() throws IOException {
    return lines()
        .map(line -> line.trim())
        .filter(line -> !line.isEmpty())
        .filter(line -> !line.equals("*"))
        .map(Long::parseLong);
  }
}
