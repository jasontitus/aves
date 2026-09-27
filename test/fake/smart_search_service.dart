import 'package:aves/services/smart_search_service.dart';
import 'package:flutter/foundation.dart';
import 'package:test/fake.dart';

class FakeSmartSearchService extends Fake implements SmartSearchService {
  bool ready = true;
  List<SmartSearchModelStatus> models = [];
  int statusRequests = 0;

  // IDs returned by searches, best first, before restriction to the allowed IDs
  List<int> rankedIds = [];

  Int32List? lastAllowedIds;
  Int32List? lastRetainedIds;
  final List<Int32List> removedIds = [];

  // indexing
  Set<int> pendingIds = {};
  String? blockReason;
  final List<bool> startedIndexingWork = [];
  // e.g. `false` when background work cannot be scheduled on recent Android versions
  bool canScheduleWhenCharging = true;
  final List<List<int>> embeddedBatches = [];
  bool leased = false;

  void reset() {
    ready = true;
    models = [];
    statusRequests = 0;
    rankedIds = [];
    lastAllowedIds = null;
    lastRetainedIds = null;
    removedIds.clear();
    pendingIds = {};
    blockReason = null;
    startedIndexingWork.clear();
    embeddedBatches.clear();
    leased = false;
    canScheduleWhenCharging = true;
  }

  @override
  Future<SmartSearchStatus?> getStatus() {
    statusRequests++;
    return SynchronousFuture(_status());
  }

  SmartSearchStatus _status() => SmartSearchStatus(
    canDownload: false,
    ready: ready,
    lowMemory: false,
    defaultChargingOnly: false,
    activeModel: 'model',
    pendingModel: null,
    averageEmbedMillis: 0,
    models: models,
    indexedCount: 0,
    failedCount: 0,
    indexBytes: 0,
  );

  @override
  Future<Int32List?> pending(Int32List ids, Int64List fingerprints) => SynchronousFuture(Int32List.fromList(ids.where(pendingIds.contains).toList()));

  @override
  Future<String?> canIndex({required bool chargingOnly}) => SynchronousFuture(blockReason);

  @override
  Future<bool> startIndexingWork({required bool whenCharging}) async {
    startedIndexingWork.add(whenCharging);
    return !whenCharging || canScheduleWhenCharging;
  }

  @override
  Future<bool> acquireIndexing() async {
    if (leased) return false;
    leased = true;
    return true;
  }

  @override
  Future<void> releaseIndexing() async => leased = false;

  @override
  Future<int?> generation() async => 0;

  @override
  Future<int?> embed(List<SmartSearchItem> items, {required int generation, required int threads, required bool background}) async {
    embeddedBatches.add(items.map((v) => v.id).toList());
    pendingIds.removeAll(items.map((v) => v.id));
    return items.length;
  }

  @override
  Future<void> retainOnly(Int32List ids) async => lastRetainedIds = ids;

  @override
  Future<void> remove(Int32List ids) async => removedIds.add(ids);

  @override
  Future<SmartSearchHits?> searchText(String query, Int32List ids, Int64List fingerprints, {int? maxResults}) async {
    lastAllowedIds = ids;
    return _hits(ids);
  }

  @override
  Future<SmartSearchHits?> searchSimilar(SmartSearchItem item, Int32List ids, Int64List fingerprints, {int? maxResults}) async {
    lastAllowedIds = ids;
    return _hits(ids);
  }

  SmartSearchHits _hits(Int32List allowed) {
    final allowedSet = allowed.toSet();
    final ids = rankedIds.where(allowedSet.contains).toList();
    return SmartSearchHits(Int32List.fromList(ids), Float32List.fromList(List.generate(ids.length, (i) => 1 - i / 100)));
  }
}
