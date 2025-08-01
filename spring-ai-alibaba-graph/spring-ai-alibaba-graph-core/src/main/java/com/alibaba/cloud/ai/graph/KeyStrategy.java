/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.graph;

import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.MergeStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.function.BiFunction;

/**
 * Strategy interface for generating cache keys based on input parameters and the overall
 * state. This interface extends BiFunction, taking two generic input parameters to
 * produce a resulting key. The key generation logic can be affected by both input
 * parameters and the global state of the system, making it adaptable for various caching
 * scenarios within the project.
 *
 * @author disaster
 * @since 1.0.0.1
 */
public interface KeyStrategy extends BiFunction<Object, Object, Object> {

	KeyStrategy REPLACE = new ReplaceStrategy();

	KeyStrategy APPEND = new AppendStrategy();

	KeyStrategy MERGE = new MergeStrategy();

}
