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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.examples.consolidate.ConsolidateRouting;
import com.mechalikh.docket.examples.consolidate.ConsolidateWithHeadroomRouting;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The packing rule of the consolidation example and its bounded variant.
 *
 * @author Charafeddine Mechalikh
 */
class ConsolidateRoutingTest {

	/** The batch a bounded replica may hold before the policy opens another one, as the example sets it. */
	private static final int PACK_LIMIT = 16;

	private final ConsolidateRouting pack = new ConsolidateRouting();
	private final ConsolidateWithHeadroomRouting guarded = new ConsolidateWithHeadroomRouting();

	@Test
	void theMostLoadedReplicaIsFilledBeforeAnIdleOneOpens() {
		ReplicaLoad busy = load(0, 5);
		ReplicaLoad idle = load(1, 0);
		assertEquals(0, pack.choose(List.of(busy, idle), request()),
				"consolidation packs the loaded machine, the opposite of the balancer");
	}

	@Test
	void equalLoadsFallBackToTheLowestIdentifier() {
		ReplicaLoad later = load(3, 4);
		ReplicaLoad first = load(0, 4);
		assertEquals(0, pack.choose(List.of(later, first), request()),
				"the lowest identifier fills first, so the traffic concentrates predictably");
	}

	@Test
	void noReplicaThatCanHoldTheContextMeansNoPlacement() {
		ReplicaLoad full = new ReplicaLoad(0, "device-0", "accelerator", 64, 0, 0, 0, 0, 0);
		assertEquals(-1, pack.choose(List.of(full), request()),
				"a replica with no free cache is not a candidate, so the request is refused");
	}

	@Test
	void theGuardPacksTheFullestReplicaBelowItsLimit() {
		ReplicaLoad emptier = load(0, 3);
		ReplicaLoad fuller = load(1, 7);
		assertEquals(1, guarded.choose(List.of(emptier, fuller), request()),
				"below the limit the guard packs exactly like the unbounded rule");
	}

	@Test
	void theGuardOpensTheNextReplicaOnceTheFullestReachesTheLimit() {
		ReplicaLoad atLimit = load(0, PACK_LIMIT);
		ReplicaLoad belowLimit = load(1, 2);
		assertEquals(1, guarded.choose(List.of(atLimit, belowLimit), request()),
				"a replica already at the batch ceiling is skipped, so a second one starts filling");
	}

	@Test
	void theGuardEmptiesToAFreshReplicaWhenEveryCandidateIsOverTheLimit() {
		ReplicaLoad fuller = load(0, PACK_LIMIT + 4);
		ReplicaLoad emptier = load(1, PACK_LIMIT + 1);
		assertEquals(1, guarded.choose(List.of(fuller, emptier), request()),
				"with nothing below the limit the request joins the least loaded machine");
	}

	@Test
	void theGuardRefusesARequestNoReplicaCanHold() {
		ReplicaLoad full = new ReplicaLoad(0, "device-0", "accelerator", 64, 0, 0, 0, 0, 0);
		assertEquals(-1, guarded.choose(List.of(full), request()));
	}

	private static ReplicaLoad load(int id, int inside) {
		return new ReplicaLoad(id, "device-" + id, "accelerator", 64, inside, 0, 1_000_000, 1_000_000, 0);
	}

	private static InferenceRequest request() {
		return new InferenceRequest(1, "edge-chat", 100);
	}
}
