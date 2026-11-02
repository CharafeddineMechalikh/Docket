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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.OutcomeReason;
import com.mechalikh.docket.examples.reservation.InteractiveReservationAdmission;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The admission rule of the protection example.
 *
 * @author Charafeddine Mechalikh
 */
class InteractiveReservationAdmissionTest {

	private static final List<ReplicaLoad> FULL = List.of(load(0, 2), load(1, 2));

	@Test
	void theProtectedClassIsAlwaysAdmitted() {
		InteractiveReservationAdmission policy = new InteractiveReservationAdmission(1);
		assertTrue(policy.decide(request("interactive"), FULL, 0).isAdmitted());
	}

	@Test
	void theOtherClassesAreRefusedOnceTheQueueIsFull() {
		InteractiveReservationAdmission policy = new InteractiveReservationAdmission(4);
		assertEquals(OutcomeReason.ADMISSION_QUEUE_LIMIT,
				policy.decide(request("batch"), FULL, 0).reason());
	}

	@Test
	void theOtherClassesAreAdmittedWhileTheQueueHasRoom() {
		InteractiveReservationAdmission policy = new InteractiveReservationAdmission(5);
		assertTrue(policy.decide(request("batch"), FULL, 0).isAdmitted());
	}

	@Test
	void theDefaultLimitReservesFourWaitingRequests() {
		InteractiveReservationAdmission policy = new InteractiveReservationAdmission();
		assertEquals(4, policy.getLimit());
		assertFalse(policy.decide(request("batch"), FULL, 0).isAdmitted());
		assertTrue(policy.decide(request("batch"), List.of(load(0, 1), load(1, 2)), 0).isAdmitted());
	}

	@Test
	void aNegativeReservationIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new InteractiveReservationAdmission(-1));
	}

	@Test
	void theDescriptionStatesWhatTheQueueReserves() {
		assertEquals("interactive-reservation(interactive always queued, others below 4 waiting)",
				new InteractiveReservationAdmission().describe());
	}

	private static ReplicaLoad load(int id, int waiting) {
		return new ReplicaLoad(id, "device-" + id, "test-tier", 64, waiting, 0, 1_000_000, 1_000_000, 0);
	}

	private static InferenceRequest request(String serviceClass) {
		return new InferenceRequest(1, "sla-mix", 128).setServiceClass(serviceClass);
	}
}
