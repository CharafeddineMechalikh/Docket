/**
 *     GenAIInferenceSim: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of GenAIInferenceSim.
 *
 *     GenAIInferenceSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     GenAIInferenceSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with GenAIInferenceSim. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.genaism.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.mechalikh.genaism.domain.InferenceRequest;

/**
 * Applies several admission rules in order and reports the first refusal.
 * <p>
 * The order matters for the reason code, not for the verdict: a request that breaks two limits
 * is refused either way, but the recorded reason is the one of the rule that came first. The
 * configuration lists the rules in the order they are evaluated, so the reason counts of a run
 * can be read back.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class CompositeAdmission implements AdmissionPolicy {

	/** The rules, evaluated in this order. */
	protected final List<AdmissionPolicy> policies;

	/**
	 * Composes the given rules.
	 *
	 * @param policies the rules to apply, at least one; the list is copied
	 */
	public CompositeAdmission(List<AdmissionPolicy> policies) {
		if (policies == null || policies.isEmpty())
			throw new IllegalArgumentException("A composite admission policy needs at least one rule");
		this.policies = new ArrayList<>(policies);
		if (this.policies.contains(null))
			throw new IllegalArgumentException("A composite admission policy cannot hold a null rule");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Returns the first refusal raised by a rule, or an accepting decision when every rule
	 * accepts.
	 */
	@Override
	public AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		for (AdmissionPolicy policy : policies) {
			AdmissionDecision decision = policy.decide(request, loads, clock);
			if (!decision.isAdmitted())
				return decision;
		}
		return AdmissionDecision.admit();
	}

	/**
	 * Returns the names of the composed rules in evaluation order.
	 *
	 * @return the policy name
	 */
	@Override
	public String name() {
		return "composite(" + policies.stream().map(AdmissionPolicy::name).collect(Collectors.joining(", ")) + ")";
	}

	/**
	 * Returns the descriptions of the composed rules joined with "and then".
	 *
	 * @return a one-line description
	 */
	@Override
	public String describe() {
		return policies.stream().map(AdmissionPolicy::describe).collect(Collectors.joining(" and then "));
	}

	/**
	 * Returns the rules in evaluation order.
	 *
	 * @return an immutable view
	 */
	public List<AdmissionPolicy> getPolicies() {
		return Collections.unmodifiableList(policies);
	}
}
