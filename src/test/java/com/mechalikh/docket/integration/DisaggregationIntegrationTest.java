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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * What prefill/decode disaggregation does to a run: the hand-off the fabric charges, the work each
 * phase never does, and the invariants a split deployment must still hold.
 *
 * @author Charafeddine Mechalikh
 */
class DisaggregationIntegrationTest {

	/** Prompt length every integration run uses, so a hand-off is known in advance. */
	private static final int PROMPT_TOKENS = 256;
	/** Tolerance for a comparison of two durations the ledger already fixed. */
	private static final double EPSILON = 1e-9;

	private static void put(Map<String, String> map, String... pairs) {
		for (int i = 0; i < pairs.length; i += 2)
			map.put(pairs[i], pairs[i + 1]);
	}

	/**
	 * The keys of the disaggregated deployment the integration runs share: a tiny model, a compute
	 * profile that prefills and a memory-rich one that decodes, reached over a declared fabric.
	 *
	 * @param pairs key and value pairs applied on top of the base
	 * @return the settings, ready to load
	 */
	private static Map<String, String> splitKeys(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		put(map, "experiment.name", "pd-run", "experiment.durationSeconds", "30", "experiment.repetitions", "1",
				"experiment.seed", "7", "model.id", "tiny-124m", "model.parameterCount", "124M", "model.layers", "12",
				"model.hiddenDimension", "768", "model.attentionHeads", "12", "model.maxContextTokens", "2048",
				"workload.pattern", "poisson", "workload.inputDistribution.name", "constant", "workload.inputDistribution.value",
				Integer.toString(PROMPT_TOKENS), "workload.outputDistribution.name", "constant",
				"workload.outputDistribution.value", "4", "workload.maxOutputTokens", "4", "cost.model",
				"roofline-analytical", "devices", "prefiller,decoder", "device.prefiller.memoryGiB", "24",
				"device.prefiller.memoryBandwidthGBs", "700", "device.prefiller.achievedTFLOPS.fp16", "100",
				"device.decoder.memoryGiB", "80", "device.decoder.memoryBandwidthGBs", "2000",
				"device.decoder.achievedTFLOPS.fp16", "50", "deployment.disaggregated", "true",
				"deployment.prefillReplicas", "1", "deployment.decodeReplicas", "1");
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

	/**
	 * Seconds the fabric owes for the cache of a prompt of the given length, before any queueing:
	 * the message size divided by the raw capacity, plus the one-way propagation.
	 *
	 * @param scenario the run that declared the fabric
	 * @param tokens   prompt tokens the cache carries
	 * @return the transfer time in seconds
	 */
	private static double transfer(Scenario scenario, int tokens) {
		double bytes = tokens * scenario.getModel().kvBytesPerToken();
		return bytes / scenario.getKvFabric().getBandwidth() + scenario.getKvFabric().getLatency();
	}

	@Test
	void aTransferredPromptPaysTheFabricInItsTimeToFirstToken() {
		Map<String, String> instantKeys = splitKeys("workload.ratePerMinute", "600", "workload.maxRequests", "1");
		Map<String, String> slowKeys = splitKeys("workload.ratePerMinute", "600", "workload.maxRequests", "1",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "200", "link.fabric.latencyMs", "0");
		Scenario slowScenario = scenario(slowKeys);

		RunResult instant = run(instantKeys);
		RunResult slow = run(slowKeys);
		assertEquals(1, instant.getRequests().size(), "the single-arrival workload produced another count");
		assertEquals(1, slow.getRequests().size(), "the two runs must carry the same request");
		InferenceRequest instantRow = instant.getRequests().get(0);
		InferenceRequest slowRow = slow.getRequests().get(0);
		assertEquals(RequestState.COMPLETED, slowRow.getState(), "the sequence did not finish within the horizon");

		// Everything before the hand-off is identical across the two runs, so the whole difference
		// the fabric makes is the time the cache spends crossing it, charged into the first token.
		double delta = slowRow.ttftSeconds() - instantRow.ttftSeconds();
		assertEquals(transfer(slowScenario, PROMPT_TOKENS), delta, EPSILON,
				"the time-to-first-token did not move by exactly the fabric transfer");
	}

	@Test
	void aFasterFabricHalvesTheChargeTheHandOffAdds() {
		Map<String, String> halfKeys = splitKeys("workload.ratePerMinute", "600", "workload.maxRequests", "1",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "200", "link.fabric.latencyMs", "0");
		Map<String, String> fullKeys = splitKeys("workload.ratePerMinute", "600", "workload.maxRequests", "1",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "400", "link.fabric.latencyMs", "0");
		Map<String, String> instantKeys = splitKeys("workload.ratePerMinute", "600", "workload.maxRequests", "1");

		double slowDelta = run(halfKeys).getRequests().get(0).ttftSeconds()
				- run(instantKeys).getRequests().get(0).ttftSeconds();
		double fastDelta = run(fullKeys).getRequests().get(0).ttftSeconds()
				- run(instantKeys).getRequests().get(0).ttftSeconds();
		assertTrue(slowDelta > 0, "a declared fabric charged no transfer");
		assertEquals(2.0, slowDelta / fastDelta, 1e-6,
				"doubling the bearer capacity did not halve the charge it added to the first token");
	}

	@Test
	void thePrefillInstanceDecodesNothingAndTheDecodeInstancePrefillsNothing() {
		RunResult run = run(splitKeys("workload.ratePerMinute", "600", "workload.maxRequests", "12",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "100000", "link.fabric.latencyMs", "0"));
		// The replicas are reported in creation order, prefill stage first, so the two rows are the
		// two phases of the split deployment.
		List<ReplicaStats> stats = run.getReplicaStats();
		assertEquals(2, stats.size());
		ReplicaStats prefill = stats.get(0);
		ReplicaStats decode = stats.get(1);
		assertTrue(prefill.prefillSteps > 0, "the prefill instance read no prompt");
		assertEquals(0, prefill.decodeIterations, "the prefill instance decoded a sequence it should have handed over");
		assertEquals(0, decode.prefillSteps, "the decode instance prefilled, which a split deployment forbids");
		assertTrue(decode.decodeIterations > 0, "the decode instance emitted no token");
	}

	@Test
	void aDisaggregatedRunKeepsEveryInvariant() {
		RunResult run = run(splitKeys("workload.ratePerMinute", "900", "workload.maxRequests", "20",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "100000", "link.fabric.latencyMs", "0"));
		assertTrue(run.getArrivedRequests() > 0, "the workload admitted no traffic");
		assertEquals(run.getArrivedRequests(), run.getTerminalRequests(),
				"a cache still on the fabric was lost when the run ended");
		assertTrue(run.getCompletedRequests() > 0, "no disaggregated sequence finished");

		double active = 0;
		for (ReplicaStats replica : run.getReplicaStats()) {
			assertTrue(replica.utilisation >= 0 && replica.utilisation <= 1, "utilisation out of range");
			assertEquals(replica.activeEnergyJoule + replica.idleEnergyJoule, replica.totalEnergyJoule, 1e-6);
			active += replica.activeEnergyJoule;
		}
		assertEquals(active, run.getRequestEnergyJoule(), 1e-6,
				"the energy the two phases charged does not add up to the energy carried by the rows");
		assertFalse(run.getRequests().isEmpty());
		for (InferenceRequest request : run.getRequests())
			assertTrue(request.getState().isTerminal(), "a disaggregated row is not terminal");
	}

	@Test
	void aSequenceStillInFlightWhenTheHorizonPassesIsRecordedRatherThanLost() {
		// A bearer slow enough that a cache cannot cross it before the run ends: the request had its
		// prompt computed on the prefill device, so its flight is charged even though it never decoded.
		RunResult run = run(splitKeys("workload.ratePerMinute", "6000", "workload.maxRequests", "20",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "0.5", "link.fabric.latencyMs", "0",
				"experiment.durationSeconds", "3"));
		List<String> counters = new ArrayList<>();
		for (InferenceRequest request : run.getRequests()) {
			if (request.getState() != RequestState.TIMED_OUT)
				continue;
			if (request.getReplicaId() >= 0)
				continue;
			counters.add(Long.toString(request.getRequestId()));
		}
		assertFalse(counters.isEmpty(), "the slow fabric delivered every cache, so an in-flight hand-off was never exercised");
		assertEquals(run.getArrivedRequests(), run.getRequests().size(),
				"a request the fabric was holding disappeared from the rows");
	}
}
