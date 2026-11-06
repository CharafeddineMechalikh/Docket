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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.cost.CostEstimate;
import com.mechalikh.docket.cost.DecodeBatch;
import com.mechalikh.docket.cost.RooflineAnalyticalCostModel;
import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.engine.ReplicaSpec;

/**
 * The three opt-in mechanisms in isolation: the quantized-kernel arithmetic, the speculative
 * acceptance formula and the prefix-reuse key. Each is checked off first, where it must reproduce
 * the plain figure, and on second, where it must move that figure in the documented direction.
 *
 * @author Charafeddine Mechalikh
 */
class MechanismTest {

	/** One layer, a thousand parameters, the arithmetic pinned to whole numbers. */
	private static ModelProfile tiny() {
		return new ModelProfile().setId("tiny").setParameterCount(1000).setLayers(1).setHiddenDimension(8)
				.setAttentionHeads(1).setKvHeads(1).setHeadDimension(8).setMaxContextTokens(1024).setPrecision("fp16")
				.setBytesPerParameter(2).setKvBytesPerScalar(2);
	}

	/**
	 * A device that is fast on the memory bus so a step stays compute-bound: the compute precision the
	 * quantized kernel selects is then what sets the duration, which is the effect under test. The bus
	 * is deliberately absurd so the arithmetic term, not the bandwidth term, decides the maximum.
	 */
	private static GpuProfile computeBound() {
		return new GpuProfile().setId("q").setMemoryBytes(1e11).setMemoryBandwidthBytesPerSecond(1e15)
				.addAchievedTeraFlops("int8", 2000).addAchievedTeraFlops("fp16", 1000).setKernelLaunchOverheadSeconds(0)
				.setIdlePowerWatts(10).setActivePowerWatts(20);
	}

	// ---- quantized kernel: the model-side arithmetic ----

	@Test
	void aQuantizedKernelOffAddsNothingAndKeepsTheStoragePrecision() {
		ModelProfile model = tiny().setPrecision("int8").setBytesPerParameter(1);
		assertEquals("int8", model.effectiveComputePrecision());
		assertEquals(0.0, model.quantizationOverheadFlops(), 1e-9);
		assertEquals(0.0, model.quantizationExpansionBytes(), 1e-9);
	}

	@Test
	void turningTheKernelOnSelectsTheComputePrecisionAndChargesDequantization() {
		ModelProfile model = tiny().setPrecision("int8").setBytesPerParameter(1);
		model.setQuantizedKernel(true).setComputePrecision("FP16").setDequantFlopsPerScalar(3)
				.setComputeBytesPerScalar(2);
		// The setter lowercases, so "FP16" is stored as "fp16" and returned as the compute format.
		assertEquals("fp16", model.effectiveComputePrecision());
		// One dequantization op per weight scalar, streamed once per step.
		assertEquals(1000.0 * 3, model.quantizationOverheadFlops(), 1e-9);
		// The compute format is one byte wider than the stored weights, over every weight.
		assertEquals(1000.0 * (2 - 1), model.quantizationExpansionBytes(), 1e-9);
	}

	@Test
	void anExpansionNarrowerThanTheStorageAddsNoExtraBytes() {
		ModelProfile model = tiny().setPrecision("fp16").setBytesPerParameter(2);
		model.setQuantizedKernel(true).setComputeBytesPerScalar(1);
		assertEquals(0.0, model.quantizationExpansionBytes(), 1e-9);
	}

	// ---- quantized kernel: the roofline reacts ----

	@Test
	void computingAtASlowerPrecisionLengthensAComputeBoundStep() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		GpuProfile device = computeBound();
		// Stored int8, the kernel off divides by the int8 throughput.
		ModelProfile off = tiny().setPrecision("int8").setBytesPerParameter(1);
		// Same weights, but the kernel unpacks to fp16, half the throughput.
		ModelProfile on = tiny().setPrecision("int8").setBytesPerParameter(1);
		on.setQuantizedKernel(true).setComputePrecision("fp16");
		CostEstimate offEstimate = cost.prefillStep(off, device, 1000, 0);
		CostEstimate onEstimate = cost.prefillStep(on, device, 1000, 0);
		// With no dequantization ops the arithmetic count is unchanged; only the throughput differs.
		assertEquals(offEstimate.flops(), onEstimate.flops(), 1e-6);
		assertTrue(onEstimate.seconds() > offEstimate.seconds(),
				"the kernel did not charge the slower compute precision");
	}

	@Test
	void dequantizationOpsAreAddedToTheStepArithmetic() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		GpuProfile device = computeBound();
		ModelProfile off = tiny().setPrecision("int8").setBytesPerParameter(1);
		ModelProfile on = tiny().setPrecision("int8").setBytesPerParameter(1);
		on.setQuantizedKernel(true).setComputePrecision("int8").setDequantFlopsPerScalar(1);
		CostEstimate offEstimate = cost.prefillStep(off, device, 1000, 0);
		CostEstimate onEstimate = cost.prefillStep(on, device, 1000, 0);
		// The kernel unpacks all 1000 weights, so exactly parameterCount extra flops appear.
		assertEquals(offEstimate.flops() + 1000.0, onEstimate.flops(), 1e-6);
	}

	// ---- speculative decoding: the acceptance formula ----

	@Test
	void speculationOffAlwaysEmitsOneTokenPerStep() {
		ReplicaSpec spec = new ReplicaSpec().setModel(tiny()).setDevice(computeBound());
		spec.setSpeculativeDecoding(false).setDraftTokens(5).setAcceptanceRate(0.99);
		assertEquals(1, spec.acceptedTokensPerStep());
	}

	@Test
	void theExpectedAcceptedCountFollowsTheTruncatedGeometricSum() {
		ReplicaSpec spec = new ReplicaSpec().setModel(tiny()).setDevice(computeBound());
		spec.setSpeculativeDecoding(true).setDraftTokens(2).setAcceptanceRate(0.7);
		// candidates = 3, expected = (1 - 0.7^3) / (1 - 0.7) = 2.19, rounded to 2.
		assertEquals(2, spec.acceptedTokensPerStep());
	}

	@Test
	void theAcceptedCountNeverExceedsTheVerifiedCandidatesAndNeverFallsBelowOne() {
		ReplicaSpec high = new ReplicaSpec().setModel(tiny()).setDevice(computeBound());
		high.setSpeculativeDecoding(true).setDraftTokens(4).setAcceptanceRate(0.99);
		// Five candidates, expected 4.905 rounds to 5, which is the cap.
		assertEquals(5, high.acceptedTokensPerStep());
		ReplicaSpec low = new ReplicaSpec().setModel(tiny()).setDevice(computeBound());
		low.setSpeculativeDecoding(true).setDraftTokens(4).setAcceptanceRate(0.0);
		// Nothing is accepted, the guaranteed token alone carries the step.
		assertEquals(1, low.acceptedTokensPerStep());
	}

	@Test
	void speculationRefusesDraftsWithoutACandidateOrAnAcceptanceOutsideTheUnitRange() {
		ReplicaSpec bad = new ReplicaSpec().setModel(tiny()).setDevice(computeBound());
		bad.setSpeculativeDecoding(true).setDraftTokens(0).setAcceptanceRate(0.5);
		assertThrows(IllegalArgumentException.class, bad::validate);
		ReplicaSpec worse = new ReplicaSpec().setModel(tiny()).setDevice(computeBound());
		worse.setSpeculativeDecoding(true).setDraftTokens(2).setAcceptanceRate(1.0);
		assertThrows(IllegalArgumentException.class, worse::validate);
	}

	@Test
	void aVerificationStepCostsMoreThanASingleTokenDecode() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		GpuProfile device = computeBound();
		DecodeBatch batch = DecodeBatch.uniform(2, 4);
		CostEstimate decode = cost.decodeIteration(tiny(), device, batch);
		CostEstimate speculative = cost.speculativeIteration(tiny(), device, batch, 2);
		assertTrue(speculative.flops() > decode.flops(), "a step over three positions cannot cost fewer operations");
		assertTrue(speculative.bytes() > decode.bytes(), "a step that materialises three positions cannot move fewer bytes");
		assertThrows(IllegalArgumentException.class, () -> cost.speculativeIteration(tiny(), device, batch, 0));
	}

	// ---- prefix reuse: the identity key and the accounting ----

	@Test
	void thePrefixKeyIsModelAndLengthWithoutASession() {
		InferenceRequest request = new InferenceRequest(1, "llama", 128);
		assertEquals("llama/len=128", request.prefixKey());
	}

	@Test
	void thePrefixKeySeparatesTwoSessionsOfTheSamePromptLength() {
		InferenceRequest a = new InferenceRequest(1, "llama", 128).setSessionId("alpha");
		InferenceRequest b = new InferenceRequest(2, "llama", 128).setSessionId("beta");
		assertEquals("llama/len=128/s=alpha", a.prefixKey());
		assertTrue(!a.prefixKey().equals(b.prefixKey()), "two sessions of one length collapsed onto one key");
	}

	@Test
	void reusedPrefixTokensAreClampedToNonNegative() {
		InferenceRequest request = new InferenceRequest(1, "llama", 128);
		assertEquals(0, request.getReusedPrefixTokens());
		request.addReusedPrefixTokens(64);
		assertEquals(64, request.getReusedPrefixTokens());
		request.addReusedPrefixTokens(-5);
		assertEquals(0, request.getReusedPrefixTokens());
	}
}
