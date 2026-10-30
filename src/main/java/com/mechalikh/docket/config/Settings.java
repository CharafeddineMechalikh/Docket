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
package com.mechalikh.docket.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The key-value settings of one experiment, read from a properties file and from the overrides
 * given on the command line.
 * <p>
 * Every read is recorded. When a run finishes loading, {@link #unusedKeys()} lists the keys that
 * were present but never read, which turns a misspelled setting into an error instead of a
 * silently ignored line. A published result must be reproducible from the file that produced it,
 * and that is only true when nothing in the file was overlooked.
 * <p>
 * A value that is read is also remembered with the source that provided it, so that the artifacts
 * can echo the effective configuration next to the numbers.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class Settings {

	/** Binary multiplier, used for the memory keys written in KiB, MiB and GiB. */
	private static final double KIB = 1024.0;
	/** Binary multiplier for MiB. */
	private static final double MIB = KIB * 1024.0;
	/** Binary multiplier for GiB. */
	private static final double GIB = MIB * 1024.0;

	/** Every key-value pair of the run. */
	protected final Map<String, String> values;
	/** Keys read at least once, in the order they were first read. */
	protected final Set<String> readKeys = new LinkedHashSet<>();
	/** Where each key came from, for the echo of the effective configuration. */
	protected final Map<String, String> origins;

	/**
	 * Wraps a map of values.
	 *
	 * @param values   the settings, copied
	 * @param origins  source label per key, for the report; may be empty
	 */
	public Settings(Map<String, String> values, Map<String, String> origins) {
		this.values = new LinkedHashMap<>(values);
		this.origins = new LinkedHashMap<>(origins);
	}

	/**
	 * Reads one properties file.
	 * <p>
	 * The file is decoded as UTF-8 rather than through {@link Properties#load(InputStream)},
	 * which reads a {@code .properties} file as ISO-8859-1 and would mangle a model name that
	 * holds a non-ASCII character.
	 *
	 * @param path      the file to read
	 * @param origin    label naming the source in the report
	 * @param overrides pairs given on the command line, applied last, {@code key=value} form
	 * @return the merged settings
	 * @throws IOException when the file cannot be read
	 */
	public static Settings fromFile(Path path, String origin, List<String> overrides) throws IOException {
		Map<String, String> values = new LinkedHashMap<>();
		Map<String, String> sources = new LinkedHashMap<>();
		try (InputStream in = Files.newInputStream(path);
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			Properties properties = new Properties();
			properties.load(reader);
			for (String name : properties.stringPropertyNames()) {
				values.put(name, properties.getProperty(name));
				sources.put(name, origin);
			}
		}
		for (String override : overrides) {
			int separator = override.indexOf('=');
			if (separator < 1)
				throw new IllegalArgumentException("An override is written key=value, found \"" + override + "\"");
			String key = override.substring(0, separator).trim();
			values.put(key, override.substring(separator + 1).trim());
			sources.put(key, "command line");
		}
		return new Settings(values, sources);
	}

	/**
	 * Reads settings held in memory, for the tests and for the built-in scenario.
	 *
	 * @param values the settings
	 * @return the settings wrapper
	 */
	public static Settings of(Map<String, String> values) {
		Map<String, String> sources = new LinkedHashMap<>();
		for (String key : values.keySet())
			sources.put(key, "in memory");
		return new Settings(values, sources);
	}

	/**
	 * Returns the raw value of a key, or {@code null} when the key is absent.
	 *
	 * @param key the setting name
	 * @return the value, possibly untrimmed and empty
	 */
	public String raw(String key) {
		readKeys.add(key);
		return values.get(key);
	}

	/**
	 * Returns a required text setting.
	 *
	 * @param key the setting name
	 * @return the trimmed value
	 * @throws IllegalArgumentException when the key is absent or blank
	 */
	public String text(String key) {
		String value = raw(key);
		if (value == null || value.trim().isEmpty())
			throw new IllegalArgumentException("The setting \"" + key + "\" is required but "
					+ (value == null ? "is absent" : "is empty"));
		return value.trim();
	}

	/**
	 * Returns a text setting, or the default when the key is absent or blank.
	 *
	 * @param key          the setting name
	 * @param defaultValue what to use when the setting is not given
	 * @return the trimmed value or the default
	 */
	public String text(String key, String defaultValue) {
		String value = raw(key);
		return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
	}

	/**
	 * Returns a required number, rejecting the values a simulation must not accept silently.
	 *
	 * @param key the setting name
	 * @return the parsed value
	 * @throws IllegalArgumentException when the key is absent, not a number, or not finite
	 */
	public double number(String key) {
		String value = text(key);
		try {
			double parsed = Double.parseDouble(value.replace(",", ""));
			if (Double.isNaN(parsed))
				throw new IllegalArgumentException("The setting \"" + key + "\" is NaN");
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The setting \"" + key + "\" must be a number, found \"" + value + "\"",
					e);
		}
	}

	/**
	 * Returns a number, or the default when the key is absent or blank.
	 *
	 * @param key          the setting name
	 * @param defaultValue what to use when the setting is not given
	 * @return the parsed value or the default
	 */
	public double number(String key, double defaultValue) {
		String value = raw(key);
		return value == null || value.trim().isEmpty() ? defaultValue : number(key);
	}

	/**
	 * Returns a required integer.
	 *
	 * @param key the setting name
	 * @return the parsed value
	 * @throws IllegalArgumentException when the value is not an integer
	 */
	public int integer(String key) {
		String value = text(key);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The setting \"" + key + "\" must be a whole number, found \"" + value
					+ "\"", e);
		}
	}

	/**
	 * Returns an integer, or the default when the key is absent or blank.
	 *
	 * @param key          the setting name
	 * @param defaultValue what to use when the setting is not given
	 * @return the parsed value or the default
	 */
	public int integer(String key, int defaultValue) {
		String value = raw(key);
		return value == null || value.trim().isEmpty() ? defaultValue : integer(key);
	}

	/**
	 * Returns a boolean written as {@code true}, {@code false}, {@code yes}, {@code no},
	 * {@code on}, {@code off}, {@code 1} or {@code 0}.
	 *
	 * @param key          the setting name
	 * @param defaultValue what to use when the setting is not given
	 * @return the parsed value
	 * @throws IllegalArgumentException when the word is not one of those
	 */
	public boolean flag(String key, boolean defaultValue) {
		String value = raw(key);
		if (value == null || value.trim().isEmpty())
			return defaultValue;
		String word = value.trim().toLowerCase(Locale.ROOT);
		switch (word) {
		case "true":
		case "yes":
		case "on":
		case "1":
			return true;
		case "false":
		case "no":
		case "off":
		case "0":
			return false;
		default:
			throw new IllegalArgumentException("The setting \"" + key + "\" must be true or false, found \"" + value
					+ "\"");
		}
	}

	/**
	 * Returns a size given in KiB, MiB or GiB, in bytes.
	 * <p>
	 * Memory is configured with binary units because that is how a runtime reports the capacity of
	 * a device, while bandwidth is configured with decimal units because that is how a datasheet
	 * writes it. Mixing the two conventions inside one key is how a scenario ends up with a cache
	 * that is a thousand times too large.
	 *
	 * @param key the setting name, whose value is a number
	 * @param unit one of {@code KiB}, {@code MiB}, {@code GiB}, {@code TB} or {@code bytes}
	 * @return the size in bytes
	 * @throws IllegalArgumentException when the unit is unknown or the value is not positive
	 */
	public double bytes(String key, String unit) {
		double value = number(key);
		if (value <= 0)
			throw new IllegalArgumentException("The setting \"" + key + "\" must be positive, found " + value);
		double multiplier;
		switch (unit.toLowerCase(Locale.ROOT)) {
		case "bytes":
		case "byte":
			multiplier = 1;
			break;
		case "kib":
			multiplier = KIB;
			break;
		case "mib":
			multiplier = MIB;
			break;
		case "gib":
			multiplier = GIB;
			break;
		case "tib":
			multiplier = GIB * 1024.0;
			break;
		default:
			throw new IllegalArgumentException("Unknown unit \"" + unit + "\" for the setting \"" + key
					+ "\"; use bytes, KiB, MiB, GiB or TiB");
		}
		return value * multiplier;
	}

	/**
	 * Returns the milliseconds of a setting as seconds.
	 *
	 * @param key          the setting name
	 * @param defaultValue what to use when the setting is not given
	 * @return seconds
	 */
	public double millisToSeconds(String key, double defaultValue) {
		return number(key, defaultValue) / 1000.0;
	}

	/**
	 * Splits a comma-separated list, dropping the empty entries.
	 *
	 * @param key the setting name
	 * @return the trimmed entries, empty when the key is absent
	 */
	public List<String> list(String key) {
		String value = raw(key);
		if (value == null || value.trim().isEmpty())
			return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (String part : value.split(","))
			if (!part.trim().isEmpty())
				out.add(part.trim());
		return out;
	}

	/**
	 * Returns the parameters of a distribution written as {@code <prefix>.name} plus one key per
	 * attribute, for instance {@code workload.inputDistribution.shape}.
	 *
	 * @param prefix the key prefix of the distribution
	 * @return the attribute map, including {@code min} and {@code max} when configured; empty when
	 *         the prefix has no attributes at all
	 */
	public Map<String, String> distributionAttributes(String prefix) {
		Map<String, String> attributes = new LinkedHashMap<>();
		for (String key : values.keySet()) {
			if (!key.startsWith(prefix + "."))
				continue;
			String attribute = key.substring(prefix.length() + 1);
			if (attribute.equals("name"))
				continue;
			String value = raw(key);
			if (value != null && !value.trim().isEmpty())
				attributes.put(attribute, value.trim());
		}
		return attributes;
	}

	/**
	 * Returns the name of the distribution written under a prefix.
	 *
	 * @param prefix the key prefix of the distribution
	 * @return the trimmed name
	 * @throws IllegalArgumentException when the name is absent
	 */
	public String distributionName(String prefix) {
		return text(prefix + ".name");
	}

	/**
	 * Returns a size given in KiB, MiB or GiB, in bytes, or zero when the key is absent.
	 *
	 * @param key  the setting name
	 * @param unit the unit the value is written in
	 * @return the size in bytes, zero when not configured
	 */
	public double bytesOrZero(String key, String unit) {
		String value = raw(key);
		return value == null || value.trim().isEmpty() ? 0 : bytes(key, unit);
	}

	/**
	 * Adds a value the loader derived rather than read, so that the artifacts echo it next to the
	 * settings the user wrote.
	 *
	 * @param key   the derived key, which must not clash with a configured one
	 * @param value what to remember
	 * @throws IllegalArgumentException when the key was already configured
	 */
	public void remember(String key, String value) {
		if (values.containsKey(key))
			throw new IllegalArgumentException("The settings already hold \"" + key + "\", a derived value cannot "
					+ "replace it");
		values.put(key, value);
		origins.put(key, "derived");
		readKeys.add(key);
	}

	/**
	 * Returns the keys that were never read, which is the signature of a typo.
	 *
	 * @return the unread keys, sorted
	 */
	public List<String> unusedKeys() {
		List<String> unused = new ArrayList<>();
		for (String key : values.keySet())
			if (!readKeys.contains(key))
				unused.add(key);
		Collections.sort(unused);
		return unused;
	}

	/**
	 * Returns every configured key with its value and its source.
	 *
	 * @return an ordered copy of the settings, for the artifacts
	 */
	public Map<String, String> effective() {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : values.entrySet())
			out.put(entry.getKey(), entry.getValue() + "  [" + origins.getOrDefault(entry.getKey(), "?") + "]");
		return out;
	}

	/**
	 * Returns how many keys the file and the overrides provide.
	 *
	 * @return the number of settings
	 */
	public int size() {
		return values.size();
	}

	/**
	 * Returns a one-line summary naming how many keys the settings hold and how many were read, so
	 * that an overlooked key is visible before the artifacts are written.
	 *
	 * @return the key counts
	 */
	@Override
	public String toString() {
		return "Settings[" + values.size() + " keys, " + readKeys.size() + " read]";
	}
}
