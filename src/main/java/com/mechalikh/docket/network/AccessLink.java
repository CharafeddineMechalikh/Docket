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

import com.mechalikh.docket.domain.InferenceRequest;

/**
 * One access link in flight: the queue of bytes it is carrying in each direction.
 * <p>
 * Each direction is a first-in first-out store-and-forward server, which is the same rule a
 * {@code NetworkLink} of PureEdgeSim applies to a file transfer: a message starts as soon as the
 * bearer is free, occupies it for its size divided by the raw capacity, and is fully readable one
 * propagation time later. Transfers queue instead of sharing the bearer, so a congested link shows
 * up as waiting time in the latency of the requests behind it rather than as a slower service rate
 * for all of them.
 * <p>
 * The two directions are independent, as they are on any full-duplex bearer: a downlink that is
 * draining responses does not slow the prompts coming up.
 * <p>
 * This is a ledger and not a simulation entity. A store-and-forward first-in first-out queue whose
 * service time is known when the message is pushed needs no event of its own to compute its exit
 * instant, only the timestamp the bearer next becomes free, so the engine schedules the delivery on
 * the service that owns the link instead of waking this object up.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class AccessLink {

	/** Bearer this queue drains. */
	protected final LinkProfile profile;
	/** Instant the uplink next becomes free. */
	protected double uplinkFreeAt;
	/** Instant the downlink next becomes free. */
	protected double downlinkFreeAt;

	/**
	 * Creates an idle queue for the given bearer.
	 *
	 * @param profile the link this queue drains, not {@code null}
	 */
	public AccessLink(LinkProfile profile) {
		if (profile == null)
			throw new IllegalArgumentException("An access link needs the bearer profile it queues on");
		profile.validate();
		this.profile = profile;
	}

	/**
	 * Queues the prompt of a request on the uplink and says when the device receives it.
	 * <p>
	 * The whole prompt travels as one message: a client sends its request and only then expects an
	 * answer, so there is nothing to pipeline in front of it. The request carries the time the
	 * bearer released it, which is the delay the row reports as {@code uplink_s}.
	 *
	 * @param request the request whose prompt is sent
	 * @param clock   instant the client released it, in seconds
	 * @return the instant the device holds the whole prompt, in seconds
	 */
	public double sendUplink(InferenceRequest request, double clock) {
		double bytes = request.getInputTokens() * profile.getBytesPerInputToken();
		double delivery = exitInstant(clock, bytes, true);
		request.addUplinkSeconds(delivery - clock);
		return delivery;
	}

	/**
	 * Queues the response of a request on the downlink and says when the client holds it.
	 * <p>
	 * The generated tokens travel as one message at the instant the last of them was produced. That
	 * is the non-streaming reading of the API: the reply is delivered complete. It is also the
	 * conservative one, because a stream that sends each token as it appears empties no earlier,
	 * and it costs the run no events.
	 *
	 * @param request the request whose response is sent
	 * @param clock   instant the last token was produced, in seconds
	 * @return the instant the client holds the whole response, in seconds
	 */
	public double sendDownlink(InferenceRequest request, double clock) {
		double bytes = request.getGeneratedTokens() * profile.getBytesPerOutputToken();
		double delivery = exitInstant(clock, bytes, false);
		request.addDownlinkSeconds(delivery - clock);
		return delivery;
	}

	/**
	 * Returns the instant a message pushed at the given time is fully readable at the far end.
	 * <p>
	 * The bearer is busy until {@code freeAt}, so a message that arrives before then waits, and the
	 * next message cannot start before this one finished transmitting. The propagation time belongs
	 * to the message, not to the bearer, so it is added to the exit instant without holding the
	 * queue.
	 *
	 * @param clock  instant the message is pushed, in seconds
	 * @param bytes  size of the message
	 * @param uplink {@code true} for the prompt direction, {@code false} for the response one
	 * @return the delivery instant, in seconds
	 */
	protected double exitInstant(double clock, double bytes, boolean uplink) {
		double freeAt = uplink ? uplinkFreeAt : downlinkFreeAt;
		double start = Math.max(clock, freeAt);
		double busyUntil = start + profile.transferSeconds(bytes);
		if (uplink)
			uplinkFreeAt = busyUntil;
		else
			downlinkFreeAt = busyUntil;
		return busyUntil + profile.getLatency();
	}

	/**
	 * Queues a key-value cache hand-off on the fabric and says when the far device holds it.
	 * <p>
	 * Used only by a disaggregated deployment, where the cache a prefill instance computed must
	 * reach a decode instance before the first token is produced there. The bytes travel as one
	 * store-and-forward message on a dedicated bearer, so a congested fabric shows up as waiting
	 * time in the time-to-first-token of the requests behind it, exactly as an access link does.
	 * Nothing is added to the request's own ledger: the transfer duration is already inside the
	 * latency, because the first token is emitted only after the hand-off event fires.
	 *
	 * @param bytes the cache footprint crossing the fabric
	 * @param clock instant the prefill instance released the cache, in seconds
	 * @return the instant the decode instance holds the whole cache, in seconds
	 */
	public double sendKvTransfer(double bytes, double clock) {
		return exitInstant(clock, bytes, true);
	}

	/**
	 * Returns the bearer this queue drains.
	 *
	 * @return the link profile
	 */
	public LinkProfile getProfile() {
		return profile;
	}

	/**
	 * Returns the name of the bearer.
	 *
	 * @return the link identifier
	 */
	public String getId() {
		return profile.getId();
	}

	/**
	 * Returns a one-line summary naming the bearer and the traffic it carried.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "AccessLink[" + profile.getId() + ", "
				+ String.format(Locale.ROOT, "%.1f Mbps", profile.getCapacityMbps()) + " up and down]";
	}
}
