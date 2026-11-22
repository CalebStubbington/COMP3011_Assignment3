/*
 *  * Authors:
 *   1. Caleb Stubbington, a2991661 in collarboration with Claude Sonnet 5.5
 */

package comp3011assignment1.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011assignment1.service.SubtitleService;
import comp3011assignment1.dto.SubtitleResponse;

/**
 * REST controller responsible for receiving an audio chunk from the video player and
 * returning timed subtitle cues for it.
 * 
 * startMs is where the chunk begins in the movie, so the return cue times are absolute
 * 
 * replaced Assignment 1's /transcription endpoint.
 * 
 * Whisper only knows times within the chunk so the offset lets us return absolute movie times.
 * 
 * Trade-off: the controller stays thin as it only reads bytes, calls the service and wraps the result.
 * Failures are passed to UniversalExceptionHandler.
 */

@RestController
@RequestMapping("/api/v1")
public class SubtitleController {
	
	private final SubtitleService subtitleService;
	
	public SubtitleController(SubtitleService subtitleService) {
		this.subtitleService = subtitleService;
	}
	
	@PostMapping(value = "/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public SubtitleResponse subtitles(@RequestParam("file") MultipartFile file, @RequestParam(value = "startMs", defaultValue = "0") long startMs) {
			try {
				return new SubtitleResponse(subtitleService.getCues(file.getBytes(), startMs));
			} catch (Exception e) {
				throw new RuntimeException("Failed to generate subtitles", e);
			}
	}

}
