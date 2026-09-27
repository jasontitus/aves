import 'dart:async';

import 'package:aves/locale/aves_locale.dart';
import 'package:aves/model/entry/entry.dart';
import 'package:aves/model/filters/covered/stored_album.dart';
import 'package:aves/model/filters/query.dart';
import 'package:aves/model/filters/trash.dart';
import 'package:aves/model/metadata/catalog.dart';
import 'package:aves/model/settings/settings.dart';
import 'package:aves/model/smart_search.dart';
import 'package:aves/model/source/collection_lens.dart';
import 'package:aves/model/source/collection_source.dart';
import 'package:aves/model/source/media_store_source.dart';
import 'package:aves/services/common/services.dart';
import 'package:aves/services/smart_search_service.dart';
import 'package:aves_model/aves_model.dart';
import 'package:flutter_test/flutter_test.dart';

import '../common.dart';
import '../fake/media_store_service.dart';
import '../fake/metadata_fetch_service.dart';
import '../fake/smart_search_service.dart';
import '../fake/storage_service.dart';

void main() {
  const albumA = '${FakeStorageService.primaryPath}Pictures/a';
  const albumB = '${FakeStorageService.primaryPath}Pictures/b';

  setUpAll(() async {
    await setUpAllServices();
  });

  setUp(() async {
    await setUpServices();
    smartSearch.reset();
    settings.enableSmartSearch = true;
  });

  tearDownAll(() async {
    await tearDownAllServices();
  });

  // only catalogued entries are indexed
  AvesEntry newImage(String album, String name) {
    final entry = FakeMediaStoreService.newImage(album, name);
    (metadataFetchService as FakeMetadataFetchService).setUp(entry, CatalogMetadata(id: entry.id));
    return entry;
  }

  FakeSmartSearchService fakeService() => smartSearchService as FakeSmartSearchService;

  Future<MediaStoreSource> initSource() async {
    final source = MediaStoreSource();
    final readyCompleter = Completer();
    source.stateNotifier.addListener(() {
      if (source.isReady && !readyCompleter.isCompleted) readyCompleter.complete();
    });
    await source.init(scope: CollectionSource.fullScope);
    await readyCompleter.future;
    return source;
  }

  test('fingerprint changes with content, not with location', () {
    final entry = newImage(albumA, 'image1');
    final base = SmartSearch.fingerprintOf(entry);
    expect(SmartSearch.fingerprintOf(entry), base);

    // moving or renaming keeps the entry ID and content
    entry
      ..uri = 'content://media/external/images/media/999'
      ..path = '$albumB/renamed.jpg';
    expect(SmartSearch.fingerprintOf(entry), base);

    entry.sizeBytes = (entry.sizeBytes ?? 0) + 1;
    final edited = SmartSearch.fingerprintOf(entry);
    expect(edited, isNot(base));

    entry.rotationDegrees = (entry.rotationDegrees + 90) % 360;
    expect(SmartSearch.fingerprintOf(entry), isNot(edited));
  });

  test('few pending items are indexed inline, most recent first', () async {
    final a1 = newImage(albumA, 'a1');
    final a2 = newImage(albumA, 'a2')..sourceDateTakenMillis = a1.sourceDateTakenMillis! + 1000;
    (mediaStoreService as FakeMediaStoreService).entries = {a1, a2};
    final source = await initSource();
    await Future.delayed(Duration.zero);
    fakeService().embeddedBatches.clear();

    fakeService().pendingIds = {a1.id, a2.id};
    await smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    // `a2` is taken later than `a1`
    expect(fakeService().embeddedBatches.expand((v) => v).toList(), [a2.id, a1.id]);
    expect(fakeService().pendingIds, isEmpty);
    expect(fakeService().leased, false);
  });

  test('indexing blocked when not charging is scheduled for charging', () async {
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    final source = await initSource();
    await Future.delayed(Duration.zero);
    fakeService()
      ..startedIndexingWork.clear()
      ..embeddedBatches.clear()
      ..pendingIds = {a1.id}
      ..blockReason = 'not_charging';

    await smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    expect(fakeService().embeddedBatches, isEmpty);
    expect(fakeService().startedIndexingWork, [true]);
  });

  test('indexing blocked when not charging resumes with the app, when it cannot be scheduled', () async {
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    final source = await initSource();
    await Future.delayed(Duration.zero);
    fakeService()
      ..startedIndexingWork.clear()
      ..embeddedBatches.clear()
      ..pendingIds = {a1.id}
      ..blockReason = 'not_charging'
      ..canScheduleWhenCharging = false;

    await smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    expect(fakeService().embeddedBatches, isEmpty);
    expect(fakeService().startedIndexingWork, [true]);

    // e.g. plugged in while the app was in the background
    fakeService().blockReason = null;
    smartSearch.onAppResumed();
    await Future.delayed(Duration.zero);
    await smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    expect(fakeService().embeddedBatches.expand((v) => v).toList(), [a1.id]);
    expect(fakeService().pendingIds, isEmpty);
  });

  test('text search keeps ranking and only considers visible entries', () async {
    final a1 = newImage(albumA, 'a1');
    final a2 = newImage(albumA, 'a2');
    final b1 = newImage(albumB, 'b1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, a2, b1};
    settings.hiddenFilters = {StoredAlbumFilter(albumB, null)};
    final source = await initSource();
    await smartSearch.refreshStatus();

    fakeService().rankedIds = [b1.id, a2.id, a1.id];
    final result = await smartSearch.searchText('query', source, source.visibleEntries);
    expect(result, isNotNull);
    // hidden entries are never sent as candidates
    expect(fakeService().lastAllowedIds!.toSet(), {a1.id, a2.id});
    expect(result!.entries.map((v) => v.id).toList(), [a2.id, a1.id]);

    final lens = CollectionLens(source: source, smartSearchResult: result);
    expect(lens.sortedEntries.map((v) => v.id).toList(), [a2.id, a1.id]);
    expect(lens.showHeaders, false);
    lens.dispose();
  });

  test('results lens drops entries hidden after the search', () async {
    final a1 = newImage(albumA, 'a1');
    final b1 = newImage(albumB, 'b1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, b1};
    final source = await initSource();
    await smartSearch.refreshStatus();

    fakeService().rankedIds = [b1.id, a1.id];
    final result = await smartSearch.searchText('query', source, source.visibleEntries);
    final lens = CollectionLens(source: source, smartSearchResult: result);
    expect(lens.sortedEntries.map((v) => v.id).toList(), [b1.id, a1.id]);

    // e.g. a vault being locked, or an album being hidden, while results are shown
    settings.hiddenFilters = {StoredAlbumFilter(albumB, null)};
    await Future.delayed(Duration.zero);
    lens.refresh();
    expect(lens.sortedEntries.map((v) => v.id).toList(), [a1.id]);
    lens.dispose();
  });

  test('similar search puts the reference entry first', () async {
    final a1 = newImage(albumA, 'a1');
    final a2 = newImage(albumA, 'a2');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, a2};
    final source = await initSource();
    await smartSearch.refreshStatus();

    fakeService().rankedIds = [a2.id, a1.id];
    final result = await smartSearch.searchSimilar(a1, source, source.visibleEntries);
    expect(result!.entries.map((v) => v.id).toList(), [a1.id, a2.id]);
  });

  test('garbage collection only after a full analysis', () async {
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    final source = await initSource();
    // the initial load runs a full analysis
    await Future.delayed(Duration.zero);
    expect(fakeService().lastRetainedIds!.toList(), [a1.id]);
    fakeService().lastRetainedIds = null;

    await smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    expect(fakeService().lastRetainedIds, isNull);

    await smartSearch.onAnalysisDone(source, isFullAnalysis: true);
    expect(fakeService().lastRetainedIds!.toList(), [a1.id]);
  });

  test('overlapping analysis completions are coalesced, and awaited until done', () async {
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    final source = await initSource();
    await Future.delayed(Duration.zero);
    fakeService()
      ..lastRetainedIds = null
      ..embeddedBatches.clear()
      ..pendingIds = {a1.id};

    final first = smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    final second = smartSearch.onAnalysisDone(source, isFullAnalysis: true);
    final third = smartSearch.onAnalysisDone(source, isFullAnalysis: false);
    await first;
    await second;
    await third;
    // a coalesced full analysis still collects garbage
    expect(fakeService().lastRetainedIds!.toList(), [a1.id]);
    expect(fakeService().embeddedBatches.expand((v) => v).toList(), [a1.id]);
    expect(fakeService().leased, false);
  });

  test('a completed download starts indexing, even when the settings are not shown', () async {
    SmartSearch.downloadPollRunningInterval = const Duration(milliseconds: 10);
    addTearDown(() => SmartSearch.downloadPollRunningInterval = const Duration(seconds: 3));
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    fakeService()
      ..ready = false
      ..models = [const SmartSearchModelStatus(id: 'model', totalSize: 1, supported: true, installed: false, downloadBytes: 1, downloadState: 'running')];
    final source = await initSource();
    await Future.delayed(Duration.zero);
    expect(fakeService().embeddedBatches, isEmpty);

    // the platform installs the downloaded files when polled
    final requests = fakeService().statusRequests;
    fakeService()
      ..pendingIds = {a1.id}
      ..ready = true
      ..models = [const SmartSearchModelStatus(id: 'model', totalSize: 1, supported: true, installed: true, downloadBytes: null, downloadState: null)];
    await Future.delayed(const Duration(milliseconds: 100));
    expect(fakeService().statusRequests, greaterThan(requests));
    expect(fakeService().embeddedBatches.expand((v) => v).toList(), [a1.id]);

    // no more polling once done
    final settled = fakeService().statusRequests;
    await Future.delayed(const Duration(milliseconds: 100));
    expect(fakeService().statusRequests, settled);
    expect(source.isReady, true);
  });

  test('disabled smart search neither indexes nor collects garbage', () async {
    settings.enableSmartSearch = false;
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    fakeService().pendingIds = {a1.id};
    final source = await initSource();
    await smartSearch.onAnalysisDone(source, isFullAnalysis: true);
    expect(fakeService().lastRetainedIds, isNull);
    expect(fakeService().embeddedBatches, isEmpty);
    expect(fakeService().startedIndexingWork, isEmpty);
    expect(await smartSearch.searchText('query', source, source.visibleEntries), isNull);
  });

  test('calendar layout is not applied to relevance results', () async {
    settings.setTileLayout('/collection', TileLayout.calendar);
    final a1 = newImage(albumA, 'a1');
    final a2 = newImage(albumA, 'a2');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, a2};
    final source = await initSource();
    await smartSearch.refreshStatus();

    fakeService().rankedIds = [a2.id, a1.id];
    final result = await smartSearch.searchText('query', source, source.visibleEntries);
    final lens = CollectionLens(source: source, smartSearchResult: result);
    // items of the same day are not stacked, and order is kept
    expect(lens.effectiveTileLayout, TileLayout.grid);
    expect(lens.sortedEntries.map((v) => v.id).toList(), [a2.id, a1.id]);
    lens.dispose();
  });

  test('searching from results searches everything, searching from an album searches within it', () async {
    final a1 = newImage(albumA, 'a1');
    final b1 = newImage(albumB, 'b1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, b1};
    final source = await initSource();
    await smartSearch.refreshStatus();

    final (all, allScoped) = SmartSearch.candidatesFor(source, null);
    expect(all.toSet(), {a1, b1});
    expect(allScoped, false);

    final album = CollectionLens(source: source, filters: {StoredAlbumFilter(albumA, null)});
    final (inAlbum, albumScoped) = SmartSearch.candidatesFor(source, album);
    expect(inAlbum.toSet(), {a1});
    expect(albumScoped, true);
    album.dispose();

    fakeService().rankedIds = [a1.id];
    final result = await smartSearch.searchText('query', source, source.visibleEntries);
    final results = CollectionLens(source: source, smartSearchResult: result);
    final (fromResults, resultsScoped) = SmartSearch.candidatesFor(source, results);
    expect(fromResults.toSet(), {a1, b1});
    expect(resultsScoped, false);
    results.dispose();
  });

  test('enter runs a smart search, unless the query is title search syntax or a matching file name', () async {
    final a1 = newImage(albumA, 'IMG_2041');
    final a2 = newImage(albumA, 'Screenshot_1');
    final a3 = newImage(albumA, 'Sunset at the lake');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, a2, a3};
    final source = await initSource();
    await smartSearch.refreshStatus();
    QueryFilter q(String v) => QueryFilter(v, ACalendar.gregorian);

    expect(smartSearch.shouldSubmitAsSmartSearch(q('ocean'), source, null), true);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('IMG_2041'), source, null), false);
    // words run a smart search even when some titles contain them, as users search for what items show
    expect(smartSearch.shouldSubmitAsSmartSearch(q('screenshot'), source, null), true);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('sunset'), source, null), true);
    // file names without any matching title are not meant as titles
    expect(smartSearch.shouldSubmitAsSmartSearch(q('IMG_9999'), source, null), true);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('Screenshot_1'), source, null), false);
    for (final syntax in ['/IMG_.*/', '"exact"', '-ocean', 'YEAR=2023', 'WIDTH>100']) {
      expect(smartSearch.shouldSubmitAsSmartSearch(q(syntax), source, null), false, reason: syntax);
    }

    // from smart search results, even a query matching titles starts a new smart search
    fakeService().rankedIds = [a1.id];
    final result = await smartSearch.searchText('dog', source, source.visibleEntries);
    final results = CollectionLens(source: source, smartSearchResult: result);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('IMG_2041'), source, results), true);
    results.dispose();

    settings.enableSmartSearch = false;
    expect(smartSearch.shouldSubmitAsSmartSearch(q('ocean'), source, null), false);
  });

  test('title matching considers items stacked by day in calendar layout', () async {
    settings.setTileLayout('/collection', TileLayout.calendar);
    final a1 = newImage(albumA, 'IMG_2041');
    final a2 = newImage(albumA, 'IMG_2042');
    (mediaStoreService as FakeMediaStoreService).entries = {a1, a2};
    final source = await initSource();
    await smartSearch.refreshStatus();
    QueryFilter q(String v) => QueryFilter(v, ACalendar.gregorian);

    final lens = CollectionLens(source: source);
    // both items are taken on the same day, and only one of them is exposed
    expect(lens.sortedEntries.length, 1);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('IMG_2041'), source, lens), false);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('IMG_2042'), source, lens), false);
    expect(smartSearch.shouldSubmitAsSmartSearch(q('ocean'), source, lens), true);
    lens.dispose();
  });

  test('no smart search from the recycle bin', () async {
    final a1 = newImage(albumA, 'a1');
    (mediaStoreService as FakeMediaStoreService).entries = {a1};
    final source = await initSource();
    await smartSearch.refreshStatus();

    final bin = CollectionLens(source: source, filters: {TrashFilter.instance});
    expect(SmartSearch.canSearchFrom(bin), false);
    expect(smartSearch.shouldSubmitAsSmartSearch(QueryFilter('ocean', ACalendar.gregorian), source, bin), false);
    bin.dispose();
    expect(SmartSearch.canSearchFrom(null), true);
  });
}
