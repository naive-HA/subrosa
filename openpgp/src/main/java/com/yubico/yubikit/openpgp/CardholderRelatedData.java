/*
 * Copyright (C) 2023 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yubico.yubikit.openpgp;

import com.yubico.yubikit.core.util.Tlv;
import com.yubico.yubikit.core.util.Tlvs;
import java.util.Arrays;
import java.util.Map;

public class CardholderRelatedData {
  private final byte[] name;
  private final byte[] language;
  private final int sex;

  public byte[] getName() {
    return Arrays.copyOf(name, name.length);
  }

  public byte[] getLanguage() {
    return Arrays.copyOf(language, language.length);
  }

  public int getSex() {
    return sex;
  }

  CardholderRelatedData(byte[] name, byte[] language, int sex) {
    this.name = name;
    this.language = language;
    this.sex = sex;
  }

  static CardholderRelatedData parse(byte[] encoded) {
    byte[] value = Tlv.parse(encoded).getValue();
    Map<Integer, byte[]> data = Tlvs.decodeMap(value);
    byte[] name = data.get(Do.NAME);
    byte[] language = data.get(Do.LANGUAGE);
    byte[] sex = data.get(Do.SEX);
    return new CardholderRelatedData(
        name != null ? name : new byte[0],
        language != null ? language : new byte[0],
        sex != null && sex.length > 0 ? (0xff & sex[0]) : 0);
  }
}
