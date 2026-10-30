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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.SchedulingPolicy;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * The hook that lets a scenario name a policy class instead of a built-in.
 * <p>
 * Without this hook the example sources in {@code examples/} could not be reached from a
 * configuration file, which is the whole point of the examples.
 *
 * @author Charafeddine Mechalikh
 */
class PolicyClassLoadingTest {

	private static final String EDF = "examples/01-sla-earliest-deadline-first/scenarios/ex01-edf.properties";

	@Test
	void aClassSettingWinsOverTheBuiltInName() {
		Scenario scenario = ScenarioTestSupport.load(EDF, "policy.scheduling=fcfs");
		assertEquals("edf", scenario.getScheduling().name());
		assertEquals("edf", scenario.getScheduling().describe());
	}

	@Test
	void everyPolicyKindCanBeNamedByClass() {
		assertEquals("interactive-reservation", ScenarioTestSupport
				.load("examples/02-sla-admission-protection/scenarios/ex02-reservation.properties")
				.getAdmission().name());
		assertEquals("session-affinity", ScenarioTestSupport
				.load("examples/04-session-affinity/scenarios/ex04-affinity.properties").getRouting().name());
		assertEquals("weighted-load", ScenarioTestSupport
				.load("examples/05-weighted-load-routing/scenarios/ex05-weighted.properties").getRouting().name());
	}

	@Test
	void anUnknownClassNamesTheSettingThatFailed() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> ScenarioTestSupport.load(EDF, "policy.scheduling.class=com.example.Missing"));
		assertTrue(error.getMessage().contains("policy.scheduling.class"), error.getMessage());
		assertTrue(error.getMessage().contains("not on the classpath"), error.getMessage());
	}

	@Test
	void aClassOfTheWrongKindIsRefused() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> ScenarioTestSupport.load(EDF,
						"policy.scheduling.class=com.mechalikh.docket.examples.affinity.SessionAffinityRouting"));
		assertTrue(error.getMessage().contains("does not implement SchedulingPolicy"), error.getMessage());
	}

	@Test
	void aClassWithoutAPublicNoArgumentConstructorIsRefused() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> ScenarioTestSupport.load(EDF, "policy.scheduling.class=" + ConfiguredOnly.class.getName()));
		assertTrue(error.getMessage().contains("public no-argument constructor"), error.getMessage());
	}

	/**
	 * A policy that can only be built with an argument, so the loader must refuse it.
	 */
	static final class ConfiguredOnly implements SchedulingPolicy {

		ConfiguredOnly(int unused) {
		}

		@Override
		public String name() {
			return "configured-only";
		}

		@Override
		public Comparator<InferenceRequest> waitingOrder() {
			return Comparator.comparingLong(InferenceRequest::getRequestId);
		}

		@Override
		public boolean usesOracleInformation() {
			return false;
		}
	}
}
