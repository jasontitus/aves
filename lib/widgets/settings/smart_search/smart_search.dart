import 'dart:async';
import 'dart:math';

import 'package:aves/model/settings/settings.dart';
import 'package:aves/model/smart_search.dart';
import 'package:aves/model/source/collection_source.dart';
import 'package:aves/services/common/services.dart';
import 'package:aves/services/smart_search_service.dart';
import 'package:aves/theme/colors.dart';
import 'package:aves/theme/icons.dart';
import 'package:aves/utils/file_utils.dart';
import 'package:aves/widgets/common/action_mixins/feedback.dart';
import 'package:aves/widgets/common/extensions/build_context.dart';
import 'package:aves/widgets/dialogs/aves_confirmation_dialog.dart';
import 'package:aves/widgets/dialogs/selection_dialogs/common.dart';
import 'package:aves/widgets/dialogs/selection_dialogs/single_selection.dart';
import 'package:aves/widgets/settings/common/tile_leading.dart';
import 'package:aves/widgets/settings/common/tiles.dart';
import 'package:aves/widgets/settings/settings_definition.dart';
import 'package:aves_model/aves_model.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

class SmartSearchSection extends SettingsSection {
  static const sectionKey = 'smart_search';

  @override
  String get key => sectionKey;

  @override
  Widget icon(BuildContext context) => SettingsTileLeading(
    icon: AIcons.smartSearch,
    color: context.select<AvesColorsData, Color>((v) => v.smartSearch),
  );

  @override
  String title(BuildContext context) => context.l10n.settingsSectionSmartSearch;

  @override
  Future<List<SettingsTile>> tiles(BuildContext context) async {
    if (settings.useTvLayout) return [];
    final status = await smartSearch.refreshStatus();
    // hide the section when no model can be obtained (e.g. builds without a model download source)
    if (status == null || !(status.canDownload || status.models.any((v) => v.installed))) return [];
    return [
      SettingsTileSmartSearchEnable(),
      SettingsTileSmartSearchModel(),
      SettingsTileSmartSearchUnmeteredOnly(),
      SettingsTileSmartSearchChargingOnly(),
      SettingsTileSmartSearchIndex(),
      SettingsTileSmartSearchDelete(),
    ];
  }

  static String modelName(BuildContext context, String modelId) {
    final l10n = context.l10n;
    return switch (modelId) {
      SmartSearchModels.bestQuality => l10n.smartSearchModelBestQuality,
      SmartSearchModels.siglip2 => l10n.smartSearchModelSiglip2,
      _ => l10n.smartSearchModelStandard,
    };
  }
}

// model IDs as defined in the platform model catalog
class SmartSearchModels {
  static const standard = 'openclip-vitb32-laion2b-v1';
  static const bestQuality = 'pe-core-b16-224-v1';
  static const siglip2 = 'siglip2-b32-256-selective-int8-v1';
}

class SettingsTileSmartSearchEnable extends SettingsTile with FeedbackMixin {
  @override
  List<String> get settingKeys => [SettingKeys.enableSmartSearchKey];

  @override
  String title(BuildContext context) => context.l10n.settingsSmartSearchEnable;

  @override
  Widget build(BuildContext context) => SettingsSwitchListTile(
    selector: (context, s) => s.enableSmartSearch,
    onChanged: (v) => _setEnabled(context, v),
    title: title,
    subtitle: (context) => context.l10n.settingsSmartSearchEnableSubtitle,
  );

  Future<void> _setEnabled(BuildContext context, bool enabled) async {
    if (!enabled) {
      settings.enableSmartSearch = false;
      return;
    }
    final status = await smartSearch.refreshStatus();
    if (status == null) return;
    final model = status.model(status.activeModel ?? SmartSearchModels.standard);
    if (model == null) return;
    if (!model.installed && !model.isDownloading && !model.isWaiting) {
      if (!context.mounted) return;
      final started = await SettingsTileSmartSearchModel.confirmDownload(context, model);
      if (!started) return;
    }
    if (status.activeModel == null) {
      await smartSearch.selectModel(model.id);
    }
    settings.enableSmartSearch = true;
    if (context.mounted) unawaited(SettingsTileSmartSearchModel.kickIndexing(context));
  }
}

class SettingsTileSmartSearchModel extends SettingsTile {
  @override
  List<String> get settingKeys => [];

  @override
  String title(BuildContext context) => context.l10n.settingsSmartSearchModel;

  // dialogs are opened with the section context, which has the right text theme
  @override
  Widget build(BuildContext context) => _SmartSearchModelTile(dialogContext: context);

  // returns whether the download started
  static Future<bool> confirmDownload(BuildContext context, SmartSearchModelStatus model) async {
    final l10n = context.l10n;
    final size = formatFileSize(settings.avesLocale, model.totalSize, round: 0);
    final confirmed = await showConfirmationDialog(
      context: context,
      message: l10n.smartSearchDownloadDialogMessage(size),
      ok: l10n.smartSearchDownloadButtonLabel,
    );
    if (!confirmed || !context.mounted) return false;
    return startDownload(context, model);
  }

  static Future<bool> startDownload(BuildContext context, SmartSearchModelStatus model) async {
    smartSearch.clearDownloadFailure(model.id);
    final result = await smartSearchService.download(model.id, unmeteredOnly: settings.smartSearchUnmeteredOnly);
    await smartSearch.refreshStatus();
    switch (result) {
      case 'started':
      case 'already_installed':
        return true;
      case 'no_space':
        if (context.mounted) {
          final size = formatFileSize(settings.avesLocale, model.totalSize * 2, round: 0);
          _FeedbackHelper().showFeedback(context, FeedbackType.warn, context.l10n.smartSearchModelNoSpace(size));
        }
        return false;
      default:
        if (context.mounted) {
          _FeedbackHelper().showFeedback(context, FeedbackType.warn, context.l10n.smartSearchModelDownloadFailed);
        }
        return false;
    }
  }

  static Future<void> kickIndexing(BuildContext context) async {
    final source = context.read<CollectionSource?>();
    if (source == null || !smartSearch.isReady) return;
    await smartSearch.onAnalysisDone(source, isFullAnalysis: false);
  }
}

class _FeedbackHelper with FeedbackMixin;

class _SmartSearchModelTile extends StatefulWidget {
  final BuildContext dialogContext;

  const new({required this.dialogContext});

  @override
  State<_SmartSearchModelTile> createState() => _SmartSearchModelTileState();
}

class _SmartSearchModelTileState extends State<_SmartSearchModelTile> {
  Timer? _pollTimer;
  bool _wasReady = false;

  static const _pollInterval = Duration(seconds: 1);

  @override
  void initState() {
    super.initState();
    _wasReady = smartSearch.isReady;
    _pollTimer = Timer.periodic(_pollInterval, (_) => _poll());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _poll() async {
    final model = smartSearch.status?.model(smartSearch.status?.activeModel);
    final wasPending = smartSearch.status?.pendingModel;
    // only poll frequently while downloading
    final pending = smartSearch.status?.model(smartSearch.status?.pendingModel);
    if (!(model?.isDownloading ?? false) && !(model?.isWaiting ?? false) && !(pending?.isDownloading ?? false) && !(pending?.isWaiting ?? false)) return;
    await smartSearch.refreshStatus();
    final switched = wasPending != null && smartSearch.status?.pendingModel == null;
    if ((switched || !_wasReady) && smartSearch.isReady && mounted) {
      // model just installed
      unawaited(SettingsTileSmartSearchModel.kickIndexing(context));
    }
    _wasReady = smartSearch.isReady;
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: smartSearch,
      builder: (context, child) {
        final l10n = context.l10n;
        final status = smartSearch.status;
        // a model being downloaded to replace the active one is shown until it takes over
        final activeId = status?.pendingModel ?? status?.activeModel ?? SmartSearchModels.standard;
        final model = status?.model(activeId);
        if (status == null || model == null) return const SizedBox();

        final locale = settings.avesLocale;
        String subtitle;
        Widget? trailing;
        Widget? progress;
        if (model.installed) {
          subtitle = l10n.smartSearchModelInstalled;
        } else if (model.isDownloading || model.isWaiting) {
          final done = model.downloadBytes ?? 0;
          subtitle = model.isWaiting
              ? (settings.smartSearchUnmeteredOnly ? l10n.smartSearchModelWaitingForUnmeteredNetwork : l10n.smartSearchModelWaitingForNetwork)
              : l10n.smartSearchModelDownloading(formatFileSize(locale, done, round: 0), formatFileSize(locale, model.totalSize, round: 0));
          progress = LinearProgressIndicator(value: model.totalSize > 0 ? done / model.totalSize : null);
          trailing = IconButton(
            icon: const Icon(AIcons.clear),
            tooltip: MaterialLocalizations.of(context).cancelButtonLabel,
            onPressed: () async {
              await smartSearchService.cancelDownload(model.id);
              await smartSearch.refreshStatus();
            },
          );
        } else if (smartSearch.hasDownloadFailed(model.id)) {
          subtitle = l10n.smartSearchModelDownloadFailed;
          trailing = TextButton(
            onPressed: () => SettingsTileSmartSearchModel.startDownload(widget.dialogContext, model),
            child: Text(l10n.smartSearchRetryButtonLabel),
          );
        } else {
          subtitle = l10n.smartSearchModelNotDownloaded;
          if (status.canDownload && settings.enableSmartSearch) {
            trailing = TextButton(
              onPressed: () => SettingsTileSmartSearchModel.confirmDownload(widget.dialogContext, model),
              child: Text(l10n.smartSearchDownloadButtonLabel),
            );
          }
        }

        final modelName = SmartSearchSection.modelName(context, activeId);
        return ListTile(
          title: Text(l10n.settingsSmartSearchModelTitle(modelName)),
          subtitle: Column(
            crossAxisAlignment: .start,
            mainAxisSize: .min,
            children: [
              // download progress already includes the size
              Text(progress != null ? subtitle : l10n.settingsSmartSearchModelSubtitle(subtitle, formatFileSize(locale, model.totalSize, round: 0))),
              if (progress != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: progress,
                ),
            ],
          ),
          trailing: trailing,
          onTap: () => _selectModel(widget.dialogContext, status),
        );
      },
    );
  }

  Future<void> _selectModel(BuildContext context, SmartSearchStatus status) async {
    final l10n = context.l10n;
    final locale = settings.avesLocale;
    // models too large for the device are not offered
    final models = status.models.where((v) => v.supported || v.id == status.activeModel);
    await showSelectionDialog<String>(
      context: context,
      builder: (context) => AvesSingleSelectionDialog<String>(
        initialValue: status.activeModel ?? SmartSearchModels.standard,
        options: Map.fromEntries(models.map((v) => MapEntry(v.id, SmartSearchSection.modelName(context, v.id)))),
        optionSubtitleBuilder: (id) {
          final model = status.model(id);
          if (model == null) return null;
          return formatFileSize(locale, model.totalSize, round: 0);
        },
        title: l10n.settingsSmartSearchModel,
      ),
      onSelection: (selected) async {
        if (selected == status.activeModel) return;
        final model = status.model(selected);
        if (model == null || !model.supported) return;
        // keep the current model until the new one is installed or on its way
        if (!model.installed && !model.isDownloading && !model.isWaiting) {
          if (!context.mounted) return;
          final started = await SettingsTileSmartSearchModel.confirmDownload(context, model);
          if (!started) return;
        }
        await smartSearch.selectModel(selected);
        if (context.mounted) unawaited(SettingsTileSmartSearchModel.kickIndexing(context));
      },
    );
  }
}

class SettingsTileSmartSearchUnmeteredOnly extends SettingsTile {
  @override
  List<String> get settingKeys => [SettingKeys.smartSearchUnmeteredOnlyKey];

  @override
  String title(BuildContext context) => context.l10n.settingsSmartSearchUnmeteredOnly;

  @override
  Widget build(BuildContext context) => SettingsSwitchListTile(
    selector: (context, s) => s.smartSearchUnmeteredOnly,
    onChanged: (v) => settings.smartSearchUnmeteredOnly = v,
    title: title,
  );
}

class SettingsTileSmartSearchChargingOnly extends SettingsTile {
  @override
  List<String> get settingKeys => [SettingKeys.smartSearchChargingOnlyKey];

  @override
  String title(BuildContext context) => context.l10n.settingsSmartSearchChargingOnly;

  @override
  Widget build(BuildContext context) => SettingsSwitchListTile(
    selector: (context, s) => s.smartSearchChargingOnly ?? smartSearch.chargingOnly,
    onChanged: (v) {
      settings.smartSearchChargingOnly = v;
      if (!v) unawaited(SettingsTileSmartSearchModel.kickIndexing(context));
    },
    title: title,
    subtitle: (context) => context.l10n.settingsSmartSearchChargingOnlySubtitle,
  );
}

class SettingsTileSmartSearchIndex extends SettingsTile {
  @override
  List<String> get settingKeys => [];

  @override
  String title(BuildContext context) => context.l10n.settingsSmartSearchIndex;

  @override
  Widget build(BuildContext context) => _SmartSearchIndexTile(title: title(context));
}

class _SmartSearchIndexTile extends StatefulWidget {
  final String title;

  const new({required this.title});

  @override
  State<_SmartSearchIndexTile> createState() => _SmartSearchIndexTileState();
}

class _SmartSearchIndexTileState extends State<_SmartSearchIndexTile> {
  Timer? _pollTimer;
  String? _blockedReason;

  static const _pollInterval = Duration(seconds: 5);

  @override
  void initState() {
    super.initState();
    _poll();
    _pollTimer = Timer.periodic(_pollInterval, (_) => _poll());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  int? _lastIndexedCount;
  bool _polling = false;

  Future<void> _poll() async {
    // polls may take longer than their interval on large collections
    if (_polling) return;
    _polling = true;
    try {
      await _pollInternal();
    } finally {
      _polling = false;
    }
  }

  Future<void> _pollInternal() async {
    final source = context.read<CollectionSource?>();
    if (source == null) return;
    final status = await smartSearch.refreshStatus();
    // coverage goes over all entries, so it is only refreshed when the index changed
    var coverage = smartSearch.coverage;
    if (coverage == null || status?.indexedCount != _lastIndexedCount) {
      _lastIndexedCount = status?.indexedCount;
      coverage = await smartSearch.refreshCoverage(source);
    }
    final blocked = coverage != null && !coverage.isComplete ? await smartSearchService.canIndex(chargingOnly: smartSearch.chargingOnly) : null;
    if (mounted && blocked != _blockedReason) {
      final wasBlocked = _blockedReason != null;
      setState(() => _blockedReason = blocked);
      // e.g. the device was plugged in: resume indexing
      if (wasBlocked && blocked == null) smartSearch.onAppResumed();
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: smartSearch,
      builder: (context, child) {
        final coverage = smartSearch.coverage;
        if (!smartSearch.isReady || coverage == null) return const SizedBox();
        final l10n = context.l10n;
        final lines = <String>[];
        // items that cannot be decoded (e.g. empty files) are covered, but not searchable
        final failed = min(smartSearch.status?.failedCount ?? 0, coverage.indexed);
        if (coverage.isComplete) {
          lines.add(l10n.smartSearchIndexComplete(coverage.indexed - failed));
          if (failed > 0) lines.add(l10n.smartSearchIndexFailed(failed));
        } else {
          lines.add(l10n.smartSearchIndexProgress(coverage.indexed, coverage.total));
          final pausedText = switch (_blockedReason) {
            'not_charging' => l10n.smartSearchIndexPausedCharging,
            'low_battery' => l10n.smartSearchIndexPausedBattery,
            'thermal' => l10n.smartSearchIndexPausedThermal,
            _ => null,
          };
          if (pausedText != null) {
            lines.add(pausedText);
          } else {
            final remaining = _remainingText(context, coverage);
            if (remaining != null) lines.add(remaining);
          }
        }
        return ListTile(
          title: Text(widget.title),
          subtitle: Column(
            crossAxisAlignment: .start,
            mainAxisSize: .min,
            children: [
              Text(lines.join('\n')),
              if (!coverage.isComplete && coverage.total > 0)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: LinearProgressIndicator(value: coverage.indexed / coverage.total),
                ),
            ],
          ),
        );
      },
    );
  }

  String? _remainingText(BuildContext context, SmartSearchCoverage coverage) {
    final averageMillis = smartSearch.status?.averageEmbedMillis ?? 0;
    if (averageMillis <= 0) return null;
    final minutes = ((coverage.total - coverage.indexed) * averageMillis / Duration.millisecondsPerMinute).ceil();
    final l10n = context.l10n;
    return minutes < 90 ? l10n.smartSearchIndexRemainingMinutes(minutes) : l10n.smartSearchIndexRemainingHours((minutes / 60).round());
  }
}

class SettingsTileSmartSearchDelete extends SettingsTile {
  @override
  List<String> get settingKeys => [];

  @override
  String title(BuildContext context) => context.l10n.settingsSmartSearchDelete;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: smartSearch,
    // the section context is used for the dialog, which needs its text theme
    builder: (_, child) {
      final status = smartSearch.status;
      final installed = status?.models.where((v) => v.installed).toList() ?? [];
      if (status == null || installed.isEmpty) return const SizedBox();
      final used = installed.fold<int>(0, (sum, v) => sum + v.totalSize) + (status.indexBytes ?? 0);
      return ListTile(
        title: Text(title(context)),
        subtitle: Text(context.l10n.settingsSmartSearchStorage(formatFileSize(settings.avesLocale, used))),
        onTap: () async {
          final confirmed = await showConfirmationDialog(
            context: context,
            message: context.l10n.smartSearchDeleteDialogMessage,
            ok: context.l10n.deleteButtonLabel,
          );
          if (!confirmed) return;
          settings.enableSmartSearch = false;
          settings.smartSearchHistory = [];
          // including a model being downloaded to replace the active one
          final pendingModel = status.pendingModel;
          if (pendingModel != null) await smartSearchService.cancelDownload(pendingModel);
          for (final model in installed) {
            await smartSearch.deleteModel(model.id);
          }
        },
      );
    },
  );
}
