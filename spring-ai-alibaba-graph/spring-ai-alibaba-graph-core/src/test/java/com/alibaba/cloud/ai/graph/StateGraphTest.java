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

import com.alibaba.cloud.ai.graph.action.*;
import com.alibaba.cloud.ai.graph.async.AsyncGenerator;
import com.alibaba.cloud.ai.graph.async.AsyncGeneratorQueue;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.serializer.plain_text.PlainTextStateSerializer;
import com.alibaba.cloud.ai.graph.state.*;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;

import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.utils.EdgeMappings;
import org.junit.jupiter.api.NamedExecutable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StateGraphTest {

	private static final Logger log = LoggerFactory.getLogger(StateGraphTest.class);

	/**
	 * Sorts a map by its keys and returns a list of entries.
	 * @param map The map to be sorted.
	 * @return A list of map entries sorted by key.
	 */
	public static <T> List<Entry<String, T>> sortMap(Map<String, T> map) {
		return map.entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toList());
	}

	/**
	 * Tests the validation logic of the StateGraph, ensuring proper exceptions are thrown
	 * when the graph is not correctly configured.
	 */
	@Test
	void testValidation() throws Exception {

		StateGraph workflow = new StateGraph();
		GraphStateException exception = assertThrows(GraphStateException.class, workflow::compile);
		System.out.println(exception.getMessage());
		assertEquals("missing Entry Point", exception.getMessage());

		workflow.addEdge(START, "agent_1");

		exception = assertThrows(GraphStateException.class, workflow::compile);
		assertEquals("edge sourceId 'agent_1' refers to undefined node!", exception.getMessage());

		workflow.addNode("agent_1", node_async((state) -> {
			log.info("agent_1\n{}", state);
			return Map.of("prop1", "test");
		}));

		assertNotNull(workflow.compile());

		workflow.addEdge("agent_1", END);

		assertNotNull(workflow.compile());

		exception = assertThrows(GraphStateException.class, () -> workflow.addEdge(END, "agent_1"));
		log.info("{}", exception.getMessage());

		workflow.addNode("agent_2", node_async(state -> {
			log.info("agent_2\n{}", state);
			return Map.of("prop2", "test");
		}));

		workflow.addEdge("agent_2", "agent_3");

		exception = assertThrows(GraphStateException.class, workflow::compile);
		log.info("{}", exception.getMessage());

		exception = assertThrows(GraphStateException.class,
				() -> workflow.addConditionalEdges("agent_1", edge_async(state -> "agent_3"), Map.of()));
		log.info("{}", exception.getMessage());

	}

	/**
	 * Tests a simple graph with one node that updates the state.
	 */
	@Test
	public void testRunningOneNode() throws Exception {
		StateGraph workflow = new StateGraph(() -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("prop1", (o, o2) -> o2);
			return keyStrategyHashMap;
		}).addEdge(START, "agent_1").addNode("agent_1", node_async(state -> {
			log.info("agent_1\n{}", state);
			return Map.of("prop1", "test");
		})).addEdge("agent_1", END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
		System.out.println("result = " + result);
		assertTrue(result.isPresent());

		Map<String, String> expected = Map.of("input", "test1", "prop1", "test");
		assertIterableEquals(sortMap(expected), sortMap(result.get().data()));
	}

	/**
	 * Tests a graph where nodes append messages to a shared list.
	 */
	@Test
	void testWithAppender() throws Exception {
		StateGraph workflow = new StateGraph(createKeyStrategyFactory()).addNode("agent_1", node_async(state -> {
			System.out.println("agent_1");
			return Map.of("messages", "message1");
		})).addNode("agent_2", node_async(state -> {
			System.out.println("agent_2");
			return Map.of("messages", new String[] { "message2" });
		})).addNode("agent_3", node_async(state -> {
			System.out.println("agent_3");
			List<String> messages = Optional.ofNullable(state.value("messages").get())
				.filter(List.class::isInstance)
				.map(List.class::cast)
				.orElse(new ArrayList<>());

			int steps = messages.size() + 1;

			return Map.of("messages", "message3", "steps", steps);
		}))
			.addEdge("agent_1", "agent_2")
			.addEdge("agent_2", "agent_3")
			.addEdge(StateGraph.START, "agent_1")
			.addEdge("agent_3", StateGraph.END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of());

		assertTrue(result.isPresent());
		System.out.println(result.get().data());
		List<String> listResult = (List<String>) result.get().value("messages").get();
		assertIterableEquals(List.of("message1", "message2", "message3"), listResult);

	}

	/**
	 * Removes an element from the list based on the provided RemoveIdentifier.
	 */
	private static void removeFromList(List<Object> result, AppenderChannel.RemoveIdentifier<Object> removeIdentifier) {
		for (int i = 0; i < result.size(); i++) {
			if (removeIdentifier.compareTo(result.get(i), i) == 0) {
				result.remove(i);
				break;
			}
		}
	}

	/**
	 * Evaluates removal operations on a list based on RemoveIdentifiers in newValues.
	 */
	private static AppenderChannel.RemoveData<Object> evaluateRemoval(List<Object> oldValues, List<?> newValues) {

		final var result = new AppenderChannel.RemoveData<>(oldValues, newValues);

		newValues.stream().filter(value -> value instanceof AppenderChannel.RemoveIdentifier<?>).forEach(value -> {
			result.newValues().remove(value);
			var removeIdentifier = (AppenderChannel.RemoveIdentifier<Object>) value;
			removeFromList(result.oldValues(), removeIdentifier);
		});
		return result;

	}

	@Test
	public void testRunnableConfigMetadata() throws Exception {

		// Create an async node action that validates metadata in the config
		var agent = AsyncNodeActionWithConfig.node_async((state, config) -> {

			// Verify that the metadata "configData" is present in the configuration
			assertTrue(config.metadata("configData").isPresent());

			log.info("agent_1\n{}", state);
			return Map.of("prop1", "test");
		});

		// Build a workflow with a custom key strategy using ReplaceStrategy for "prop1"
		var workflow = new StateGraph(() -> {
			Map<String, KeyStrategy> keyStrategyMap = new HashMap<>();
			keyStrategyMap.put("prop1", new ReplaceStrategy());
			return keyStrategyMap;
		}).addEdge(START, "agent_1").addNode("agent_1", agent).addEdge("agent_1", END);

		// Compile the workflow into a runnable graph
		var app = workflow.compile();

		// Configure RunnableConfig with metadata to be passed during execution
		var config = RunnableConfig.builder().addMetadata("configData", "test").build();

		// Execute the graph with input and configured metadata
		var result = app.invoke(Map.of("input", "test1"), config);
		assertTrue(result.isPresent());

		// Expected output after execution
		Map<String, String> expected = Map.of("input", "test1", "prop1", "test");

		// Validate the actual output matches the expected values
		assertIterableEquals(sortMap(expected), sortMap(result.get().data()));
	}

	/**
	 * Tests message appending and single message removal in a graph flow.
	 */
	@Test
	void testWithAppenderOneRemove() throws Exception {
		StateGraph workflow = new StateGraph(createKeyStrategyFactory()).addNode("agent_1", node_async(state -> {
			log.info("agent_1");
			return Map.of("messages", "message1");
		})).addNode("agent_2", node_async(state -> {
			log.info("agent_2");
			return Map.of("messages", new String[] { "message2" });
		})).addNode("agent_3", node_async(state -> {
			System.out.println("agent_3");
			List<String> messages = Optional.ofNullable(state.value("messages").get())
				.filter(List.class::isInstance)
				.map(List.class::cast)
				.orElse(new ArrayList<>());

			int steps = messages.size() + 1;
			return Map.of("messages", RemoveByHash.of("message2"), "steps", steps);
		}))
			.addEdge("agent_1", "agent_2")
			.addEdge("agent_2", "agent_3")
			.addEdge(START, "agent_1")
			.addEdge("agent_3", END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of());

		assertTrue(result.isPresent());
		log.info("{}", result.get().data());
		List<String> messages = (List<String>) result.get().value("messages").get();
		assertEquals(3, result.get().value("steps").get());
		assertEquals(1, messages.size());
		assertIterableEquals(List.of("message1"), messages);

	}

	/**
	 * Tests combining both appending and removing messages in a multi-step graph flow.
	 */
	@Test
	void testWithAppenderOneAppendOneRemove() throws Exception {
		StateGraph workflow = new StateGraph(createKeyStrategyFactory())
			.addNode("agent_1", node_async(state -> Map.of("messages", "message1")))
			.addNode("agent_2", node_async(state -> Map.of("messages", new String[] { "message2" })))
			.addNode("agent_3",
					node_async(state -> Map.of("messages", List.of("message3", RemoveByHash.of("message2")))))
			.addNode("agent_4", node_async(state -> {
				System.out.println("agent_3");
				List messages = Optional.of(state.value("messages").get())
					.filter(List.class::isInstance)
					.map(List.class::cast)
					.orElse(new ArrayList<>());

				int steps = messages.size() + 1;
				return Map.of("messages", List.of("message4"), "steps", steps);
			}))
			.addEdge("agent_1", "agent_2")
			.addEdge("agent_2", "agent_3")
			.addEdge("agent_3", "agent_4")
			.addEdge(START, "agent_1")
			.addEdge("agent_4", END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of());

		assertTrue(result.isPresent());

		System.out.println(result.get().data());
		List<String> messages = (List<String>) result.get().value("messages").get();
		assertEquals(3, result.get().value("steps").get());
		assertEquals(3, messages.size());
		assertIterableEquals(List.of("message1", "message3", "message4"), messages);

	}

	/**
	 * Creates an OverAllState instance with predefined strategies for testing purposes.
	 */
	private static KeyStrategyFactory createKeyStrategyFactory() {
		return () -> {
			Map<String, KeyStrategy> keyStrategyMap = new HashMap<>();
			keyStrategyMap.put("steps", (o, o2) -> o2);
			keyStrategyMap.put("messages", new AppendStrategy());
			return keyStrategyMap;
		};
	}

	/**
	 * Tests subgraph functionality where one graph is embedded within another.
	 */
	@Test
	public void testWithSubgraph() throws Exception {

		var childStep1 = node_async((OverAllState state) -> Map.of("messages", "child:step1"));

		var childStep2 = node_async((OverAllState state) -> Map.of("messages", "child:step2"));

		var childStep3 = node_async((OverAllState state) -> Map.of("messages", "child:step3"));

		var workflowChild = new StateGraph().addNode("child:step_1", childStep1)
			.addNode("child:step_2", childStep2)
			.addNode("child:step_3", childStep3)
			.addEdge(START, "child:step_1")
			.addEdge("child:step_1", "child:step_2")
			.addEdge("child:step_2", "child:step_3")
			.addEdge("child:step_3", END);

		var step1 = node_async((OverAllState state) -> Map.of("messages", "step1"));

		var step2 = node_async((OverAllState state) -> Map.of("messages", "step2"));

		var step3 = node_async((OverAllState state) -> Map.of("messages", "step3"));

		var workflowParent = new StateGraph(createKeyStrategyFactory()).addNode("step_1", step1)
			.addNode("step_2", step2)
			.addNode("step_3", step3)
			.addNode("subgraph", workflowChild)
			.addEdge(START, "step_1")
			.addEdge("step_1", "step_2")
			.addEdge("step_2", "subgraph")
			.addEdge("subgraph", "step_3")
			.addEdge("step_3", END)
			.compile();

		var result = workflowParent.stream(Map.of())
			.stream()
			.peek(System.out::println)
			.map(NodeOutput::state)
			.reduce((a, b) -> b);

		assertTrue(result.isPresent());
		assertIterableEquals(List.of("step1", "step2", "child:step1", "child:step2", "child:step3", "step3"),
				(List<Object>) result.get().value("messages").get());

	}

	/**
	 * Helper method to create a node action with logging functionality.
	 */
	private AsyncNodeAction makeNode(String id) {
		return node_async(state -> {
			log.info("call node {}", id);
			if (id.equalsIgnoreCase("A2")) {
				log.info("sleep A2");
				Thread.sleep(2000);
			}
			return Map.of("messages", id);
		});
	}

	private AsyncNodeAction makeNodeForStream(String id) {
		return node_async(state -> {
			log.info("call node {}", id);
			final AsyncGenerator<NodeOutput> it = AsyncGeneratorQueue.of(new LinkedBlockingQueue<>(), queue -> {
				for (int i = 0; i < 10; ++i) {
					queue.add(AsyncGenerator.Data.of(completedFuture(new StreamingOutput(id + i, id, state))));
				}
			});

			return Map.of("messages", it);
		});
	}

	/**
	 * Tests parallel branch execution in a graph.
	 */
	@Test
	void testWithParallelBranch() throws Exception {
		var workflow = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNode("A1"))
			.addNode("A2", makeNode("A2"))
			.addNode("A3", makeNode("A3"))
			.addNode("B", makeNode("B"))
			.addNode("C", makeNode("C"))
			.addEdge("A", "A1")
			.addEdge("A", "A2")
			.addEdge("A", "A3")
			.addEdge("A1", "B")
			.addEdge("A2", "B")
			.addEdge("A3", "B")
			.addEdge("B", "C")
			.addEdge(START, "A")
			.addEdge("C", END);

		var app = workflow.compile(CompileConfig.builder().withLifecycleListener(new GraphLifecycleListener() {
			@Override
			public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
				log.info("node before ,node = {},state = {}", nodeId, state);
			}

			@Override
			public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
				log.info("node after ,node = {},state = {}", nodeId, state);
			}
		}).build());

		var result = app
			.stream(Map.of(), RunnableConfig.builder().addParallelNodeExecutor("A", ForkJoinPool.commonPool()).build())
			.stream()
			.peek(System.out::println)
			.reduce((a, b) -> b)
			.map(NodeOutput::state);
		assertTrue(result.isPresent());
		List<String> messages = (List<String>) result.get().value("messages").get();
		log.info("messages: {}", messages);

		// 验证所有节点都被执行，但不关心并行节点的顺序
		assertEquals("A", messages.get(0)); // A 应该是第一个
		assertEquals("B", messages.get(messages.size() - 2)); // B 应该是倒数第二个
		assertEquals("C", messages.get(messages.size() - 1)); // C 应该是最后一个

		// 验证并行节点 A1, A2, A3 都在结果中
		assertTrue(messages.contains("A1"), "A1 should be in the result");
		assertTrue(messages.contains("A2"), "A2 should be in the result");
		assertTrue(messages.contains("A3"), "A3 should be in the result");

		// 验证总长度正确
		assertEquals(6, messages.size(), "Should have 6 messages: A, A1, A2, A3, B, C");

		workflow = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNode("A1"))
			.addNode("A2", makeNode("A2"))
			.addNode("A3", makeNode("A3"))
			.addNode("B", makeNode("B"))
			.addNode("C", makeNode("C"))
			.addEdge("A1", "B")
			.addEdge("A2", "B")
			.addEdge("A3", "B")
			.addEdge("B", "C")
			.addEdge(START, "A1")
			.addEdge(START, "A2")
			.addEdge(START, "A3")
			.addEdge("C", END);

		app = workflow.compile();

		result = app.stream(Map.of(),
				RunnableConfig.builder().addParallelNodeExecutor(START, Executors.newSingleThreadExecutor()).build())
			.stream()
			.peek(System.out::println)
			.reduce((a, b) -> b)
			.map(NodeOutput::state);

		assertTrue(result.isPresent());
		List<String> messages2 = (List<String>) result.get().value("messages").get();

		// 验证所有节点都被执行，但不关心并行节点的顺序
		assertEquals("B", messages2.get(messages2.size() - 2)); // B 应该是倒数第二个
		assertEquals("C", messages2.get(messages2.size() - 1)); // C 应该是最后一个

		// 验证并行节点 A1, A2, A3 都在结果中
		assertTrue(messages2.contains("A1"), "A1 should be in the result");
		assertTrue(messages2.contains("A2"), "A2 should be in the result");
		assertTrue(messages2.contains("A3"), "A3 should be in the result");

		// 验证总长度正确
		assertEquals(5, messages2.size(), "Should have 5 messages: A1, A2, A3, B, C");

	}

	@Test
	public void testWithParallelBranchWithStream() throws GraphStateException, GraphRunnerException {
		var workflow = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNodeForStream("A1"))
			.addNode("A2", makeNodeForStream("A2"))
			.addNode("C", makeNode("C"))
			.addEdge("A", "A1")
			.addEdge("A", "A2")
			.addEdge("A1", "C")
			.addEdge("A2", "C")
			.addEdge(START, "A")
			.addEdge("C", END);
		var app = workflow.compile();

		for (var output : app.stream(Map.of())) {
			if (output instanceof AsyncGenerator<?>) {
				AsyncGenerator asyncGenerator = (AsyncGenerator) output;
				System.out.println("Streaming chunk: " + asyncGenerator);
			}
			else {
				System.out.println("Node output: " + output);
			}
		}
	}

	/**
	 * Tests error conditions related to parallel branches in graph configuration.
	 */
	@Test
	void testWithParallelBranchWithErrors() throws Exception {
		var onlyOneTarget = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNode("A1"))
			.addNode("A2", makeNode("A2"))
			.addNode("A3", makeNode("A3"))
			.addNode("B", makeNode("B"))
			.addNode("C", makeNode("C"))
			.addEdge("A", "A1")
			.addEdge("A", "A2")
			.addEdge("A", "A3")
			.addEdge("A1", "B")
			.addEdge("A2", "B")
			.addEdge("A3", "C")
			.addEdge("B", "C")
			.addEdge(START, "A")
			.addEdge("C", END);

		var exception = assertThrows(GraphStateException.class, onlyOneTarget::compile);
		assertEquals("parallel node [A] must have only one target, but [B, C] have been found!",
				exception.getMessage());

		var noConditionalEdge = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNode("A1"))
			.addNode("A2", makeNode("A2"))
			.addNode("A3", makeNode("A3"))
			.addNode("B", makeNode("B"))
			.addNode("C", makeNode("C"))
			.addEdge("A", "A1")
			.addEdge("A", "A3")
			.addEdge("A1", "B")
			.addEdge("A2", "B")
			.addEdge("A3", "B")
			.addEdge("B", "C")
			.addEdge(START, "A")
			.addEdge("C", END);

		exception = assertThrows(GraphStateException.class,
				() -> noConditionalEdge.addConditionalEdges("A", edge_async(state -> "next"), Map.of("next", "A2")));
		assertEquals("conditional edge from 'A' already exist!", exception.getMessage());

		var noConditionalEdgeOnBranch = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNode("A1"))
			.addNode("A2", makeNode("A2"))
			.addNode("A3", makeNode("A3"))
			.addNode("B", makeNode("B"))
			.addNode("C", makeNode("C"))
			.addEdge("A", "A1")
			.addEdge("A", "A2")
			.addEdge("A", "A3")
			.addEdge("A1", "B")
			.addEdge("A2", "B")
			.addConditionalEdges("A3", edge_async(state -> "next"), Map.of("next", "B"))
			.addEdge("B", "C")
			.addEdge(START, "A")
			.addEdge("C", END);

		exception = assertThrows(GraphStateException.class, noConditionalEdgeOnBranch::compile);
		assertEquals(
				"parallel node doesn't support conditional branch, but on [A] a conditional branch on [A3] have been found!",
				exception.getMessage());

		var noDuplicateTarget = new StateGraph(createKeyStrategyFactory()).addNode("A", makeNode("A"))
			.addNode("A1", makeNode("A1"))
			.addNode("A2", makeNode("A2"))
			.addNode("A3", makeNode("A3"))
			.addNode("B", makeNode("B"))
			.addNode("C", makeNode("C"))
			.addEdge("A", "A1")
			.addEdge("A", "A2")
			.addEdge("A", "A3")
			.addEdge("A", "A2")
			.addEdge("A1", "B")
			.addEdge("A2", "B")
			.addEdge("A3", "B")
			.addEdge("B", "C")
			.addEdge(START, "A")
			.addEdge("C", END);

		exception = assertThrows(GraphStateException.class, noDuplicateTarget::compile);
		assertEquals("edge [A] has duplicate targets [A2]!", exception.getMessage());

	}

	/**
	 * Tests serialization capabilities of the StateGraph using different serializers.
	 */
	@Test
	public void testWithSubSerialize() throws Exception {
		KeyStrategyFactory keyStrategyFactory = () -> {
			Map<String, KeyStrategy> keyStrategyMap = new HashMap<>();
			keyStrategyMap.put("prop1", (o, o2) -> o2);
			return keyStrategyMap;
		};
		PlainTextStateSerializer plainTextStateSerializer = new StateGraph.JacksonSerializer();
		StateGraph workflow = new StateGraph(keyStrategyFactory, plainTextStateSerializer).addEdge(START, "agent_1")
			.addNode("agent_1", node_async(state -> {
				log.info("agent_1\n{}", state);
				return Map.of("prop1", "test");
			}))
			.addEdge("agent_1", END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
		System.out.println("result = " + result);
		assertTrue(result.isPresent());

		Map<String, String> expected = Map.of("input", "test1", "prop1", "test");
		assertIterableEquals(sortMap(expected), sortMap(result.get().data()));
	}

	/**
	 * Tests creation of a StateGraph using a custom OverAllStateFactory.
	 */
	@Test
	public void testCreateStateGraph() throws Exception {
		StateGraph workflow = new StateGraph(createKeyStrategyFactory()).addEdge(START, "agent_1")
			.addNode("agent_1", node_async(state -> {
				log.info("agent_1\n{}", state);
				return Map.of("prop1", "test");
			}))
			.addEdge("agent_1", END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
		System.out.println("result = " + result);
		assertTrue(result.isPresent());

		Map<String, String> expected = Map.of("input", "test1", "prop1", "test");
		assertIterableEquals(sortMap(expected), sortMap(result.get().data()));
	}

	/**
	 * Test creating a state graph with custom key strategies using a lambda function.
	 * This test verifies that the graph correctly handles state updates using
	 * ReplaceStrategy for specific keys.
	 */
	@Test
	public void testKeyStrategyFactoryCreateStateGraph() throws Exception {
		StateGraph workflow = new StateGraph(() -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("prop1", new ReplaceStrategy());
			keyStrategyHashMap.put("input", new ReplaceStrategy());
			return keyStrategyHashMap;
		}).addEdge(START, "agent_1").addNode("agent_1", node_async(state -> {
			log.info("agent_1\n{}", state);
			return Map.of("prop1", "test");
		})).addEdge("agent_1", END);

		CompiledGraph app = workflow.compile();

		Optional<OverAllState> result = app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
		System.out.println("result = " + result);
		assertTrue(result.isPresent());

		Map<String, String> expected = Map.of("input", "test1", "prop1", "test");
		assertIterableEquals(sortMap(expected), sortMap(result.get().data()));
	}

	@Test
	public void testLifecycleListenerGraphWithLIFO() throws Exception {
		StateGraph workflow = new StateGraph(() -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("prop1", new ReplaceStrategy());
			keyStrategyHashMap.put("input", new ReplaceStrategy());
			return keyStrategyHashMap;
		}).addEdge(START, "agent_1").addNode("agent_1", node_async(state -> {
			log.info("agent_1\n{}", state);
			return Map.of("prop1", "test");
		})).addEdge("agent_1", END);

		CompiledGraph app = workflow
			.compile(CompileConfig.builder().withLifecycleListener(new GraphLifecycleListener() {
				@Override
				public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("listener1 ,node = {},state = {}", nodeId, state);
				}

				@Override
				public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("listener1 ,node = {},state = {}", nodeId, state);
				}
			}).withLifecycleListener(new GraphLifecycleListener() {
				@Override
				public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("listener2 ,node = {},state = {}", nodeId, state);
				}

				@Override
				public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("listener2 ,node = {},state = {}", nodeId, state);
				}
			}).build());

		app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
	}

	/**
	 * Test graph execution lifecycle listeners for start and complete events. This test
	 * ensures that onStart and onComplete callbacks are properly triggered during graph
	 * execution.
	 */
	@Test
	public void testLifecycleListenerGraphWithCompleteAndStart() throws Exception {
		StateGraph workflow = new StateGraph(() -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("prop1", new ReplaceStrategy());
			keyStrategyHashMap.put("input", new ReplaceStrategy());
			return keyStrategyHashMap;
		}).addEdge(START, "agent_1").addNode("agent_1", node_async(state -> {
			log.info("agent_1\n{}", state);
			return Map.of("prop1", "test");
		})).addEdge("agent_1", END);

		CompiledGraph app = workflow
			.compile(CompileConfig.builder().withLifecycleListener(new GraphLifecycleListener() {
				@Override
				public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("node = {},state = {}", nodeId, state);
				}

				@Override
				public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("node = {},state = {}", nodeId, state);
				}
			}).build());

		app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
	}

	/**
	 * Test graph execution error handling through lifecycle listener. This test ensures
	 * that onError callback is properly triggered when an exception occurs during node
	 * execution.
	 */
	@Test
	public void testLifecycleListenerGraphWithError() throws Exception {
		StateGraph workflow = new StateGraph(() -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			keyStrategyHashMap.put("prop1", new ReplaceStrategy());
			keyStrategyHashMap.put("input", new ReplaceStrategy());
			return keyStrategyHashMap;
		}).addEdge(START, "agent_1").addNode("agent_1", node_async(state -> {
			log.info("agent_1\n{}", state);
			int a = 1 / 0; // Force division by zero error
			return Map.of("prop1", "test");
		})).addEdge("agent_1", END);

		CompiledGraph app = workflow
			.compile(CompileConfig.builder().withLifecycleListener(new GraphLifecycleListener() {
				@Override
				public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("node = {},state = {}", nodeId, state);
				}

				@Override
				public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
					log.info("node = {},state = {}", nodeId, state);
				}

				@Override
				public void onError(String nodeId, Map<String, Object> state, Throwable ex, RunnableConfig config) {
					log.error("node = {},state = {}", nodeId, state, ex);
				}
			}).build());

		assertThrows(CompletionException.class,
				(NamedExecutable) () -> app.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1")));
	}

	@Test
	public void testCommandEdgeGraph() throws Exception {
		StateGraph workflow = new StateGraph(
				() -> Map.of("prop1", new ReplaceStrategy(), "input", new ReplaceStrategy()))

			.addNode("agent_1", node_async(state -> {
				log.info("agent_1\n{}", state);

				return Map.of("prop1", "agent_1");
			}))
			.addNode("agent_2", node_async(state -> {
				log.info("agent_2\n{}", state);

				return Map.of("prop1", "agent_2");
			}))
			.addNode("agent_3", node_async(state -> {
				log.info("agent_3\n{}", state);
				assertEquals("command content", state.value("prop1", String.class).get());
				return Map.of("prop1", "agent_3");
			}))
			.addConditionalEdges("agent_2",
					AsyncCommandAction
						.node_async((state, config) -> new Command("agent_2", Map.of("prop1", "command content"))),
					Map.of("agent_2", "agent_3"))
			.addEdge(START, "agent_1")
			.addEdge("agent_3", END)
			.addEdge("agent_1", "agent_2");
		CompiledGraph compile = workflow.compile();
		compile.invoke(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test1"));
	}

	@Test
	public void testCommandNodeGraph() throws Exception {
		StateGraph graph = new StateGraph(() -> {
			HashMap<String, KeyStrategy> stringKeyStrategyHashMap = new HashMap<>();
			stringKeyStrategyHashMap.put("messages", new AppendStrategy());
			return stringKeyStrategyHashMap;
		});

		CommandAction commandAction = (state, config) -> new Command("node1", Map.of("messages", "go to command node"));
		graph.addNode("testCommandNode", AsyncCommandAction.node_async(commandAction),
				Map.of("node1", "node1", "node2", "node2"));

		graph.addNode("node1", makeNode("node1"));
		graph.addNode("node2", makeNode("node2"));

		graph.addEdge(START, "testCommandNode");
		graph.addEdge("node1", "node2");
		graph.addEdge("node2", END);

		CompiledGraph compile = graph.compile();
		String plantuml = compile.getGraph(GraphRepresentation.Type.PLANTUML).content();
		String mermaid = compile.getGraph(GraphRepresentation.Type.MERMAID).content();
		System.out.println("===============plantuml===============");
		System.out.println(plantuml);
		System.out.println("===============mermaid===============");
		System.out.println(mermaid);

		OverAllState state = compile.invoke(Map.of()).orElseThrow();
		assertEquals(List.of("go to command node", "node1", "node2"), state.value("messages", List.class).get());
	}

	@Test
	void testCommandNode() throws Exception {

		AsyncCommandAction commandAction = (state,
				config) -> completedFuture(new Command("C2", Map.of("messages", "B", "next_node", "C2")));

		var graph = new StateGraph().addNode("A", makeNode("A"))
			.addNode("B", commandAction, EdgeMappings.builder().toEND().to("C1").to("C2").build())
			.addNode("C1", makeNode("C1"))
			.addNode("C2", makeNode("C2"))
			.addEdge(START, "A")
			.addEdge("A", "B")
			.addEdge("C1", END)
			.addEdge("C2", END)
			.compile();

		var steps = graph.stream(Map.of()).stream().peek(System.out::println).toList();

		assertEquals(5, steps.size());
		assertEquals("B", steps.get(2).node());
		assertEquals("C2", steps.get(2).state().value("next_node").orElse(null));

	}

}
