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
package com.mechalikh.docket.network;

import java.util.Locale;

import com.mechalikh.docket.domain.Provenance;

/**
 * The one hop between a client and the device that serves its request.
 * <p>
 * A device profile points at one link profile, so a phone reached over a cellular bearer and an
 * accelerator reached over a data-centre fabric can be described in the same scenario without
 * pretending the two hops are alike. The figures are those an access link is quoted with: a
 * capacity in megabits per second and a one-way latency in milliseconds.
 * <p>
 * This class extends the PureEdgeSim {@link com.mechalikh.pureedgesim.network.LinkProfile},
 * reusing its two bearer figures: the capacity, which this view converts to the bytes per second
 * the engine works with, and the one-way propagation time, already stored in seconds. What the
 * base does not know is the payload: bytes are counted per token, not per request, because the
 * payload of a generative request is its text, the prompt travelling up and the generated tokens
 * travelling down. The default of four bytes per token counts the characters of that text and
 * nothing else, so a scenario that wants to include framing, headers or a serialisation envelope
 * raises the two figures to what its own transport adds.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class LinkProfile extends com.mechalikh.pureedgesim.network.LinkProfile {

	/** Megabits in one bit-second, the decimal unit an access link is quoted in. */
	private static final double BITS_PER_MEGABIT = 1e6;
	/** Bits in one byte. */
	private static final double BITS_PER_BYTE = 8.0;
	/** Milliseconds in one second. */
	private static final double MILLIS_PER_SECOND = 1000.0;
	/** Bytes the scenario charges for one prompt token when it states none. */
	private static final double DEFAULT_BYTES_PER_TOKEN = 4;

	/** Name the device profiles refer to this link by. */
	protected String id;
	/** Bytes charged for one prompt token travelling up. */
	protected double bytesPerInputToken = DEFAULT_BYTES_PER_TOKEN;
	/** Bytes charged for one generated token travelling down. */
	protected double bytesPerOutputToken = DEFAULT_BYTES_PER_TOKEN;
	/** Where the capacity and the latency come from. */
	protected Provenance provenance = Provenance.SYNTHETIC;
	/** Free-text remark copied into the artifacts. */
	protected String note = "";

	/**
	 * Checks that the link can carry anything.
	 *
	 * @throws IllegalArgumentException when a figure is missing or out of range
	 */
	public void validate() {
		if (id == null || id.isEmpty())
			throw new IllegalArgumentException("A link needs a name, written as \"link.<name>.capacityMbps\"");
		if (!(getBandwidth() > 0))
			throw new IllegalArgumentException("Link \"" + id + "\" has a capacity of " + getBandwidth()
					+ " bytes per second; a link that carries nothing should be left out of the scenario instead");
		if (getLatency() < 0)
			throw new IllegalArgumentException("Link \"" + id + "\" has a negative one-way latency");
		if (bytesPerInputToken <= 0)
			throw new IllegalArgumentException("Link \"" + id + "\" charges " + bytesPerInputToken
					+ " bytes per prompt token, at least one is required");
		if (bytesPerOutputToken <= 0)
			throw new IllegalArgumentException("Link \"" + id + "\" charges " + bytesPerOutputToken
					+ " bytes per generated token, at least one is required");
	}

	/**
	 * Seconds needed to push one message onto the bearer, ignoring the propagation time.
	 *
	 * @param bytes payload size, zero or more
	 * @return the transmission time, in seconds
	 */
	public double transferSeconds(double bytes) {
		if (bytes < 0)
			throw new IllegalArgumentException("Link \"" + id + "\" cannot carry a message of " + bytes + " bytes");
		return bytes / getBandwidth();
	}

	/**
	 * Returns the name a device profile writes in its {@code link} key to reach this link.
	 *
	 * @return the link name
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the name of the link.
	 *
	 * @param id the link name, required by {@link #validate()}
	 * @return this profile
	 */
	public LinkProfile setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * Returns the raw capacity of the bearer, converting the bits per second the engine
	 * profile stores to the bytes per second the simulation works with.
	 * <p>
	 * The configuration itself states the capacity in megabits per second, the unit an access
	 * link is advertised in, so the conversion happens once, here at the edge of the two
	 * worlds.
	 *
	 * @return bytes per second
	 */
	@Override
	public double getBandwidth() {
		return super.getBandwidth() / BITS_PER_BYTE;
	}

	/**
	 * Sets the capacity of the bearer, stored in the engine profile in bits per second.
	 *
	 * @param bytesPerSecond capacity of the link, positive
	 * @return this profile
	 */
	@Override
	public LinkProfile setBandwidth(double bytesPerSecond) {
		super.setBandwidth(bytesPerSecond * BITS_PER_BYTE);
		return this;
	}

	/**
	 * Sets the capacity of the bearer from a value in megabits per second.
	 *
	 * @param megabitsPerSecond capacity advertised by the link, positive
	 * @return this profile
	 */
	public LinkProfile setCapacityMbps(double megabitsPerSecond) {
		return setBandwidth(megabitsPerSecond * BITS_PER_MEGABIT / BITS_PER_BYTE);
	}

	/**
	 * Returns the capacity in the unit the configuration uses.
	 *
	 * @return megabits per second
	 */
	public double getCapacityMbps() {
		return getBandwidth() * BITS_PER_BYTE / BITS_PER_MEGABIT;
	}

	/**
	 * Returns the one-way propagation time, the part of the delay that does not depend on how
	 * much is sent.
	 *
	 * @return seconds
	 */
	@Override
	public double getLatency() {
		return super.getLatency();
	}

	/**
	 * Sets the one-way propagation time, in seconds.
	 *
	 * @param seconds round-trip-free delay of the hop, zero or more
	 * @return this profile
	 */
	@Override
	public LinkProfile setLatency(double seconds) {
		super.setLatency(seconds);
		return this;
	}

	/**
	 * Sets the one-way propagation time from a value in milliseconds.
	 *
	 * @param milliseconds round-trip-free delay of the hop, zero or more
	 * @return this profile
	 */
	public LinkProfile setLatencyMs(double milliseconds) {
		return setLatency(milliseconds / MILLIS_PER_SECOND);
	}

	/**
	 * Returns the one-way latency in the unit the configuration uses.
	 *
	 * @return milliseconds
	 */
	public double getLatencyMs() {
		return getLatency() * MILLIS_PER_SECOND;
	}

	/**
	 * Returns the bytes one prompt token costs on the uplink.
	 *
	 * @return bytes per token
	 */
	public double getBytesPerInputToken() {
		return bytesPerInputToken;
	}

	/**
	 * Sets the bytes one prompt token costs on the uplink.
	 *
	 * @param bytesPerInputToken bytes per token, above zero
	 * @return this profile
	 */
	public LinkProfile setBytesPerInputToken(double bytesPerInputToken) {
		this.bytesPerInputToken = bytesPerInputToken;
		return this;
	}

	/**
	 * Returns the bytes one generated token costs on the downlink.
	 *
	 * @return bytes per token
	 */
	public double getBytesPerOutputToken() {
		return bytesPerOutputToken;
	}

	/**
	 * Sets the bytes one generated token costs on the downlink.
	 *
	 * @param bytesPerOutputToken bytes per token, above zero
	 * @return this profile
	 */
	public LinkProfile setBytesPerOutputToken(double bytesPerOutputToken) {
		this.bytesPerOutputToken = bytesPerOutputToken;
		return this;
	}

	/**
	 * Returns where the figures of this link come from.
	 *
	 * @return the provenance, {@link Provenance#SYNTHETIC} unless the scenario says otherwise
	 */
	public Provenance getProvenance() {
		return provenance;
	}

	/**
	 * Sets the provenance of the link.
	 *
	 * @param provenance declared, measured or taken from the literature
	 * @return this profile
	 */
	public LinkProfile setProvenance(Provenance provenance) {
		this.provenance = provenance == null ? Provenance.SYNTHETIC : provenance;
		return this;
	}

	/**
	 * Returns the remark written next to the link in the artifacts.
	 *
	 * @return the note, empty when none was set
	 */
	public String getNote() {
		return note;
	}

	/**
	 * Sets the remark written next to the link in the artifacts.
	 *
	 * @param note free text, {@code null} is stored as an empty string
	 * @return this profile
	 */
	public LinkProfile setNote(String note) {
		this.note = note == null ? "" : note;
		return this;
	}

	/**
	 * Returns the payload figures as the compact text the artifacts use, with no trailing fraction
	 * when the scenario wrote a whole number of bytes.
	 *
	 * @return {@code "<input>/<output> bytes per input/output token"}
	 */
	public String payloadSummary() {
		return trimmed(bytesPerInputToken) + "/" + trimmed(bytesPerOutputToken)
				+ " bytes per input/output token";
	}

	/**
	 * Formats a byte count without a trailing fraction.
	 *
	 * @param value bytes per token
	 * @return the compact text
	 */
	private static String trimmed(double value) {
		if (value == Math.floor(value) && !Double.isInfinite(value))
			return Integer.toString((int) value);
		return Double.toString(value);
	}

	/**
	 * Returns a one-line description naming the capacity, the latency and the payload size.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "LinkProfile[" + id + ", " + String.format(Locale.ROOT, "%.1f Mbps", getCapacityMbps()) + ", "
				+ String.format(Locale.ROOT, "%.1f ms", getLatencyMs()) + ", " + payloadSummary() + "]";
	}
}
