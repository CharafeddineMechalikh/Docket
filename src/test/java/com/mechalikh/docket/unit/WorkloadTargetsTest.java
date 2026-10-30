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

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.engine.WorkloadPlan;
import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * The objectives a workload states, globally and per service class.
 * <p>
 * A recorded trace carries arrivals and lengths but not the promises an operator made to the
 * classes it mixes, so the scenario states those. These tests pin the precedence (row, then class,
 * then global), the refusal of a target that no request could meet, and the consequence that
 * matters: goodput becomes a measured number instead of not applicable.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
class WorkloadTargetsTest {

	/** The Qwen replay, whose rows are split across four service classes. */
	private static final String QWEN = "examples/real-traces/scenarios/qwen-traceA.properties";

	@Test
	void aClassTargetOverridesTheGlobalOne() {
		WorkloadPlan plan = new WorkloadPlan().setTtftTargetSeconds(1.5).setEndToEndTargetSeconds(15)
				.setTpotTargetSeconds(0.05).setClassTarget("api", 2.0, 30, 0.1);
		assertEquals(2.0, plan.ttftTargetFor("api"), 1e-9, "the class target must win over the global one");
		assertEquals(30, plan.endToEndTargetFor("api"), 1e-9);
		assertEquals(0.1, plan.tpotTargetFor("api"), 1e-9);
		assertEquals(1.5, plan.ttftTargetFor("batch"), 1e-9, "a class without targets keeps the global ones");
	}

	@Test
	void aZeroInAClassTargetKeepsTheGlobalOne() {
		WorkloadPlan plan = new WorkloadPlan().setTtftTargetSeconds(1.5).setEndToEndTargetSeconds(15)
				.setClassTarget("api", 2.0, 0, 0);
		assertEquals(2.0, plan.ttftTargetFor("api"), 1e-9);
		assertEquals(15, plan.endToEndTargetFor("api"), 1e-9,
				"a field the class leaves at zero must fall back, not cancel the objective");
		assertEquals(15, plan.endToEndTargetFor("batch"), 1e-9);
	}

	@Test
	void anEmptyClassOrANegativeTargetIsRefused() {
		WorkloadPlan plan = new WorkloadPlan();
		assertThrows(IllegalArgumentException.class, () -> plan.setClassTarget("  ", 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> plan.setClassTarget("api", -1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> plan.setClassTarget("api", 1, -1, 1));
		assertThrows(IllegalArgumentException.class, () -> plan.setClassTarget("api", 1, 1, -1));
	}

	@Test
	void aClassTargetThatTheTraceDoesNotCarryIsRefused() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> ScenarioTestSupport.load(QWEN, "workload.ttftTargetSeconds.nope=1"));
		assertTrue(error.getMessage().contains("nope"), error.getMessage());
		assertTrue(error.getMessage().contains("text"), "the message must name the classes the trace does carry: "
				+ error.getMessage());
	}

	@Test
	void aClassTargetThatNoGeneratedRequestHasIsRefused() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> ScenarioTestSupport.load(ScenarioTestSupport.LIGHT, "workload.ttftTargetSeconds.api=1"));
		assertTrue(error.getMessage().contains("api"), error.getMessage());
	}

	@Test
	void statingTargetsPerClassTurnsGoodputFromNotApplicableIntoMeasured() {
		RunResult withoutTargets = new Experiment(ScenarioTestSupport.load(QWEN)).runRepetition(1);
		assertEquals(0, slo(withoutTargets, "text").applicable("all"),
				"a trace with no objectives must report none of them as applicable");

		Scenario withTargets = ScenarioTestSupport.load(QWEN, "workload.ttftTargetSeconds.text=2",
				"workload.endToEndTargetSeconds.text=30", "workload.tpotTargetSeconds.text=0.05",
				"workload.ttftTargetSeconds.search=3", "workload.endToEndTargetSeconds.search=60",
				"workload.tpotTargetSeconds.search=0.08");
		RunResult measured = new Experiment(withTargets).runRepetition(1);
		RunResult.ServiceClassSlo text = slo(measured, "text");
		assertTrue(text.applicable("all") > 0, "the class must now have requests that state the objective");
		assertTrue(text.applicable("all") <= text.offered);
		double attainment = text.attainment("all");
		assertTrue(attainment >= 0 && attainment <= 1, "attainment is a ratio, found " + attainment);
		assertTrue(slo(measured, "search").applicable("all") > 0, "the second class states its own objectives");
		assertEquals(0, slo(measured, "image").applicable("all"),
				"a class the scenario does not state must stay not applicable");
	}

	/**
	 * Returns the SLO counters of one service class, failing when the run has none.
	 *
	 * @param run          the finished repetition
	 * @param serviceClass the class to look up
	 * @return its counters
	 */
	private static RunResult.ServiceClassSlo slo(RunResult run, String serviceClass) {
		RunResult.ServiceClassSlo counters = run.getSloByServiceClass().get(serviceClass);
		assertTrue(counters != null, "the run must carry the class \"" + serviceClass + "\"");
		return counters;
	}
}
