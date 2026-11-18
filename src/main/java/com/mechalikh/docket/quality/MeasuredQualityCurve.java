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
package com.mechalikh.docket.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A piecewise-linear quality curve read from a CSV of measured points.
 * <p>
 * The file format is deliberately small: one {@code fidelity,quality} pair per line,
 * with {@code #} comments and a header allowed. An optional third column names a
 * service class; an unnamed point applies to every class, and a named point overrides
 * it for that class. Points are validated to lie in {@code [0, 1]}, to be strictly
 * increasing in fidelity and non-decreasing in quality, and to reach at least 0.95
 * at the highest fidelity. The last rule prevents a file truncated below full effort
 * from silently claiming to describe q(1).
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0
 */
public final class MeasuredQualityCurve {

	/** One measured or supplied point. */
	private static final class Point {
		private final double fidelity;
		private final double quality;
		private final String serviceClass;

		Point(double fidelity, double quality, String serviceClass) {
			this.fidelity = fidelity;
			this.quality = quality;
			this.serviceClass = serviceClass;
		}

		double fidelity() {
			return fidelity;
		}

		double quality() {
			return quality;
		}

		String serviceClass() {
			return serviceClass;
		}
	}

	private final List<Point> points;
	private final String source;

	private MeasuredQualityCurve(List<Point> points, String source) {
		this.points = List.copyOf(points);
		this.source = source;
	}

	/**
	 * Reads a curve file.
	 *
	 * @param path the CSV to read, relative to the working directory
	 * @return the validated curve
	 */
	public static MeasuredQualityCurve from(Path path) {
		List<Point> points = new ArrayList<>();
		try {
			int lineNumber = 0;
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
				lineNumber++;
				String text = line.trim();
				if (text.isEmpty() || text.startsWith("#"))
					continue;
				String[] words = text.split(",", -1);
				if (words.length < 2)
					throw new IllegalArgumentException(path + ":" + lineNumber + ": expected fidelity,quality");
				if ("fidelity".equalsIgnoreCase(words[0].trim()) && "quality".equalsIgnoreCase(words[1].trim()))
					continue;
				try {
					double fidelity = Double.parseDouble(words[0].trim());
					double quality = Double.parseDouble(words[1].trim());
					String serviceClass = words.length > 2 ? words[2].trim() : "";
					if (fidelity < 0 || fidelity > 1 || quality < 0 || quality > 1)
						throw new IllegalArgumentException(path + ":" + lineNumber + ": both columns must lie in [0, 1]");
					points.add(new Point(fidelity, quality, serviceClass));
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(path + ":" + lineNumber + ": non-numeric point", e);
				}
			}
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not read the quality curve \"" + path + "\": " + e.getMessage(), e);
		}
		// A header is allowed, but only if it was skipped as non-numeric; reject the case where
		// the numeric part is empty so a misspelled file cannot yield an empty curve.
		if (points.isEmpty())
			throw new IllegalArgumentException("The quality curve \"" + path + "\" contains no points");
		for (String serviceClass : new java.util.TreeSet<>(points.stream().map(Point::serviceClass)
				.collect(java.util.stream.Collectors.toList()))) {
			List<Point> selected = points.stream().filter(p -> p.serviceClass().equals(serviceClass))
					.sorted(Comparator.comparingDouble(Point::fidelity))
					.collect(java.util.stream.Collectors.toList());
			if (selected.size() < 2)
				throw new IllegalArgumentException("The quality curve \"" + path + "\" needs at least two points"
						+ (serviceClass.isEmpty() ? "" : " for class \"" + serviceClass + "\""));
			for (int i = 0; i < selected.size(); i++) {
				Point point = selected.get(i);
				if (i > 0) {
					Point previous = selected.get(i - 1);
					if (point.fidelity() <= previous.fidelity())
						throw new IllegalArgumentException("The quality curve \"" + path + "\" must be strictly "
								+ "increasing in fidelity, found " + previous.fidelity() + " then " + point.fidelity());
					if (point.quality() + 1e-9 < previous.quality())
						throw new IllegalArgumentException("The quality curve \"" + path + "\" must be non-decreasing "
								+ "in quality, found " + previous.quality() + " then " + point.quality());
				}
			}
			if (selected.get(selected.size() - 1).quality() < 0.95)
				throw new IllegalArgumentException("The quality curve \"" + path + "\" reaches only "
						+ selected.get(selected.size() - 1).quality() + " at full fidelity; a normalised curve must "
						+ "reach at least 0.95 at its last point");
		}
		return new MeasuredQualityCurve(points, path.toString());
	}

	/**
	 * Returns the interpolated quality at a fidelity level.
	 *
	 * @param fidelity  the granted fidelity in {@code [0, 1]}
	 * @param serviceClass the request's service class, may be {@code null}
	 * @return interpolated quality in {@code [0, 1]}
	 */
	public double value(double fidelity, String serviceClass) {
		String key = serviceClass == null ? "" : serviceClass;
		List<Point> selected = points.stream().filter(p -> p.serviceClass().equals(key))
				.collect(java.util.stream.Collectors.toList());
		if (selected.isEmpty())
			selected = points.stream().filter(p -> p.serviceClass().isEmpty())
					.collect(java.util.stream.Collectors.toList());
		selected.sort(Comparator.comparingDouble(Point::fidelity));
		if (selected.isEmpty())
			return fidelity;
		if (fidelity <= selected.get(0).fidelity())
			return selected.get(0).quality();
		for (int i = 1; i < selected.size(); i++) {
			Point lower = selected.get(i - 1);
			Point upper = selected.get(i);
			if (fidelity <= upper.fidelity()) {
				double span = upper.fidelity() - lower.fidelity();
				if (span <= 0)
					return upper.quality();
				double t = (fidelity - lower.fidelity()) / span;
				return lower.quality() + t * (upper.quality() - lower.quality());
			}
		}
		return selected.get(selected.size() - 1).quality();
	}

	/**
	 * Returns the source path, written into the artifacts.
	 *
	 * @return the curve file name
	 */
	public String source() {
		return source;
	}
}
