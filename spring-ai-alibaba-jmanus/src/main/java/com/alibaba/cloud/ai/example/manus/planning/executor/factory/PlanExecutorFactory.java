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
package com.alibaba.cloud.ai.example.manus.planning.executor.factory;

import com.alibaba.cloud.ai.example.manus.config.ManusProperties;
import com.alibaba.cloud.ai.example.manus.dynamic.agent.entity.DynamicAgentEntity;
import com.alibaba.cloud.ai.example.manus.dynamic.agent.service.AgentService;
import com.alibaba.cloud.ai.example.manus.dynamic.agent.service.IDynamicAgentLoader;
import com.alibaba.cloud.ai.example.manus.llm.ILlmService;
import com.alibaba.cloud.ai.example.manus.planning.executor.DirectResponseExecutor;
import com.alibaba.cloud.ai.example.manus.planning.executor.MapReducePlanExecutor;
import com.alibaba.cloud.ai.example.manus.planning.executor.PlanExecutor;
import com.alibaba.cloud.ai.example.manus.planning.executor.PlanExecutorInterface;
import com.alibaba.cloud.ai.example.manus.planning.model.vo.PlanInterface;
import com.alibaba.cloud.ai.example.manus.recorder.PlanExecutionRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plan Executor Factory - Creates appropriate executor based on plan type Factory class
 * that selects the appropriate PlanExecutor implementation based on the planType from
 * PlanInterface
 */
@Component
public class PlanExecutorFactory implements IPlanExecutorFactory {

	private static final Logger log = LoggerFactory.getLogger(PlanExecutorFactory.class);

	private final IDynamicAgentLoader dynamicAgentLoader;

	private final ILlmService llmService;

	private final AgentService agentService;

	private final PlanExecutionRecorder recorder;

	private final ManusProperties manusProperties;

	private final ObjectMapper objectMapper;

	public PlanExecutorFactory(IDynamicAgentLoader dynamicAgentLoader, ILlmService llmService,
			AgentService agentService, PlanExecutionRecorder recorder, ManusProperties manusProperties,
			ObjectMapper objectMapper) {
		this.dynamicAgentLoader = dynamicAgentLoader;
		this.llmService = llmService;
		this.agentService = agentService;
		this.recorder = recorder;
		this.manusProperties = manusProperties;
		this.objectMapper = objectMapper;
	}

	/**
	 * Create the appropriate executor based on plan type
	 * @param plan The execution plan containing type information
	 * @return The appropriate PlanExecutorInterface implementation
	 * @throws IllegalArgumentException if plan type is not supported
	 */
	public PlanExecutorInterface createExecutor(PlanInterface plan) {
		if (plan == null) {
			throw new IllegalArgumentException("Plan cannot be null");
		}

		// Check if this is a direct response plan first
		if (plan.isDirectResponse()) {
			log.info("Creating direct response executor for plan: {}", plan.getCurrentPlanId());
			return createDirectResponseExecutor();
		}

		String planType = plan.getPlanType();
		if (planType == null || planType.trim().isEmpty()) {
			log.warn("Plan type is null or empty, defaulting to simple executor for plan: {}", plan.getCurrentPlanId());
			planType = "simple";
		}

		log.info("Creating executor for plan type: {} (planId: {})", planType, plan.getCurrentPlanId());

		return switch (planType.toLowerCase()) {
			case "simple" -> createSimpleExecutor();
			case "advanced" -> createAdvancedExecutor();
			default -> {
				log.warn("Unknown plan type: {}, defaulting to simple executor", planType);
				yield createSimpleExecutor();
			}
		};
	}

	/**
	 * Create a simple plan executor for basic sequential execution
	 * @return PlanExecutor instance for simple plans
	 */
	private PlanExecutorInterface createSimpleExecutor() {
		log.debug("Creating simple plan executor");
		List<DynamicAgentEntity> agents = dynamicAgentLoader.getAllAgents();
		return new PlanExecutor(agents, recorder, agentService, llmService, manusProperties);
	}

	/**
	 * Create a direct response executor for handling direct response plans
	 * @return DirectResponseExecutor instance for direct response plans
	 */
	private PlanExecutorInterface createDirectResponseExecutor() {
		log.debug("Creating direct response executor");
		List<DynamicAgentEntity> agents = dynamicAgentLoader.getAllAgents();
		return new DirectResponseExecutor(agents, recorder, agentService, llmService, manusProperties);
	}

	/**
	 * Create an advanced plan executor for MapReduce execution
	 * @return MapReducePlanExecutor instance for advanced plans
	 */
	private PlanExecutorInterface createAdvancedExecutor() {
		log.debug("Creating advanced MapReduce plan executor");
		List<DynamicAgentEntity> agents = dynamicAgentLoader.getAllAgents();
		return new MapReducePlanExecutor(agents, recorder, agentService, llmService, manusProperties, objectMapper);
	}

	/**
	 * Get supported plan types
	 * @return Array of supported plan type strings
	 */
	public String[] getSupportedPlanTypes() {
		return new String[] { "simple", "advanced", "direct" };
	}

	/**
	 * Check if a plan type is supported
	 * @param planType The plan type to check
	 * @return true if the plan type is supported, false otherwise
	 */
	public boolean isPlanTypeSupported(String planType) {
		if (planType == null) {
			return false;
		}
		String normalizedType = planType.toLowerCase();
		return "simple".equals(normalizedType) || "advanced".equals(normalizedType) || "direct".equals(normalizedType);
	}

	/**
	 * Create executor with explicit plan type (useful for testing or special cases)
	 * @param planType The explicit plan type to use
	 * @param planId Plan ID for logging purposes
	 * @return The appropriate PlanExecutorInterface implementation
	 */
	public PlanExecutorInterface createExecutorByType(String planType, String planId) {
		log.info("Creating executor for explicit plan type: {} (planId: {})", planType, planId);

		if (planType == null || planType.trim().isEmpty()) {
			planType = "simple";
		}

		return switch (planType.toLowerCase()) {
			case "simple" -> createSimpleExecutor();
			case "advanced" -> createAdvancedExecutor();
			case "direct" -> createDirectResponseExecutor();
			default -> {
				log.warn("Unknown explicit plan type: {}, defaulting to simple executor", planType);
				yield createSimpleExecutor();
			}
		};
	}

}
