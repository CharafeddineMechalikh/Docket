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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.examples.edf.EarliestDeadlineFirst;

/**
 * The order the earliest-deadline-first example hands to the waiting queue.
 *
 * @author Charafeddine Mechalikh
 */
class EarliestDeadlineFirstTest {

	private final Comparator<InferenceRequest> order = new EarliestDeadlineFirst().waitingOrder();

	@Test
	void theEarliestOfTheTwoStatedDeadlinesComesFirst() {
		InferenceRequest looseStart = request(1).setTtftDeadline(10).setEndToEndDeadline(40);
		InferenceRequest tightEndToEnd = request(2).setTtftDeadline(10).setEndToEndDeadline(4);
		InferenceRequest tightStart = request(3).setTtftDeadline(2).setEndToEndDeadline(40);
		assertEquals(List.of(3L, 2L, 1L), sorted(tightStart, tightEndToEnd, looseStart));
	}

	@Test
	void aRequestThatStatesNoDeadlineWaitsLast() {
		InferenceRequest none = request(1);
		InferenceRequest timed = request(2).setTtftDeadline(30);
		assertEquals(List.of(2L, 1L), sorted(none, timed));
	}

	@Test
	void equalDeadlinesFallBackToPriorityThenIdentifier() {
		InferenceRequest low = request(5).setTtftDeadline(3).setPriority(0);
		InferenceRequest high = request(4).setTtftDeadline(3).setPriority(7);
		InferenceRequest highLaterId = request(6).setTtftDeadline(3).setPriority(7);
		assertEquals(List.of(4L, 6L, 5L), sorted(low, high, highLaterId));
	}

	@Test
	void theOrderUsesNoOracleInformation() {
		assertFalse(new EarliestDeadlineFirst().usesOracleInformation());
	}

	private List<Long> sorted(InferenceRequest... requests) {
		List<InferenceRequest> list = new ArrayList<>(List.of(requests));
		list.sort(order);
		List<Long> ids = new ArrayList<>();
		for (InferenceRequest request : list)
			ids.add(request.getRequestId());
		return ids;
	}

	private static InferenceRequest request(long id) {
		return new InferenceRequest(id, "sla-mix", 128);
	}
}
