/**
 *     Docket: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of Docket.
 *
 *     Docket is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Docket is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Docket. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.docket.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.config.Settings;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.RequestState;
import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.experiment.RunResult.ReplicaStats;

/**
 * The three opt-in mechanisms on the live serving loop: speculative decoding trims the number of
 * decode iterations without trimming the tokens, prefix reuse spares a prompt recompute across two
 * identical requests, and with every mechanism off the plain loop emits exactly one token per
 * iteration and recomputes every prompt.
 *
 * @author Charafeddine Mechalikh
 */
class MechanismsIntegrationTest {

	/** Prompt length every run uses, so a full prefix hit is known in advance. */
	private static final int PROMPT_TOKENS = 512;
	/** Tokens one request asks the model to generate. */
	private static final int OUTPUT_TOKENS = 8;

	private static void put(Map<String, String> map, String... pairs) {
		for (int i = 0; i < pairs.length; i += 2)
			map.put(pairs[i], pairs[i + 1]);
	}

	/**
	 * One colocated replica of a tiny model on one device, with short constant traffic, so the only
	 * thing that moves between runs is the mechanism switched on.
	 *
	 * @param pairs key and value pairs applied on top of the base
	 * @return the settings, ready to load
	 */
	private static Map<String, String> colocated(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		put(map, "experiment.name", "mech-run", "experiment.durationSeconds", "30", "experiment.repetitions", "1",
				"experiment.seed", "7", "model.id", "tiny-124m", "model.parameterCount", "124M", "model.layers", "12",
				"model.hiddenDimension", "768", "model.attentionHeads", "12", "model.kvHeads", "12",
				"model.headDimension", "64", "model.maxContextTokens", "2048", "model.precision", "fp16",
				"device.id", "gpu", "device.memoryGiB", "80", "device.memoryBandwidthGBs", "2000",
				"device.achievedTFLOPS.fp16", "100", "replicas.count", "1", "replica.maxBatchSize", "32",
				"replica.maxPrefillChunkTokens", "512", "replica.tokensPerBlock", "16", "workload.pattern", "poisson",
				"workload.ratePerMinute", "60", "workload.inputDistribution.name", "constant",
				"workload.inputDistribution.value", Integer.toString(PROMPT_TOKENS), "workload.outputDistribution.name",
				"constant", "workload.outputDistribution.value", Integer.toString(OUTPUT_TOKENS),
				"workload.maxOutputTokens", Integer.toString(OUTPUT_TOKENS), "workload.prediction", "oracle",
				"cost.model", "roofline-analytical", "policy.admission", "unlimited", "policy.routing", "least-queued",
				"policy.scheduling", "fcfs");
		put(map, pairs);
		return map;
	}

	private static Scenario scenario(Map<String, String> keys) {
		try {
			return Scenario.load(Settings.of(keys), null);
		} catch (IOException e) {
			throw new UncheckedIOException("in-memory settings cannot fail to read", e);
		}
	}

	private static RunResult run(Map<String, String> keys) {
		return new Experiment(scenario(keys)).runRepetition(1);
	}

	private static long decodeIterations(RunResult run) {
		long total = 0;
		for (ReplicaStats replica : run.getReplicaStats())
			total += replica.decodeIterations;
		return total;
	}

	private static long prefillSteps(RunResult run) {
		long total = 0;
		for (ReplicaStats replica : run.getReplicaStats())
			total += replica.prefillSteps;
		return total;
	}

	@Test
	void speculationOffEmitsExactlyOneTokenPerDecodeIteration() {
		RunResult run = run(colocated("workload.maxRequests", "1"));
		assertEquals(1, run.getRequests().size());
		InferenceRequest row = run.getRequests().get(0);
		assertEquals(RequestState.COMPLETED, row.getState(), "the request did not finish inside the horizon");
		assertEquals(OUTPUT_TOKENS, row.getGeneratedTokens());
		// The prefill emits the first token, then every post-prefill token costs exactly one iteration.
		assertEquals(OUTPUT_TOKENS - 1, decodeIterations(run), "the plain loop did not emit one token per iteration");
	}

	@Test
	void speculationCutsTheDecodeIterationsWithoutCuttingTheTokens() {
		Map<String, String> on = colocated("workload.maxRequests", "1", "replica.speculativeDecoding", "true",
				"replica.draftTokens", "2", "replica.acceptanceRate", "0.7");
		Map<String, String> off = colocated("workload.maxRequests", "1");
		RunResult specRun = run(on);
		RunResult plainRun = run(off);
		InferenceRequest specRow = specRun.getRequests().get(0);
		assertEquals(OUTPUT_TOKENS, specRow.getGeneratedTokens(), "speculation lost the requested output");
		assertEquals(RequestState.COMPLETED, specRow.getState());
		// Accepted two per step, so the seven tokens after the first clear in about half the iterations.
		assertTrue(decodeIterations(specRun) < decodeIterations(plainRun),
				"speculative decoding did not reduce the number of decode iterations");
	}

	@Test
	void speculationNeverEmitsMoreTokensThanTheRequestAskedFor() {
		Map<String, String> on = colocated("workload.maxRequests", "1", "replica.speculativeDecoding", "true",
				"replica.draftTokens", "4", "replica.acceptanceRate", "0.9");
		RunResult run = run(on);
		assertEquals(OUTPUT_TOKENS, run.getRequests().get(0).getGeneratedTokens(),
				"a speculative step overshot the output cap");
	}

	@Test
	void aSharedPromptIsRecomputedOnlyForTheFirstOfTwoIdenticalRequests() {
		Map<String, String> on = colocated("workload.maxRequests", "2", "replica.prefixCache", "true");
		Map<String, String> off = colocated("workload.maxRequests", "2");
		RunResult reuse = run(on);
		RunResult plain = run(off);
		assertEquals(2, reuse.getRequests().size(), "the two serialized arrivals did not both run");
		assertEquals(2, plain.getRequests().size());

		// With the mechanism off every prompt pays its own recompute, and no request reports a reuse.
		long plainSteps = prefillSteps(plain);
		for (InferenceRequest row : plain.getRequests())
			assertEquals(0, row.getReusedPrefixTokens(), "a run without the prefix cache reported a reuse");

		int reused = 0;
		for (InferenceRequest row : reuse.getRequests())
			if (row.getReusedPrefixTokens() == PROMPT_TOKENS)
				reused++;
		assertEquals(1, reused, "exactly one of the two identical prompts should have been served from the cache");
		assertTrue(prefillSteps(reuse) < plainSteps,
				"the shared prompt still cost a full recompute on the second request");
	}

	@Test
	void everyMechanismKeepsTheCacheConsistent() {
		Map<String, String> on = colocated("workload.maxRequests", "6", "workload.ratePerMinute", "600",
				"replica.speculativeDecoding", "true", "replica.draftTokens", "2", "replica.acceptanceRate", "0.7",
				"replica.prefixCache", "true");
		RunResult run = run(on);
		assertTrue(run.getCompletedRequests() > 0, "the busy speculative run finished nothing");
		for (InferenceRequest row : run.getRequests())
			assertTrue(row.getState().isTerminal(), "a request was left in a non-terminal state");
		// The tokens the rows generated are the tokens the engine counted, mechanism or no mechanism.
		long fromRows = 0;
		for (InferenceRequest row : run.getRequests())
			fromRows += row.getGeneratedTokens();
		assertEquals(fromRows, run.getTokensGenerated(), "the aggregate token count and the rows disagree");
	}
}
