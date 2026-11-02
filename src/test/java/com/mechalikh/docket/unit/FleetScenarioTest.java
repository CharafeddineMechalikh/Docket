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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.config.Settings;
import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.engine.ReplicaSpec;
import com.mechalikh.docket.network.LinkProfile;

/**
 * What a scenario says when it declares several machines, and the bearer each of them is reached
 * through.
 *
 * @author Charafeddine Mechalikh
 */
class FleetScenarioTest {

	/** The identifier of the fast profile used by every fleet test. */
	private static final String FAST = "fast";
	/** The identifier of the slow profile used by every fleet test. */
	private static final String SLOW = "slow";

	/**
	 * The keys of the smallest fleet: one small model, a light workload and two machine profiles
	 * that differ by three orders of magnitude, one replica of each.
	 *
	 * @return the settings, modifiable so a test can drop or add a key
	 */
	private static Map<String, String> fleetKeys() {
		Map<String, String> map = new LinkedHashMap<>();
		put(map, "experiment.name", "fleet-test", "experiment.durationSeconds", "60", "model.id", "tiny-124m",
				"model.parameterCount", "124M", "model.layers", "12", "model.hiddenDimension", "768",
				"model.attentionHeads", "12", "model.maxContextTokens", "2048", "workload.pattern", "poisson",
				"workload.ratePerMinute", "6", "workload.inputDistribution.name", "constant",
				"workload.inputDistribution.value", "256", "workload.outputDistribution.name", "constant",
				"workload.outputDistribution.value", "32", "cost.model", "roofline-analytical", "devices",
				FAST + "," + SLOW, "device." + FAST + ".memoryGiB", "24", "device." + FAST + ".memoryBandwidthGBs",
				"700", "device." + FAST + ".achievedTFLOPS.fp16", "100", "device." + SLOW + ".memoryGiB", "8",
				"device." + SLOW + ".memoryBandwidthGBs", "17", "device." + SLOW + ".achievedTFLOPS.fp16", "0.0042",
				"replicas", FAST + "," + SLOW);
		return map;
	}

	/** The keys of the flat form, which stays supported: one device block, clones counted. */
	private static Map<String, String> flatKeys() {
		Map<String, String> map = new LinkedHashMap<>();
		put(map, "experiment.name", "flat-test", "experiment.durationSeconds", "60", "model.id", "tiny-124m",
				"model.parameterCount", "124M", "model.layers", "12", "model.hiddenDimension", "768",
				"model.attentionHeads", "12", "model.maxContextTokens", "2048", "workload.pattern", "poisson",
				"workload.ratePerMinute", "6", "workload.inputDistribution.name", "constant",
				"workload.inputDistribution.value", "256", "workload.outputDistribution.name", "constant",
				"workload.outputDistribution.value", "32", "cost.model", "roofline-analytical", "device.id", "gpu",
				"device.memoryGiB", "24", "device.memoryBandwidthGBs", "700", "device.achievedTFLOPS.fp16", "100");
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
	 * Loads the two-profile fleet with extra keys applied on top.
	 *
	 * @param pairs key and value pairs, alternating
	 * @return the scenario
	 * @throws IOException never, in-memory settings cannot fail to read
	 */
	private static Scenario fleet(String... pairs) throws IOException {
		Map<String, String> keys = fleetKeys();
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

	@Test
	void eachProfileIsReadFromItsOwnBlock() throws IOException {
		Scenario scenario = fleet();
		Map<String, GpuProfile> profiles = scenario.getDeviceProfiles();
		assertEquals(2, profiles.size());
		assertEquals(24, profiles.get(FAST).getMemoryBytes() / (1024L * 1024 * 1024));
		assertEquals(8, profiles.get(SLOW).getMemoryBytes() / (1024L * 1024 * 1024));
		assertEquals(100.0, profiles.get(FAST).getAchievedTeraFlops("fp16"), 1e-12);
		assertEquals(0.0042, profiles.get(SLOW).getAchievedTeraFlops("fp16"), 1e-12);
		// The representative device stays the first profile, so the readers that know one device
		// keep working unchanged.
		assertEquals(FAST, scenario.getDevice().getId());
	}

	@Test
	void aProfileUsedOnceKeepsItsNameAndAProfileUsedTwiceBecomesNumbered() throws IOException {
		Scenario scenario = fleet("replicas", SLOW + "," + FAST + "," + SLOW);
		StringBuilder ids = new StringBuilder();
		for (ReplicaSpec spec : scenario.getReplicas())
			ids.append(spec.getDevice().getId()).append(" ");
		assertEquals(SLOW + "-1 " + FAST + " " + SLOW + "-2 ", ids.toString());
	}

	@Test
	void aProfileCarriesItsOwnDeploymentLimitsOnTopOfTheSharedOnes() throws IOException {
		Scenario scenario = fleet("replica.maxBatchSize", "16", "replica.tokensPerBlock", "32",
				"replica." + FAST + ".maxBatchSize", "4", "replica." + SLOW + ".allowPreemption", "false");
		ReplicaSpec fast = scenario.getReplicas().get(0);
		ReplicaSpec slow = scenario.getReplicas().get(1);
		assertEquals(4, fast.getMaxBatchSize());
		assertEquals(16, slow.getMaxBatchSize());
		assertEquals(32, fast.getTokensPerBlock());
		assertEquals(32, slow.getTokensPerBlock());
		assertTrue(fast.isAllowPreemption());
		assertFalse(slow.isAllowPreemption());
	}

	@Test
	void anUnknownKeyUnderAProfileNamesTheProfileThatWroteIt() {
		String message = failure(() -> fleet("replica." + FAST + ".maxBatchLize", "4"));
		assertTrue(message.contains("Unknown replica setting \"replica." + FAST + ".maxBatchLize\""), message);
	}

	@Test
	void aFleetAndACountOfClonesCannotBothBeWritten() {
		String message = failure(() -> fleet("replicas.count", "2"));
		assertTrue(message.contains("drop one of the two"), message);
	}

	@Test
	void aFlatDeviceBlockAndAProfileListCannotBothBeWritten() {
		Map<String, String> keys = fleetKeys();
		put(keys, "device.id", "leftover");
		String message = failure(() -> load(keys));
		assertTrue(message.contains("write every machine as device.<profile>.<key>"), message);
	}

	@Test
	void aFleetEntryHasToBeADeclaredProfile() {
		String message = failure(() -> fleet("replicas", FAST + ",laptop"));
		assertTrue(message.contains("The fleet entry \"laptop\" is not a declared device profile"), message);
	}

	@Test
	void declaringProfilesWithoutUsingThemIsRefused() {
		Map<String, String> keys = fleetKeys();
		keys.remove("replicas");
		String message = failure(() -> load(keys));
		assertTrue(message.contains("but \"replicas\" names none of them"), message);
	}

	@Test
	void aProfileListedTwiceIsATypoNotAFleet() {
		String message = failure(() -> fleet("devices", FAST + "," + FAST));
		assertTrue(message.contains("is listed twice in \"devices\""), message);
	}

	@Test
	void aProfileBlockNeedsItsOwnThroughput() {
		Map<String, String> keys = fleetKeys();
		keys.remove("device." + SLOW + ".achievedTFLOPS.fp16");
		String message = failure(() -> load(keys));
		assertTrue(message.contains("device." + SLOW + ".achievedTFLOPS.fp16"), message);
	}

	@Test
	void theHeaderNamesEveryProfileOfAFleet() throws IOException {
		Scenario scenario = fleet();
		String text = header(scenario);
		assertTrue(text.contains("2 device profiles"), text);
		assertTrue(text.contains(FAST + ": 24.0 GiB"), text);
		assertTrue(text.contains(SLOW + ": 8.0 GiB"), text);
		// Identical limits print the way a single deployment always did, without a segment per
		// replica.
		assertTrue(text.contains("batch <= 64"), text);
		assertFalse(text.contains(FAST + " batch<="), text);
	}

	@Test
	void theHeaderSplitsWhenTheReplicasDiffer() throws IOException {
		Scenario scenario = fleet("replica." + FAST + ".maxBatchSize", "4");
		String text = header(scenario);
		assertTrue(text.contains(FAST + " batch<=4"), text);
		assertTrue(text.contains(SLOW + " batch<=64"), text);
	}

	@Test
	void theWeakestMachineOfAFleetDecidesTheDeviceProvenance() throws IOException {
		Scenario scenario = fleet("device." + FAST + ".provenance", "measured", "device." + SLOW + ".provenance",
				"literature");
		assertEquals(Provenance.LITERATURE.label(), scenario.deviceProvenance());
	}

	@Test
	void theFlatFormStillReadsOneDeviceAndClonesIt() throws IOException {
		Map<String, String> keys = flatKeys();
		put(keys, "replicas.count", "3");
		Scenario scenario = load(keys);
		assertEquals(1, scenario.getDeviceProfiles().size());
		assertEquals("gpu", scenario.getDevice().getId());
		assertEquals(3, scenario.getReplicas().size());
		assertEquals("gpu-2", scenario.getReplicas().get(1).getDevice().getId());
		assertNull(scenario.networkSummary());
		assertFalse(header(scenario).contains("network"), header(scenario));
	}

	@Test
	void theFlatFormWithOneReplicaKeepsTheDeviceIdentifierUnchanged() throws IOException {
		Scenario scenario = load(flatKeys());
		assertEquals("gpu", scenario.getReplicas().get(0).getDevice().getId());
	}

	@Test
	void aDeviceBlockNamesTheBearerItsClientsReachItThrough() throws IOException {
		Scenario scenario = fleet("device." + SLOW + ".link", "cellular", "link.cellular.capacityMbps", "20",
				"link.cellular.latencyMs", "40");
		LinkProfile link = scenario.getReplicas().get(1).getLink();
		assertEquals("cellular", link.getId());
		assertEquals(20.0, link.getCapacityMbps(), 1e-12);
		assertEquals(0.04, link.getLatency(), 1e-12);
		assertEquals(4, link.getBytesPerInputToken(), 1e-12);
		assertNull(scenario.getReplicas().get(0).getLink());
		assertEquals(1, scenario.declaredLinks().size());
		String text = header(scenario);
		assertTrue(text.contains("cellular: 20.0 Mbps, 40.0 ms one way, 4/4 bytes per input/output token"), text);
	}

	@Test
	void twoDevicesBehindOneAccessPointShareItsQueue() throws IOException {
		Scenario scenario = fleet("device." + FAST + ".link", "shared", "device." + SLOW + ".link", "shared",
				"link.shared.capacityMbps", "100");
		assertSame(scenario.getReplicas().get(0).getLink(), scenario.getReplicas().get(1).getLink());
		assertEquals(1, scenario.declaredLinks().size());
		assertEquals(1, scenario.networkSummary().split(";").length);
	}

	@Test
	void theFlatFormCanNameABearerToo() throws IOException {
		Map<String, String> keys = flatKeys();
		put(keys, "replicas.count", "2", "device.link", "wifi", "link.wifi.capacityMbps", "300");
		Scenario scenario = load(keys);
		assertSame(scenario.getReplicas().get(0).getLink(), scenario.getReplicas().get(1).getLink());
		assertTrue(scenario.networkSummary().contains("wifi: 300.0 Mbps"), scenario.networkSummary());
	}

	@Test
	void aNamedBearerNeedsACapacity() {
		String message = failure(
				() -> fleet("device." + SLOW + ".link", "cellular", "link.cellular.latencyMs", "40"));
		assertTrue(message.contains("link.cellular.capacityMbps"), message);
	}

	@Test
	void aBearerIsAPartOfTheProvenanceOfTheRun() throws IOException {
		Scenario scenario = fleet("device." + FAST + ".link", "fiber", "link.fiber.capacityMbps", "1000",
				"link.fiber.provenance", "measured", "device." + FAST + ".provenance", "measured", "device." + SLOW
						+ ".provenance", "measured", "model.provenance", "measured");
		assertEquals(Provenance.MEASURED, scenario.declaredLinks().get("fiber").getProvenance());
		assertEquals(Provenance.MEASURED.label(), scenario.deviceProvenance());
		// The uncalibrated cost model still caps what the run may claim, and the bearer is one of
		// the parts it is capped by once the devices are measured.
		assertEquals(Provenance.SYNTHETIC.label(), scenario.overallProvenance());
	}

	@Test
	void aGuessedBearerDowngradesARunOfMeasuredMachines() throws IOException {
		Map<String, String> keys = fleetKeys();
		put(keys, "device." + FAST + ".provenance", "measured", "device." + SLOW + ".provenance", "measured",
				"model.provenance", "measured", "cost.model", "roofline-calibrated", "cost.calibration.source",
				"instruments", "device." + FAST + ".link", "guess", "link.guess.capacityMbps", "100");
		Scenario scenario = load(keys);
		assertEquals(Provenance.SYNTHETIC.label(), scenario.overallProvenance());
	}

	@Test
	void aBearerOfUnknownProvenanceIsRefusedByName() {
		String message = failure(() -> fleet("device." + SLOW + ".link", "cellular", "link.cellular.capacityMbps",
				"20", "link.cellular.provenance", "guessed"));
		assertTrue(message.contains("link.cellular.provenance"), message);
	}

	@Test
	void eachMachineCanGetItsOwnBearer() throws IOException {
		Scenario scenario = fleet("device." + FAST + ".link", "fabric", "link.fabric.capacityMbps", "10000",
				"device." + SLOW + ".link", "cellular", "link.cellular.capacityMbps", "10");
		assertEquals(2, scenario.declaredLinks().size());
		assertTrue(scenario.networkSummary().contains(";"), scenario.networkSummary());
		assertEquals("fabric", scenario.getReplicas().get(0).getLink().getId());
		assertEquals("cellular", scenario.getReplicas().get(1).getLink().getId());
	}

	@Test
	void aReplicaThatNamesABearerReportsItInItsOwnSummary() throws IOException {
		Scenario scenario = fleet("device." + SLOW + ".link", "cellular", "link.cellular.capacityMbps", "20");
		assertTrue(scenario.getReplicas().get(1).toString().contains("link cellular"),
				scenario.getReplicas().get(1).toString());
		assertFalse(scenario.getReplicas().get(0).toString().contains("link"),
				scenario.getReplicas().get(0).toString());
	}

	@Test
	void aBearerOfZeroCapacityIsRefusedRatherThanSilentlyIgnored() {
		String message = failure(
				() -> fleet("device." + SLOW + ".link", "cellular", "link.cellular.capacityMbps", "0"));
		assertTrue(message.contains("Link \"cellular\""), message);
	}
}
