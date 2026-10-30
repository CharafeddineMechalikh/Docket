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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.cost.CostEstimate;
import com.mechalikh.docket.cost.DecodeBatch;
import com.mechalikh.docket.cost.RooflineAnalyticalCostModel;
import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.Provenance;

/**
 * The roofline arithmetic and the evidence every estimate must carry.
 * <p>
 * The expected counts below are computed by hand from a one-layer model with integer dimensions,
 * so they pin the formula rather than echo the code.
 *
 * @author Charafeddine Mechalikh
 */
class CostModelTest {

	/** One layer, eight hidden scalars in a single head, a thousand parameters, two bytes each. */
	private static ModelProfile tiny() {
		return new ModelProfile().setId("tiny").setParameterCount(1000).setLayers(1).setHiddenDimension(8)
				.setAttentionHeads(1).setKvHeads(1).setHeadDimension(8).setMaxContextTokens(1024).setPrecision("fp16")
				.setBytesPerParameter(2).setKvBytesPerScalar(2);
	}

	private static GpuProfile device() {
		return new GpuProfile().setId("d").setMemoryBytes(1e9).setMemoryBandwidthBytesPerSecond(1000)
				.addAchievedTeraFlops("fp16", 1000).setIdlePowerWatts(10).setActivePowerWatts(20);
	}

	@Test
	void prefillArithmeticMatchesTheHandCount() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		ModelProfile model = tiny();
		// projections = 2 * 1000 * 2 = 4000; attention = 4*1*8*(2*0 + 2*3/2) = 32*3 = 96.
		assertEquals(4096.0, cost.prefillFlops(model, 2, 0), 1e-9);
		// weights 2000 + cache 32*(0+2) + activation 0 = 2064.
		assertEquals(2064.0, cost.prefillBytes(model, 2, 0), 1e-9);
	}

	@Test
	void decodeArithmeticMatchesTheHandCount() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		ModelProfile model = tiny();
		DecodeBatch batch = DecodeBatch.uniform(2, 4);
		// projections = 2*1000*2 = 4000; attention = 4*1*8*8 = 256.
		assertEquals(4256.0, cost.decodeFlops(model, batch), 1e-9);
		// weights 2000 + read 32*8 + write 32*2 = 2000 + 256 + 64 = 2320.
		assertEquals(2320.0, cost.decodeBytes(model, batch), 1e-9);
	}

	@Test
	void chunkedPrefillsReReadTheWeightsSoTheyCostMore() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		ModelProfile model = tiny();
		double oneChunk = cost.prefillBytes(model, 4, 0);
		double twoChunks = cost.prefillBytes(model, 2, 0) + cost.prefillBytes(model, 2, 2);
		assertTrue(twoChunks > oneChunk, "two chunks move the weights twice and must cost more bytes");
	}

	@Test
	void theDurationIsTheRooflineMaximumPlusOverhead() {
		GpuProfile d = device();
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		ModelProfile model = tiny();
		CostEstimate estimate = cost.prefillStep(model, d, 2, 0);
		double compute = estimate.flops() / d.flopsPerSecond("fp16");
		double memory = estimate.bytes() / d.getMemoryBandwidthBytesPerSecond();
		double expected = Math.max(compute, memory) + d.getKernelLaunchOverheadSeconds();
		assertEquals(expected, estimate.seconds(), 1e-12);
	}

	@Test
	void aNarrowBandwidthMakesTheStepMemoryBound() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		GpuProfile slow = device().setMemoryBandwidthBytesPerSecond(1);
		CostEstimate estimate = cost.prefillStep(tiny(), slow, 2, 0);
		assertEquals(CostEstimate.LimitingTerm.MEMORY, estimate.limitingTerm());
		assertTrue(estimate.arithmeticIntensity() > 0);
	}

	@Test
	void launchOverheadIsFlaggedWhenItDominatesTheWork() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		GpuProfile d = device().setKernelLaunchOverheadSeconds(10_000);
		CostEstimate estimate = cost.decodeIteration(tiny(), d, DecodeBatch.uniform(1, 1));
		assertEquals(CostEstimate.LimitingTerm.OVERHEAD, estimate.limitingTerm());
		assertTrue(estimate.warnings().stream().anyMatch(w -> w.contains("launch overhead")));
	}

	@Test
	void aBatchAboveTheCheckedRangeIsReportedAsExtrapolated() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		cost.setSupportedMaxBatchSize(4);
		CostEstimate estimate = cost.decodeIteration(tiny(), device(), DecodeBatch.uniform(5, 1));
		assertTrue(estimate.warnings().stream().anyMatch(w -> w.contains("exceeds the 4")));
	}

	@Test
	void aCacheQuantizedMoreThanWeightsWarnsAboutTheBackend() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		ModelProfile model = tiny().setKvBytesPerScalar(1);
		assertTrue(cost.extrapolationWarning(model, device(), null, 2).contains("quantized more aggressively"));
	}

	@Test
	void anUnknownPrecisionIsRefusedNotGuessed() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		ModelProfile model = tiny().setPrecision("int4");
		assertThrows(IllegalArgumentException.class, () -> cost.prefillStep(model, device(), 2, 0));
	}

	@Test
	void everyEstimateDeclaresItselfSyntheticAndProvisional() {
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		assertFalse(cost.isCalibrated());
		assertEquals(Provenance.SYNTHETIC, cost.provenance());
		CostEstimate estimate = cost.prefillStep(tiny(), device(), 2, 0);
		assertFalse(estimate.isCalibrated());
		assertTrue(estimate.isProvisional());
		assertTrue(estimate.warnings().contains(RooflineAnalyticalCostModel.NAME
				+ " is analytical and uncalibrated: no measured timing of this model, device or "
				+ "backend was used, so durations are estimates of the hardware limits, not of a serving engine"));
	}

	@Test
	void aDecodeBatchRefusesInconsistentContexts() {
		assertThrows(IllegalArgumentException.class, () -> new DecodeBatch(0, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new DecodeBatch(3, 2, 2));
		assertThrows(IllegalArgumentException.class, () -> new DecodeBatch(2, 8, 9));
	}

	@Test
	void anEstimateRefusesANonPositiveDuration() {
		assertThrows(IllegalArgumentException.class, () -> new CostEstimate(0, 1, 1, CostEstimate.LimitingTerm.COMPUTE,
				Provenance.SYNTHETIC, false, null));
	}
}
