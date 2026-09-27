import 'package:aves/model/settings/defaults.dart';
import 'package:aves_model/aves_model.dart';

mixin SmartSearchSettings on SettingsAccess {
  static const int smartSearchHistoryMax = 10;

  bool get enableSmartSearch => getBool(SettingKeys.enableSmartSearchKey) ?? SettingsDefaults.enableSmartSearch;

  set enableSmartSearch(bool newValue) => set(SettingKeys.enableSmartSearchKey, newValue);

  bool get smartSearchUnmeteredOnly => getBool(SettingKeys.smartSearchUnmeteredOnlyKey) ?? SettingsDefaults.smartSearchUnmeteredOnly;

  set smartSearchUnmeteredOnly(bool newValue) => set(SettingKeys.smartSearchUnmeteredOnlyKey, newValue);

  // `null` when unset, in which case the device-dependent default applies
  bool? get smartSearchChargingOnly => getBool(SettingKeys.smartSearchChargingOnlyKey);

  set smartSearchChargingOnly(bool? newValue) => set(SettingKeys.smartSearchChargingOnlyKey, newValue);

  bool get smartSearchPromoDismissed => getBool(SettingKeys.smartSearchPromoDismissedKey) ?? false;

  set smartSearchPromoDismissed(bool newValue) => set(SettingKeys.smartSearchPromoDismissedKey, newValue);

  List<String> get smartSearchHistory => getStringList(SettingKeys.smartSearchHistoryKey) ?? [];

  set smartSearchHistory(List<String> newValue) => set(SettingKeys.smartSearchHistoryKey, newValue.take(smartSearchHistoryMax).toList());
}
