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
package com.alibaba.cloud.ai.example.manus.dynamic.agent.service;

import com.alibaba.cloud.ai.example.manus.dynamic.agent.entity.DynamicAgentEntity;
import com.alibaba.cloud.ai.example.manus.dynamic.agent.model.enums.AgentEnum;
import com.alibaba.cloud.ai.example.manus.dynamic.agent.repository.DynamicAgentRepository;
import com.alibaba.cloud.ai.example.manus.dynamic.agent.startupAgent.StartupAgentConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent initialization service for managing agent configurations with multi-language
 * support
 */
@Service
public class AgentInitializationService {

	private static final Logger log = LoggerFactory.getLogger(AgentInitializationService.class);

	@Autowired
	private DynamicAgentRepository agentRepository;

	@Autowired
	private StartupAgentConfigLoader configLoader;

	@Value("${namespace.value}")
	private String namespace;

	/**
	 * Initialize agents for namespace with default language
	 * @param namespace Namespace
	 */
	public void initializeAgentsForNamespace(String namespace) {
		String defaultLanguage = "en";
		for (AgentEnum agent : AgentEnum.values()) {
			createAgentIfNotExists(namespace, agent, defaultLanguage);
		}
	}

	/**
	 * Initialize agents for namespace with specific language
	 * @param namespace Namespace
	 * @param language Language code
	 */
	public void initializeAgentsForNamespaceWithLanguage(String namespace, String language) {
		for (AgentEnum agent : AgentEnum.values()) {
			updateAgentForLanguage(namespace, agent, language);
		}
	}

	/**
	 * Reset all agents to default language version for a namespace
	 * @param namespace Namespace
	 * @param language Target language
	 */
	@Transactional
	public void resetAllAgentsToLanguage(String namespace, String language) {
		log.info("Resetting all agents to language: {} for namespace: {}", language, namespace);

		List<DynamicAgentEntity> existingAgents = agentRepository.findAllByNamespace(namespace);

		// Delete all existing agents for this namespace
		agentRepository.deleteAll(existingAgents);
		log.info("Deleted {} existing agents for namespace: {}", existingAgents.size(), namespace);

		// Reinitialize with new language
		initializeAgentsForNamespaceWithLanguage(namespace, language);

		log.info("Successfully reset all agents to language: {} for namespace: {}", language, namespace);
	}

	/**
	 * Create agent if it doesn't exist
	 * @param namespace Namespace
	 * @param agent Agent enum
	 * @param language Language code
	 */
	private void createAgentIfNotExists(String namespace, AgentEnum agent, String language) {
		DynamicAgentEntity agentEntity = agentRepository.findByNamespaceAndAgentName(namespace, agent.getAgentName());

		if (agentEntity == null) {
			agentEntity = new DynamicAgentEntity();
			agentEntity.setAgentName(agent.getAgentName());
			agentEntity.setNamespace(namespace);
			// Description will be loaded from config file
			agentEntity.setClassName(""); // YAML-based agents

			String agentPath = agent.getAgentPath();
			StartupAgentConfigLoader.AgentConfig agentConfig = configLoader.loadAgentConfig(agentPath, language);

			if (agentConfig != null) {
				agentEntity.setAgentDescription(agentConfig.getAgentDescription());
				agentEntity.setNextStepPrompt(agentConfig.getNextStepPrompt());
				agentEntity.setAvailableToolKeys(agentConfig.getAvailableToolKeys());
			}

			try {
				agentRepository.save(agentEntity);
				log.info("Created agent: {} for namespace: {} with language: {}", agent.getAgentName(), namespace,
						language);
			}
			catch (Exception e) {
				log.error("Failed to create agent: {} for namespace: {} with language: {}", agent.getAgentName(),
						namespace, language, e);
			}
		}
	}

	/**
	 * Update agent for specific language
	 * @param namespace Namespace
	 * @param agent Agent enum
	 * @param language Language code
	 */
	private void updateAgentForLanguage(String namespace, AgentEnum agent, String language) {
		DynamicAgentEntity agentEntity = agentRepository.findByNamespaceAndAgentName(namespace, agent.getAgentName());

		if (agentEntity != null) {
			String agentPath = agent.getAgentPath();
			StartupAgentConfigLoader.AgentConfig agentConfig = configLoader.loadAgentConfig(agentPath, language);

			if (agentConfig != null) {
				agentEntity.setAgentDescription(agentConfig.getAgentDescription());
				agentEntity.setNextStepPrompt(agentConfig.getNextStepPrompt());
				agentEntity.setAvailableToolKeys(agentConfig.getAvailableToolKeys());
			}

			try {
				agentRepository.save(agentEntity);
				log.info("Updated agent: {} for namespace: {} with language: {}", agent.getAgentName(), namespace,
						language);
			}
			catch (Exception e) {
				log.error("Failed to update agent: {} for namespace: {} with language: {}", agent.getAgentName(),
						namespace, language, e);
			}
		}
		else {
			createAgentIfNotExists(namespace, agent, language);
		}
	}

}
