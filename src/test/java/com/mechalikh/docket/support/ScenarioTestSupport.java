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
package com.mechalikh.docket.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.config.Settings;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.experiment.RunResult;

/**
 * Shared loaders for the tests that drive the whole engine.
 *
 * @author Charafeddine Mechalikh
 */
public final class ScenarioTestSupport {

	/** The light scenario that keeps every request short and never queues. */
	public static final String LIGHT = "scenarios/single-gpu-poisson.properties";
	/** The memory-pressure scenario that batches, preempts and refuses cache. */
	public static final String LOADED = "scenarios/loaded-7b-memory-pressure.properties";

	private ScenarioTestSupport() {
	}

	/**
	 * Loads a committed scenario file from the project root, exactly as the CLI does.
	 *
	 * @param relativePath path to the scenario, relative to the working directory
	 * @param overrides    {@code key=value} pairs applied on top of the file
	 * @return the loaded scenario
	 */
	public static Scenario load(String relativePath, String... overrides) {
		Path path = Paths.get(relativePath);
		List<String> list = new ArrayList<>();
		for (String override : overrides)
			list.add(override);
		try {
			Settings settings = Settings.fromFile(path, path.getFileName().toString(), list);
			return Scenario.load(settings, path.toAbsolutePath().getParent());
		} catch (IOException e) {
			throw new UncheckedIOException("The committed scenario \"" + relativePath + "\" must load", e);
		}
	}

	/**
	 * A stable, order-independent signature of the per-request rows of a run, used to compare two
	 * repetitions that are supposed to be identical or different.
	 * <p>
	 * Only the fields that a deterministic run fixes are folded in: identity, outcome and the
	 * rounded latencies. Two runs that differ by a single emitted token or one queued request
	 * produce a different string.
	 *
	 * @param result the finished repetition
	 * @return the signature
	 */
	public static String fingerprint(RunResult result) {
		StringBuilder builder = new StringBuilder();
		builder.append("arrived=").append(result.getArrivedRequests()).append(";completed=")
				.append(result.getCompletedRequests()).append(";generated=").append(result.getTokensGenerated())
				.append(";energy=").append(round(result.getDeviceEnergyJoule())).append('\n');
		for (InferenceRequest request : result.getRequests()) {
			builder.append(request.getSerial()).append('|').append(request.getState()).append('|')
					.append(request.getOutcomeReason()).append('|').append(request.getGeneratedTokens()).append('|')
					.append(round(request.ttftSeconds())).append('|').append(round(request.endToEndSeconds()))
					.append('|').append(round(request.getQueueingSeconds())).append('\n');
		}
		return builder.toString();
	}

	private static long round(double value) {
		return Double.isNaN(value) ? -1 : Math.round(value * 1e9);
	}
}
