import 'dart:async';

import 'package:aves/services/common/channel.dart';
import 'package:aves/services/common/services.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

// On-device semantic search over images, backed by the platform smart search engine.
abstract class SmartSearchService {
  Future<SmartSearchStatus?> getStatus();

  Future<void> setActiveModel(String? modelId);

  Future<String?> download(String modelId, {required bool unmeteredOnly});

  Future<void> cancelDownload(String modelId);

  Future<void> deleteModel(String modelId);

  // returns the reason why indexing cannot run now, if any
  Future<String?> canIndex({required bool chargingOnly});

  // starts indexing in background work, now or when the device is charging; returns whether it was scheduled
  Future<bool> startIndexingWork({required bool whenCharging});

  Future<bool> acquireIndexing();

  Future<void> releaseIndexing();

  Future<bool> isQueryPending();

  Future<int?> generation();

  Future<Int32List?> pending(Int32List ids, Int64List fingerprints);

  // returns the number of stored items, or null on failure
  Future<int?> embed(List<SmartSearchItem> items, {required int generation, required int threads, required bool background});

  Future<void> remove(Int32List ids);

  Future<void> retainOnly(Int32List ids);

  Future<SmartSearchHits?> searchText(String query, Int32List ids, Int64List fingerprints, {int? maxResults});

  Future<SmartSearchHits?> searchSimilar(SmartSearchItem item, Int32List ids, Int64List fingerprints, {int? maxResults});
}

class PlatformSmartSearchService implements SmartSearchService {
  static const _platform = AvesMethodChannel('deckers.thibault/aves/smart_search');

  @override
  Future<SmartSearchStatus?> getStatus() async {
    try {
      final result = await _platform.invokeMethod('getStatus');
      if (result is Map) return SmartSearchStatus.fromMap(result);
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return null;
  }

  @override
  Future<void> setActiveModel(String? modelId) async {
    try {
      await _platform.invokeMethod('setActiveModel', <String, Object?>{'id': modelId});
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
  }

  @override
  Future<String?> download(String modelId, {required bool unmeteredOnly}) async {
    try {
      return await _platform.invokeMethod<String>('download', <String, Object?>{
        'id': modelId,
        'unmeteredOnly': unmeteredOnly,
      });
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return null;
  }

  @override
  Future<void> cancelDownload(String modelId) async {
    try {
      await _platform.invokeMethod('cancelDownload', <String, Object?>{'id': modelId});
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
  }

  @override
  Future<void> deleteModel(String modelId) async {
    try {
      await _platform.invokeMethod('deleteModel', <String, Object?>{'id': modelId});
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
  }

  @override
  Future<String?> canIndex({required bool chargingOnly}) async {
    try {
      return await _platform.invokeMethod<String>('canIndex', <String, Object?>{'chargingOnly': chargingOnly});
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return 'error';
  }

  @override
  Future<bool> startIndexingWork({required bool whenCharging}) async {
    try {
      return await _platform.invokeMethod<bool>('startIndexingWork', <String, Object?>{'whenCharging': whenCharging}) ?? false;
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return false;
  }

  @override
  Future<bool> acquireIndexing() async {
    try {
      return await _platform.invokeMethod<bool>('acquireIndexing') ?? false;
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return false;
  }

  @override
  Future<void> releaseIndexing() async {
    try {
      await _platform.invokeMethod('releaseIndexing');
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
  }

  @override
  Future<bool> isQueryPending() async {
    try {
      return await _platform.invokeMethod<bool>('isQueryPending') ?? false;
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return false;
  }

  @override
  Future<int?> generation() async {
    try {
      return await _platform.invokeMethod<int>('generation');
    } on PlatformException catch (e) {
      // model may not be ready, no need to report
      debugPrint('$runtimeType generation failed with error=$e');
    }
    return null;
  }

  @override
  Future<Int32List?> pending(Int32List ids, Int64List fingerprints) async {
    try {
      return await _platform.invokeMethod<Int32List>('pending', <String, Object?>{
        'ids': ids,
        'fingerprints': fingerprints,
      });
    } on PlatformException catch (e) {
      debugPrint('$runtimeType pending failed with error=$e');
    }
    return null;
  }

  @override
  Future<int?> embed(List<SmartSearchItem> items, {required int generation, required int threads, required bool background}) async {
    try {
      return await _platform.invokeMethod<int>('embed', <String, Object?>{
        'items': items.map((v) => v.toMap()).toList(),
        'generation': generation,
        'threads': threads,
        'background': background,
      });
    } on PlatformException catch (e, stack) {
      // losing the lease to another engine is expected, e.g. when a charging work takes over
      if (e.code != 'embed-lease-lost') {
        await reportService.recordError(e, stack);
      }
    }
    return null;
  }

  @override
  Future<void> remove(Int32List ids) async {
    if (ids.isEmpty) return;
    try {
      await _platform.invokeMethod('remove', <String, Object?>{'ids': ids});
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
  }

  @override
  Future<void> retainOnly(Int32List ids) async {
    try {
      await _platform.invokeMethod('retainOnly', <String, Object?>{'ids': ids});
    } on PlatformException catch (e) {
      debugPrint('$runtimeType retainOnly failed with error=$e');
    }
  }

  @override
  Future<SmartSearchHits?> searchText(String query, Int32List ids, Int64List fingerprints, {int? maxResults}) async {
    try {
      final result = await _platform.invokeMethod('searchText', <String, Object?>{
        'query': query,
        'ids': ids,
        'fingerprints': fingerprints,
        'maxResults': ?maxResults,
      });
      if (result is Map) return SmartSearchHits.fromMap(result);
    } on PlatformException catch (e, stack) {
      await reportService.recordError(e, stack);
    }
    return null;
  }

  @override
  Future<SmartSearchHits?> searchSimilar(SmartSearchItem item, Int32List ids, Int64List fingerprints, {int? maxResults}) async {
    try {
      final result = await _platform.invokeMethod('searchSimilar', <String, Object?>{
        'item': item.toMap(),
        'ids': ids,
        'fingerprints': fingerprints,
        'maxResults': ?maxResults,
      });
      if (result is Map) return SmartSearchHits.fromMap(result);
    } on PlatformException catch (e) {
      // e.g. media that cannot be decoded
      debugPrint('$runtimeType searchSimilar failed with error=$e');
    }
    return null;
  }
}

@immutable
class SmartSearchItem {
  final int id;
  final int fingerprint;
  final String uri;
  final String mimeType;
  final int rotationDegrees;
  final bool isFlipped;

  const new({
    required this.id,
    required this.fingerprint,
    required this.uri,
    required this.mimeType,
    required this.rotationDegrees,
    required this.isFlipped,
  });

  Map<String, Object?> toMap() => {
    'id': id,
    'fingerprint': fingerprint,
    'uri': uri,
    'mimeType': mimeType,
    'rotationDegrees': rotationDegrees,
    'isFlipped': isFlipped,
  };
}

@immutable
class SmartSearchHits {
  final Int32List ids;
  final Float32List scores;

  const new(this.ids, this.scores);

  factory fromMap(Map map) => SmartSearchHits(
    map['ids'] as Int32List? ?? Int32List(0),
    map['scores'] as Float32List? ?? Float32List(0),
  );
}

@immutable
class SmartSearchModelStatus extends Equatable {
  final String id;
  final int totalSize;
  final bool supported, installed;
  final int? downloadBytes;
  final String? downloadState;

  @override
  List<Object?> get props => [id, totalSize, supported, installed, downloadBytes, downloadState];

  const new({
    required this.id,
    required this.totalSize,
    required this.supported,
    required this.installed,
    required this.downloadBytes,
    required this.downloadState,
  });

  bool get isDownloading => downloadState == 'running';

  bool get isWaiting => downloadState == 'waiting';

  factory fromMap(Map map) => SmartSearchModelStatus(
    id: map['id'] as String,
    totalSize: map['totalSize'] as int? ?? 0,
    supported: map['supported'] as bool? ?? false,
    installed: map['installed'] as bool? ?? false,
    downloadBytes: map['downloadBytes'] as int?,
    downloadState: map['downloadState'] as String?,
  );
}

@immutable
class SmartSearchStatus extends Equatable {
  final bool canDownload, ready, lowMemory, defaultChargingOnly;
  final String? activeModel, pendingModel;
  final int averageEmbedMillis;
  final List<SmartSearchModelStatus> models;
  final int? indexedCount, failedCount, indexBytes;

  @override
  List<Object?> get props => [canDownload, ready, lowMemory, defaultChargingOnly, activeModel, pendingModel, averageEmbedMillis, models, indexedCount, failedCount, indexBytes];

  const new({
    required this.canDownload,
    required this.ready,
    required this.lowMemory,
    required this.defaultChargingOnly,
    required this.activeModel,
    required this.pendingModel,
    required this.averageEmbedMillis,
    required this.models,
    required this.indexedCount,
    required this.failedCount,
    required this.indexBytes,
  });

  SmartSearchModelStatus? model(String? id) => models.where((v) => v.id == id).firstOrNull;

  factory fromMap(Map map) => SmartSearchStatus(
    canDownload: map['canDownload'] as bool? ?? false,
    ready: map['ready'] as bool? ?? false,
    lowMemory: map['lowMemory'] as bool? ?? true,
    defaultChargingOnly: map['defaultChargingOnly'] as bool? ?? true,
    activeModel: map['activeModel'] as String?,
    pendingModel: map['pendingModel'] as String?,
    averageEmbedMillis: map['averageEmbedMillis'] as int? ?? 0,
    models: ((map['models'] as List?) ?? []).cast<Map>().map(SmartSearchModelStatus.fromMap).toList(),
    indexedCount: map['indexedCount'] as int?,
    failedCount: map['failedCount'] as int?,
    indexBytes: map['indexBytes'] as int?,
  );
}
