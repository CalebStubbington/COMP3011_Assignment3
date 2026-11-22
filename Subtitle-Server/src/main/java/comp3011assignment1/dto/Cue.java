package comp3011assignment1.dto;

/**
 * A single subtitle that is sent to the video player. startMs is when to show it, endMs is when
 * to hide it, plus its text. Times are in milliseconds from the start of the movie. A line break inside the text ("\n")
 * means the player should draw two lines.
 * 
 * The server returns a finished cue so the player stays thin, and an immutable value
 * is safe to share between cached responses.
 * 
 * Trade-off: the player has its own nearly identical SubtitleCue (it uses microseconds instead)
 * because the two programs share no code. This keeps them independent, at the cost of a small conversion in the player's
 * fetcher.
 */

public record Cue(long startMs, long endMs, String text) {

}
