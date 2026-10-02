/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

final class Constants {

    static final String PREF_TURBO_ENABLED = "turbo_enable";
    static final String PREF_TURBO_CURRENT = "turbo_current";
    static final String PREF_SPORTS_MODE   = "sports_mode";

    static final String DEFAULT_OFF_VALUE = "6000000";
    static final String DEFAULT_ON_VALUE  = "9750000";

    static final String CHARGE_CURRENT_NODE = "/sys/class/power_supply/battery/constant_charge_current";
    static final String SPORTS_MODE_NODE    = "/sys/class/qcom-battery/sport_mode";

    private Constants() {}
}
