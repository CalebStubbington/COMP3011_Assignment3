package comp3011assignment1.dto;

import java.util.List;

/**
 * JSON body returned by POST /api/v1/subtitles: ({"cues":[...]})
 * 
 * wrapped in an object so more fields can be added later without breaking the player. The cost is one extra level in the JSON.
 */

public record SubtitleResponse(List<Cue> cues) {

}
