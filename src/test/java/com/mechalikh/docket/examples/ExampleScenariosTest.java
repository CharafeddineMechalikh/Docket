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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * The example scenarios that differ only by configuration, run end to end.
 * <p>
 * These tests pin the claims the example READMEs make: the order of a composite admission policy
 * changes the reason a request is refused but not the set of requests served, aborting on a
 * missed deadline trades completions for bounded tail latency, and the template policy reproduces
 * the built-in first-come-first-served order exactly.
 *
 * @author Charafeddine Mechalikh
 */
class ExampleScenariosTest {

	private static final String COMPOSITE = "examples/06-composite-admission/scenarios/";
	private static final String ABORT = "examples/07-deadline-abort/scenarios/";

	@Test
	void theCompositeOrderChangesTheReasonButNotTheServedRequests() {
		RunResult cacheFirst = run(COMPOSITE + "ex06-cache-then-queue.properties");
		RunResult queueFirst = run(COMPOSITE + "ex06-queue-then-cache.properties");
		assertEquals(cacheFirst.getCompletedRequests(), queueFirst.getCompletedRequests(),
				"two pure rules refuse the same requests whichever is evaluated first");
		Map<String, Integer> first = reasons(cacheFirst);
		Map<String, Integer> second = reasons(queueFirst);
		assertNotEquals(first, second, "the recorded reason belongs to the rule that came first");
		assertTrue(second.getOrDefault("admission_queue_limit", 0) > first.getOrDefault("admission_queue_limit", 0),
				"checking the queue first attributes more refusals to the queue");
		assertTrue(first.getOrDefault("admission_no_cache_capacity", 0) > 0);
		assertTrue(second.getOrDefault("admission_no_cache_capacity", 0) > 0);
	}

	@Test
	void abortingOnAMissedDeadlineTradesCompletionsForABoundedTail() {
		RunResult running = run(ABORT + "ex07-abort-off.properties");
		RunResult aborting = run(ABORT + "ex07-abort-on.properties");
		assertEquals(0, reasons(running).getOrDefault("deadline_missed", 0));
		assertTrue(reasons(aborting).getOrDefault("deadline_missed", 0) > 0);
		assertTrue(aborting.getCompletedRequests() < running.getCompletedRequests(),
				"aborted requests free the device, so fewer of the offered requests complete");
	}

	@Test
	void theTemplatePolicyReproducesTheBuiltInFirstComeFirstServedOrder() {
		String template = ScenarioTestSupport.fingerprint(
				run("examples/09-custom-policy-template/scenarios/ex09-template.properties"));
		String builtIn = ScenarioTestSupport.fingerprint(
				run("examples/01-sla-earliest-deadline-first/scenarios/ex01-fcfs.properties"));
		assertEquals(builtIn, template, "the template is the built-in order written out by hand");
	}

	private static RunResult run(String scenario) {
		return new Experiment(ScenarioTestSupport.load(scenario)).run().get(0);
	}

	private static Map<String, Integer> reasons(RunResult result) {
		Map<String, Integer> counts = new HashMap<>();
		List<InferenceRequest> requests = result.getRequests();
		for (InferenceRequest request : requests)
			if (!request.getOutcomeReason().isEmpty())
				counts.merge(request.getOutcomeReason(), 1, Integer::sum);
		return counts;
	}
}
