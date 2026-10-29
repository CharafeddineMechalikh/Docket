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

import java.util.List;

import com.mechalikh.genaism.domain.InferenceRequest;

/**
 * Accepts every request. This is the default, so a run that turns off admission says so through
 * the policy name instead of hiding an unbounded queue behind a policy that silently drops.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class UnlimitedAdmission implements AdmissionPolicy {

	/**
	 * {@inheritDoc}
	 *
	 * @return always an accepting decision
	 */
	@Override
	public AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		return AdmissionDecision.admit();
	}

	@Override
	public String name() {
		return "unlimited";
	}
}
