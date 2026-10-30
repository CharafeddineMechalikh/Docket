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
package com.mechalikh.docket.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * Every committed example scenario loads and names the policy the example is about.
 *
 * @author Charafeddine Mechalikh
 */
class ExampleInventoryTest {

	private static final List<String> SCENARIOS = List.of(
			"examples/01-sla-earliest-deadline-first/scenarios/ex01-fcfs.properties",
			"examples/01-sla-earliest-deadline-first/scenarios/ex01-priority.properties",
			"examples/01-sla-earliest-deadline-first/scenarios/ex01-edf.properties",
			"examples/02-sla-admission-protection/scenarios/ex02-unlimited.properties",
			"examples/02-sla-admission-protection/scenarios/ex02-reservation.properties",
			"examples/03-class-fair-share/scenarios/ex03-least-queued.properties",
			"examples/03-class-fair-share/scenarios/ex03-class-objective.properties",
			"examples/04-session-affinity/scenarios/ex04-least-queued.properties",
			"examples/04-session-affinity/scenarios/ex04-affinity.properties",
			"examples/05-weighted-load-routing/scenarios/ex05-least-queued.properties",
			"examples/05-weighted-load-routing/scenarios/ex05-weighted.properties",
			"examples/06-composite-admission/scenarios/ex06-unlimited.properties",
			"examples/06-composite-admission/scenarios/ex06-cache-then-queue.properties",
			"examples/06-composite-admission/scenarios/ex06-queue-then-cache.properties",
			"examples/07-deadline-abort/scenarios/ex07-abort-off.properties",
			"examples/07-deadline-abort/scenarios/ex07-abort-on.properties",
			"examples/08-policy-sweep/scenarios/ex08-fcfs.properties",
			"examples/08-policy-sweep/scenarios/ex08-priority.properties",
			"examples/08-policy-sweep/scenarios/ex08-shortest-expected-first.properties",
			"examples/09-custom-policy-template/scenarios/ex09-template.properties");

	@Test
	void everyExampleScenarioIsCommittedAndLoads() {
		for (String path : SCENARIOS) {
			Scenario scenario = ScenarioTestSupport.load(path);
			assertNotNull(scenario.getAdmission(), path);
			assertNotNull(scenario.getRouting(), path);
			assertNotNull(scenario.getScheduling(), path);
		}
	}

	@Test
	void theSweepScenariosNameTheThreeBuiltInOrderings() {
		assertEquals("fcfs", ScenarioTestSupport
				.load("examples/08-policy-sweep/scenarios/ex08-fcfs.properties").getScheduling().name());
		assertEquals("priority", ScenarioTestSupport
				.load("examples/08-policy-sweep/scenarios/ex08-priority.properties").getScheduling().name());
		assertFalse(ScenarioTestSupport
				.load("examples/08-policy-sweep/scenarios/ex08-shortest-expected-first.properties")
				.getScheduling().usesOracleInformation(),
				"the sweep schedules on a prediction, which a production controller can form");
	}

	@Test
	void theExamplesThatNameAClassGetThatClass() {
		assertEquals("edf", ScenarioTestSupport
				.load("examples/01-sla-earliest-deadline-first/scenarios/ex01-edf.properties")
				.getScheduling().name());
		assertEquals("class-objective", ScenarioTestSupport
				.load("examples/03-class-fair-share/scenarios/ex03-class-objective.properties").getRouting().name());
		assertEquals("template-fcfs", ScenarioTestSupport
				.load("examples/09-custom-policy-template/scenarios/ex09-template.properties")
				.getScheduling().name());
	}
}
