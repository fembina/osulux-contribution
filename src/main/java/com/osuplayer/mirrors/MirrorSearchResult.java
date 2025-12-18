package com.osuplayer.mirrors;

import java.util.List;

import com.osuplayer.osu.OsuApiClient;

public record MirrorSearchResult(List<OsuApiClient.BeatmapsetSummary> beatmapsets,
                                 boolean hasMore) {
}
