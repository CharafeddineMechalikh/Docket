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

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.cost.CalibratedRooflineCostModel;
import com.mechalikh.docket.cost.CostEstimate;
import com.mechalikh.docket.cost.DecodeBatch;
import com.mechalikh.docket.cost.RooflineAnalyticalCostModel;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * The measured calibration committed with the project.
 * <p>
 * The scenario's device coefficients were fitted to llama-bench captures on a real laptop CPU.
 * These tests pin the two things that make the label meaningful: a calibrated model refuses to
 * load without naming its benchmark, and the shipped coefficients reproduce the measured
 * prefill and decode at the sizes that were benchmarked. They do not re-run the benchmark, so
 * they check the arithmetic between the capture and the scenario, not the hardware again.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
class CalibrationTest {

	private static final String SCENARIO = "examples/calibration/scenarios/laptop-cpu-qwen05b.properties";

	/** llama-bench pp512, the capture in examples/calibration/measurements, in seconds. */
	private static final double MEASURED_PREFILL_512_S = 5.8146;
	/** llama-bench tg128 at depth 0, in seconds per generated token. */
	private static final double MEASURED_DECODE_S = 1 / 10.21;

	@Test
	void theCommittedScenarioIsMeasuredEndToEnd() {
		Scenario scenario = ScenarioTestSupport.load(SCENARIO);
		assertEquals(CalibratedRooflineCostModel.NAME, scenario.getCost().name());
		assertTrue(scenario.getCost().isCalibrated(), "the calibrated model must report itself calibrated");
		assertFalse(scenario.getCost().calibrationSource().isEmpty(), "the benchmark must be named");
		assertEquals("measured", scenario.overallProvenance(),
				"a measured model, a measured device and a calibrated cost model must lift the run");
	}

	@Test
	void theCoefficientsReproduceTheMeasuredPrefill() {
		Scenario scenario = ScenarioTestSupport.load(SCENARIO);
		CostEstimate estimate = scenario.getCost().prefillStep(scenario.getModel(), scenario.getDevice(), 512, 0);
		assertEquals(MEASURED_PREFILL_512_S, estimate.seconds(), MEASURED_PREFILL_512_S * 0.05,
				"the fitted throughput must stay within 5% of the 512-token prefill it was fitted to");
	}

	@Test
	void theCoefficientsReproduceTheMeasuredDecode() {
		Scenario scenario = ScenarioTestSupport.load(SCENARIO);
		CostEstimate estimate = scenario.getCost().decodeIteration(scenario.getModel(), scenario.getDevice(),
				DecodeBatch.uniform(1, 63));
		assertEquals(MEASURED_DECODE_S, estimate.seconds(), MEASURED_DECODE_S * 0.01,
				"the fitted bandwidth must reproduce the batch-1 decode it was fitted to");
	}

	@Test
	void aCalibratedModelWithoutItsSourceIsRefused() {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> ScenarioTestSupport.load(SCENARIO, "cost.calibration.source="));
		assertTrue(failure.getMessage().contains("cost.calibration.source"),
				"the refusal must name the missing setting, found: " + failure.getMessage());
	}

	@Test
	void anUncalibratedCostModelCannotCarryMeasuredProvenance() {
		Scenario scenario = ScenarioTestSupport.load(SCENARIO, "cost.model=" + RooflineAnalyticalCostModel.NAME);
		assertFalse(scenario.getCost().isCalibrated());
		assertEquals("synthetic", scenario.overallProvenance(),
				"measured profiles priced by an uncalibrated model are still a synthetic result");
	}
}
