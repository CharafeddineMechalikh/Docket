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
package com.mechalikh.docket.regression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * Regression anchors for the two committed scenarios, pinned to a freshly measured run of the
 * current code.
 * <p>
 * These are not claims about real hardware: every scenario is labelled synthetic and uncalibrated.
 * They are the output of a deterministic simulator at a fixed seed, and they exist so that a change
 * to the engine, the cost model or the accounting that shifts the numbers is caught here instead of
 * in a paper. A legitimate change is allowed to move them; the point is that it must move them
 * deliberately, with this file updated, rather than silently.
 * <p>
 * Measured on this branch, seed 1, roofline-analytical cost model, before this test was written.
 *
 * @author Charafeddine Mechalikh
 */
class ScenarioRegressionTest {

	/** arrived, completed, tokens generated, device energy, mean TTFT, peak queueing. */
	private static final double[][] LIGHT = { { 3, 3, 1181, 1263.338, 0.001251, 0.0 },
			{ 8, 8, 1568, 1292.583, 0.003097, 0.0 }, { 6, 6, 1130, 1264.760, 0.002652, 0.0 } };

	private static final double[][] LOADED = { { 59, 35, 9217, 4485.046, 0.230358, 0.564691 },
			{ 75, 44, 9439, 4501.424, 0.244998, 1.500924 }, { 71, 31, 8873, 4466.402, 0.240104, 1.019170 } };

	@Test
	void lightScenarioReproducesItsMeasuredAnchors() {
		checkAnchors(ScenarioTestSupport.LIGHT, LIGHT);
	}

	@Test
	void loadedScenarioReproducesItsMeasuredAnchors() {
		checkAnchors(ScenarioTestSupport.LOADED, LOADED);
	}

	private void checkAnchors(String scenarioFile, double[][] expected) {
		List<RunResult> runs = new Experiment(ScenarioTestSupport.load(scenarioFile)).run();
		assertEquals(expected.length, runs.size());
		for (int i = 0; i < expected.length; i++) {
			RunResult run = runs.get(i);
			double[] anchor = expected[i];
			String at = scenarioFile + " repetition " + (i + 1) + ": ";
			assertEquals((long) anchor[0], run.getArrivedRequests(), at + "arrived");
			assertEquals((int) anchor[1], run.getCompletedRequests(), at + "completed");
			assertEquals((long) anchor[2], run.getTokensGenerated(), at + "generated tokens");
			assertEquals(anchor[3], run.getDeviceEnergyJoule(), 0.5, at + "device energy");
			assertEquals(anchor[4], run.stat("ttft_s").mean(), 1e-4, at + "mean TTFT");
			assertEquals(anchor[5], run.stat("queueing_s").max(), 1e-4, at + "peak queueing");
			assertEquals(3L, run.getSamplerBindings(), at + "random samplers rebuilt for the repetition");
			assertTrue(run.getUtilisation() <= 1.0 + 1e-9, at + "utilisation must stay capped at one");
		}
	}

	@Test
	void loadedScenarioMeetsTheObjectivesExactlyForTheTrafficItCompleted() {
		// In the loaded scenario every stated objective (TTFT, end-to-end, pace) is met only by the
		// requests that finished: the measured run has good == completed in every repetition, so a
		// request cut by the horizon or refused never counts as a success.
		Experiment experiment = new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LOADED));
		for (RunResult run : experiment.run()) {
			assertEquals(run.getCompletedRequests(), run.getGoodRequests(),
					"repetition " + run.getRepetition() + " met a goodput other than its completions");
			assertEquals(run.getArrivedRequests(), run.getObjectiveRequests(),
					"every offered request in this scenario states an objective");
		}
	}
}
