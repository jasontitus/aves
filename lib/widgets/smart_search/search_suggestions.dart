import 'package:aves/model/settings/settings.dart';
import 'package:aves/model/smart_search.dart';
import 'package:aves/theme/icons.dart';
import 'package:aves/theme/styles.dart';
import 'package:aves/widgets/common/extensions/build_context.dart';
import 'package:aves/widgets/smart_search/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

// Smart search entries at the top of the search page:
// - with a query: a suggestion to search photos by content,
// - without a query: recent smart searches,
// - when not set up: a dismissible prompt leading to settings.
class SmartSearchSuggestions extends StatelessWidget {
  final String query;
  final ValueChanged<String> onSearch;
  final VoidCallback onSetUp;

  const new({
    super.key,
    required this.query,
    required this.onSearch,
    required this.onSetUp,
  });

  @override
  Widget build(BuildContext context) {
    if (settings.useTvLayout) return const SizedBox();
    return ListenableBuilder(
      listenable: smartSearch,
      builder: (context, child) {
        if (!smartSearch.isEnabled) return _buildPromo(context);
        final cleanQuery = query.trim();
        if (cleanQuery.isEmpty) return _buildHistory(context);
        if (!isLongEnough(cleanQuery)) return const SizedBox();
        return _buildSuggestion(context, cleanQuery);
      },
    );
  }

  // avoid suggesting on the first keystrokes, except for scripts where a couple of characters is a word
  static bool isLongEnough(String query) {
    final hasIdeographs = query.runes.any((c) => c >= 0x2E80);
    return query.length >= (hasIdeographs ? 1 : 3);
  }

  Widget _buildSuggestion(BuildContext context, String cleanQuery) {
    final l10n = context.l10n;
    final ready = smartSearch.isReady;
    final subtitleLines = [
      if (!ready) l10n.searchSmartSearchUnavailable else SmartSearchText.partialIndexText(context) ?? l10n.searchSmartSearchSuggestionSubtitle,
      if (ready && SmartSearchText.needsLanguageHint) l10n.smartSearchLanguageHint,
    ];
    return ListTile(
      leading: const Icon(AIcons.smartSearch),
      title: Text(
        l10n.searchSmartSearchSuggestion(cleanQuery),
        maxLines: 2,
        overflow: .ellipsis,
      ),
      subtitle: Text(subtitleLines.join('\n')),
      enabled: ready,
      onTap: () => onSearch(cleanQuery),
    );
  }

  Widget _buildHistory(BuildContext context) {
    final history = context.select<Settings, List<String>>((s) => s.saveSearchHistory ? s.smartSearchHistory : const []);
    if (history.isEmpty || !smartSearch.isReady) return const SizedBox();
    final l10n = context.l10n;
    return Column(
      crossAxisAlignment: .start,
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Text(
            l10n.searchSmartSearchRecentSectionTitle,
            style: AStyles.knownTitleText,
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: history
                .map(
                  (v) => InputChip(
                    avatar: const Icon(AIcons.smartSearch),
                    label: Text(v),
                    onPressed: () => onSearch(v),
                    onDeleted: () => settings.smartSearchHistory = settings.smartSearchHistory..remove(v),
                    deleteButtonTooltipMessage: MaterialLocalizations.of(context).deleteButtonTooltip,
                  ),
                )
                .toList(),
          ),
        ),
      ],
    );
  }

  Widget _buildPromo(BuildContext context) {
    final dismissed = context.select<Settings, bool>((s) => s.smartSearchPromoDismissed);
    final status = smartSearch.status;
    // only promote when the model can actually be obtained
    final available = status != null && (status.canDownload || status.models.any((v) => v.installed));
    if (dismissed || !available || query.trim().isNotEmpty) return const SizedBox();
    final l10n = context.l10n;
    return ListTile(
      leading: const Icon(AIcons.smartSearch),
      title: Text(l10n.searchSmartSearchPromoTitle),
      subtitle: Text(l10n.searchSmartSearchPromoSubtitle),
      onTap: onSetUp,
      trailing: IconButton(
        icon: const Icon(AIcons.clear),
        tooltip: l10n.hideTooltip,
        onPressed: () => settings.smartSearchPromoDismissed = true,
      ),
    );
  }
}
