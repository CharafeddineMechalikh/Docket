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
package com.mechalikh.docket.policy;

import java.util.List;
import java.util.Locale;

import com.mechalikh.docket.domain.InferenceRequest;

/**
 * Walks a fidelity ladder down from full fidelity and grants the richest budget that some replica is
 * predicted to finish inside the request's own deadline.
 * <p>
 * The rule is the one an operator runs when a request is late rather than merely expensive: keep the
 * answer as complete as the fleet can deliver it, and shed only as much as the shortfall needs. For
 * each level, from the top of the ladder down, the budget is turned into seconds by the deployment's
 * execution cost model — the prefill of the prompt, the decode of the budget at the batch each
 * machine is holding now, the work the queue already promised and the delay of the bearer in front of
 * it — and the level survives when the nearest replica is predicted to fit inside the remaining slack
 * with a margin kept back. The first level that survives is granted, so a request that can be served
 * whole is served whole.
 * <p>
 * Three honest limits come with the rule, and they are the limits of any controller that decides
 * before it measures. When no level survives, the policy still sheds, to the floor of the ladder:
 * a shorter answer that misses is worth more than a longer one that misses, and the alternative —
 * granting everything and watching the deadline go by — delivers less to the client. The prediction
 * uses the length the controller was allowed to know, which is the predicted length and never the
 * drawn one, so a scenario with a poor predictor sheds at the wrong depth, and the artifacts record
 * both numbers so a reader can tell a gain from a better predictor apart from a gain from a better
 * rule. And the queue estimate is the work already promised, not the work that will arrive, so under
 * a burst the prediction is optimistic in the same direction it always is.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class DeadlineLadderFidelity implements FidelityPolicy {

	/** Share of the slack kept back so a prediction error does not become a missed deadline. */
	protected final double margin;
	/** Ladder the policy walks, richest level first. */
	protected final FidelityLadder ladder;

	/**
	 * Creates the rule with the levels of {@code 1, 0.5, 0.25, 0.1} and no margin.
	 */
	public DeadlineLadderFidelity() {
		this(new FidelityLadder(java.util.Arrays.asList(1.0, 0.5, 0.25, 0.1), 1), 0);
	}

	/**
	 * Creates the rule.
	 *
	 * @param ladder the levels to walk, richest first, not {@code null}
	 * @param margin share of the remaining slack kept back, in {@code [0, 1)}
	 */
	public DeadlineLadderFidelity(FidelityLadder ladder, double margin) {
		if (ladder == null)
			throw new IllegalArgumentException("A deadline ladder needs the levels it walks");
		if (margin < 0 || margin >= 1)
			throw new IllegalArgumentException("The kept-back margin must be in [0, 1), found " + margin);
		this.ladder = ladder;
		this.margin = margin;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "deadline-ladder";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (levels " + ladder.describeLevels() + ", floor " + ladder.minTokens() + " tokens, margin "
				+ String.format(Locale.ROOT, "%.2f", margin) + ")";
	}

	/**
	 * Returns the ladder the rule walks.
	 *
	 * @return the ladder
	 */
	public FidelityLadder getLadder() {
		return ladder;
	}

	/**
	 * Returns the share of the slack the rule keeps back.
	 *
	 * @return the margin
	 */
	public double getMargin() {
		return margin;
	}

	/**
	 * Grants the richest budget some replica is predicted to finish inside the deadline.
	 * <p>
	 * A request that states no deadline is left alone, because there is nothing to fit: shedding an
	 * answer nobody is waiting for is a cost without a benefit. The same holds when no replica
	 * publishes prices — a load snapshot hand-built by a test, or a deployment whose serving loop gave
	 * none — since a rule that cannot price a level cannot claim to have found a feasible one.
	 *
	 * @param request the request being placed
	 * @param loads   the loads of the replicas that could serve it, priced for this request
	 * @param clock   the arrival instant, in seconds
	 * @return the granted budget, or {@code 0} to leave the workload's ask in place
	 */
	@Override
	public int chooseBudget(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		if (loads == null || loads.isEmpty())
			return 0;
		double slack = slackSeconds(request, clock);
		if (Double.isNaN(slack))
			return 0;
		// The controller decides on what it may know: the length the workload drew is not available
		// before the answer exists, so the ladder is walked over the predicted length.
		int asked = Math.max(1, request.getPredictedOutputTokens());
		// A load snapshot that publishes no prices — one a test hand-built, or a serving loop that
		// gave none — cannot tell this rule anything, and shedding to the floor because nothing could
		// be priced would be a decision made out of ignorance. The request keeps its ask.
		if (!isPriced(loads, asked))
			return 0;
		for (int level = 0; level < ladder.size(); level++) {
			int budget = ladder.budgetAt(level, asked);
			if (isFeasible(request, loads, budget, slack))
				// The top of the ladder grants everything, and a cap of "everything" is no cap: the
				// request keeps the ask the workload wrote instead of carrying a number that only
				// repeats it, which is what keeps the artifact honest about what was shed.
				return budget >= asked ? 0 : budget;
		}
		// Nothing fits. The fallback is the floor of the ladder, which is the shortest answer the
		// deployment is willing to give and therefore the one most likely to arrive; the row still
		// says what was asked and what was generated, so the shed is visible rather than implied.
		int deepest = ladder.budgetAt(ladder.size() - 1, asked);
		return deepest >= asked ? 0 : deepest;
	}

	/**
	 * Returns whether any replica published the prices this rule reads.
	 *
	 * @param loads the priced loads of the candidates
	 * @param asked the length the controller believes the request will generate
	 * @return {@code true} when at least one replica can be priced
	 */
	protected static boolean isPriced(List<ReplicaLoad> loads, int asked) {
		for (ReplicaLoad load : loads)
			if (!Double.isNaN(load.predictedDelaySeconds(asked)))
				return true;
		return false;
	}

	/**
	 * Returns whether some replica is predicted to finish the request at the given budget before the
	 * deadline, with the margin kept back.
	 *
	 * @param request the request being placed
	 * @param loads   the priced loads of the candidates
	 * @param budget  the reasoning budget under test
	 * @param slack   seconds left before the deadline
	 * @return {@code true} when a replica both has the cache and is predicted to fit
	 */
	protected boolean isFeasible(InferenceRequest request, List<ReplicaLoad> loads, int budget, double slack) {
		double allowance = slack * (1 - margin);
		for (ReplicaLoad load : loads) {
			double seconds = load.predictedDelaySeconds(budget);
			if (Double.isNaN(seconds))
				return false;
			if (load.canHoldContext(request.getInputTokens() + budget) && seconds <= allowance)
				return true;
		}
		return false;
	}

	/**
	 * Returns the seconds a request has left before the objective it states is due.
	 * <p>
	 * The end-to-end objective is the one a reasoning budget decides, because the budget is spent
	 * after the first token; a request that states only a first-token target is left alone, since
	 * shortening the answer does not shorten the time to the first of its tokens.
	 *
	 * @param request the request being placed
	 * @param clock   the current instant, in seconds
	 * @return the remaining seconds, or {@code NaN} when the request states no end-to-end objective
	 */
	protected static double slackSeconds(InferenceRequest request, double clock) {
		double deadline = request.getEndToEndDeadline();
		if (deadline <= 0)
			return Double.NaN;
		return Math.max(0, deadline - clock);
	}
}
