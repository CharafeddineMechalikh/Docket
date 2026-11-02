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
package com.mechalikh.docket.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.config.Settings;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.engine.ReplicaSpec;
import com.mechalikh.docket.network.LinkProfile;

/**
 * What a scenario says when it splits prefill and decode onto separate replicas: the two stages it
 * builds, the fabric a cache crosses, and the settings that refuse to describe a split deployment
 * twice.
 *
 * @author Charafeddine Mechalikh
 */
class DisaggregationScenarioTest {

	/** Profile the prefill stage is drawn from. */
	private static final String PREFILLER = "prefiller";
	/** Profile the decode stage is drawn from. */
	private static final String DECODER = "decoder";

	/**
	 * The keys of the smallest disaggregated deployment: a tiny model, a light workload, and two
	 * machine profiles that play different phases, one replica of each.
	 *
	 * @return the settings, modifiable so a test can drop or add a key
	 */
	private static Map<String, String> splitKeys() {
		Map<String, String> map = new LinkedHashMap<>();
		put(map, "experiment.name", "pd-test", "experiment.durationSeconds", "60", "model.id", "tiny-124m",
				"model.parameterCount", "124M", "model.layers", "12", "model.hiddenDimension", "768",
				"model.attentionHeads", "12", "model.maxContextTokens", "2048", "workload.pattern", "poisson",
				"workload.ratePerMinute", "6", "workload.inputDistribution.name", "constant",
				"workload.inputDistribution.value", "256", "workload.outputDistribution.name", "constant",
				"workload.outputDistribution.value", "32", "cost.model", "roofline-analytical", "devices",
				PREFILLER + "," + DECODER, "device." + PREFILLER + ".memoryGiB", "24", "device." + PREFILLER
						+ ".memoryBandwidthGBs", "700", "device." + PREFILLER + ".achievedTFLOPS.fp16", "100",
				"device." + DECODER + ".memoryGiB", "80", "device." + DECODER + ".memoryBandwidthGBs", "2000",
				"device." + DECODER + ".achievedTFLOPS.fp16", "50", "deployment.disaggregated", "true",
				"deployment.prefillReplicas", "1", "deployment.decodeReplicas", "1");
		return map;
	}

	/** The keys of a flat single-device deployment, which disaggregation also has to support. */
	private static Map<String, String> flatKeys() {
		Map<String, String> map = new LinkedHashMap<>();
		put(map, "experiment.name", "pd-flat", "experiment.durationSeconds", "60", "model.id", "tiny-124m",
				"model.parameterCount", "124M", "model.layers", "12", "model.hiddenDimension", "768",
				"model.attentionHeads", "12", "model.maxContextTokens", "2048", "workload.pattern", "poisson",
				"workload.ratePerMinute", "6", "workload.inputDistribution.name", "constant",
				"workload.inputDistribution.value", "256", "workload.outputDistribution.name", "constant",
				"workload.outputDistribution.value", "32", "cost.model", "roofline-analytical", "device.id", "gpu",
				"device.memoryGiB", "24", "device.memoryBandwidthGBs", "700", "device.achievedTFLOPS.fp16", "100",
				"deployment.disaggregated", "true", "deployment.prefillReplicas", "1", "deployment.decodeReplicas", "1");
		return map;
	}

	private static void put(Map<String, String> map, String... pairs) {
		for (int i = 0; i < pairs.length; i += 2)
			map.put(pairs[i], pairs[i + 1]);
	}

	private static Scenario load(Map<String, String> keys) throws IOException {
		return Scenario.load(Settings.of(keys), null);
	}

	/**
	 * Loads the two-profile split with extra keys applied on top.
	 *
	 * @param pairs key and value pairs, alternating
	 * @return the scenario
	 * @throws IOException never, in-memory settings cannot fail to read
	 */
	private static Scenario split(String... pairs) throws IOException {
		Map<String, String> keys = splitKeys();
		put(keys, pairs);
		return load(keys);
	}

	private static String header(Scenario scenario) {
		return String.join("\n", scenario.describe());
	}

	private static String failure(RunnableLoad load) {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, load::run);
		return error.getMessage();
	}

	/** A scenario load that a test expects to fail. */
	private interface RunnableLoad {
		/**
		 * Runs the load.
		 *
		 * @throws IOException when the settings were unreadable
		 */
		void run() throws IOException;
	}

	private static List<ReplicaSpec.Role> roles(Scenario scenario) {
		List<ReplicaSpec.Role> out = new ArrayList<>();
		for (ReplicaSpec spec : scenario.getReplicas())
			out.add(spec.getRole());
		return out;
	}

	private static List<String> deviceIds(Scenario scenario) {
		List<String> out = new ArrayList<>();
		for (ReplicaSpec spec : scenario.getReplicas())
			out.add(spec.getDevice().getId());
		return out;
	}

	@Test
	void disaggregationIsOffByDefaultAndEveryReplicaStaysColocated() throws IOException {
		Map<String, String> keys = splitKeys();
		keys.remove("deployment.disaggregated");
		keys.remove("deployment.prefillReplicas");
		keys.remove("deployment.decodeReplicas");
		put(keys, "replicas", PREFILLER + "," + DECODER);
		Scenario scenario = load(keys);
		assertFalse(scenario.isDisaggregated());
		assertNull(scenario.getKvFabric());
		for (ReplicaSpec.Role role : roles(scenario))
			assertEquals(ReplicaSpec.Role.COLOCATED, role);
		assertFalse(header(scenario).contains("disaggregated"), header(scenario));
	}

	@Test
	void theTwoProfilesBecomeAPrefillStageThenADecodeStage() throws IOException {
		Scenario scenario = split();
		assertTrue(scenario.isDisaggregated());
		assertEquals(2, scenario.getReplicas().size());
		assertEquals(List.of(ReplicaSpec.Role.PREFILL, ReplicaSpec.Role.DECODE), roles(scenario));
		assertEquals(PREFILLER + "-p1", scenario.getReplicas().get(0).getDevice().getId());
		assertEquals(DECODER + "-d1", scenario.getReplicas().get(1).getDevice().getId());
		assertNull(scenario.getKvFabric(), "no fabric was declared, so the hand-off must be instant");
	}

	@Test
	void eachStageClonesItsDeviceWithADistinctIdentifier() throws IOException {
		Scenario scenario = split("deployment.prefillReplicas", "2", "deployment.decodeReplicas", "3");
		assertEquals(5, scenario.getReplicas().size());
		assertEquals(List.of(ReplicaSpec.Role.PREFILL, ReplicaSpec.Role.PREFILL, ReplicaSpec.Role.DECODE,
				ReplicaSpec.Role.DECODE, ReplicaSpec.Role.DECODE), roles(scenario));
		assertEquals(List.of(PREFILLER + "-p1", PREFILLER + "-p2", DECODER + "-d1", DECODER + "-d2",
				DECODER + "-d3"), deviceIds(scenario));
	}

	@Test
	void aSingleDeviceProfileStillSplitsIntoTwoDistinctMachines() throws IOException {
		Map<String, String> keys = flatKeys();
		Scenario scenario = load(keys);
		assertTrue(scenario.isDisaggregated());
		// The flat form declares one machine kind, so both stages clone it; the role tag keeps the
		// two apart, because a service refuses two replicas that claim the same device.
		assertEquals(List.of("gpu-p1", "gpu-d1"), deviceIds(scenario));
		assertEquals(List.of(ReplicaSpec.Role.PREFILL, ReplicaSpec.Role.DECODE), roles(scenario));
	}

	@Test
	void aSplitDeploymentRefusesACountOfClones() {
		String message = failure(() -> split("replicas.count", "2"));
		assertTrue(message.contains("drop \"replicas.count\" and \"replicas\""), message);
	}

	@Test
	void aSplitDeploymentRefusesAFleetList() {
		String message = failure(() -> split("replicas", PREFILLER + "," + DECODER));
		assertTrue(message.contains("drop \"replicas.count\" and \"replicas\""), message);
	}

	@Test
	void thePrefillCountIsRequired() {
		Map<String, String> keys = splitKeys();
		keys.remove("deployment.prefillReplicas");
		String message = failure(() -> load(keys));
		assertTrue(message.contains("deployment.prefillReplicas"), message);
	}

	@Test
	void theDecodeCountIsRequired() {
		Map<String, String> keys = splitKeys();
		keys.remove("deployment.decodeReplicas");
		String message = failure(() -> load(keys));
		assertTrue(message.contains("deployment.decodeReplicas"), message);
	}

	@Test
	void aSplitNeedsAtLeastOneOfEachPhase() {
		String message = failure(() -> split("deployment.decodeReplicas", "0"));
		assertTrue(message.contains("at least one prefill and one decode"), message);
	}

	@Test
	void theFabricIsReadFromTheNamedLinkBlock() throws IOException {
		Scenario scenario = split("deployment.fabric", "fabric", "link.fabric.capacityMbps", "1000",
				"link.fabric.latencyMs", "0.5");
		LinkProfile fabric = scenario.getKvFabric();
		assertEquals("fabric", fabric.getId());
		assertEquals(1000.0, fabric.getCapacityMbps(), 1e-9);
		assertEquals(0.0005, fabric.getLatency(), 1e-12);
	}

	@Test
	void theHeaderNamesTheSplitAndTheFabric() throws IOException {
		Scenario scenario = split("deployment.prefillReplicas", "2", "deployment.decodeReplicas", "1",
				"deployment.fabric", "fabric", "link.fabric.capacityMbps", "10000");
		String text = header(scenario);
		assertTrue(text.contains("disaggregated: 2 prefill + 1 decode"), text);
		assertTrue(text.contains("KV fabric fabric"), text);
	}

	@Test
	void anInstantHandOffIsReportedAsSuch() throws IOException {
		assertTrue(header(split()).contains("instant KV hand-off"), header(split()));
	}

	@Test
	void theSplitLimitsPrintAStampedRoleForEachReplica() throws IOException {
		Scenario scenario = split("replica." + PREFILLER + ".maxBatchSize", "8",
				"replica." + DECODER + ".maxBatchSize", "64");
		String text = header(scenario);
		assertTrue(text.contains(PREFILLER + "-p1 [prefill]"), text);
		assertTrue(text.contains(DECODER + "-d1 [decode]"), text);
	}

	@Test
	void aProfileOverrideReachesTheStageThatDeclaredIt() throws IOException {
		Scenario scenario = split("replica." + PREFILLER + ".maxBatchSize", "8",
				"replica." + DECODER + ".maxBatchSize", "64", "replica." + DECODER + ".tokensPerBlock", "32");
		assertEquals(8, scenario.getReplicas().get(0).getMaxBatchSize());
		assertEquals(64, scenario.getReplicas().get(1).getMaxBatchSize());
		assertEquals(32, scenario.getReplicas().get(1).getTokensPerBlock());
	}

	@Test
	void theFabricIsAPartOfTheProvenanceOfTheRun() throws IOException {
		Map<String, String> keys = splitKeys();
		put(keys, "model.provenance", "measured", "device." + PREFILLER + ".provenance", "measured", "device."
				+ DECODER + ".provenance", "measured", "cost.model", "roofline-calibrated", "cost.calibration.source",
				"instruments", "deployment.fabric", "fabric", "link.fabric.capacityMbps", "1000",
				"link.fabric.provenance", "literature");
		Scenario scenario = load(keys);
		// Every machine and the cost are measured, but the fabric is only quoted from the
		// literature, so the run may claim no more than that.
		assertEquals(Provenance.LITERATURE.label(), scenario.overallProvenance());
	}

	@Test
	void anUnknownKeyUnderAStageProfileStillNamesTheProfile() {
		String message = failure(() -> split("replica." + PREFILLER + ".maxBatchLize", "8"));
		assertTrue(message.contains("Unknown replica setting \"replica." + PREFILLER + ".maxBatchLize\""), message);
	}
}
