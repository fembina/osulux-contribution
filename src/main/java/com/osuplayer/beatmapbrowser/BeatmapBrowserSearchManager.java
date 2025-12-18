package com.osuplayer.beatmapbrowser;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.osuplayer.lang.I18n;
import com.osuplayer.mirrors.MirrorSearchResult;
import com.osuplayer.mirrors.MirrorServer;
import com.osuplayer.osu.OsuApiClient;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.TextField;

public class BeatmapBrowserSearchManager {

    private static final int PAGE_SIZE = 20;
    private static final int DEFAULT_VIDEO_ENRICH_REQUESTS = 12;
    private static final int VIDEO_FILTER_ENRICH_REQUESTS = 180;

    private final OsuApiClient apiClient;
    private final List<MirrorServer> mirrorServers;
    private final ExecutorService executor;
    private final ObservableList<OsuApiClient.BeatmapsetSummary> currentResults;
    private final BeatmapBrowserView view;
    private final DialogCallbacks dialogs;
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private volatile Task<?> activeSearchTask;
    private MirrorServer lastMirrorSearchServer;
    private boolean lastMirrorPinnedByAuto;

    private final List<String> officialCursorTrail = new ArrayList<>();
    private int currentPage = 0;
    private boolean lastResultHasMore = false;

    private final List<OsuApiClient.BeatmapsetSummary> bufferedRawResults = new ArrayList<>();
    private ProcessedResults bufferedProcessedResults = new ProcessedResults(List.of(), true, false);
    private boolean bufferedHasMoreRaw = true;
    private int bufferedOfficialRawPage = 0;
    private int bufferedMirrorRawPage = 0;
    private MirrorServer bufferedMirrorServer;
    private int remainingVideoEnrichBudget = 0;
    private boolean requireAccurateVideoData = false;
    private VideoFilterOption activeVideoFilter = VideoFilterOption.ALL;
    private int bufferedOfficialTotal = -1;

    public BeatmapBrowserSearchManager(OsuApiClient apiClient,
                                       List<MirrorServer> mirrorServers,
                                       ExecutorService executor,
                                       ObservableList<OsuApiClient.BeatmapsetSummary> currentResults,
                                       BeatmapBrowserView view,
                                       DialogCallbacks dialogs) {
        this.apiClient = apiClient;
        this.mirrorServers = mirrorServers;
        this.executor = executor;
        this.currentResults = currentResults;
        this.view = view;
        this.dialogs = dialogs;
        updateSourceDependentControls();
    }

    public void triggerSearch(boolean resetPage) {
        int generation = searchGeneration.incrementAndGet();
        cancelActiveSearch();
        if (resetPage) {
            currentPage = 0;
            clearOfficialCursorTrail();
            resetBufferedResults();
        }

        disableSearchControls(true);
        String query = safeText(view.queryField());
        OsuApiClient.BeatmapMode mode = view.modeCombo().getValue();
        OsuApiClient.BeatmapStatus status = view.statusCombo().getValue();
        RelevanceSortOption sortOption = view.relevanceCombo().getValue();
        VideoFilterOption videoFilterOption = view.videoFilterCombo().getValue();
        boolean applyStatusFilter = usesStatusFilter(status);
        SourceOption option = resolveSearchSource();

        view.showStatusMessage(() -> buildSearchingLabel(query, option));

        final int pageIndex = currentPage;
        final OsuApiClient.BeatmapStatus statusFilter = status;
        final boolean applyStatus = applyStatusFilter;
        final RelevanceSortOption selectedSort = sortOption == null ? RelevanceSortOption.MOST_RELEVANT : sortOption;
        final VideoFilterOption selectedVideoFilter = videoFilterOption == null ? VideoFilterOption.ALL : videoFilterOption;
        final boolean preferVideoAssets = view.includeVideoCheck().isSelected();
        final boolean videoFilterActive = selectedVideoFilter != VideoFilterOption.ALL;
        final boolean requireAccurateVideoOfficial = preferVideoAssets || videoFilterActive;
        final boolean requireAccurateVideoMirror = preferVideoAssets
            || videoFilterActive
            || (option.server() != null && shouldForceVideoAccuracy(option.server()));

        Task<SearchOutcome> task = new Task<>() {
            @Override
            protected SearchOutcome call() throws Exception {
                boolean allowStatusFallback = !option.official();
                boolean requireAccurateVideo = option.official() ? requireAccurateVideoOfficial : requireAccurateVideoMirror;
                if (bufferedRawResults.isEmpty()) {
                    requireAccurateVideoData = requireAccurateVideo;
                    activeVideoFilter = selectedVideoFilter;
                    remainingVideoEnrichBudget = requireAccurateVideoData
                            ? resolveVideoEnrichBudget(selectedVideoFilter)
                            : 0;
                    bufferedMirrorServer = option.server();
                }

                FetchStats stats = ensureBufferedResults(option,
                    query,
                    mode,
                    statusFilter,
                    applyStatus,
                    allowStatusFallback,
                    selectedSort,
                    selectedVideoFilter,
                    pageIndex,
                    requireAccurateVideo);

                if (isCancelled()) {
                    return null;
                }

                List<OsuApiClient.BeatmapsetSummary> filtered = bufferedProcessedResults.results();
                int start = Math.min(pageIndex * PAGE_SIZE, filtered.size());
                int end = Math.min(start + PAGE_SIZE, filtered.size());
                List<OsuApiClient.BeatmapsetSummary> pageSlice = new ArrayList<>(filtered.subList(start, end));
                boolean moreFiltered = filtered.size() > end;
                boolean hasMore = moreFiltered || bufferedHasMoreRaw;
                int totalHint = option.official() ? stats.totalAvailableHint() : (hasMore ? -1 : filtered.size());
                int rawCountForLabel = stats.rawFetched() == 0 ? pageSlice.size() : stats.rawFetched();
                return new SearchOutcome(pageSlice,
                        hasMore,
                        option.official() ? option.label() : resolvedMirrorLabel(option),
                        totalHint,
                    rawCountForLabel,
                        bufferedProcessedResults.filterRespected(),
                        bufferedProcessedResults.fallbackUsed());
            }
        };

        task.setOnSucceeded(evt -> {
            SearchOutcome outcome = task.getValue();
            if (outcome == null || generation != searchGeneration.get()) {
                return;
            }
            currentResults.setAll(outcome.results());
            lastResultHasMore = outcome.hasMore();
            view.showStatusMessage(() -> formatStatusLabel(outcome));
            disableSearchControls(false);
            clearActiveSearch(task);
        });

        task.setOnFailed(evt -> {
            if (generation != searchGeneration.get()) {
                return;
            }
            lastResultHasMore = false;
            Throwable ex = task.getException();
            view.showStatusMessage(() -> I18n.tr("Error al buscar beatmaps"));
            dialogs.showError(I18n.tr("Error al buscar"), ex == null ? I18n.tr("Error desconocido") : ex.getMessage());
            disableSearchControls(false);
            clearActiveSearch(task);
        });

        task.setOnCancelled(evt -> {
            if (generation != searchGeneration.get()) {
                return;
            }
            disableSearchControls(false);
            clearActiveSearch(task);
        });

        activeSearchTask = task;
        executor.submit(task);
    }

    private void cancelActiveSearch() {
        Task<?> current = activeSearchTask;
        if (current != null && current.isRunning()) {
            current.cancel(true);
        }
    }

    private void clearActiveSearch(Task<?> task) {
        if (activeSearchTask == task) {
            activeSearchTask = null;
        }
    }

    private String buildSearchingLabel(String query, SourceOption option) {
        String trimmed = query == null ? "" : query.trim();
        String term = trimmed.isEmpty() ? I18n.tr("todo") : trimmed;
        String source = option == null
            ? I18n.tr("Mirrors")
            : option.official() ? option.label() : (option.server() != null ? option.server().displayName() : option.label());
        return I18n.trf("Buscando \"%s\" en %s...", term, source);
    }

    public void changePage(int delta) {
        int newPage = currentPage + delta;
        if (newPage < 0) {
            newPage = 0;
        }
        if (newPage == currentPage) {
            return;
        }
        currentPage = newPage;
        triggerSearch(false);
    }

    public void refreshSourceControls() {
        updateSourceDependentControls();
    }

    private void disableSearchControls(boolean running) {
        view.searchButton().setDisable(false); 
        view.progressIndicator().setVisible(running);
        if (running) {
            view.previousButton().setDisable(true);
            view.nextButton().setDisable(true);
        } else {
            view.previousButton().setDisable(currentPage <= 0);
            view.nextButton().setDisable(!lastResultHasMore);
        }
    }

    private void updateSourceDependentControls() {
        SourceOption option = getSelectedSourceOption();
        boolean show = option != null && option.official();
        view.toggleOfficialControls(show);
    }

    private SourceOption resolveSearchSource() {
        SourceOption option = getSelectedSourceOption();
        if (option == null) {
            return new SourceOption("Mirrors (auto)", false, true, null);
        }
        if (!option.official() || apiClient.hasUserSession() || hasCredentials()) {
            return option;
        }
        SourceOption auto = findAutoSourceOption();
        if (auto != null) {
            SourceOption finalAuto = auto;
            Platform.runLater(() -> view.sourceCombo().getSelectionModel().select(finalAuto));
            Platform.runLater(() -> view.showStatusMessage(() -> I18n.tr("Sin credenciales de osu!, usando mirrors.")));
            return finalAuto;
        }
        dialogs.showError(I18n.tr("Faltan credenciales"), I18n.tr("Configura client_id y client_secret o usa los mirrors públicos."));
        return option;
    }

    private boolean hasCredentials() {
        String clientId = safeText(view.clientIdField());
        String clientSecret = safeText(view.clientSecretField());
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    private SourceOption findAutoSourceOption() {
        for (SourceOption option : view.sourceCombo().getItems()) {
            if (option != null && option.autoMirrors()) {
                return option;
            }
        }
        return null;
    }

    private SourceOption getSelectedSourceOption() {
        return view.sourceCombo().getValue();
    }

    private MirrorSearchContext performMirrorSearch(SourceOption option,
                                                    String query,
                                                    OsuApiClient.BeatmapMode mode,
                                                    OsuApiClient.BeatmapStatus status,
                                                    int pageIndex) throws IOException {
        if (option.server() != null) {
            MirrorServer server = option.server();
            MirrorSearchResult result = server.search(query, mode, status, pageIndex, PAGE_SIZE);
            lastMirrorSearchServer = server;
            lastMirrorPinnedByAuto = false;
            return new MirrorSearchContext(result, server);
        }

        List<String> failures = new ArrayList<>();
        for (MirrorServer server : orderAutoMirrors()) {
            try {
                MirrorSearchResult result = server.search(query, mode, status, pageIndex, PAGE_SIZE);
                lastMirrorSearchServer = server;
                lastMirrorPinnedByAuto = true;
                return new MirrorSearchContext(result, server);
            } catch (IOException ex) {
                failures.add(server.displayName() + ": " + ex.getMessage());
            }
        }
        throw new IOException(I18n.trf("Todos los mirrors fallaron:%n%s", String.join("\n", failures)));
    }

    private List<MirrorServer> orderAutoMirrors() {
        if (lastMirrorPinnedByAuto && lastMirrorSearchServer != null) {
            List<MirrorServer> ordered = new ArrayList<>(mirrorServers.size());
            ordered.add(lastMirrorSearchServer);
            for (MirrorServer server : mirrorServers) {
                if (!server.id().equals(lastMirrorSearchServer.id())) {
                    ordered.add(server);
                }
            }
            return ordered;
        }
        return new ArrayList<>(mirrorServers);
    }

    private FetchStats ensureBufferedResults(SourceOption option,
                                             String query,
                                             OsuApiClient.BeatmapMode mode,
                                             OsuApiClient.BeatmapStatus statusFilter,
                                             boolean applyStatusFilter,
                                             boolean allowStatusFallback,
                                             RelevanceSortOption sortOption,
                                             VideoFilterOption videoFilterOption,
                                             int pageIndex,
                                             boolean requireAccurateVideo) throws IOException {
        int requiredFiltered = Math.max(PAGE_SIZE, (pageIndex + 1) * PAGE_SIZE);
        int rawFetched = 0;

        while (bufferedHasMoreRaw && bufferedProcessedResults.results().size() < requiredFiltered) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Search cancelled");
            }
            RawPage page = option.official()
                    ? fetchOfficialPage(query, mode, statusFilter, bufferedOfficialRawPage)
                    : fetchMirrorPage(option, query, mode, statusFilter, bufferedMirrorRawPage);

            if (!option.official() && page.server() != null && shouldForceVideoAccuracy(page.server())) {
                requireAccurateVideo = true;
            }

            List<OsuApiClient.BeatmapsetSummary> entries = page.entries() == null ? List.of() : page.entries();
            if (!entries.isEmpty() && (requireAccurateVideoData || requireAccurateVideo)) {
                if (!requireAccurateVideoData && requireAccurateVideo) {
                    requireAccurateVideoData = true;
                    remainingVideoEnrichBudget = resolveVideoEnrichBudget(activeVideoFilter);
                }
                if (remainingVideoEnrichBudget != 0) {
                    EnrichmentResult enrichment = enrichVideoMetadata(entries, remainingVideoEnrichBudget);
                    entries = enrichment.list();
                    remainingVideoEnrichBudget -= enrichment.requestsUsed();
                }
            }

            bufferedRawResults.addAll(entries);
            rawFetched += entries.size();
            bufferedHasMoreRaw = page.hasMore();

            if (option.official()) {
                bufferedOfficialRawPage++;
                if (page.totalHint() >= 0) {
                    bufferedOfficialTotal = page.totalHint();
                }
            } else {
                bufferedMirrorRawPage++;
                if (page.server() != null) {
                    bufferedMirrorServer = page.server();
                }
            }

                bufferedProcessedResults = postProcessResults(bufferedRawResults,
                    query,
                    statusFilter,
                    applyStatusFilter,
                    allowStatusFallback,
                    sortOption,
                    videoFilterOption,
                    option.official());

            if (!bufferedHasMoreRaw) {
                break;
            }
        }

        int totalAvailableHint = option.official()
                ? bufferedOfficialTotal
                : (bufferedHasMoreRaw ? -1 : bufferedProcessedResults.results().size());
        return new FetchStats(rawFetched, totalAvailableHint);
    }

    private ProcessedResults postProcessResults(List<OsuApiClient.BeatmapsetSummary> input,
                                                String query,
                                                OsuApiClient.BeatmapStatus statusFilter,
                                                boolean applyStatusFilter,
                                                boolean allowStatusFallback,
                                                RelevanceSortOption sortOption,
                                                VideoFilterOption videoFilterOption,
                                                boolean applyQueryFilter) {
        List<OsuApiClient.BeatmapsetSummary> list = input == null ? List.of() : input;
        List<OsuApiClient.BeatmapsetSummary> filtered = filterByStatus(list, statusFilter, applyStatusFilter);
        filtered = filterByVideo(filtered, videoFilterOption);
        filtered = applyQueryFilter ? filterByQueryTokens(filtered, query) : filtered;
        boolean filterRespected = !applyStatusFilter || !filtered.isEmpty() || list.isEmpty();
        boolean fallbackUsed = false;

        if (!filterRespected && allowStatusFallback && !list.isEmpty()) {
            filtered = applyQueryFilter ? filterByQueryTokens(list, query) : list;
            fallbackUsed = true;
        }

        List<OsuApiClient.BeatmapsetSummary> sorted = sortByOption(filtered, sortOption, query, statusFilter, applyStatusFilter);
        return new ProcessedResults(sorted, filterRespected, fallbackUsed);
    }

    private RawPage fetchOfficialPage(String query,
                                      OsuApiClient.BeatmapMode mode,
                                      OsuApiClient.BeatmapStatus status,
                                      int rawPageIndex) throws IOException {
        int offset = rawPageIndex * PAGE_SIZE;
        String cursorHint = cursorForOfficialPage(rawPageIndex);
        OsuApiClient.BeatmapSearchResult apiResult = apiClient.searchBeatmapSets(query, mode, status, offset, cursorHint);
        storeOfficialCursor(rawPageIndex, apiResult.cursorString());
        boolean hasMore = apiResult.cursorString() != null
                || (apiResult.beatmapsets() != null && apiResult.beatmapsets().size() == PAGE_SIZE)
                || apiResult.total() > ((rawPageIndex + 1) * PAGE_SIZE);
        return new RawPage(apiResult.beatmapsets(), hasMore, apiResult.total(), null);
    }

    private RawPage fetchMirrorPage(SourceOption option,
                                    String query,
                                    OsuApiClient.BeatmapMode mode,
                                    OsuApiClient.BeatmapStatus status,
                                    int rawPageIndex) throws IOException {
        MirrorSearchContext context = performMirrorSearch(option, query, mode, status, rawPageIndex);
        MirrorSearchResult mirrorResult = context.result();
        boolean hasMore = mirrorResult.hasMore();
        return new RawPage(mirrorResult.beatmapsets(), hasMore, -1, context.server());
    }

    private boolean shouldForceVideoAccuracy(MirrorServer server) {
        if (server == null) {
            return false;
        }
        String id = server.id();
        return "ripple".equalsIgnoreCase(id) || "beatconnect".equalsIgnoreCase(id);
    }

    private EnrichmentResult enrichVideoMetadata(List<OsuApiClient.BeatmapsetSummary> list,
                                                 int maxRequests) {
        if (list.isEmpty() || maxRequests <= 0) {
            return new EnrichmentResult(list, 0);
        }
        List<OsuApiClient.BeatmapsetSummary> enriched = new ArrayList<>(list.size());
        int requests = 0;
        for (OsuApiClient.BeatmapsetSummary summary : list) {
            OsuApiClient.BeatmapsetSummary current = summary;
            if (current != null && !current.video() && requests < maxRequests) {
                try {
                    OsuApiClient.BeatmapsetSummary remote = apiClient.fetchBeatmapset(current.id());
                    if (remote != null) {
                        current = mergeSummaries(current, remote);
                    }
                    requests++;
                } catch (IOException ignored) { }
            }
            enriched.add(current);
        }
        return new EnrichmentResult(enriched, requests);
    }

    private OsuApiClient.BeatmapsetSummary mergeSummaries(OsuApiClient.BeatmapsetSummary base,
                                                          OsuApiClient.BeatmapsetSummary override) {
        if (override == null) {
            return base;
        }
        return new OsuApiClient.BeatmapsetSummary(
                base.id(),
                chooseValue(override.title(), base.title()),
                chooseValue(override.artist(), base.artist()),
                chooseValue(override.creator(), base.creator()),
                chooseValue(override.status(), base.status()),
                override.bpm() > 0 ? override.bpm() : base.bpm(),
                override.video() || base.video(),
                Math.max(base.favouriteCount(), override.favouriteCount()),
                Math.max(base.playCount(), override.playCount()),
                override.lastUpdated() != null ? override.lastUpdated() : base.lastUpdated(),
                chooseValue(override.coverUrl(), base.coverUrl()));
    }

    private String chooseValue(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private List<OsuApiClient.BeatmapsetSummary> filterByStatus(List<OsuApiClient.BeatmapsetSummary> list,
                                                                OsuApiClient.BeatmapStatus status,
                                                                boolean applyFilter) {
        if (!applyFilter || status == null || status == OsuApiClient.BeatmapStatus.ANY) {
            return list;
        }
        List<OsuApiClient.BeatmapsetSummary> filtered = new ArrayList<>();
        for (OsuApiClient.BeatmapsetSummary summary : list) {
            if (summary == null || summary.status() == null) {
                continue;
            }
            if (normalizeStatus(summary.status()).equals(normalizeStatus(status.name()))) {
                filtered.add(summary);
            }
        }
        return filtered;
    }

    private List<OsuApiClient.BeatmapsetSummary> filterByVideo(List<OsuApiClient.BeatmapsetSummary> list,
                                                                VideoFilterOption option) {
        if (option == null || option == VideoFilterOption.ALL) {
            return list;
        }
        List<OsuApiClient.BeatmapsetSummary> filtered = new ArrayList<>();
        for (OsuApiClient.BeatmapsetSummary summary : list) {
            if (summary == null) {
                continue;
            }
            boolean hasVideo = summary.video();
            if (option == VideoFilterOption.ONLY_WITH_VIDEO && hasVideo) {
                filtered.add(summary);
            } else if (option == VideoFilterOption.ONLY_WITHOUT_VIDEO && !hasVideo) {
                filtered.add(summary);
            }
        }
        return filtered;
    }

    private int resolveVideoEnrichBudget(VideoFilterOption option) {
        if (option != null && option != VideoFilterOption.ALL) {
            return VIDEO_FILTER_ENRICH_REQUESTS;
        }
        return DEFAULT_VIDEO_ENRICH_REQUESTS;
    }

    private List<OsuApiClient.BeatmapsetSummary> sortByOption(List<OsuApiClient.BeatmapsetSummary> list,
                                                             RelevanceSortOption sortOption,
                                                             String query,
                                                             OsuApiClient.BeatmapStatus preferredStatus,
                                                             boolean prioritizePreferredStatus) {
        Comparator<OsuApiClient.BeatmapsetSummary> comparator = comparatorForSort(sortOption,
                query,
                preferredStatus,
                prioritizePreferredStatus);
        return list.stream().sorted(comparator).toList();
    }

    private Comparator<OsuApiClient.BeatmapsetSummary> comparatorForSort(RelevanceSortOption sortOption,
                                                                         String query,
                                                                         OsuApiClient.BeatmapStatus preferredStatus,
                                                                         boolean prioritizePreferredStatus) {
        RelevanceSortOption effective = sortOption == null ? RelevanceSortOption.MOST_RELEVANT : sortOption;
        return switch (effective) {
            case MOST_RELEVANT -> (a, b) -> compareByRelevance(a, b, query, preferredStatus, prioritizePreferredStatus);
            case LEAST_RELEVANT -> (a, b) -> compareByRelevance(b, a, query, preferredStatus, prioritizePreferredStatus);
            case MOST_DOWNLOADED -> Comparator.comparingInt(OsuApiClient.BeatmapsetSummary::playCount).reversed();
            case LEAST_DOWNLOADED -> Comparator.comparingInt(OsuApiClient.BeatmapsetSummary::playCount);
            case MOST_FAVOURITED -> Comparator.comparingInt(OsuApiClient.BeatmapsetSummary::favouriteCount).reversed();
            case LEAST_FAVOURITED -> Comparator.comparingInt(OsuApiClient.BeatmapsetSummary::favouriteCount);
            case MOST_RECENT -> Comparator.comparing(this::safeLastUpdated).reversed();
            case OLDEST -> Comparator.comparing(this::safeLastUpdated);
        };
    }

    private Instant safeLastUpdated(OsuApiClient.BeatmapsetSummary summary) {
        if (summary == null || summary.lastUpdated() == null) {
            return Instant.EPOCH;
        }
        return summary.lastUpdated();
    }

    private String normalizeStatus(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "approved" -> "ranked";
            case "wip" -> "pending";
            case "grave" -> "graveyard";
            default -> value;
        };
    }

    private int statusWeight(String normalizedStatus) {
        return switch (normalizedStatus) {
            case "ranked" -> 0;
            case "loved" -> 1;
            case "qualified" -> 2;
            case "pending" -> 3;
            case "graveyard" -> 4;
            default -> 5;
        };
    }

    private int compareByRelevance(OsuApiClient.BeatmapsetSummary a,
                                   OsuApiClient.BeatmapsetSummary b,
                                   String query,
                                   OsuApiClient.BeatmapStatus preferredStatus,
                                   boolean prioritizePreferredStatus) {
        int statusComparison = compareByStatusPriority(a, b, preferredStatus, prioritizePreferredStatus);
        if (statusComparison != 0) {
            return statusComparison;
        }
        int scoreA = relevanceScore(a, query);
        int scoreB = relevanceScore(b, query);
        if (scoreA != scoreB) {
            return Integer.compare(scoreB, scoreA);
        }
        int favouriteCompare = Integer.compare(b.favouriteCount(), a.favouriteCount());
        if (favouriteCompare != 0) {
            return favouriteCompare;
        }
        int playCompare = Integer.compare(b.playCount(), a.playCount());
        if (playCompare != 0) {
            return playCompare;
        }
        return compareInstants(b.lastUpdated(), a.lastUpdated());
    }

    private int compareByStatusPriority(OsuApiClient.BeatmapsetSummary a,
                                        OsuApiClient.BeatmapsetSummary b,
                                        OsuApiClient.BeatmapStatus preferredStatus,
                                        boolean prioritizePreferredStatus) {
        if (!prioritizePreferredStatus || preferredStatus == null || preferredStatus == OsuApiClient.BeatmapStatus.ANY) {
            return 0;
        }
        String normalizedPreferred = normalizeStatus(preferredStatus.name());
        String statusA = normalizeStatus(a.status());
        String statusB = normalizeStatus(b.status());
        boolean aMatches = statusA.equals(normalizedPreferred);
        boolean bMatches = statusB.equals(normalizedPreferred);
        if (aMatches != bMatches) {
            return aMatches ? -1 : 1;
        }
        return Integer.compare(statusWeight(statusA), statusWeight(statusB));
    }

    private int relevanceScore(OsuApiClient.BeatmapsetSummary summary, String query) {
        int score = popularityBonus(summary);
        List<String> tokens = tokenizeQuery(query);
        if (tokens.isEmpty()) {
            return score;
        }

        int matchedTokens = 0;
        for (String token : tokens) {
            int tokenScore = bestTokenScore(summary, token);
            if (tokenScore > 0) {
                matchedTokens++;
                score += tokenScore;
            }
        }

        if (matchedTokens == tokens.size()) {
            score += 25; 
        } else {
            score -= (tokens.size() - matchedTokens) * 15;
        }
        return score;
    }

    private int bestTokenScore(OsuApiClient.BeatmapsetSummary summary, String token) {
        int titleScore = matchScore(summary.title(), token, 80);
        int artistScore = matchScore(summary.artist(), token, 70);
        int creatorScore = matchScore(summary.creator(), token, 50);
        int displayScore = matchScore(summary.displayName(), token, 40);
        return Math.max(Math.max(titleScore, artistScore), Math.max(creatorScore, displayScore));
    }

    private List<String> tokenizeQuery(String raw) {
        if (raw == null) {
            return List.of();
        }
        String[] pieces = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<String> tokens = new ArrayList<>(pieces.length);
        for (String piece : pieces) {
            if (piece != null && !piece.isBlank()) {
                tokens.add(piece);
            }
        }
        return tokens;
    }

    private List<OsuApiClient.BeatmapsetSummary> filterByQueryTokens(List<OsuApiClient.BeatmapsetSummary> list, String query) {
        List<String> tokens = tokenizeQuery(query);
        if (tokens.isEmpty()) {
            return list;
        }
        List<OsuApiClient.BeatmapsetSummary> filtered = new ArrayList<>();
        for (OsuApiClient.BeatmapsetSummary summary : list) {
            if (summary == null) {
                continue;
            }
            if (matchesAllTokens(summary, tokens)) {
                filtered.add(summary);
            }
        }
        return filtered;
    }

    private boolean matchesAllTokens(OsuApiClient.BeatmapsetSummary summary, List<String> tokens) {
        if (summary == null || tokens.isEmpty()) {
            return true;
        }
        String title = safeLower(summary.title());
        String artist = safeLower(summary.artist());
        String creator = safeLower(summary.creator());
        String display = safeLower(summary.displayName());

        for (String token : tokens) {
            boolean matched = containsToken(title, token)
                || containsToken(artist, token)
                || containsToken(creator, token)
                || containsToken(display, token);
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean containsToken(String haystack, String token) {
        return haystack != null && haystack.contains(token);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private int matchScore(String value, String needle, int baseWeight) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String haystack = value.toLowerCase(Locale.ROOT);
        if (haystack.equals(needle)) {
            return baseWeight + 25;
        }
        if (haystack.startsWith(needle)) {
            return baseWeight + 15;
        }
        if (haystack.contains(needle)) {
            return baseWeight;
        }
        return 0;
    }

    private int popularityBonus(OsuApiClient.BeatmapsetSummary summary) {
        int favourites = Math.min(summary.favouriteCount(), 5000);
        int plays = Math.min(summary.playCount(), 100_000);
        return (favourites / 5) + (plays / 200);
    }

    private int compareInstants(Instant a, Instant b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    private String formatStatusLabel(SearchOutcome outcome) {
        int displayed = currentResults.size();
        int rawCount = Math.max(0, outcome.pageRawCount());
        StringBuilder builder = new StringBuilder(I18n.trf("[%s] Página %d • %d resultados",
            I18n.tr(outcome.sourceLabel()), currentPage + 1, displayed));

        if (outcome.totalAvailable() >= 0) {
            builder.append(' ').append(I18n.trf("de %d totales", outcome.totalAvailable()));
        } else {
            int lowerBound = estimateLowerBoundTotal(rawCount);
            if (lowerBound > 0) {
                String pattern = outcome.hasMore() ? "de ≥%d totales" : "de %d totales";
                builder.append(' ').append(I18n.trf(pattern, lowerBound));
            }
        }

        if (rawCount > 0 && rawCount != displayed) {
            builder.append(I18n.trf(" (filtrados desde %d)", rawCount));
        }

        if (outcome.filterFallback()) {
            builder.append(' ').append(I18n.tr("• El mirror no admite filtros por estado; mostrando todo"));
        }

        return builder.toString();
    }

    private String resolvedMirrorLabel(SourceOption option) {
        if (bufferedMirrorServer != null) {
            return bufferedMirrorServer.displayName();
        }
        if (option != null && option.server() != null) {
            return option.server().displayName();
        }
        return option == null ? "Mirrors" : option.label();
    }

    private int estimateLowerBoundTotal(int rawCount) {
        int pageBase = currentPage * PAGE_SIZE;
        int contribution = Math.max(rawCount, currentResults.size());
        return pageBase + contribution;
    }

    private boolean usesStatusFilter(OsuApiClient.BeatmapStatus status) {
        return status != null && status != OsuApiClient.BeatmapStatus.ANY;
    }

    private synchronized void clearOfficialCursorTrail() {
        officialCursorTrail.clear();
    }

    private synchronized void resetBufferedResults() {
        bufferedRawResults.clear();
        bufferedProcessedResults = new ProcessedResults(List.of(), true, false);
        bufferedHasMoreRaw = true;
        bufferedOfficialRawPage = 0;
        bufferedMirrorRawPage = 0;
        bufferedMirrorServer = null;
        remainingVideoEnrichBudget = 0;
        requireAccurateVideoData = false;
        activeVideoFilter = VideoFilterOption.ALL;
        bufferedOfficialTotal = -1;
    }

    private synchronized String cursorForOfficialPage(int pageIndex) {
        if (pageIndex <= 0) {
            return null;
        }
        int idx = pageIndex - 1;
        return idx < officialCursorTrail.size() ? officialCursorTrail.get(idx) : null;
    }

    private synchronized void storeOfficialCursor(int pageIndex, String cursor) {
        if (pageIndex < 0) {
            return;
        }
        while (officialCursorTrail.size() <= pageIndex) {
            officialCursorTrail.add(null);
        }
        officialCursorTrail.set(pageIndex, cursor);
    }

    private String safeText(TextField field) {
        if (field == null) {
            return "";
        }
        String text = field.getText();
        return text == null ? "" : text.trim();
    }

    private record ProcessedResults(List<OsuApiClient.BeatmapsetSummary> results,
                                    boolean filterRespected,
                                    boolean fallbackUsed) { }

    private record FetchStats(int rawFetched, int totalAvailableHint) { }

    private record RawPage(List<OsuApiClient.BeatmapsetSummary> entries,
                           boolean hasMore,
                           int totalHint,
                           MirrorServer server) { }

    private record EnrichmentResult(List<OsuApiClient.BeatmapsetSummary> list,
                                    int requestsUsed) { }

    private record SearchOutcome(List<OsuApiClient.BeatmapsetSummary> results,
                                 boolean hasMore,
                                 String sourceLabel,
                                 int totalAvailable,
                                 int pageRawCount,
                                 boolean filterRespected,
                                 boolean filterFallback) { }

    private record MirrorSearchContext(MirrorSearchResult result, MirrorServer server) { }
}
