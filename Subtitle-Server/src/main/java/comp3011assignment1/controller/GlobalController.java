/*
 *  * Authors:
 *   1. Caleb Stubbington, a2991661 in collarboration with Claude Sonnet 5.5
 */

package comp3011assignment1.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011assignment1.dto.UsageResponse;
import comp3011assignment1.service.GlobalStatsService;

/**
 * REST controller that provides global app statistics.
 * 
 * Reveals the total transaction usage, in seconds, since the start of the server.
 */

@RestController
@RequestMapping("/api/v1/global")
public class GlobalController {
	
	private final GlobalStatsService globalStatsService;
	
	public GlobalController(GlobalStatsService globalStatsService) {
		this.globalStatsService = globalStatsService;
	}
	
	@GetMapping("/usage")
	public UsageResponse getUsage() {
		return globalStatsService.getUsage();
	}
	
}