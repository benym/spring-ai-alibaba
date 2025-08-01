/*
 * Copyright 2025 the original author or authors.
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

package com.alibaba.cloud.ai.example.manus.dynamic.prompt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class PromptDataInitializer implements CommandLineRunner, IPromptDataInitializer {

	private final PromptInitializationService promptInitializationService;

	@Value("${namespace.value}")
	private String namespace;

	private static final Logger log = LoggerFactory.getLogger(PromptDataInitializer.class);

	public PromptDataInitializer(PromptInitializationService promptInitializationService) {
		this.promptInitializationService = promptInitializationService;
	}

	@Override
	public void run(String... args) {
		try {
			promptInitializationService.initializePromptsForNamespace(namespace);
		}
		catch (Exception e) {
			log.error("Failed to initialize prompt data", e);
			throw e;
		}
	}

}
