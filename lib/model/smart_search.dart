import 'dart:async';

import 'package:aves/model/entry/entry.dart';
import 'package:aves/model/entry/extensions/props.dart';
import 'package:aves/model/filters/query.dart';
import 'package:aves/model/filters/trash.dart';
import 'package:aves/model/settings/settings.dart';
import 'package:aves/model/source/analysis_controller.dart';
import 'package:aves/model/source/collection_lens.dart';
import 'package:aves/model/source/collection_source.dart';
import 'package:aves/model/source/events.dart';
import 'package:aves/model/vaults/vaults.dart';
import 'package:aves/services/common/services.dart';
import 'package:aves/services/smart_search_service.dart';
import 'package:aves/widgets/aves_app.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter/foundation.dart';

final SmartSearch smartSearch = SmartSearch._private();

// Coordinates on-device semantic search: model status, indexing, and queries.
// Vectors and inference live on the platform side; this side decides what to index
// and which entries a query may return (only visible entries, e.g. excluding hidden and locked items).
class SmartSearch with ChangeNotifier {
  static const _batchSize = 8;

  // above this number of pending items, indexing is delegated to the analysis service
  static const inlineIndexingMaxCount = 20;
  static const _serviceIndexingThreads = 2;
  static const _leaseRetryDelay = Duration(seconds: 2);
  static const _leaseMaxAttempts = 60;
  static const _blockNotCharging = 'not_charging';

  SmartSearchStatus? _status;
  bool _indexing = false;
  Future<void>? _analysisDone, _analysisRerun;
  bool _rerunIsFullAnalysis = false;
  SmartSearchCoverage? _coverage;
  CollectionSource? _lastSource;

  final ValueNotifier<ProgressEvent?> indexingProgressNotifier = ValueNotifier(null);

  new _private() {
    if (kFlutterMemoryAllocationsEnabled) ChangeNotifier.maybeDispatchObjectCreation(this);
  }

  SmartSearchStatus? get status => _status;

  SmartSearchCoverage? get coverage => _coverage;

  // entry points are hidden on TV, where it cannot be enabled
  bool get isEnabled => settings.enableSmartSearch;

  bool get isReady => isEnabled && (_status?.ready ?? false);

  bool get chargingOnly => settings.smartSearchChargingOnly ?? _status?.defaultChargingOnly ?? true;

  // downloads are installed when polled, so they are polled until done, even when the settings are not shown
  Timer? _downloadPollTimer;
  @visibleForTesting
  static Duration downloadPollRunningInterval = const Duration(seconds: 3);
  @visibleForTesting
  static Duration downloadPollWaitingInterval = const Duration(seconds: 30);

  // download failures are reported once by the platform, so we keep track of them
  final Set<String> _failedDownloads = {};

  bool hasDownloadFailed(String modelId) => _failedDownloads.contains(modelId);

  void clearDownloadFailure(String modelId) => _failedDownloads.remove(modelId);

  Future<SmartSearchStatus?> refreshStatus() async {
    final status = await smartSearchService.getStatus();
    status?.models.where((v) => v.downloadState == 'failed').forEach((v) => _failedDownloads.add(v.id));
    if (status != _status) {
      final becameReady = !(_status?.ready ?? false) && (status?.ready ?? false);
      final modelChanged = _status != null && _status?.activeModel != status?.activeModel && (status?.ready ?? false);
      _status = status;
      notifyListeners();
      // e.g. model download just completed, or a downloaded model replaced the previous one
      final source = _lastSource;
      if ((becameReady || modelChanged) && source != null) {
        unawaited(onAnalysisDone(source, isFullAnalysis: false));
      }
    }
    _scheduleDownloadPoll(status);
    return status;
  }

  void _scheduleDownloadPoll(SmartSearchStatus? status) {
    _downloadPollTimer?.cancel();
    _downloadPollTimer = null;
    final downloads = status?.models.where((v) => v.isDownloading || v.isWaiting).toList() ?? [];
    if (downloads.isEmpty) return;
    final delay = downloads.any((v) => v.isDownloading) ? downloadPollRunningInterval : downloadPollWaitingInterval;
    _downloadPollTimer = Timer(delay, () => unawaited(refreshStatus()));
  }

  // how many of the visible entries are indexed
  Future<SmartSearchCoverage?> refreshCoverage(CollectionSource source) async {
    if (!isReady) return null;
    final candidates = source.visibleEntries.where(_isIndexingCandidate).toList();
    final (ids, fingerprints) = _toArrays(candidates);
    final pending = await smartSearchService.pending(ids, fingerprints);
    if (pending == null) return null;
    final coverage = SmartSearchCoverage(indexed: candidates.length - pending.length, total: candidates.length);
    if (coverage != _coverage) {
      _coverage = coverage;
      notifyListeners();
    }
    return coverage;
  }

  // entries

  static bool isIndexable(AvesEntry entry) => entry.id > 0 && !entry.trashed && !entry.isSvg;

  // Entries are indexed once catalogued, when their orientation is known,
  // and never when in a vault, as vectors are stored outside of the vault protection.
  static bool _isIndexingCandidate(AvesEntry entry) => isIndexable(entry) && entry.isCatalogued && !vaults.isVaultEntryUri(entry.uri);

  // Identifies the media content an embedding was computed from.
  // Any content change (e.g. edited or rotated file, ID reused for another item) invalidates the stored vector,
  // while moving or renaming a file (which keeps the entry ID) does not.
  static int fingerprintOf(AvesEntry entry) {
    // FNV-1a 64-bit
    var h = -0x340d631b7bdddcdb;
    void add(Object? v) {
      for (final c in '$v|'.codeUnits) {
        h ^= c;
        h *= 0x100000001b3;
      }
    }

    add(entry.mimeType);
    add(entry.sizeBytes);
    add(entry.width);
    add(entry.height);
    add(entry.sourceDateTakenMillis);
    add(entry.rotationDegrees);
    add(entry.isFlipped);
    return h;
  }

  static SmartSearchItem toItem(AvesEntry entry) => SmartSearchItem(
    id: entry.id,
    fingerprint: fingerprintOf(entry),
    uri: entry.uri,
    mimeType: entry.mimeType,
    rotationDegrees: entry.rotationDegrees,
    isFlipped: entry.isFlipped,
  );

  // expands stacks (bursts, developed RAWs) into their actual entries
  static Set<AvesEntry> expandStacks(Iterable<AvesEntry> entries) => entries.expand((entry) => entry.stackedEntries ?? [entry]).where(isIndexable).toSet();

  static (Int32List, Int64List) _toArrays(Iterable<AvesEntry> entries) {
    final list = entries.toList();
    final ids = Int32List(list.length);
    final fingerprints = Int64List(list.length);
    for (var i = 0; i < list.length; i++) {
      ids[i] = list[i].id;
      fingerprints[i] = fingerprintOf(list[i]);
    }
    return (ids, fingerprints);
  }

  // queries

  // Whether submitting `queryFilter` (e.g. with the keyboard enter key) should run a smart search
  // rather than filter items by title: when smart search is ready, unless the query is title search syntax,
  // or looks like a file name that some item title matches, e.g. "IMG_2041".
  // Words like "sunset" run a smart search even when some titles contain them,
  // as filtering by title remains available from the query suggestion.
  bool shouldSubmitAsSmartSearch(QueryFilter queryFilter, CollectionSource source, CollectionLens? parent) {
    if (!isReady || !canSearchFrom(parent)) return false;
    final query = queryFilter.query.trim();
    if (query.isEmpty || isTitleQuerySyntax(queryFilter)) return false;
    if (parent?.smartSearchResult != null || !_fileNamePattern.hasMatch(query)) return true;
    // stacks (e.g. bursts, days in calendar layout) only expose their main entry
    final candidates = parent != null ? expandStacks(parent.sortedEntries) : source.visibleEntries;
    return !candidates.any(queryFilter.test);
  }

  // e.g. "IMG_2041", "PXL_20240101", "scan.jpg", "DSC0042"
  static final _fileNamePattern = RegExp(r'^\S*[0-9_.]\S*$');

  // e.g. regex (`/.../`), exact (`"..."`), negation (`-...`), and field queries (`YEAR=2023`)
  static final _titleSyntaxPattern = RegExp(r'^(/.*/|".*"|-.*)$');

  static bool isTitleQuerySyntax(QueryFilter queryFilter) {
    final query = queryFilter.query.trim();
    return _titleSyntaxPattern.hasMatch(query) || queryFilter.fieldTest(query.toUpperCase()) != null;
  }

  // there is nothing to search in the recycle bin, as trashed items are not indexed
  static bool canSearchFrom(CollectionLens? parent) => parent == null || !parent.filters.contains(TrashFilter.instance);

  // Candidates of a search started from `parent`, and whether they are restricted to it:
  // within the collection when it is restricted by filters (e.g. an album),
  // but not within previous smart search results, as a new description starts a new search.
  static (Iterable<AvesEntry>, bool) candidatesFor(CollectionSource source, CollectionLens? parent) {
    final isScoped = parent != null && parent.smartSearchResult == null && (parent.filters.isNotEmpty || parent.fixedSelection != null);
    return (isScoped ? parent.sortedEntries : source.visibleEntries, isScoped);
  }

  // `isScoped`: whether candidates are restricted to a filtered collection, rather than all visible entries
  Future<SmartSearchResult?> searchText(String query, CollectionSource source, Iterable<AvesEntry> candidates, {bool isScoped = false}) async {
    if (!isReady) return null;
    final allowed = expandStacks(candidates);
    final (ids, fingerprints) = _toArrays(allowed);
    final hits = await smartSearchService.searchText(query, ids, fingerprints);
    if (hits == null) return null;
    _addToHistory(query);
    return SmartSearchResult._fromHits(source, hits, query: query, isScoped: isScoped);
  }

  Future<SmartSearchResult?> searchSimilar(AvesEntry entry, CollectionSource source, Iterable<AvesEntry> candidates) async {
    if (!isReady || !isIndexable(entry)) return null;
    final allowed = expandStacks(candidates)..add(entry);
    final (ids, fingerprints) = _toArrays(allowed);
    final hits = await smartSearchService.searchSimilar(toItem(entry), ids, fingerprints);
    if (hits == null) return null;
    return SmartSearchResult._fromHits(source, hits, similarTo: entry);
  }

  void _addToHistory(String query) {
    if (!settings.saveSearchHistory) return;
    final history = settings.smartSearchHistory
      ..remove(query)
      ..insert(0, query);
    settings.smartSearchHistory = history;
  }

  // indexing

  // Called by the UI engine when analysis completes.
  // Removes vectors of deleted entries after a full analysis, then indexes pending entries,
  // inline when there are few of them, otherwise in background work.
  // Overlapping calls are coalesced into a single subsequent run, over all entries,
  // and their futures complete when that run is done.
  Future<void> onAnalysisDone(CollectionSource source, {required bool isFullAnalysis, Set<AvesEntry>? entries}) {
    _lastSource = source;
    final running = _analysisDone;
    if (running == null) {
      // registered before running, as the run may synchronously lead to another call, e.g. when becoming ready
      final completer = Completer<void>();
      _analysisDone = completer.future;
      _runAnalysisDone(source, isFullAnalysis: isFullAnalysis, entries: entries).whenComplete(() => _analysisDone = null).then(completer.complete, onError: completer.completeError);
      return completer.future;
    }
    _rerunIsFullAnalysis |= isFullAnalysis;
    return _analysisRerun ??= running.catchError((_) {}).then((_) {
      _analysisRerun = null;
      final isFull = _rerunIsFullAnalysis;
      _rerunIsFullAnalysis = false;
      return onAnalysisDone(_lastSource ?? source, isFullAnalysis: isFull);
    });
  }

  Future<void> _runAnalysisDone(CollectionSource source, {required bool isFullAnalysis, Set<AvesEntry>? entries}) async {
    if (!isEnabled || _indexing) return;
    final status = await refreshStatus();
    if (status == null || !status.ready) return;

    if (isFullAnalysis) {
      await _collectGarbage(source);
    }

    // after a partial analysis (e.g. new items), only the analyzed items are checked
    final pending = await _pendingEntries(source, entries: entries);
    if (pending.isEmpty) return;

    final blocked = await smartSearchService.canIndex(chargingOnly: chargingOnly);
    if (blocked != null) {
      if (blocked == _blockNotCharging) {
        // not available on all Android versions, in which case indexing resumes when the app is next resumed
        await smartSearchService.startIndexingWork(whenCharging: true);
      }
      return;
    }

    if (pending.length > inlineIndexingMaxCount) {
      // foreground services can only be started when the app is visible
      switch (AvesApp.lifecycleStateNotifier.value) {
        case .resumed:
        case .inactive:
          await smartSearchService.startIndexingWork(whenCharging: false);
        default:
          break;
      }
      return;
    }
    await index(source, controller: AnalysisController(canStartService: false), threads: 1, background: true);
  }

  // e.g. when the device may have been plugged in since indexing paused
  void onAppResumed() {
    final source = _lastSource;
    if (source != null && isEnabled) {
      unawaited(onAnalysisDone(source, isFullAnalysis: false));
    }
  }

  // Called by the smart search background work, once its source is ready.
  Future<void> indexInService(CollectionSource source, AnalysisController controller) async {
    if (!isEnabled) return;
    final status = await refreshStatus();
    if (status == null || !status.ready) return;
    await index(source, controller: controller, threads: _serviceIndexingThreads, background: false, waitForLease: true, inService: true);
  }

  Future<void> _collectGarbage(CollectionSource source) async {
    final ids = Int32List.fromList(source.allEntries.map((entry) => entry.id).toList());
    // guard against an incomplete source wiping the index
    if (ids.isEmpty) return;
    await smartSearchService.retainOnly(ids);
  }

  Future<List<AvesEntry>> _pendingEntries(CollectionSource source, {Set<AvesEntry>? entries}) async {
    // most recent first
    final candidates = (entries != null ? source.sortedEntriesByDate.where(entries.contains) : source.sortedEntriesByDate).where(_isIndexingCandidate).toList();
    if (candidates.isEmpty) return [];
    final (ids, fingerprints) = _toArrays(candidates);
    final pendingIds = await smartSearchService.pending(ids, fingerprints);
    if (pendingIds == null || pendingIds.isEmpty) return [];
    final pendingSet = pendingIds.toSet();
    return candidates.where((entry) => pendingSet.contains(entry.id)).toList();
  }

  Future<void> index(
    CollectionSource source, {
    required AnalysisController controller,
    required int threads,
    required bool background,
    bool waitForLease = false,
    bool inService = false,
  }) async {
    if (_indexing) return;
    _indexing = true;
    var leased = false;
    try {
      var attempts = 0;
      while (!(leased = await smartSearchService.acquireIndexing())) {
        if (!waitForLease || controller.isStopping || ++attempts > _leaseMaxAttempts) return;
        await Future.delayed(_leaseRetryDelay);
      }

      final pending = await _pendingEntries(source);
      if (pending.isEmpty) return;
      await reportService.log('Smart search indexing ${pending.length} items with threads=$threads');

      var done = 0;
      indexingProgressNotifier.value = ProgressEvent(done: done, total: pending.length);
      for (var i = 0; i < pending.length; i += _batchSize) {
        if (inService) {
          // settings may be changed in the UI while indexing in the background
          await settings.reload();
        }
        if (controller.isStopping || !isEnabled) break;
        final blocked = await smartSearchService.canIndex(chargingOnly: chargingOnly);
        if (blocked != null) {
          await reportService.log('Smart search indexing paused: $blocked');
          if (blocked == _blockNotCharging) {
            await smartSearchService.startIndexingWork(whenCharging: true);
          }
          break;
        }
        final generation = await smartSearchService.generation();
        if (generation == null) break;
        final batch = pending.skip(i).take(_batchSize).where((entry) => source.getEntryById(entry.id) != null).map(toItem).toList();
        if (batch.isNotEmpty) {
          // e.g. encoder failure, or the lease was taken over by another engine
          final stored = await smartSearchService.embed(batch, generation: generation, threads: threads, background: background);
          if (stored == null) break;
        }
        done += _batchSize;
        indexingProgressNotifier.value = ProgressEvent(done: done.clamp(0, pending.length), total: pending.length);
      }
    } finally {
      if (leased) await smartSearchService.releaseIndexing();
      indexingProgressNotifier.value = null;
      _indexing = false;
      unawaited(refreshStatus());
    }
  }

  // applies even when disabled, as vectors may remain from when it was enabled
  Future<void> onEntriesRemoved(Iterable<int> ids) async {
    await smartSearchService.remove(Int32List.fromList(ids.where((id) => id > 0).toList()));
  }

  @visibleForTesting
  void reset() {
    _status = null;
    _coverage = null;
    _lastSource = null;
    _indexing = false;
    _downloadPollTimer?.cancel();
    _downloadPollTimer = null;
    _analysisDone = null;
    _analysisRerun = null;
    _rerunIsFullAnalysis = false;
    _failedDownloads.clear();
  }

  // model management

  Future<void> selectModel(String modelId) async {
    await smartSearchService.setActiveModel(modelId);
    await refreshStatus();
  }

  Future<void> deleteModel(String modelId) async {
    await smartSearchService.deleteModel(modelId);
    if (_status?.activeModel == modelId) {
      await smartSearchService.setActiveModel(null);
    }
    await refreshStatus();
  }
}

@immutable
class SmartSearchCoverage extends Equatable {
  final int indexed, total;

  @override
  List<Object?> get props => [indexed, total];

  const new({required this.indexed, required this.total});

  bool get isComplete => indexed >= total;
}

// Ranked search results, as a snapshot of entries at search time.
@immutable
class SmartSearchResult {
  final String? query;
  final AvesEntry? similarTo;
  final bool isScoped;
  final List<AvesEntry> entries;
  final Map<int, double> scores;

  const new _internal({
    required this.query,
    required this.similarTo,
    required this.isScoped,
    required this.entries,
    required this.scores,
  });

  factory _fromHits(CollectionSource source, SmartSearchHits hits, {String? query, AvesEntry? similarTo, bool isScoped = false}) {
    final entries = <AvesEntry>[];
    final scores = <int, double>{};
    for (var i = 0; i < hits.ids.length; i++) {
      final entry = source.getEntryById(hits.ids[i]);
      if (entry != null) {
        entries.add(entry);
        scores[entry.id] = hits.scores[i];
      }
    }
    // the reference entry comes first
    if (similarTo != null && entries.remove(similarTo)) {
      entries.insert(0, similarTo);
    }
    return SmartSearchResult._internal(query: query, similarTo: similarTo, isScoped: isScoped, entries: entries, scores: scores);
  }
}
