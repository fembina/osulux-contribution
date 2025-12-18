package com.osuplayer.mirrors.servers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.gson.JsonObject;
import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.mirrors.MirrorHttp;
import com.osuplayer.mirrors.MirrorSearchResult;
import com.osuplayer.mirrors.MirrorServer;

public class BeatconnectMirrorServer implements MirrorServer {

    private static final String SEARCH_ENDPOINT = "https://beatconnect.io/search";
    private static final String DOWNLOAD_ENDPOINT = "https://beatconnect.io/b/%d/";
    private static final String OSU_DIRECT_SEARCH_ENDPOINT = "https://osu.direct/api/v2/search";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int STATUS_HINT_PAGE_SIZE = 80;
    private static final Pattern ID_PATTERN = Pattern.compile("(\\d+)");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String id() {
        return "beatconnect";
    }

    @Override
    public String displayName() {
        return "Beatconnect";
    }

    @Override
    public MirrorSearchResult search(String query,
                                     OsuApiClient.BeatmapMode mode,
                                     OsuApiClient.BeatmapStatus status,
                                     int pageIndex,
                                     int pageSize) throws IOException {
        int effectiveSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, DEFAULT_PAGE_SIZE);
        String statusFilter = mapStatus(status);
        String modeFilter = mapMode(mode);
        String encodedQuery = MirrorHttp.encode(query == null ? "" : query.trim());
        String url = SEARCH_ENDPOINT
            + "?q=" + encodedQuery
            + "&s=" + MirrorHttp.encode(statusFilter)
            + "&m=" + MirrorHttp.encode(modeFilter)
            + "&p=" + Math.max(0, pageIndex);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", MirrorHttp.USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("La búsqueda en Beatconnect fue interrumpida", ex);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " al consultar Beatconnect");
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return new MirrorSearchResult(List.of(), false);
        }

        Document document = Jsoup.parse(body);
        Elements cards = document.select(".beatmap-card");
        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>(Math.min(cards.size(), effectiveSize));
        for (Element card : cards) {
            if (summaries.size() >= effectiveSize) {
                break;
            }
            OsuApiClient.BeatmapsetSummary summary = parseCard(card, status, statusFilter);
            if (summary != null) {
                summaries.add(summary);
            }
        }

        applyStatusHints(summaries, query, mode);
        List<OsuApiClient.BeatmapsetSummary> filteredSummaries = filterSummariesByStatus(summaries, status);

        boolean hasMore = !cards.isEmpty();
        return new MirrorSearchResult(filteredSummaries, hasMore);
    }

    @Override
    public Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException {
        String url = String.format(DOWNLOAD_ENDPOINT, beatmapsetId);
        return MirrorHttp.downloadWithProgress(url, "osulux-beatconnect-", false, "https://beatconnect.io/", beatmapsetId, listener);
    }

    private OsuApiClient.BeatmapsetSummary parseCard(Element card,
                                                    OsuApiClient.BeatmapStatus requestedStatus,
                                                    String statusFilter) {
        Element link = card.selectFirst("a.download");
        if (link == null) {
            return null;
        }
        long id = extractId(link.attr("href"));
        if (id <= 0) {
            Element playBtn = card.selectFirst(".play-btn.audio");
            id = extractId(playBtn != null ? playBtn.attr("data-id") : null);
        }
        if (id <= 0) {
            return null;
        }

        String title = text(link.selectFirst(".beatmap-title"));
        String artist = text(link.selectFirst(".beatmap-artist"));
        String creator = text(card.selectFirst(".meta-item.creator span"));
        String coverUrl = textAttr(card.selectFirst(".beatmap-image img"), "src");
        if (coverUrl.isBlank()) {
            coverUrl = String.format("https://assets.ppy.sh/beatmaps/%d/covers/list.jpg", id);
        }

        double bpm = extractBpm(card);
        String status = resolveStatusLabel(requestedStatus, statusFilter);

        return new OsuApiClient.BeatmapsetSummary(id,
                title,
                artist,
                creator,
                status,
                bpm,
                false,
                0,
                0,
                (Instant) null,
                coverUrl);
    }

    private String resolveStatusLabel(OsuApiClient.BeatmapStatus requestedStatus, String statusFilter) {
        String preferred = mapRequestedStatus(requestedStatus);
        if (preferred != null) {
            return preferred;
        }
        return normalizeStatus(statusFilter);
    }

    private String mapRequestedStatus(OsuApiClient.BeatmapStatus status) {
        if (status == null || status == OsuApiClient.BeatmapStatus.ANY) {
            return null;
        }
        return switch (status) {
            case RANKED -> "ranked";
            case QUALIFIED -> "qualified";
            case LOVED -> "loved";
            case PENDING -> "pending";
            case GRAVEYARD -> "graveyard";
            default -> null;
        };
    }

    private double extractBpm(Element card) {
        for (Element meta : card.select(".beatmap-meta .meta-item")) {
            String text = meta.text();
            if (text != null && text.toUpperCase().contains("BPM")) {
                String numeric = text.replace("BPM", "").replace(",", "").trim();
                numeric = numeric.replaceAll("[^0-9.]+", "");
                if (numeric.isEmpty()) {
                    continue;
                }
                try {
                    return Double.parseDouble(numeric);
                } catch (NumberFormatException ignored) {
                    return 0d;
                }
            }
        }
        return 0d;
    }

    private long extractId(String source) {
        if (source == null || source.isBlank()) {
            return -1;
        }
        Matcher matcher = ID_PATTERN.matcher(source);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private String mapMode(OsuApiClient.BeatmapMode mode) {
        if (mode == null || mode == OsuApiClient.BeatmapMode.ANY) {
            return "all";
        }
        return switch (mode) {
            case OSU -> "std";
            case TAIKO -> "taiko";
            case CATCH -> "ctb";
            case MANIA -> "mania";
            default -> "all";
        };
    }

    private String mapStatus(OsuApiClient.BeatmapStatus status) {
        if (status == null || status == OsuApiClient.BeatmapStatus.ANY) {
            return "ranked,qualified,loved,unranked";
        }
        return switch (status) {
            case RANKED -> "ranked";
            case QUALIFIED -> "qualified";
            case LOVED -> "loved";
            case PENDING, GRAVEYARD -> "unranked";
            default -> "ranked";
        };
    }

    private String normalizeStatus(String filterValue) {
        if (filterValue == null || filterValue.isBlank()) {
            return "ranked";
        }
        if (!filterValue.contains(",")) {
            return translateStatus(filterValue.trim());
        }
        if (filterValue.contains("ranked")) {
            return "ranked";
        }
        String[] tokens = filterValue.split(",");
        for (String token : tokens) {
            if (!token.isBlank()) {
                return translateStatus(token.trim());
            }
        }
        return "ranked";
    }

    private String translateStatus(String raw) {
        if (raw == null) {
            return "ranked";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "qualified" -> "qualified";
            case "loved" -> "loved";
            case "pending" -> "pending";
            case "graveyard" -> "graveyard";
            case "wip" -> "pending";
            case "unranked" -> "pending";
            default -> "ranked";
        };
    }

    private void applyStatusHints(List<OsuApiClient.BeatmapsetSummary> summaries,
                                  String query,
                                  OsuApiClient.BeatmapMode mode) {
        if (summaries.isEmpty()) {
            return;
        }
        Map<Long, StatusHint> hints = fetchStatusHints(query, mode);
        if (hints.isEmpty()) {
            return;
        }
        for (int i = 0; i < summaries.size(); i++) {
            OsuApiClient.BeatmapsetSummary summary = summaries.get(i);
            StatusHint hint = hints.get(summary.id());
            if (hint == null) {
                continue;
            }
            String normalized = hint.status() == null ? summary.status() : translateStatus(hint.status());
            summaries.set(i, cloneWithHints(summary, normalized, hint.favouriteCount(), hint.playCount()));
        }
    }

    private Map<Long, StatusHint> fetchStatusHints(String query, OsuApiClient.BeatmapMode mode) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        StringBuilder url = new StringBuilder(OSU_DIRECT_SEARCH_ENDPOINT)
            .append("?q=").append(MirrorHttp.encode(query.trim()))
            .append("&amount=").append(STATUS_HINT_PAGE_SIZE);
        if (mode != null && mode != OsuApiClient.BeatmapMode.ANY) {
            url.append("&mode=").append(mapOsuDirectMode(mode));
        }
        try {
            Map<Long, StatusHint> result = new HashMap<>();
            for (var element : MirrorHttp.getJsonArray(url.toString())) {
                if (!element.isJsonObject()) {
                    continue;
                }
                var obj = element.getAsJsonObject();
                long id = obj.has("id") && obj.get("id").isJsonPrimitive() ? obj.get("id").getAsLong() : -1;
                if (id <= 0) {
                    continue;
                }
                String status = obj.has("status") && obj.get("status").isJsonPrimitive()
                        ? obj.get("status").getAsString()
                        : null;
                Integer favourites = readInt(obj, "favourite_count");
                Integer plays = readInt(obj, "play_count");
                if (status == null && favourites == null && plays == null) {
                    continue;
                }
                result.put(id, new StatusHint(status, favourites, plays));
            }
            return result;
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    private Integer readInt(JsonObject obj, String member) {
        if (obj == null || member == null || !obj.has(member) || !obj.get(member).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(member).getAsInt();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<OsuApiClient.BeatmapsetSummary> filterSummariesByStatus(List<OsuApiClient.BeatmapsetSummary> summaries,
                                                                         OsuApiClient.BeatmapStatus requestedStatus) {
        if (summaries.isEmpty() || requestedStatus == null || requestedStatus == OsuApiClient.BeatmapStatus.ANY) {
            return summaries;
        }
        String normalized = translateStatus(requestedStatus.name());
        summaries.removeIf(summary -> summary == null || !translateStatus(summary.status()).equals(normalized));
        return summaries;
    }

    private String mapOsuDirectMode(OsuApiClient.BeatmapMode mode) {
        return switch (mode) {
            case OSU -> "osu";
            case TAIKO -> "taiko";
            case CATCH -> "fruits";
            case MANIA -> "mania";
            default -> "";
        };
    }

    private OsuApiClient.BeatmapsetSummary cloneWithHints(OsuApiClient.BeatmapsetSummary summary,
                                                          String status,
                                                          Integer favouriteCount,
                                                          Integer playCount) {
        String resolvedStatus = (status == null || status.isBlank()) ? summary.status() : status;
        int resolvedFavourites = favouriteCount != null && favouriteCount >= 0 ? favouriteCount : summary.favouriteCount();
        int resolvedPlays = playCount != null && playCount >= 0 ? playCount : summary.playCount();
        return new OsuApiClient.BeatmapsetSummary(
                summary.id(),
                summary.title(),
                summary.artist(),
                summary.creator(),
                resolvedStatus,
                summary.bpm(),
                summary.video(),
                resolvedFavourites,
                resolvedPlays,
                summary.lastUpdated(),
                summary.coverUrl()
        );
    }

    private String text(Element element) {
        return element == null ? "" : element.text();
    }

    private String textAttr(Element element, String attr) {
        return element == null ? "" : element.attr(attr);
    }

    private record StatusHint(String status, Integer favouriteCount, Integer playCount) { }
}