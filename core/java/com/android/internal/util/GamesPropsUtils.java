/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.Map;

/** @hide */
public class GamesPropsUtils {

  private static final String TAG = "GamesPropsUtils";
  private static final boolean DEBUG = false;

  private static final String PACKAGE_AETHER_GAZER = "com.YoStar.AetherGazer";
  private static final String PACKAGE_ARENA_BREAKOUT = "com.proximabeta.mf.uamo";

  private static final String PACKAGE_BLACK_DESERT = "com.pearlabyss.blackdesertm.gl";
  private static final String PACKAGE_BLACK_DESERT_KR = "com.pearlabyss.blackdesertm";

  private static final String PACKAGE_COD_MOBILE = "com.activision.callofduty.shooter";
  private static final String PACKAGE_COD_MOBILE_AS = "com.garena.game.codm";
  private static final String PACKAGE_COD_MOBILE_CN = "com.tencent.tmgp.cod";
  private static final String PACKAGE_COD_MOBILE_KR = "com.tencent.tmgp.kr.codm";
  private static final String PACKAGE_COD_MOBILE_VN = "com.vng.codmvn";

  private static final String PACKAGE_DELTA_FORCE = "com.garena.game.df";
  private static final String PACKAGE_FREE_FIRE = "com.dts.freefire";
  private static final String PACKAGE_FREE_FIRE_MAX = "com.dts.freefiremax";

  private static final String PACKAGE_EPICGAMES_LAUNCHER = "com.epicgames.portal";
  private static final String PACKAGE_FORTNITE = "com.epicgames.fortnite";

  private static final String PACKAGE_HIGH_ENERGY_HEROES = "com.tencent.tmgp.gnyx";

  private static final String PACKAGE_HONOR_OF_KINGS = "com.levelinfinite.sgameGlobal";
  private static final String PACKAGE_HONOR_OF_KINGS_CN = "com.tencent.tmgp.sgame";

  private static final String PACKAGE_LEAGUE_OF_LEGENDS = "com.riotgames.league.wildrift";
  private static final String PACKAGE_LEAGUE_OF_LEGENDS_CN = "com.tencent.lolm";
  private static final String PACKAGE_LEAGUE_OF_LEGENDS_TW = "com.riotgames.league.wildrifttw";
  private static final String PACKAGE_LEAGUE_OF_LEGENDS_VN = "com.riotgames.league.wildriftvn";

  private static final String PACKAGE_MOBILE_LEGENDS = "com.mobile.legends";
  private static final String PACKAGE_MOBILE_LEGENDS_VN = "com.vng.mlbbvn";

  private static final String PACKAGE_NARUTO = "com.tencent.KiHan";

  private static final String PACKAGE_PUBG_MOBILE = "com.tencent.ig";
  private static final String PACKAGE_PUBG_MOBILE_CN = "com.tencent.tmgp.pubgmhd";
  private static final String PACKAGE_PUBG_MOBILE_IN = "com.pubg.imobile";
  private static final String PACKAGE_PUBG_MOBILE_KR = "com.pubg.krmobile";
  private static final String PACKAGE_PUBG_MOBILE_TW = "com.rekoo.pubgm";
  private static final String PACKAGE_PUBG_MOBILE_VN = "com.vng.pubgmobile";

  private static final String PACKAGE_SHADOWGUN_LEGENDS = "com.madfingergames.legends";
  private static final String PACKAGE_TOWER_OF_FANTASY = "com.levelinfinite.hotta.gp";

  private static final Map<String, String> sLenovoLegionProps =
      Map.of(
          "DEVICE", "TB-9707F",
          "MANUFACTURER", "Lenovo",
          "BRAND", "Lenovo",
          "MODEL", "Lenovo TB-9707F");

  private static final Map<String, String> sOnePlusNineProps =
      Map.of(
          "DEVICE", "OnePlus9R",
          "MANUFACTURER", "OnePlus",
          "BRAND", "OnePlus",
          "MODEL", "LE2101");

  private static final Map<String, String> sPocoSixProProps =
      Map.of(
          "PRODUCT", "vermeer",
          "DEVICE", "23113RKC6G",
          "MANUFACTURER", "Xiaomi",
          "BRAND", "Xiaomi",
          "MODEL", "23049PCD8G");

  private static final Map<String, String> sRedMagicNineProProps =
      Map.of(
          "PRODUCT", "NX769J",
          "DEVICE", "REDMAGIC 9 Pro",
          "MANUFACTURER", "ZTE",
          "BRAND", "nubia",
          "MODEL", "NX769J");

  private static final Map<String, String> sRogPhoneOneProps =
      Map.of(
          "PRODUCT", "WW_Z01QD",
          "DEVICE", "ASUS_Z01QD_1",
          "MANUFACTURER", "asus",
          "BRAND", "asus",
          "MODEL", "ASUS_Z01QD");

  private static final Map<String, String> sRogPhoneEightProps =
      Map.of(
          "PRODUCT", "WW_AI2401_A",
          "DEVICE", "ASUS_AI2401_A",
          "MANUFACTURER", "asus",
          "BRAND", "asus",
          "MODEL", "ASUS_AI2401_A");

  private static final Map<String, String> sSamsungTwentyFourUltraProps =
      Map.of(
          "PRODUCT", "e3qxeea",
          "DEVICE", "SM-S928B",
          "MANUFACTURER", "samsung",
          "BRAND", "samsung",
          "MODEL", "SM-S928B");

  private static final Map<String, String> sVivoElevenProps =
      Map.of(
          "PRODUCT", "PD2243",
          "DEVICE", "PD2243",
          "MANUFACTURER", "vivo",
          "BRAND", "vivo",
          "MODEL", "V2243A");

  private static final Map<String, String> sXiaomiFourProProps =
      Map.of(
          "PRODUCT", "shennong",
          "DEVICE", "shennong",
          "MANUFACTURER", "Xiaomi",
          "BRAND", "Xiaomi",
          "MODEL", "23116PN5BG");

  private static final Map<String, String> sXperiaFiveProps =
      Map.of(
          "PRODUCT", "XQ-DE54_EEA",
          "DEVICE", "XQ-DE54",
          "MANUFACTURER", "Sony",
          "BRAND", "Sony",
          "MODEL", "XQ-DE54");

  public static void setProps(Context context) {
    final String packageName = context.getPackageName();

    if (TextUtils.isEmpty(packageName)) {
      Log.e(TAG, "Null package name");
      return;
    }

    switch (packageName) {
      case PACKAGE_COD_MOBILE:
      case PACKAGE_COD_MOBILE_AS:
      case PACKAGE_COD_MOBILE_KR:
      case PACKAGE_COD_MOBILE_VN:
        dlog("Spoofing Lenovo Legion Y700 for: " + packageName);
        setProps(sLenovoLegionProps);
        return;
      case PACKAGE_EPICGAMES_LAUNCHER:
      case PACKAGE_FORTNITE:
      case PACKAGE_LEAGUE_OF_LEGENDS:
      case PACKAGE_LEAGUE_OF_LEGENDS_CN:
      case PACKAGE_LEAGUE_OF_LEGENDS_TW:
      case PACKAGE_LEAGUE_OF_LEGENDS_VN:
        dlog("Spoofing OnePlus 9R for: " + packageName);
        setProps(sOnePlusNineProps);
        return;
      case PACKAGE_MOBILE_LEGENDS:
      case PACKAGE_MOBILE_LEGENDS_VN:
      case PACKAGE_TOWER_OF_FANTASY:
        dlog("Spoofing POCO F6 Pro for: " + packageName);
        setProps(sPocoSixProProps);
        return;
      case PACKAGE_FREE_FIRE:
      case PACKAGE_FREE_FIRE_MAX:
        dlog("Spoofing RogPhone 1 for: " + packageName);
        setProps(sRogPhoneOneProps);
        return;
      case PACKAGE_DELTA_FORCE:
        dlog("Spoofing RedMagic 9 Pro for: " + packageName);
        setProps(sRedMagicNineProProps);
        return;
      case PACKAGE_BLACK_DESERT:
      case PACKAGE_BLACK_DESERT_KR:
      case PACKAGE_SHADOWGUN_LEGENDS:
        dlog("Spoofing RogPhone 8 for: " + packageName);
        setProps(sRogPhoneEightProps);
        return;
      case PACKAGE_PUBG_MOBILE:
      case PACKAGE_PUBG_MOBILE_CN:
      case PACKAGE_PUBG_MOBILE_IN:
      case PACKAGE_PUBG_MOBILE_KR:
      case PACKAGE_PUBG_MOBILE_TW:
      case PACKAGE_PUBG_MOBILE_VN:
        dlog("Spoofing Samsung Galaxy S24 Ultra for: " + packageName);
        setProps(sSamsungTwentyFourUltraProps);
        return;
      case PACKAGE_COD_MOBILE_CN:
      case PACKAGE_HIGH_ENERGY_HEROES:
      case PACKAGE_NARUTO:
        dlog("Spoofing Vivo iQOO 11 for: " + packageName);
        setProps(sVivoElevenProps);
        return;
      case PACKAGE_AETHER_GAZER:
      case PACKAGE_HONOR_OF_KINGS:
      case PACKAGE_HONOR_OF_KINGS_CN:
        dlog("Spoofing Xiaomi 14 Pro for: " + packageName);
        setProps(sXiaomiFourProProps);
        return;
      case PACKAGE_ARENA_BREAKOUT:
        dlog("Spoofing Sony Xperia 5 V for: " + packageName);
        setProps(sXperiaFiveProps);
        return;
    }
  }

  private static void setProps(Map<String, String> props) {
    props.forEach(GamesPropsUtils::setPropValue);
  }

  private static void setPropValue(String key, String value) {
    try {
      dlog("Setting prop " + key + " to " + value.toString());
      Class clazz = Build.class;
      Field field = clazz.getDeclaredField(key);
      field.setAccessible(true);
      field.set(null, field.getType().equals(Integer.TYPE) ? Integer.parseInt(value) : value);
      field.setAccessible(false);
    } catch (Exception e) {
      Log.e(TAG, "Failed to set prop " + key, e);
    }
  }

  public static void dlog(String msg) {
    if (DEBUG) Log.d(TAG, msg);
  }
}
