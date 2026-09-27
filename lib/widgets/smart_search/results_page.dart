import 'package:aves/model/entry/entry.dart';
import 'package:aves/model/smart_search.dart';
import 'package:aves/model/source/collection_source.dart';
import 'package:aves/theme/icons.dart';
import 'package:aves/widgets/collection/collection_page.dart';
import 'package:aves/widgets/common/basic/scaffold.dart';
import 'package:aves/widgets/common/extensions/build_context.dart';
import 'package:aves/widgets/common/identity/empty.dart';
import 'package:aves/widgets/smart_search/widgets.dart';
import 'package:material_ui/material_ui.dart';

// Shows a progress state while searching, then the ranked results as a collection.
class SmartSearchResultsPage extends StatefulWidget {
  static const routeName = '/smart_search_results';

  final CollectionSource source;
  final String title;
  final Future<SmartSearchResult?> Function() search;

  const new({
    super.key,
    required this.source,
    required this.title,
    required this.search,
  });

  @override
  State<SmartSearchResultsPage> createState() => _SmartSearchResultsPageState();

  static Route<void> route({
    required CollectionSource source,
    required String title,
    required Future<SmartSearchResult?> Function() search,
  }) {
    return MaterialPageRoute(
      settings: const RouteSettings(name: routeName),
      builder: (context) => SmartSearchResultsPage(source: source, title: title, search: search),
    );
  }

  // results for a text query
  static Route<void> textRoute({
    required CollectionSource source,
    required String query,
    required Iterable<AvesEntry> candidates,
    required bool isScoped,
  }) {
    return route(
      source: source,
      title: query,
      search: () => smartSearch.searchText(query, source, candidates, isScoped: isScoped),
    );
  }

  // items similar to `entry`, among all visible entries
  static Route<void> similarRoute(
    BuildContext context, {
    required CollectionSource source,
    required AvesEntry entry,
  }) {
    final l10n = context.l10n;
    final name = entry.bestTitle;
    return route(
      source: source,
      title: name != null ? l10n.smartSearchSimilarTitle(name) : l10n.smartSearchSimilarTitleGeneric,
      search: () => smartSearch.searchSimilar(entry, source, source.visibleEntries),
    );
  }
}

class _SmartSearchResultsPageState extends State<SmartSearchResultsPage> {
  late final Future<SmartSearchResult?> _resultFuture;

  @override
  void initState() {
    super.initState();
    _resultFuture = widget.search();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<SmartSearchResult?>(
      future: _resultFuture,
      builder: (context, snapshot) {
        final result = snapshot.data;
        if (snapshot.connectionState != ConnectionState.done) {
          return _buildPlaceholder(
            context,
            child: const Align(
              alignment: .topCenter,
              child: LinearProgressIndicator(),
            ),
          );
        }
        if (result == null) {
          return _buildPlaceholder(
            context,
            child: EmptyContent(
              icon: AIcons.error,
              text: context.l10n.smartSearchFailed,
              bottom: const SmartSearchEmptyHint(),
            ),
          );
        }
        return CollectionPage(
          source: widget.source,
          filters: null,
          smartSearchResult: result,
        );
      },
    );
  }

  Widget _buildPlaceholder(BuildContext context, {required Widget child}) {
    return AvesScaffold(
      appBar: AppBar(
        title: Text(
          widget.title,
          softWrap: false,
          overflow: .fade,
          maxLines: 1,
        ),
      ),
      body: SafeArea(child: child),
    );
  }
}
