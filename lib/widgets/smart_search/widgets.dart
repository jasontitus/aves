import 'package:aves/model/smart_search.dart';
import 'package:aves/model/settings/settings.dart';
import 'package:aves/widgets/common/extensions/build_context.dart';
import 'package:material_ui/material_ui.dart';

// hints shown when smart search yields nothing: incomplete index, non-English locale
class SmartSearchEmptyHint extends StatelessWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context) {
    final lines = [
      ?SmartSearchText.partialIndexText(context),
      if (SmartSearchText.needsLanguageHint) context.l10n.smartSearchLanguageHint,
    ];
    if (lines.isEmpty) return const SizedBox();
    final style = Theme.of(context).textTheme.bodySmall;
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        mainAxisSize: .min,
        children: lines.map((v) => Text(v, style: style, textAlign: .center)).toList(),
      ),
    );
  }
}

class SmartSearchText {
  // the embedding models are trained on English captions
  static bool get needsLanguageHint => settings.resolvedLocale.languageCode != 'en';

  // `null` when the index is complete or unknown
  static String? partialIndexText(BuildContext context) {
    final coverage = smartSearch.coverage;
    if (coverage == null || coverage.isComplete) return null;
    if (coverage.indexed == 0) return context.l10n.searchSmartSearchNotIndexedYet;
    return context.l10n.searchSmartSearchPartialIndex(coverage.indexed, coverage.total);
  }
}
