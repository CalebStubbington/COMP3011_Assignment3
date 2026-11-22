/*
 *  * Authors:
 *   1. Caleb Stubbington, a2991661 in collarboration with Claude Sonnet 5.5
 */

package comp3011;

/**
 * A single subtitle cue held by the player. startUs when it starts, endUs when it ends, and its text.
 * 
 * Microseconds are used instead of milliseconds as frame timestamps are microseconds, allowing for cues
 * and frames to be compared directly.
 * 
 * Trade-off: the servers Cues use milliseconds so SubtitleFetcher converts once when it parses the response. 
 */

public record SubtitleCue(long startUs, long endUs, String text) {

}
