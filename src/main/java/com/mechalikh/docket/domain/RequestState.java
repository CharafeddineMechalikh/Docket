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
package com.mechalikh.docket.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle states of an {@link InferenceRequest}, together with the transitions the
 * simulation is allowed to make.
 * <p>
 * The states are declared so that a request is never simultaneously queued, running and
 * completed: every move goes through {@link InferenceRequest#transitionTo(RequestState)},
 * which rejects transitions that are not listed here.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public enum RequestState {
	/** The request exists but has not entered the system yet. */
	CREATED,
	/** The request reached the service and waits for an admission decision. */
	ARRIVED,
	/** The admission policy accepted the request; it has not been queued yet. */
	ADMITTED,
	/** The request waits for its turn on a replica. */
	QUEUED,
	/** The prompt is being processed on a GPU. */
	PREFILLING,
	/** Tokens are being generated, one iteration at a time. */
	DECODING,
	/** The GPU work was suspended and the cached state was dropped. */
	PREEMPTED,
	/** Every requested token was generated. */
	COMPLETED,
	/** The request was refused before any GPU work started. */
	REJECTED,
	/** A deadline was missed and the request was abandoned. */
	TIMED_OUT,
	/** The client cancelled the request. */
	CANCELLED;

	/**
	 * Returns whether the state ends the lifecycle of a request.
	 *
	 * @return {@code true} for {@link #COMPLETED}, {@link #REJECTED}, {@link #TIMED_OUT}
	 *         and {@link #CANCELLED}
	 */
	public boolean isTerminal() {
		return this == COMPLETED || this == REJECTED || this == TIMED_OUT || this == CANCELLED;
	}

	/**
	 * Returns whether the request currently occupies a GPU.
	 *
	 * @return {@code true} while prefilling or decoding
	 */
	public boolean isRunningOnGpu() {
		return this == PREFILLING || this == DECODING;
	}

	/**
	 * Returns whether the request is inside the system, waiting or running.
	 *
	 * @return {@code true} for the non-terminal states after arrival
	 */
	public boolean isInSystem() {
		return this == ADMITTED || this == QUEUED || isRunningOnGpu() || this == PREEMPTED;
	}

	/**
	 * Returns the states reachable from this one in a single step.
	 *
	 * @return the allowed next states, never {@code null}
	 */
	public Set<RequestState> allowedNextStates() {
		switch (this) {
		case CREATED:
			return EnumSet.of(ARRIVED, CANCELLED, REJECTED);
		case ARRIVED:
			return EnumSet.of(ADMITTED, REJECTED, TIMED_OUT, CANCELLED);
		case ADMITTED:
			return EnumSet.of(QUEUED, REJECTED, TIMED_OUT, CANCELLED);
		case QUEUED:
			// A queued prompt whose whole sequence cannot fit the pool is refused before it starts
			// prefilling, so the queue can lead straight to a rejection.
			return EnumSet.of(PREFILLING, REJECTED, TIMED_OUT, CANCELLED);
		case PREFILLING:
			return EnumSet.of(DECODING, PREEMPTED, COMPLETED, TIMED_OUT, CANCELLED);
		case DECODING:
			return EnumSet.of(DECODING, COMPLETED, PREEMPTED, TIMED_OUT, CANCELLED);
		case PREEMPTED:
			return EnumSet.of(QUEUED, REJECTED, TIMED_OUT, CANCELLED);
		default:
			return Collections.emptySet();
		}
	}

	/**
	 * Checks whether a transition from this state to {@code next} is representable.
	 *
	 * @param next the target state
	 * @return {@code true} when the move is allowed
	 */
	public boolean canTransitionTo(RequestState next) {
		return this == next ? this == DECODING : allowedNextStates().contains(next);
	}
}
