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
import com.mechalikh.docket.examples.template.TemplateScheduling;

/**
 * The starting point a reader copies to write a scheduling policy of their own.
 *
 * @author Charafeddine Mechalikh
 */
class TemplateSchedulingTest {

	private final TemplateScheduling policy = new TemplateScheduling();

	@Test
	void theTemplateOrdersByArrivalThenIdentifier() {
		InferenceRequest second = request(1);
		second.setArrivalTime(2);
		InferenceRequest first = request(2);
		first.setArrivalTime(1);
		InferenceRequest firstAgain = request(3);
		firstAgain.setArrivalTime(2);
		List<InferenceRequest> list = new ArrayList<>(List.of(second, firstAgain, first));
		list.sort(policy.waitingOrder());
		List<Long> ids = new ArrayList<>();
		for (InferenceRequest request : list)
			ids.add(request.getRequestId());
		assertEquals(List.of(2L, 1L, 3L), ids);
	}

	@Test
	void theTemplateDeclaresThatItUsesNoOracleInformation() {
		assertFalse(policy.usesOracleInformation());
		assertEquals("template-fcfs", policy.name());
	}

	@Test
	void theTemplateIsATotalOrder() {
		Comparator<InferenceRequest> order = policy.waitingOrder();
		InferenceRequest a = request(7);
		a.setArrivalTime(1);
		InferenceRequest b = request(8);
		b.setArrivalTime(1);
		assertEquals(0, order.compare(a, a));
		assertEquals(-1, Integer.signum(order.compare(a, b)));
		assertEquals(1, Integer.signum(order.compare(b, a)));
	}

	private static InferenceRequest request(long id) {
		return new InferenceRequest(id, "sla-mix", 128);
	}
}
