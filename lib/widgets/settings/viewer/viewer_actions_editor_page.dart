import 'package:aves/model/settings/settings.dart';
import 'package:aves/view/view.dart';
import 'package:aves/widgets/common/extensions/build_context.dart';
import 'package:aves/widgets/settings/common/quick_actions/editor_page.dart';
import 'package:aves/widgets/viewer/overlay/bottom/bottom.dart';
import 'package:aves_model/aves_model.dart';
import 'package:collection/collection.dart';
import 'package:material_ui/material_ui.dart';

class ViewerActionEditorPage extends StatefulWidget {
  static const routeName = '/settings/viewer/actions';

  static const List<String> settingKeys = [SettingKeys.viewerQuickActionsKey];

  const new({super.key});

  @override
  State<ViewerActionEditorPage> createState() => _ViewerActionEditorPageState();
}

class _ViewerActionEditorPageState extends State<ViewerActionEditorPage> {
  late final QuickActionEditorController<EntryAction> _controller;

  static final _allActions = <List<EntryAction>>[
    [
      .share,
      .edit,
      .rename,
      .delete,
      .copy,
      .move,
      .toggleFavourite,
      .findSimilar,
      .rotateScreen,
      .viewSource,
      .rotateCCW,
      .rotateCW,
      .flip,
    ],
    [
      ...EntryActions.export,
    ],
    [
      ...EntryActions.video.whereNot((v) => v == .videoSettings),
    ],
    [
      ...EntryActions.commonMetadataActions,
      .settings,
    ],
  ];

  // smart search actions are only offered when the feature is enabled
  static List<List<EntryAction>> get allAvailableActions {
    if (settings.enableSmartSearch) return _allActions;
    return _allActions.map((group) => group.whereNot((v) => v == .findSimilar).toList()).toList();
  }

  @override
  void initState() {
    super.initState();
    _controller = QuickActionEditorController(
      load: () => settings.viewerQuickActions,
      save: (actions) => settings.viewerQuickActions = actions,
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return QuickActionEditorPage<EntryAction>(
      title: context.l10n.settingsViewerQuickActionEditorPageTitle,
      bannerText: context.l10n.settingsViewerQuickActionEditorBanner,
      displayedButtonsDirection: ViewerBottomOverlay.actionsDirection,
      allAvailableActions: allAvailableActions,
      actionIcon: (context, action) => action.getIcon(),
      actionText: (context, action) => action.getText(context),
      controller: _controller,
    );
  }
}
